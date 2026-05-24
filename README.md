# Keycloak SSO — domain stack demo

A working reference for federating standalone domain apps against a single Keycloak realm with **P1 SAML SSO**. Each demo "product" is a self-contained domain under `domains/<name>/` with its own React+Vite frontend, its own Micronaut BFF, and its own OIDC client. Domains share only the realm and the SAML federation to P1; they never share code.

Domains are surfaced through the **P1 sidebar** (under *Integrations*) in the original GeoWealth Tomcat app at `~/geowealth/`. Clicking a link bounces through Keycloak → P1 → back, lands on the domain SPA already signed in.

## Topology

```
~/geowealth (Tomcat, P1 sidebar) ─┬─ Demo Billing  → https://billing.geowealth.int:5184  → bff-billing :8084
                                  └─ Demo Trading  → https://trading.geowealth.int:5185  → bff-trading :8085

  Keycloak                         :8898    realm "demo-realm" — clients: demo-billing-client, demo-trading-client
  user-service                     :8090    REST source of truth for the 3 demo users
  Postgres                                  Keycloak metadata + federated identities

  Identity flow (every domain link):
    browser → Keycloak /auth?kc_idp_hint=p1
            → P1 /saml/idp/sso.do  (SP-init SAML, signed Response with InResponseTo)
            → Keycloak (correlate, first-broker-login auto-link by email)
            → domain SPA at https://<name>.geowealth.int:<port>/
```

## Domains in the demo

| Domain | Source | FE | BFF |
|---|---|---|---|
| billing | `domains/billing/` | `https://billing.geowealth.int:5184` | `:8084` — `/api/summary`, `/api/invoices`, `/api/usage` |
| trading | `domains/trading/` | `https://trading.geowealth.int:5185` | `:8085` — `/api/portfolio`, `/api/positions`, `/api/orders` |

Each FE has the same shape:

- `src/auth/keycloak.ts` — `new Keycloak({ url, realm, clientId })` with sensible defaults that match the demo realm
- `src/auth/AuthProvider.tsx` — singleton-gated `keycloak.init({ onLoad: 'check-sso' })`, falls through to `keycloak.login({ idpHint: 'p1' })` when no session is present
- `src/api.ts` — small typed wrapper around `fetch` that pulls a fresh token via `keycloak.updateToken(30)` before each call
- `src/App.tsx` — dashboard rendered from the BFF's stub data

Each BFF has the same shape:

- A single Micronaut module with `io.micronaut.security:micronaut-security-jwt`
- JWT validation against Keycloak's JWKS endpoint (`/realms/demo-realm/protocol/openid-connect/certs`)
- `@Secured({"isAuthenticated()"})` controllers returning deterministic stub data — no DB, no external calls
- CORS allowlist for the matching domain origin only

## Identity tier (shared)

- **`user-service/`** — standalone Micronaut REST service that owns the demo users. Source of truth for `verify-credentials`, `findByUsername`, `findByEmail`.
- **`keycloak-provider/`** — Keycloak SPI jar with two providers in one:
  - **`DemoUserStorageProvider`** — User Storage SPI that delegates lookup and password verify to `user-service` over REST.
  - **`EmailOtpAuthenticator`** — second-factor email OTP step (used only on the browser flow, not on direct-grant or SAML-brokered flows).
- **`user-api/openapi.yaml`** — contract between the SPI client and `user-service`. Both sides regenerate from it at build time.
- **`keycloak/realm-export.json`** — `demo-realm` seed: the two OIDC clients, realm roles, SPI registration, `demo-browser` flow with the OTP step, P1 SAML broker config.

Three demo users (hardcoded in `user-service/src/main/java/demo/userservice/UserController.java`, identified by email because `loginWithEmailAllowed=true`):

| Username | Email | Password | Roles |
|---|---|---|---|
| democlient | nikiiv.linococo@gmail.com | 123 | `client` |
| demouser   | nikolay.ivanchev@gmail.com | 123 | `user` |
| demoadmin  | nikolai.ivanchev@gmail.com | 123 | `admin`, `user` |

In the live demo, identity normally arrives through SAML federation from P1, so the OTP step is bypassed (P1 already authenticated the user). Direct-grant against demo-realm still works for `curl` testing and also bypasses OTP.

## Running it

Prerequisites:

- Docker or Podman + compose
- `mkcert` (or any tool that produces a locally-trusted cert pair)
- `/etc/hosts` entries:

  ```
  127.0.0.1 billing.geowealth.int
  127.0.0.1 trading.geowealth.int
  ```

