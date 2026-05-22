# GeoWealth white-labeling POC — quick start

A second Vite app at `:5174` that signs into a second Keycloak realm
(`geowealth-realm`) whose login page is **branded per firm based on the
hostname the user typed into the browser**. Sibling app to the existing
MFE shell at `:5173`; it doesn't modify the demo stack and can be torn
down without touching it.

This README is the operator's view: how to start it, what to expect,
and where to look when something is off. The architecture and rationale
live in `../../../geowealth-keycloak-poc-migration-plan.md` and
`../../../geowealth-keycloak-whitelabel-sync.md`; security probes in
`../../../SECURITY-CHECKS.md`.

---

## What you should see

| You open | You get |
|---|---|
| `http://changepath.localhost:5174/` → "Sign in" | Keycloak login at `changepath.localhost:8898`, **ChangePath** logo + palette |
| `http://default.localhost:5174/` → "Sign in"   | Keycloak login at `default.localhost:8898`, **GeoWealth** default palette |
| `http://localhost:5174/` → "Sign in"           | Same as `default.localhost` (firm code falls back to `default`) |

Login as `poc-user` / `123` (the only user in `geowealth-realm`). On
return, the POC app shows the username, email, and roles parsed from the
ID token.

The branding decision is logged once per render on the Keycloak side; see
`OPERATIONS.md` for the exact greps.

---

## Prerequisites

1. **Docker or Podman.** `./start.sh` from the repo root auto-detects;
   override with `CONTAINER_ENGINE=docker|podman`.
2. **`.envrc`** must export `POC_BRANDING_API_TOKEN`. The dev fake and
   the Keycloak SPI both read it from there. Generate one once:

   ```bash
   echo "export POC_BRANDING_API_TOKEN=\"\$(openssl rand -base64 32)\"" >> .envrc
   direnv allow .  # or `source .envrc`
   ```
3. **`*.localhost` resolution.**
   - **macOS:** works out of the box — `*.localhost` resolves to
     `127.0.0.1` via `mDNSResponder`. No `/etc/hosts` edits.
   - **Linux:** systemd-resolved handles `.localhost` by default. If
     yours doesn't, add four lines to `/etc/hosts`:

     ```text
     127.0.0.1 changepath.localhost
     127.0.0.1 default.localhost
     127.0.0.1 cca.localhost
     127.0.0.1 unknown.localhost
     ```
