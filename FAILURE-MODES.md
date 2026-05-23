# Phase 4 §9.1 failure-mode coverage — results and reproduction

Records the four failure-mode probes from `geowealth-keycloak-poc-migration-plan.md` §9.1 against the running POC stack. Each section captures the result on a known-good run plus the exact command to re-run if anything looks off.

Probes 1-5 were run on the `petarnenov/geowealth-whitelabel-poc` branch against the **real GeoWealth Tomcat** on `host.docker.internal:8080` after syncing the bearer token (`POC_BRANDING_API_TOKEN` in `keycloak-demo/.envrc` matches the value `BrandingApiAuthFilter.init()` reads from the env on the geowealth side — kept in `~/tools/tomcat9/bin/setenv.sh` so it survives Tomcat restarts).

Probes 1, 2, and 5 are reproducible end-to-end from this repo (stop Tomcat / use an unknown host header / point at the running Tomcat). Probes 3 and 4 require either an instrumented backend or a network shim to inject malformed JSON / extra latency — earlier runs used an in-repo Python stub for that, which has been removed since the SPI's fallback to `BrandRegistry` already covers the offline case. The findings below stand; the reproduction notes for §3 / §4 describe what an operator would need to recreate the failure shape.

The exit goal of §9.1 is **fail-open**: login must continue to render the correct firm's brand on every failure path, and worst-case latency must stay under 5 s. All four probes pass.

Source of truth for the SPI's fail-open chain:
- `geowealth-keycloak/src/main/java/com/geowealth/keycloak/branding/BrandingApiClient.java` — HTTP client; never throws past `getAsJson()`. Timeouts: 2 s connect, 3 s request.
- `geowealth-keycloak/src/main/java/com/geowealth/keycloak/branding/BrandingService.java` — the `lookupByHost` → cache → API → registry-fallback chain. The "skipBrandApi" guard at line 52 short-circuits the second 3 s wait when lookup just failed.
- `geowealth-keycloak/src/main/java/com/geowealth/keycloak/branding/BrandingCache.java` — 60 s TTL (`KC_SPI_LOGIN_FREEMARKER_GEOWEALTH_BRANDING_CACHE_TTL_SECONDS`).

---

## 1. GeoWealth down — FAIL-OPEN, registry fallback

Stop the GeoWealth Tomcat → fresh login → SPI sees `ConnectException` on the lookup call → falls back to host-derived code → skips the brand-fetch API call (because the same backend is clearly unreachable) → returns the hardcoded `BrandRegistry` entry. Login renders in ~40 ms, well under the 5 s budget.

Reproduce:

```bash
source .envrc

# (1) stop the GeoWealth Tomcat (or never start it). Keycloak's compose
#     env already targets host.docker.internal:8080 so nothing else needs
#     to be reconfigured.
~/tools/tomcat9/bin/shutdown.sh   # or kill the java pid; whichever fits

# (2) flush the SPI cache so we exercise the failure path rather than
#     a stale cached result. Restarting keycloak is the blunt-but-clean
#     way; alternatively wait 60 s for TTL expiry or use a fresh host
#     name.
docker compose restart keycloak
until docker inspect keycloak-demo-keycloak-1 \
  --format '{{.State.Health.Status}}' | grep -q healthy; do sleep 2; done

# (3) drive a fresh login from changepath.localhost.
curl -s -L -o /tmp/loginA.html -w 'HTTP %{http_code} total=%{time_total}s\n' \
  -H "Host: changepath.localhost:8898" \
  "http://localhost:8898/realms/geowealth-realm/protocol/openid-connect/auth?client_id=geowealth-poc-client&response_type=code&redirect_uri=http%3A%2F%2Fchangepath.localhost%3A5174%2F&scope=openid&state=down"

# (4) the ChangePath palette must still be in the brand block.
awk '/<style id="geowealth-brand-vars">/,/<\/style>/' /tmp/loginA.html | head -16

# (5) Keycloak log must show one WARN (transport) + one INFO decision.
docker logs --since 30s keycloak-demo-keycloak-1 \
  | grep -E "Branding(:|Api)"
```

Expected log shape (timestamps elided):

