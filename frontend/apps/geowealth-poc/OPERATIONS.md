# GeoWealth white-labeling POC — operations one-pager

What to look at when you want to know the white-labeling pipeline is
healthy, plus the grep lines that surface each signal. Scoped to the
POC — Keycloak side, single node, no external observability. Pair with
`README.md` for run-the-thing instructions and `SECURITY-CHECKS.md` for
the security audit results.

All log lines come from the Keycloak container
(`keycloak-demo-keycloak-1` under docker, `keycloak_1` under podman).
Replace the container name with whatever `docker compose ps keycloak`
prints if it differs.

---

## The one line per login render

Every login render emits one INFO line from `BrandingService.lookupByHost`:

```
INFO  Branding: host=<Host:port> code=<firm> code_src=<source> brand_src=<source> latency_ms=<n>
```

Field meanings:

| Field | Values | What it tells you |
|---|---|---|
| `host` | `changepath.localhost:8898`, `default.localhost:8898`, `-` (no Host header) | What the browser asked for. |
| `code` | `changepath`, `cca`, `default`, anything the API returns | Firm code that drove the brand fetch. |
| `code_src` | `cache_hit` / `api_hit` / `host_derived` / `host_empty` | How the firm code was resolved. |
| `brand_src` | `cache_hit` / `api_hit` / `registry_fallback` / `registry_fallback_skip` | Where the brand payload came from. `_skip` means the host lookup just failed so we deliberately skipped a second API call to avoid stacking timeouts. |
| `latency_ms` | end-to-end resolution wall time | Includes any in-render HTTP + the 3 s timeout if we hit it. |

Always grep with the prefix to keep this separate from the rest of
Keycloak's logs:

```bash
docker compose logs --since 5m keycloak | grep -E '^\S+ INFO  Branding:'
```

### Expected steady-state distribution

After the cache warms up (first login per firm per minute):

- `code_src=cache_hit` and `brand_src=cache_hit` on every subsequent render
- `latency_ms` p99 **under 5 ms** (in-JVM map read)
- One `api_hit` / `api_hit` pair per firm per 60 s window (the TTL)

If the steady state shifts away from `cache_hit`, the most likely cause
is the container being recreated (cache is per-JVM, lost on restart) or
the TTL being lowered. The TTL is the
`KC_SPI_LOGIN_FREEMARKER_GEOWEALTH_BRANDING_CACHE_TTL_SECONDS` env on
the keycloak service in `docker-compose.yml`.

### Fallback states are normal, transiently

- `brand_src=registry_fallback` → the API returned non-200, non-JSON,
  timed out, or 200'd with a payload that didn't pass `Brand` validation.
  A small number of these per hour is fine; sustained = the API is down.
- `code_src=host_derived` → either the API has no host→code mapping for
  this hostname (unknown firm) or the API is unreachable. Combined with
  `brand_src=cache_hit` for the same firm code this is healthy degraded
  mode.

---

## Health checks — one-liners

### "Is branding being served at all?"

```bash
curl -sI "http://changepath.localhost:8898/realms/geowealth-realm/protocol/openid-connect/auth?client_id=geowealth-poc-client&response_type=code&redirect_uri=http://changepath.localhost:5174/&scope=openid" \
  | head -1
# expect: HTTP/1.1 200 OK
```

Then in another shell, immediately:

```bash
docker compose logs --since 10s keycloak | grep 'Branding:' | tail -1
```

One line should appear matching the request you just made.

### "Did the SPI configure the API client at boot?"

Looked up once at startup:

```bash
docker compose logs keycloak | grep -E 'GeoWealth branding API (enabled|not configured)'
# enabled state (good):
#   INFO  GeoWealth branding API enabled: http://host.docker.internal:8080 (cache TTL 60s)
# disabled state (the demo without an upstream branding API):
#   INFO  GeoWealth branding API not configured — using hardcoded registry fallback only
```

### "Is the API itself reachable from inside the keycloak container?"

```bash
docker compose exec keycloak sh -c \
  'curl -sf -H "Authorization: Bearer $KC_SPI_LOGIN_FREEMARKER_GEOWEALTH_BRANDING_API_TOKEN" \
  http://host.docker.internal:8080/branding-api/keycloak/whitelabel/changepath \
  | head -c 200; echo'
# 200 + JSON body = green.
```

### "Cache hit ratio over the last 5 minutes"

```bash
docker compose logs --since 5m keycloak | grep -oE 'brand_src=[a-z_]+' \
  | sort | uniq -c
# example:
#   42 brand_src=cache_hit
#    2 brand_src=api_hit
#    0 brand_src=registry_fallback
```

Anything where `cache_hit` is not the dominant bucket after the first
minute is worth investigating. If `registry_fallback*` accumulates, the
API is unreachable or returning garbage — see "Failure signals" below.

---

## Failure signals worth alerting on

