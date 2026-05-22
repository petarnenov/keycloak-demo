# Fake GeoWealth branding API

Stand-in for the Tomcat-side servlet that the geowealth repo eventually exposes at `/branding-api/keycloak/*`. Lets the Keycloak white-labeling SPI exercise the full loop — host lookup, brand fetch, Caffeine cache, FreeMarker injection — against a real HTTP endpoint, without needing access to the geowealth tree.

When the real backend is up, point Keycloak at it (change `KC_SPI_LOGIN_FREEMARKER_GEOWEALTH_BRANDING_API_URL` in `docker-compose.yml`) and shut this fake down. The contract is identical (see `contracts/branding-api.openapi.yaml`), so the SPI doesn't notice the swap.

## Run

```bash
source .envrc                     # exports POC_BRANDING_API_TOKEN
./dev-branding-api.sh             # or: python3 dev-branding-api/server.py
```

By default the server binds to `127.0.0.1:8080`. Keycloak in the compose stack reaches it via `host.docker.internal:8080` on macOS (Docker Desktop / Podman). Override with `HOST=` / `PORT=`.

The token is read from `POC_BRANDING_API_TOKEN`. The server refuses to start without it — a fake that silently lets unauthenticated calls through would mask real misconfiguration.

## Endpoints

All match `contracts/branding-api.openapi.yaml`.

```bash
T="$POC_BRANDING_API_TOKEN"

# Brand JSON — what BrandingApiClient.fetchBrand() consumes.
curl -s -H "Authorization: Bearer $T" \
  http://127.0.0.1:8080/branding-api/keycloak/whitelabel/changepath | jq

# Host → firm code — what BrandingApiClient.lookupHost() consumes.
curl -s -H "Authorization: Bearer $T" \
  "http://127.0.0.1:8080/branding-api/keycloak/whitelabel/lookup?host=changepath.localhost:8898" | jq

# Asset BLOB — placeholder SVGs / a tiny PDF byte sequence. Not consumed by
# the Keycloak SPI today; pinned for future phases.
curl -s -H "Authorization: Bearer $T" \
  http://127.0.0.1:8080/branding-api/keycloak/whitelabel/changepath/asset/logo-login
```

## Firms served

| code | displayName | colors |
|---|---|---|
| `changepath` | ChangePath | dark blue + soft green gradient |
| `cca` | GeoWealth | terracotta + teal + sand gradient (default firm) |

Color palettes mirror `geowealth-keycloak/.../BrandRegistry.java` exactly, so the rendered login looks identical whether the SPI is reading from this fake or falling back to its hardcoded registry. If you change palettes here, mirror them in `BrandRegistry`, or vice versa, or the fallback will visually drift from the API path.

## What this fake intentionally doesn't do

- **No retries / no backoff.** The Keycloak SPI is fail-open with a 3 s request timeout; this server should respond fast or be killed.
- **No persistence.** Restarting wipes nothing because there is no state.
- **No HTTPS.** Dev-only; the real GeoWealth side will be on a service-mesh / internal listener.
- **No invalidation webhook.** The architecture doc's `POST /invalidate` endpoint isn't here — the Keycloak side relies on its 60 s TTL during the POC.