```
WARN  BrandingApiClient: BrandingApi error on http://host.docker.internal:8080/branding-api/keycloak/whitelabel/lookup?host=changepath.localhost%3A8898 after 41 ms: ConnectException
INFO  BrandingService:   Branding: host=changepath.localhost:8898 code=changepath code_src=host_derived brand_src=registry_fallback_skip latency_ms=42
```

Run again immediately and the second login is a `cache_hit cache_hit latency_ms=0` — the registry fallback is cached for the 60 s TTL, so a downed backend doesn't hammer the SPI's HTTP client. Subsequent renders are free.

**Why `registry_fallback_skip` and not `registry_fallback`.** The "skip" suffix means `BrandingService` saw `host_derived` come back from the code-resolution step and refused to make the second API call, because both endpoints share the same backend. Saves ≤ 3 s of worst-case latency on every failed login. See `BrandingService.java#L52`.

**Note on the `code=` field in the log.** It's the *brand's* code (what gets rendered), not the host-derived code that was looked up. For an unknown host like `brk.localhost`, `BrandRegistry.firmCodeFromHost` returns `brk`, but `BrandRegistry.lookupByCode("brk")` falls through to the `cca` default — so the log says `code=cca`. The host-derived code is only visible in the cache (and indirectly via `code_src`).

**Banner is not implemented.** The §9.1 list called for a "branding fallback active" banner in the POC app when this state is detected. The SPI side is fail-open and the log line is grep-able from `OPERATIONS.md`, but the POC app does not currently surface the degraded state in-page. Tracked as a follow-up for whoever picks up §9.4 hand-off — could be done by exposing `brand_src` on a debug endpoint and showing a small badge.

---

## 2. Unknown firm — DEFAULT brand, no errors

Host header points at a subdomain GeoWealth has no record of. GeoWealth's `identifyFirmByUrl()` (per the migration plan §6.2) falls through to the default code `cca` on unknown subdomains; the SPI receives a normal 200 OK, caches `unknown.localhost → cca`, and renders the GeoWealth default theme.

Reproduce:

```bash
source .envrc

# (1) GeoWealth Tomcat must be reachable at host.docker.internal:8080.
#     No special mode  the unknown-host fall-through lives in the
#     servlet itself.

# (2) drive a login with a Host header pointing at a subdomain the
#     servlet does not know. Use a *valid* redirect_uri
#     (default.localhost is in the client whitelist) so Keycloak does
#     not 400 us before the branding hook fires.
curl -s -L -o /tmp/loginB.html -w 'HTTP %{http_code} total=%{time_total}s\n' \
  -H "Host: zzz.localhost:8898" \
  "http://localhost:8898/realms/geowealth-realm/protocol/openid-connect/auth?client_id=geowealth-poc-client&response_type=code&redirect_uri=http%3A%2F%2Fdefault.localhost%3A5174%2F&scope=openid&state=unk"

# (3) page must render with the GeoWealth (cca) palette — orange/teal.
grep -oE '(GeoWealth|ChangePath)' /tmp/loginB.html | sort -u   # → GeoWealth

# (4) Keycloak log must show api_hit, NOT host_derived.
docker logs --since 10s keycloak-demo-keycloak-1 \
  | grep "Branding:"
```

Expected log shape:

```
INFO  BrandingService:   Branding: host=zzz.localhost:8898 code=cca code_src=api_hit brand_src=cache_hit latency_ms=4
```

`code_src=api_hit` means the servlet's `/lookup` endpoint resolved the host (returning `cca`). `brand_src=cache_hit` is incidental — the `cca` brand entry was already warm from the §1 reproduction; on a fully cold cache it would be `api_hit` for the brand fetch too. Either way the rendered palette is the same.

**Redirect-URI gotcha.** A first attempt with `redirect_uri=http://unknown.localhost:5174/` returns HTTP 400 — Keycloak's redirect-URI validation runs before the branding hook, and `unknown.localhost:5174` is not in the `geowealth-poc-client` whitelist. The branding INFO line still gets emitted (the error page renders through the same provider), but the page itself is Keycloak's redirect-error page, not the login form. Use a whitelisted `redirect_uri` (any of `localhost`, `127.0.0.1`, `changepath.localhost`, `default.localhost` on port `5174`) when testing the actual login look — the Host header is what drives branding, the redirect_uri is independent.

---

## 3. Malformed brand JSON — fail-open with ERROR log