| Log line | Severity | Meaning | Action |
|---|---|---|---|
| `BrandingApi <uri> -> HTTP <code>` | WARN | API returned non-200. Single occurrences are noise; sustained = the API is hard-down or auth is broken. | Check the API's own logs and `POC_BRANDING_API_TOKEN`. |
| `BrandingApi timeout on <uri> after <n> ms (limit 3000 ms)` | WARN | Request took ≥ 3 s. The branding fetch is dropped; login continues with cache or fallback. | Check API latency. Sustained timeouts make every uncached login take a 3 s hit. |
| `BrandingApi error on <uri> after <n> ms: <ExceptionClass>` | WARN | Transport-level error (DNS, refused, reset). | Check connectivity from the keycloak container to `host.docker.internal:8080`. |
| `BrandingApi JSON parse failure on <uri> after <n> ms: <reason>` | ERROR | 200 OK with a body Jackson couldn't parse. Contract violation, not transport. | Look at the actual response body; coordinate with whoever owns the API. |
| `BrandingApi contract violation on <uri>: response was valid JSON but missing required fields (code/cssVariables); falling back` | ERROR | 200 OK + valid JSON, but missing required fields. | Same as above. |
| `Brand[<code>]: dropping unsafe CSS variable key '<prefix>…'` | WARN | The API returned a `cssVariables` key that didn't match `^--[a-zA-Z0-9_-]{1,64}$`. The entry is dropped before reaching the template. | If the key is legitimate, fix the API. If the key isn't legitimate, that's an injection attempt — investigate the API's data source. |
| `Brand[<code>]: dropping unsafe CSS value for key '<key>' (value prefix: '<prefix>…')` | WARN | Same as above for values; allowed values are hex colors and a fixed allowlist of keywords (`transparent`, `inherit`, `initial`, `unset`, `none`, `currentColor`). | Same investigation path. See `SECURITY-CHECKS.md` §3. |

Note: WARN-level branding errors do NOT block login — they're observed
fallbacks. The user always gets a valid login page. ERROR is reserved
for "the API broke its contract"; alert on it.

---

## Expected latencies

Numbers are wall-clock from `latency_ms` in the `Branding:` log line.
They were measured against the dev fake on the same host, so they're
optimistic; expect real GeoWealth network round-trips to add their own
~10–30 ms.

| Scenario | Expected p50 | Expected p99 | Comment |
|---|---|---|---|
| Both caches hit                          | < 1 ms   | < 5 ms   | The only path that matters at steady state. |
| Host miss → API hit → brand miss → API hit | 30–60 ms | < 150 ms | First login of a firm after cold start or TTL roll. |
| Host miss → API timeout → registry       | ~3000 ms | ~3000 ms | The 3 s timeout dominates. Sustained → API is down. |
| Host miss → API timeout, brand-fetch skipped | ~3000 ms | ~3000 ms | The `registry_fallback_skip` shortcut keeps this from being ~6 s. |

If the steady-state p99 climbs above ~5 ms while `brand_src=cache_hit`,
something is monopolizing CPU on the keycloak container — unrelated to
branding.

---

## Cache topology (good to know when reading the numbers)

- **Two caches**, both in-JVM, both TTL-`KC_SPI_..._CACHE_TTL_SECONDS`
  (default `60`):
  - host → firm code
  - firm code → resolved `Brand`
- **Single-node only.** Restarting the keycloak container empties both;
  expect the first login per firm after a restart to be an `api_hit` pair.
- **No invalidation hook.** Admin edits to a brand on the GeoWealth side
  take up to one TTL to propagate. Bump the TTL down temporarily for a
  faster feedback loop during admin-side QA.

The webhook-based fan-out + cluster-wide invalidation is explicitly out
of scope for the POC. Tracked in `geowealth-keycloak-poc-migration-plan.md`
§10 and the architecture doc's "Phase 2 — invalidation" section.

---

## When the POC seems broken — first three things to check

1. **Did the realm get imported?** `curl -s http://localhost:8898/realms/geowealth-realm/.well-known/openid-configuration | head -1` should return a JSON object, not an error page. If it doesn't, the realm export wasn't picked up — most often because the volume already has the demo realm and `IGNORE_EXISTING` skipped the second one. `down -v` then `./start.sh`.
2. **Did the SPI load?** Search the startup logs for `GeoWealth branding API`. Absent = the theme JAR isn't in `/opt/keycloak/providers/`, usually because `docker compose build keycloak` was skipped. `docker compose build keycloak && docker compose up -d --force-recreate keycloak`.
3. **Is there a `Branding:` line per request?** `docker compose logs --since 1m keycloak | grep Branding:`. Absent during a fresh login → the `GeoWealthLoginFormsProvider` isn't intercepting; check that the keycloak-themes.json declares the override and the `META-INF/services/org.keycloak.forms.login.LoginFormsProviderFactory` entry is in the JAR.