- TLS cert pairs (Vite preview must serve HTTPS — keycloak-js v26 uses `crypto.subtle`, which the browser only exposes in secure contexts):

  ```bash
  mkcert -cert-file proxy/certs/billing.geowealth.int.crt \
         -key-file  proxy/certs/billing.geowealth.int.key \
         billing.geowealth.int localhost 127.0.0.1

  mkcert -cert-file proxy/certs/trading.geowealth.int.crt \
         -key-file  proxy/certs/trading.geowealth.int.key \
         trading.geowealth.int localhost 127.0.0.1
  ```

Then:

```bash
./start.sh           # bring everything up
./start.sh --reset   # wipe Postgres + Keycloak data, re-seed from JSON exports
./stop.sh            # graceful down (volumes preserved)
./stop.sh --wipe     # graceful down + volume wipe
```

The script auto-detects docker vs podman. Override with `CONTAINER_ENGINE=docker|podman`.

After it's up:

- **Keycloak admin console**: <http://localhost:8898/admin/> (admin / admin)
- **user-service**: <http://localhost:8090/health>
- **Billing FE**: <https://billing.geowealth.int:5184/>
- **Trading FE**: <https://trading.geowealth.int:5185/>

To exercise the full P1 → Keycloak → domain flow, you also need the `~/geowealth/` Tomcat app running (separate repo). Hit a domain SPA directly first to validate the SSO chain on its own; once that works, drive it from P1's sidebar.

## Sanity checks

Token + endpoint smoke test (direct-grant; bypasses OTP and SAML):

```bash
TOKEN=$(curl -s -X POST "http://localhost:8898/realms/demo-realm/protocol/openid-connect/token" \
  -d "client_id=demo-billing-client&grant_type=password&username=demouser&password=123" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')

curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8084/api/summary   | python3 -m json.tool
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8085/api/portfolio | python3 -m json.tool
```

If both return JSON: Keycloak issues tokens, the BFFs trust Keycloak's JWKS, the realm clients are wired. If you get a `401`, decode the JWT (`echo "$TOKEN" | cut -d. -f2 | base64 -d`) and check the `iss` claim — it must match the BFF's `KEYCLOAK_AUTH_SERVER_URL`.

## Adding a new domain

```bash
# 1. Copy + rename
cp -r domains/billing domains/reporting
# Then edit:
#   domains/reporting/web/package.json    → name
#   domains/reporting/web/vite.config.ts  → ports, allowedHosts, proxy path
#   domains/reporting/web/src/auth/keycloak.ts → KC_CLIENT default
#   domains/reporting/web/Dockerfile      → ports
#   domains/reporting/bff/settings.gradle → rootProject.name
#   domains/reporting/bff/build.gradle.kts→ group, application class
#   domains/reporting/bff/Dockerfile      → jar name
#   domains/reporting/bff/src/main/java/demo/reporting/* → new package + controller
#   domains/reporting/bff/src/main/resources/application.yml → app.source, CORS_ORIGIN default

# 2. Register the new OIDC client in keycloak/realm-export.json (model after demo-billing-client)
# 3. Add demo-reporting + bff-reporting services in docker-compose.yml
# 4. Add /etc/hosts entry + mkcert cert pair
# 5. In ~/geowealth/WebContent/react/app/src/pages/PlatformOne/sidebar/_hooks/useIntegrationLinks.js:
#    links.push({
#      label: 'Demo Reporting',
#      linkUrl: kcAuthorize('demo-reporting-client', 'https://reporting.geowealth.int:<port>/', 'demo-reporting'),
#      id: 'demo-reporting',
#      absoluteUrl: true,
#    });
```

## What's not in here

- **No MFE shell, no Module Federation.** The previous iteration of this repo had a React shell at `:5173` lazy-loading three role-gated MFEs through `@originjs/vite-plugin-federation`. That whole layer is gone — the domain stack replaces it. See `CLAUDE.md` for the editing notes that survived the migration.
- **No fancy data layer.** The BFFs return stub data inline (`Map.of(...)`-style literals). Real backends would put a service + DB + cache here.
- **No real billing/trading logic.** The dashboards are visual scaffolding to demonstrate the SSO chain renders something believable; the numbers are mock.

## See also

- `CLAUDE.md` — survival notes for editing this repo
- `keycloak/realm-export.json` — the realm seed
- `geowealth-keycloak/` — separate Keycloak SPI for white-label login theming (orthogonal to the domain stack; ships in the same Keycloak image)