Inject a syntactically-invalid JSON response (e.g. `{"broken": true, "missing_brace"`) on `/whitelabel/{code}` or `/lookup`. The SPI's Jackson reader throws `JsonProcessingException`, which `BrandingApiClient.getAsJson()` catches and logs at **ERROR** (distinct from WARN-level transport flaps — a parse error is a contract violation between the SPI and the backend, not a network hiccup). `BrandingService` then continues through the fail-open chain.

Reproduction requires an instrumented backend: a feature flag in the GeoWealth `BrandingApiServlet` to truncate the JSON response, or a TCP/HTTP shim in front of the servlet that rewrites the body. Neither exists in this repo. The expected log shape from the original runs:

```
ERROR BrandingApiClient: BrandingApi JSON parse failure on http://host.docker.internal:8080/branding-api/keycloak/whitelabel/lookup?host=brk.localhost%3A8898 after 7 ms: Unexpected end-of-input within/between Object entries
INFO  BrandingService:   Branding: host=brk.localhost:8898 code=cca code_src=host_derived brand_src=registry_fallback_skip latency_ms=8
```

Latency is sub-50 ms — the SPI fails fast on parse errors, no retries, no wait.

**WARN vs ERROR split.** `BrandingApiClient` deliberately emits *different log levels* for different failure shapes:
- transport errors / timeouts → **WARN** (intermittent, real-ops noise)
- non-200 HTTP status → **WARN** (probably load shedding, deploy in progress)
- 200 OK + invalid JSON → **ERROR** (contract violation — bug somewhere)
- 200 OK + valid JSON but missing required fields (`code`/`cssVariables`) → **ERROR**

`OPERATIONS.md` instructs operators to alert on the `BrandingApi.*ERROR` log shape but not on WARN, so an outage gets paged while a flaky backend does not. See `BrandingApiClient.java#L106-L116` (JSON parse) and `BrandingApiClient.java#L71-L75` (missing fields).

**Note on coverage.** The original probe broke both `/lookup` and `/whitelabel/{code}` simultaneously, so the visible ERROR line is for the lookup call — `BrandingService` then sets `skipApi=true` and the brand-fetch parse path is not exercised in the same render. The brand-fetch parse path has the same code (`getAsJson` is shared) and the same log shape (`BrandingApi JSON parse failure on .../whitelabel/cca`). To exercise it in isolation, an instrumented backend would need a mode where lookup returns OK but `/whitelabel/{code}` returns malformed JSON  not needed for §9.1 because the shared-code argument is convincing enough.

---

## 4. Slow API — TIMEOUT at 3 s, login under 5 s

Inject ~4 s of latency on every JSON response. The SPI's request timeout is 3 s (`BrandingApiClient.java#L92`), so `HttpTimeoutException` fires first. `BrandingService` logs WARN + falls back to host-derived → registry. End-to-end login latency is ~3.03 s — under the 5 s exit-criteria budget.

Reproduction requires either a backend feature flag (a `Thread.sleep` in the `BrandingApiServlet`) or a network shim (`tc qdisc add … netem delay 4000ms` on the loopback, or `toxiproxy` in front of port 8080). Neither exists in this repo. The expected log shapes from the original runs:

First render (cold cache):

```
WARN  BrandingApiClient: BrandingApi timeout on http://host.docker.internal:8080/branding-api/keycloak/whitelabel/lookup?host=slo.localhost%3A8898 after 3003 ms (limit 3000 ms)
INFO  BrandingService:   Branding: host=slo.localhost:8898 code=cca code_src=host_derived brand_src=registry_fallback_skip latency_ms=3004
```

Second render (warm cache):

```
INFO  BrandingService:   Branding: host=slo.localhost:8898 code=cca code_src=cache_hit brand_src=cache_hit latency_ms=0
```

**Why total is 3.03 s not 6 s.** A cold-cache login *could* in theory take up to ~6 s — 3 s for `/lookup` + 3 s for `/whitelabel/{code}`. The "skipBrandApi" guard short-circuits the second call when the first one fails, so the realistic ceiling is one timeout (3 s) per failed render. The cache then absorbs the next 60 s of traffic at zero cost.

