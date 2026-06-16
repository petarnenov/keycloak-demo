# Keycloak SSO — domain stack demo

A working reference for federating standalone domain apps against a single Keycloak realm with **P1 SAML SSO**. Each demo "product" is a self-contained domain under `domains/<name>/` with its own React+Vite frontend and its own (auth-unaware) Micronaut data BFF. All login and the OIDC code flow run through one shared, multi-tenant **token-handler** service using a single shared OIDC client (`demo-shared-client`); domains share only the realm, that token-handler, and the SAML federation to P1.

There are no native Keycloak users in this demo. Every login goes through the SAML broker to P1 — either started from the **P1 sidebar** (Integrations → Demo Billing / Demo Trading) in the `~/geowealth/` Tomcat app, or by visiting a domain SPA URL directly. Both flows end up doing the same thing: Keycloak's authorize endpoint with `kc_idp_hint=p1` → SAML AuthnRequest to P1 → signed Response back → first-broker-login auto-link by email → OIDC code → the token-handler stores the tokens server-side (Redis) and sets the `GWSESSION` session cookie.

## Topology

```
~/geowealth (Tomcat, P1 sidebar) ─┬─ Demo Billing  → https://billing.geowealth.int:5184  → bff-billing :8084
                                  └─ Demo Trading  → https://trading.geowealth.int:5185  → bff-trading :8085

  Keycloak                         :8898    realm "demo-realm" — login client: demo-shared-client (+ vestigial demo-billing-client / demo-trading-client)
  Postgres                                  Keycloak metadata + federated identities

  SAML login (every domain link, every direct SPA visit):
    SPA → Keycloak /auth?kc_idp_hint=p1
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

- `src/auth/AuthProvider.tsx` — checks the server-side session via `/auth/me`; when none is present it redirects to the token-handler's `/oauth/login`, which drives the OIDC code flow against `demo-shared-client`. The SPA never holds tokens — they live server-side behind the token-handler.
- `src/api.ts` — small typed wrapper around `fetch` that calls its own `/api/<name>` (no Authorization header; nginx forward-auth + the `GWSESSION` cookie carry identity)
- `src/App.tsx` — dashboard rendered from the BFF's stub data

Each data BFF has the same shape, and is **auth-unaware** (forward-auth):

- A single Micronaut module with no security filters, no session, no token refresh
- Identity arrives via the `X-Auth-*` headers that nginx injects after the token-handler's `/auth/verify` (`HeaderIdentity`)
- `isAnonymous` controllers (nginx gates `/api/**` via `auth_request`) returning deterministic stub data — no DB, no external calls
- Tokens, sessions, login, logout, refresh, and the per-request authorization decision all live in the shared **token-handler** (Redis-backed sessions)

## Identity tier

- **Keycloak** at `:8898` — stock `quay.io/keycloak/keycloak:26.0.7` image. No custom SPIs.
- **`keycloak/realm-export.json`** — `demo-realm` seed: the shared login client `demo-shared-client` (plus the now-vestigial per-domain `demo-billing-client` / `demo-trading-client` and `p1-self-client` for P1 silent SSO), the p1 SAML identity provider, the SAML attribute mappers (email/firstName/lastName/firmCd/roles), the `p1-first-broker-login` flow.
- **Postgres** — backs Keycloak. Holds federated identities created on first broker login.

Users land in the realm only when P1 authenticates them through the SAML broker. There are no usernames or passwords to manage on the Keycloak side.

## Running it

Prerequisites:

- Docker or Podman + compose
- `mkcert` (or any tool that produces a locally-trusted cert pair)
- `/etc/hosts` entries:

  ```
  127.0.0.1 auth.geowealth.int
  127.0.0.1 billing.geowealth.int
  127.0.0.1 trading.geowealth.int
  ```

- TLS cert pairs at `proxy/certs/<name>.geowealth.int.{crt,key}`. Each public-facing nginx serves HTTPS — keycloak-js v26 uses `crypto.subtle`, which the browser only exposes in secure contexts:

  ```bash
  mkcert -cert-file proxy/certs/auth.geowealth.int.crt \
         -key-file  proxy/certs/auth.geowealth.int.key \
         auth.geowealth.int localhost 127.0.0.1

  mkcert -cert-file proxy/certs/billing.geowealth.int.crt \
         -key-file  proxy/certs/billing.geowealth.int.key \
         billing.geowealth.int localhost 127.0.0.1

  mkcert -cert-file proxy/certs/trading.geowealth.int.crt \
         -key-file  proxy/certs/trading.geowealth.int.key \
         trading.geowealth.int localhost 127.0.0.1
  ```

- `~/geowealth/` Tomcat running on `localhost:8080` — that's where the P1 SAML IdP lives (`/saml/idp/sso.do`). Without it, the SAML chain has nothing to bounce through.

Then:

```bash
./start.sh           # bring everything up
./start.sh --reset   # wipe Postgres + Keycloak data, re-seed from realm-export.json
./stop.sh            # graceful down (volumes preserved)
./stop.sh --wipe     # graceful down + volume wipe
```

The script auto-detects docker vs podman. Override with `CONTAINER_ENGINE=docker|podman`.

After it's up:

- **Keycloak admin console**: <https://auth.geowealth.int:5180/admin/> (admin / admin)
- **Billing FE**: <https://billing.geowealth.int:5184/>
- **Trading FE**: <https://trading.geowealth.int:5185/>

## Testing the login

Two entry points, same flow.

**From the P1 sidebar (the integration scenario):**

1. Log into P1 at `http://localhost:8080/`
2. Sidebar → **Integrations** → click **Demo Billing** or **Demo Trading**
3. A new tab opens Keycloak `…/auth?…&kc_idp_hint=p1`; since you already have a P1 session, P1 returns a signed SAML Response immediately (no login prompt)
4. Keycloak runs first-broker-login (auto-link by email, silent on subsequent visits), issues an OIDC code to the token-handler's callback; the token-handler stores tokens in Redis, sets the `GWSESSION` cookie, and redirects to the domain SPA
5. The dashboard renders with stub data from the BFF (fetched through nginx forward-auth, no token in the browser)

**Directly (same SAML chain, no P1 sidebar):**

1. Open `https://billing.geowealth.int:5184/` (or `…/trading…:5185/`)
2. SPA's `AuthProvider` checks `/auth/me`. No session → it redirects to the token-handler's `/oauth/login`, which kicks the browser to Keycloak with `kc_idp_hint=p1`
3. Same SAML round-trip; if no P1 session exists, P1 prompts; if it does, silent
4. The token-handler exchanges the code, stores tokens in Redis behind the `GWSESSION` cookie, and the SPA renders the dashboard

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

# 2. No new OIDC client. Multi-tenant: add the new host to demo-shared-client's
#    redirectUris / webOrigins / post.logout.redirect.uris in keycloak/realm-export.json
#    (realm imports are IGNORE_EXISTING; for a running realm, PATCH the client via admin API
#    or run scripts/reconcile-realm.sh too), then add one app.tenants.<slug> entry
#    (host + per-host gate) in token-handler/application.yml.
# 3. Add demo-reporting + bff-reporting services in docker-compose.yml
#    (no per-domain token-handler — the one multi-tenant token-handler already fronts it;
#    rebuild + restart token-handler to pick up the new tenant)
# 4. Add /etc/hosts entry + mkcert cert pair
# 5. In ~/geowealth/WebContent/react/app/src/pages/PlatformOne/sidebar/_hooks/useIntegrationLinks.js:
#    links.push({
#      label: 'Demo Reporting',
#      linkUrl: kcAuthorize('demo-shared-client', 'https://reporting.geowealth.int:<port>/', 'demo-reporting'),
#      id: 'demo-reporting',
#      absoluteUrl: true,
#    });
```

## What's not in here

- **No MFE shell, no Module Federation.** A previous iteration of this repo had a React shell at `:5173` lazy-loading three role-gated MFEs through `@originjs/vite-plugin-federation`. That whole layer is gone.
- **No native users, no User Storage SPI, no email OTP, no `user-service`.** Earlier iterations exposed a Keycloak User Storage SPI backed by a separate Micronaut service holding three hardcoded demo users, plus an email-OTP second factor. Everything that depended on Keycloak having its own user store is removed — the realm is fed entirely by P1's SAML broker.
- **No whitelabel POC.** Earlier iterations had a `geowealth-realm` + dynamic-login-theming SPI + branding-API client; all of it is removed.
- **No fancy data layer.** The BFFs return stub data inline (`Map.of(...)`-style literals). Real backends would put a service + DB + cache here.
- **No real billing/trading logic.** The dashboards are visual scaffolding to demonstrate the SSO chain renders something believable; the numbers are mock.

## See also

- `CLAUDE.md` — survival notes for editing this repo
- `keycloak/realm-export.json` — the realm seed