4. **Python 3.8+** on `PATH` (only if you'll run the dev fake; see below).

---

## Start the stack

From the repo root:

```bash
./start.sh            # demo mode
# — or —
./start.sh --dev      # MFE rebuild-on-save (doesn't affect this POC)
```

The Keycloak SPI in `geowealth-keycloak/` will try to reach the GeoWealth
branding API at `http://host.docker.internal:8080` (compose env, already
wired). One of two states is acceptable for the demo:

| State | Behavior | When to use |
|---|---|---|
| **Fake branding API up** (`./dev-branding-api.sh`) | Full cache/lookup/fetch path exercised; theme variables sourced from the fake's brand map. | Default — closest to production shape. |
| **Nothing on `:8080`** | SPI's HTTP call fails; falls back to the hardcoded `BrandRegistry` (`changepath`, `cca`, `default`). | Quick demo without spinning up a second shell. |

Either way the login page renders correctly. The difference is whether
the brand entries came over HTTP or from the in-JVM map.

### Start the fake (recommended)

In a second terminal:

```bash
./dev-branding-api.sh        # foreground, ctrl-c to stop
```

It binds `127.0.0.1:8080`, serves
`GET /branding-api/keycloak/lookup-host?host=<host>` and
`GET /branding-api/keycloak/whitelabel/<code>` per
`contracts/branding-api.openapi.yaml`, and requires the same bearer
token the SPI carries.

Knobs (read at startup; restart to change):

| Env var | Effect |
|---|---|
| `SLEEP_MS=4000`           | Adds delay; the SPI's 3 s timeout will fire and fall back. |
| `BREAK_MODE=json`         | Returns invalid JSON. |
| `BREAK_MODE=status_500`   | Returns 500. |
| `BREAK_MODE=status_503`   | Returns 503. |
| `INJECT_POISON=1`         | Adds malicious entries to `cssVariables` — the SPI's `BrandCss` validator must drop them. |
| `PORT=18080`              | Different port (then also set `KC_SPI_LOGIN_FREEMARKER_GEOWEALTH_BRANDING_API_URL` on the keycloak service). |

---

## Verify

```bash
# (1) the POC app is up
curl -sI http://changepath.localhost:5174/ | head -1
# HTTP/1.1 200 OK

# (2) Keycloak login page renders with the firm-specific brand block
curl -s "http://changepath.localhost:8898/realms/geowealth-realm/protocol/openid-connect/auth?client_id=geowealth-poc-client&response_type=code&redirect_uri=http://changepath.localhost:5174/&scope=openid&state=t" \
  | grep -A2 'id="geowealth-brand-vars"' | head -5

# (3) one INFO line per render with the decision path
docker compose logs --since 30s keycloak | grep -E '^.*Branding:'
# example:
# INFO  Branding: host=changepath.localhost:8898 code=changepath code_src=api_hit brand_src=api_hit latency_ms=12
```

A green end-to-end check is two browser tabs (`changepath.localhost:5174`
and `default.localhost:5174`) showing visibly different login pages,
both signing in cleanly.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| POC app loads, but login button does nothing | `keycloak.init` failed silently. | Check browser console; usually `webOrigins`/`redirectUris` doesn't include the hostname. Update `keycloak/geowealth-realm-export.json`, then either `down -v` (wipes both realms) or PATCH via admin API. |
| Login page is unstyled / blank | Theme JAR didn't land in the Keycloak image. | `docker compose build keycloak && docker compose up -d --force-recreate keycloak`. SPI changes need a full image rebuild. |
| Both hosts render the same default brand | SPI couldn't reach the branding API and the registry has no entry for that firm code. | Confirm `./dev-branding-api.sh` is up, then check Keycloak logs for `Branding: ... brand_src=registry_fallback*` lines. |
| Slow login (~3 s pause) | Branding API is reachable but slow. | The SPI's HTTP timeout is 3 s; expect one stall then fallback. Restart the fake without `SLEEP_MS`, or check that `host.docker.internal` resolves. |
| Linux: `host.docker.internal` doesn't resolve from the keycloak container | Docker Desktop only feature. | Add `extra_hosts: ["host.docker.internal:host-gateway"]` to the keycloak service in `docker-compose.yml`. |
| Browser shows `*.localhost` as "site can't be reached" | Linux resolver doesn't route `.localhost`. | Add the four `/etc/hosts` entries above. |

---

## How to wipe and re-import the realm

The geowealth realm only loads from `geowealth-realm-export.json` on a
**fresh DB**. To pick up an edit you made there:

```bash
docker compose down -v          # wipes the demo realm too
./start.sh
```

For live edits without losing sessions, use the admin API on port 8898
(`/admin/realms/geowealth-realm/...`). The CLAUDE.md has the token grab.

---

## Where things live

| File / dir | What |
|---|---|
| `src/App.tsx`, `src/firm.ts`, `src/auth/` | This POC app — React + keycloak-js, ~150 lines total. |
| `vite.config.ts` | Wildcards `.localhost` so `changepath.localhost:5174` works. |
| `../../../geowealth-keycloak/` | Keycloak SPI: `LoginFormsProvider` override, `BrandingService`, `BrandingApiClient`, `BrandCss` validator, branded theme. |
| `../../../keycloak/geowealth-realm-export.json` | Realm seed: `geowealth-poc-client`, the four allowed redirect URIs, the `poc-user`. |
| `../../../dev-branding-api/`, `../../../dev-branding-api.sh` | The Python fake standing in for the GeoWealth Tomcat. |
| `../../../contracts/branding-api.openapi.yaml` | The wire contract both sides target. |
| `../../../OPERATIONS.md` *(coming next)* | Log greps, expected latencies, cache hit ratio. |
| `../../../SECURITY-CHECKS.md` | Phase 4 §9.3 security probe results + repro. |
| `../../../geowealth-keycloak-poc-migration-plan.md` | Full plan, phase exit criteria, risk register. |

---

## What the POC is **not**

See `../../../geowealth-keycloak-poc-migration-plan.md` §10 for the full
production-gap table. Highlights:

- Single-node Keycloak; no Infinispan-backed cache fan-out.
- Public OIDC client; production wants per-app confidential clients.
- Static token; production wants a secret manager + rotation.
- Assets streamed from the (fake) Tomcat; production wants a CDN.
- No webhook for brand invalidation — relies on the 60 s TTL.
- English only. No per-locale theming.