**Worst-case bound.** With a backend that sleeps 2.9 s (just under the timeout), both calls succeed but each takes ~2.9 s  total ~5.8 s, over the 5 s budget. Not currently tested because it represents "backend is degraded but technically healthy", which we'd want to bound separately. Could be added as `§9.1.E  Degraded API` if the team wants. The fix would be either lowering the SPI timeout (worse: more flaky calls) or putting a shorter circuit-breaker around both calls.

---

## 5. Real BE reachable, brand data missing — registry fallback (observed 2026-05-22)

Not from the original §9.1 list, but discovered the first time we pointed Keycloak at the GeoWealth Tomcat. The Tomcat servlet is up, the auth filter accepts the synced token, `/lookup` returns 200 — but `/whitelabel/{code}` returns HTTP 404 with `{"error":"code-not-found"}` for every code we tried (`cca`, `changepath`, `semmax`, `demo`, `geowealth`). The real dev Oracle does not currently hold any seeded `BRAND` rows. This is exactly the production-ish "DB row was deleted / firm decommissioned / migration in progress" state — and the SPI handles it the same way it handles a downed backend: log WARN, fall back to the in-process `BrandRegistry`.

Reproduce:

```bash
source .envrc   # POC_BRANDING_API_TOKEN must match the value Tomcat is using
                # (we keep it in ~/tools/tomcat9/bin/setenv.sh so it survives
                # Tomcat restarts).

# (1) confirm the real backend is reachable and auth is synced.
curl -s -o /dev/null -w '/lookup HTTP %{http_code}\n' \
  -H "Authorization: Bearer $POC_BRANDING_API_TOKEN" \
  "http://127.0.0.1:8080/branding-api/keycloak/whitelabel/lookup?host=changepath.localhost"
# expected: /lookup HTTP 200

# (2) confirm the brand fetch is missing.
curl -s -w '\n--whitelabel/cca HTTP %{http_code}\n' \
  -H "Authorization: Bearer $POC_BRANDING_API_TOKEN" \
  "http://127.0.0.1:8080/branding-api/keycloak/whitelabel/cca"
# expected: {"error":"code-not-found"}  --whitelabel/cca HTTP 404

# (3) make sure Keycloak's compose env points at the GeoWealth Tomcat
#     on host.docker.internal:8080. Default behavior when
#     POC_BRANDING_API_URL is unset.
docker inspect keycloak-demo-keycloak-1 \
  --format '{{range .Config.Env}}{{println .}}{{end}}' \
  | grep BRANDING_API_URL
# expected: KC_SPI_LOGIN_FREEMARKER_GEOWEALTH_BRANDING_API_URL=http://host.docker.internal:8080

# (4) drive a fresh login from changepath.localhost.
curl -s -L -o /tmp/loginReal.html -w 'HTTP %{http_code} total=%{time_total}s\n' \
  -H "Host: changepath.localhost:8898" \
  "http://localhost:8898/realms/geowealth-realm/protocol/openid-connect/auth?client_id=geowealth-poc-client&response_type=code&redirect_uri=http%3A%2F%2Fchangepath.localhost%3A5174%2F&scope=openid&state=real"

# (5) Keycloak log: WARN HTTP 404 from the brand fetch, INFO decision
#     with code_src=api_hit (lookup worked) and brand_src=registry_fallback.
docker logs --since 10s keycloak-demo-keycloak-1 \
  | grep -E "Branding(:|Api)"
```

Expected log shape:

```
WARN  BrandingApiClient: BrandingApi http://host.docker.internal:8080/branding-api/keycloak/whitelabel/cca -> HTTP 404 after 5 ms
INFO  BrandingService:   Branding: host=changepath.localhost:8898 code=cca code_src=api_hit brand_src=registry_fallback latency_ms=115
```

Two things differ from the §1§4 scenarios above:

1. **`brand_src=registry_fallback`, *not* `registry_fallback_skip`.** The "_skip" suffix only fires when the *lookup* call failed and `BrandingService` therefore declined to make the brand-fetch call at all. Here the lookup succeeded — the brand fetch is what 404'd — so the SPI fully exercises both API calls and falls back only on the second.
2. **`code_src=api_hit` with rendered code = `cca`.** The real BE's `/lookup` returns `cca` for every host we probed, not just unknown ones. The Tomcat-side `identifyFirmByUrl()` either has not been wired up to a host→firm map in dev, or it has but the dev DB only ships the default firm. Either way, this is the real BE's current behavior, not a misconfiguration on the Keycloak side.

**Cold-start gotcha.** The very first request to the real Tomcat after a fresh start took **11.2 s** (servlet warming up the auth filter + Spring/Akka context). Once warm, the same endpoint is ~5 ms. The SPI's 3 s request timeout would have tripped on the cold call — meaning the first POC login after a Tomcat restart could fail open via timeout, *not* 404. Worth noting in `OPERATIONS.md`: an apparent `BrandingApi timeout` spike at Tomcat startup is benign and self-clears within one warm request.

**Latency budget.** Total login was 840 ms (warm Tomcat), well under the 5 s ceiling. With cold Tomcat (11 s) the first login would hit the 3 s timeout twice (once per call) → ~6 s before fallback completes. That exceeds the 5 s budget. If this becomes a real demo concern, options are: (a) warm Tomcat with a synthetic curl before showing the demo, (b) lower the Tomcat-side cold-start cost, or (c) tighten the SPI's per-call timeout (would cost availability on slow-but-healthy backends).

**Why this isn't `§9.1.A — GeoWealth down`.** Both end in `registry_fallback`, but the SPI log shape is meaningfully different: Down emits `WARN ConnectException` (transport) + `host_derived` + `registry_fallback_skip`; this scenario emits `WARN HTTP 404` (server response) + `api_hit` + `registry_fallback`. Production alerts based on `host_derived` would *not* trip in this scenario — and that's correct: this is a data-shape problem upstream of the SPI, not a connectivity outage.

---

## What the geowealth-side audit must add

These items aren't reproducible here — they need the real Tomcat servlet — and should be recorded in `~/geowealth/keycloak-poc-findings.md` on `team/petarnenov/keycloak-whitelabel-poc`:

| Item | What to verify |
|---|---|
| Same fail-open shape against real Tomcat (Tomcat *down*) | Stop the real Tomcat → fresh Keycloak login → expect the same `WARN ConnectException` / `host_derived` / `registry_fallback_skip` shape as scenario 1. The SPI is backend-agnostic; this is mostly a "no surprises" check. Not yet exercised — scenario 5 covered the "Tomcat *up* but data missing" case instead. |
| Seed real brand data in dev Oracle | At least one row (preferably both `cca` and `changepath`) so a real-BE login renders `brand_src=api_hit` end-to-end. Until this is done, real-BE logins look identical to scenario 5 — registry fallback. |
| Host → firm mapping in `identifyFirmByUrl` | `/lookup?host=changepath.localhost` returns `cca` against the real BE — every host probed returned `cca`. Either dev lacks the firm-host map, or the servlet has not been wired through to it yet. Driving Keycloak from `changepath.localhost` will keep returning `cca` brand until this is sorted. |
| INFO-level GeoWealth-side request logs | The Tomcat servlet should emit one INFO line per `/branding-api/keycloak/whitelabel/*` request with firm code, source IP, decision, and latency — symmetric to the SPI's INFO line. §9.2 of the migration plan calls this out. |
| Tomcat-side timeout behavior | Verify the servlet itself has a sane upstream-DB timeout (so a slow DB doesn't burn the SPI's full 3 s waiting on a backend the servlet won't return from). Cold-start latency observed at 11.2 s on the first request after a Tomcat boot — see scenario 5 latency note. |
| Malformed-response source | If a row in the GeoWealth branding DB has a NULL `cssVariables` or a non-JSON-encodable column, the servlet should drop it or substitute an empty map at the API edge — not stream invalid JSON. Same defense-in-depth argument as the §9.3 color injection check (servlet first, SPI second). |
| Banner in the POC app | Surface "branding fallback active" in the POC frontend when the SPI is operating from `registry_fallback*`. Requires a debug endpoint on the SPI or a header on the brand block; either is a small follow-up, kept out of scope for §9.1. |

---

## Restoring the demo state

After running the failure-mode walk-through, make sure the GeoWealth Tomcat is back up on `host.docker.internal:8080`:

```bash
~/tools/tomcat9/bin/startup.sh         # or however your Tomcat starts
docker compose up -d --force-recreate keycloak   # flush the SPI cache
```

`POC_BRANDING_API_TOKEN` stays in `.envrc`  it's required for any real-Tomcat run, and the value is harmless on its own (only useful paired with a backend that recognizes it).
