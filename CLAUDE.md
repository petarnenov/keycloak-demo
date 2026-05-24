# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Survival notes for working in this repo. The **README.md** is the user-facing doc; this file only captures things that matter when editing the code and that you can't reliably infer from a first read.

## What this is

A Keycloak SSO demo arranged as a **production-style domain stack**. There is no MFE shell anymore. Each demo "product" is a self-contained domain under `domains/<name>/` with its own FE and its own BFF, sharing only the realm and the SAML federation to P1. Domains are surfaced as entries in the P1 sidebar (under **Integrations**) in the original GeoWealth Tomcat app at `~/geowealth/`.

Current domains:

| Domain | FE host | FE port | BFF service | BFF port | OIDC client |
|---|---|---|---|---|---|
| billing | `billing.geowealth.int` | 5184 | `bff-billing` | 8084 | `demo-billing-client` |
| trading | `trading.geowealth.int` | 5185 | `bff-trading` | 8085 | `demo-trading-client` |

Each FE is a small standalone React + Vite + keycloak-js app, packaged into its own image via its own `Dockerfile` (no bind-mount). Each BFF is a standalone Micronaut module with its own `Dockerfile` and its own image. P1 SAML federation provides the actual identity; the per-domain SPA opens Keycloak's authorize endpoint with `kc_idp_hint=p1` so Keycloak skips its own login screen and bounces straight to P1's IdP.

The identity tier is shared and unchanged:

- **`user-service/`** — standalone Micronaut REST service that owns the demo users. Hardcoded `USERS` map in `src/main/java/demo/userservice/UserController.java`.
- **`keycloak-provider/`** — Keycloak SPI jar that talks REST to `user-service` (User Storage SPI + Email-OTP Authenticator). This is the canonical "where do users come from" pattern; preserving it across this refactor was a hard requirement.
- **`user-api/openapi.yaml`** — contract between the two; both sides regenerate from it at build time.
- **`keycloak/realm-export.json`** — `demo-realm` seed: OIDC clients, realm roles, SPI registration, `demo-browser` flow with the OTP step, P1 SAML broker config.

Demo users (hardcoded in `user-service/.../UserController.java`) — identified by email because `loginWithEmailAllowed=true`:

| Username | Email | Password | Roles |
|---|---|---|---|
| democlient | nikiiv.linococo@gmail.com | 123 | `client` |
| demouser   | nikolay.ivanchev@gmail.com | 123 | `user` |
| demoadmin  | nikolai.ivanchev@gmail.com | 123 | `admin`, `user` |

In the live demo, identity normally arrives through the SAML federation, not direct username/password. Direct-grant on demo-realm still works for `curl` testing (see "Testing" below) but bypasses the OTP step.

## Layout and where to make common changes

- `domains/<name>/web/` — the FE. Standalone npm project (no monorepo), Vite + React + keycloak-js. Builds into its own image via `domains/<name>/web/Dockerfile`. Talks only to its own BFF via Vite preview's `/api/<name>` proxy. Auth lives in `src/auth/AuthProvider.tsx` + `src/auth/keycloak.ts` — `keycloak.init({ onLoad: 'check-sso' })` once per page load, gated by a module-level `initPromise` so React 18 StrictMode doesn't double-init. If `check-sso` returns false, `keycloak.login({ idpHint: 'p1' })` jumps to the SAML broker.
- `domains/<name>/bff/` — the BFF. Standalone Micronaut module, fat-jar via `com.gradleup.shadow`, separate image. Bears + validates the JWT against Keycloak's JWKS endpoint (`KEYCLOAK_AUTH_SERVER_URL/protocol/openid-connect/certs`). The controller (`<Name>Controller.java`) is just `@Get` endpoints returning stub data; no DB, no external calls.
- `user-service/`, `keycloak-provider/`, `user-api/`, `keycloak/realm-export.json` — see above. Identity tier; rarely changes during day-to-day domain work.
- `geowealth-keycloak/` — Keycloak SPI for white-labeling (LoginFormsProvider override). Same image as the User-Storage SPI; ships in `Dockerfile.keycloak` alongside `keycloak-provider/`. Independent of the domain stack.
- `keycloak/geowealth-realm-export.json` — a second realm (`geowealth-realm`) imported alongside `demo-realm`. Currently unused by the active domains; legacy from the whitelabel POC.
- `docker-compose.yml` — services: `postgres`, `keycloak` (8898), `user-service` (8090), then one `demo-<name>` + one `bff-<name>` per domain. No MFE shell, no shared BFF image.
- `Dockerfile.keycloak` — multi-stage: `gradle shadowJar` on `keycloak-provider/` (and `geowealth-keycloak/`) → `kc.sh build`. Build context is the **repo root** so the build can read `user-api/`. SPI changes need a full image rebuild.
- `start.sh` / `stop.sh` — canonical entrypoints. Auto-detect docker vs podman, source `.envrc`, tear down (volumes preserved by default), rebuild, bring everything back up. `./start.sh --reset` and `./stop.sh --wipe` are the only opt-in destructive paths.

The P1 sidebar that links into these domains lives **outside** this repo, in `~/geowealth/WebContent/react/app/src/pages/PlatformOne/sidebar/_hooks/useIntegrationLinks.js`. Each domain gets a `links.push(...)` with the Keycloak authorize URL + `kc_idp_hint=p1` + `redirect_uri` pointing at the domain SPA.

## Adding a new domain

The shape is fixed; copy `domains/billing/` (or `trading/`) and adapt. Roughly:

1. Pick a slug (`reporting`), a host (`reporting.geowealth.int`), a FE port (next free `518X`), a BFF port (next free `808X`).
2. `cp -r domains/billing domains/reporting` and rename: `package.json#name`, `vite.config.ts` (proxy path, ports, allowedHosts), `src/auth/keycloak.ts` (KC_CLIENT default), Gradle group/rootProject, Java package, controller class, all stub data.
3. Add a new OIDC client to `keycloak/realm-export.json` (model after `demo-billing-client`); add it to the live realm via admin API too, since realm imports are `IGNORE_EXISTING` on existing DBs.
4. Add `demo-<name>` and `bff-<name>` services to `docker-compose.yml`.
5. Add `/etc/hosts` entry `127.0.0.1 <name>.geowealth.int` and generate an mkcert cert pair at `proxy/certs/<name>.geowealth.int.{crt,key}`.
6. In `~/geowealth/...useIntegrationLinks.js`, push a link with `kcAuthorize('demo-<name>-client', 'https://<name>.geowealth.int:<port>/', 'demo-<name>')`.

## Persistence model — what survives a restart

| Lives in | Persists across | Wiped only by |
|---|---|---|
| Keycloak realms, native users, federated identities, sessions, role mappings, live admin-API edits | `docker compose restart`, `docker compose down` + `up`, `./start.sh`, `./stop.sh` then `./start.sh`, image rebuild + `--force-recreate` | `docker compose down -v`, **`./start.sh --reset`**, or **`./stop.sh --wipe`** |
| Postgres data backing all of the above | same | same |
| `keycloak/data` (import sources, KeyStore, exported state) | same | same |
| `user-service` user store (the `democlient` / `demouser` / `demoadmin` map) | always — it's a hardcoded `Map.of(...)` in `UserController.java` | source edit + rebuild |

Practical consequences:

- **A SAML-brokered login through P1 writes a federated identity to Keycloak's Postgres on first sign-in.** That identity survives every routine `restart` / `down+up` / image rebuild. The next time the same P1 user lands on the broker flow, Keycloak finds the existing record and skips the first-broker-login flow.
- **A user created through the admin UI or self-registration** is a native user in the realm's Postgres tables. Same persistence guarantees as brokered identities.
- **The three `user-service` demo users** are not persisted because they don't need to be — they're code-defined and re-appear on every container start. To add a new demo user, edit `user-service/src/main/java/demo/userservice/UserController.java`, then `podman compose up -d --build --force-recreate user-service`. The Keycloak side picks the change up on the next login (the SPI is `NO_CACHE`).
- **`./start.sh` and `./stop.sh` are non-destructive.** Both run `down` (without `-v`) so the Postgres volume stays in place.

## Non-obvious runtime gotchas

- **Realm imports are `IGNORE_EXISTING` by default.** `--import-realm` seeds an empty Postgres only. To apply a `realm-export.json` edit on a running stack, either `./start.sh --reset` (destructive — wipes all users) or change the live realm via admin API (fast, preserves sessions). Always update both files if you want reproducibility.
- **SPI auto-creates realm roles.** `DemoUser.getRoleMappingsInternal()` calls `realm.addRole(name)` if a referenced role is missing, so roles named by user-service records that aren't in `realm-export.json` still work at runtime — but a fresh-DB install would lack them until the first login. Keep BFF-gated role names declared in the JSON too.
- **`defaultRoles` is deprecated in Keycloak 26.** The realm JSON no longer has `"defaultRoles"`; the old effect (every user gets `user`) lived in the `default-roles-demo-realm` composite, from which we removed `user`. Don't add `defaultRoles` back — it won't behave as you expect.
- **SPI is `NO_CACHE`.** The realm component config sets `cachePolicy: NO_CACHE`, so every Keycloak lookup hits `user-service` over REST. Changes in `user-service` are visible on the next request (good for the demo) but every login is at least 3 round-trips over the compose network — don't be surprised by the latency.
- **`UserServiceClient` is fail-closed.** Any non-200 / transport error on `/users/verify-credentials` returns `false`. If `user-service` is down, a `grant_type=password` request returns `invalid_grant` rather than hanging. Lookups (`getUserByUsername`, etc.) similarly map any error to `null`. This is intentional: never authenticate when the user store is unreachable.
- **Generated code lives in `build/`, not `src/`.** `openApiGenerate` writes `keycloak-provider/build/generated/openapi/` and `user-service/build/generated/openapi/`. Both are gitignored and regenerated every build. Editing them is pointless. `user-service/openapi-generator-ignore` suppresses the generator's sample controller stub so only the hand-written `UserController` compiles.
- **The provider jar ships shaded Jackson.** `shadowJar` in `keycloak-provider/build.gradle.kts` relocates `com.fasterxml.jackson` → `com.example.keycloak.shaded.jackson` so the in-JVM SPI client cannot collide with the Jackson Keycloak already loads on the provider classpath. If you add a new dependency to the provider, think about classloader clashes.
- **All BFFs and `user-service` run as baked fat jars, not `gradle run`.** Each Micronaut module is built once into its own image (`<module>/Dockerfile`) using the `com.gradleup.shadow` plugin → `eclipse-temurin:17-jre` base. PID 1 is `java -jar /app/<svc>.jar`; there is no source tree, no Gradle cache, and no bind mount. A source edit needs a `--build --force-recreate`. `--force-recreate` matters: after a rebuild, the `*:latest` tag points to a new image ID, but existing containers still hold the *old* ID at creation time. `up -d` alone won't notice; `--force-recreate` unconditionally destroys + recreates so they bind to the new image.
- **Shadow plugin is declared explicitly in every BFF's `build.gradle.kts`.** Micronaut 4.4's application plugin doesn't register `shadowJar` on its own (it prefers its own `buildLayers`/`dockerBuild` flow); each domain BFF adds `id("com.gradleup.shadow") version "8.3.5"` so `gradle shadowJar` produces `build/libs/*-all.jar` for the Dockerfiles to copy. All Gradle modules use the Kotlin DSL — `build.gradle.kts`.
- **Build context for the Keycloak and user-service images is the repo root**, not their own subdirectories. Both Dockerfiles read `user-api/openapi.yaml` as a sibling of their module, so the compose `build.context` is `.` with an explicit `dockerfile:` path. Don't rewrite these to use a subdirectory context — generation will fail to find the spec.
- **Keycloak admin API is on port 8898 (not 8080).** Get a token at `/realms/master/protocol/openid-connect/token` with `client_id=admin-cli&grant_type=password&username=admin&password=admin`. Admin endpoints live under `/admin/realms/demo-realm/...`.
- **`keycloak-js` must be ≥ 26.x.** Older versions validate a `nonce` claim that Keycloak 26 no longer emits.
- **Domain hosts need HTTPS.** keycloak-js v26 uses `crypto.subtle`, which the browser only exposes in secure contexts: HTTPS, or the loopback hostnames `localhost`/`127.0.0.1`. A custom hostname like `billing.geowealth.int` resolves to 127.0.0.1 via `/etc/hosts` but the browser classifies secure-context by hostname literal, not resolved IP — so plain `http://billing.geowealth.int:5184` is NOT secure and `crypto.subtle` is `undefined` there. Each domain serves HTTPS via Vite preview with mkcert-issued certs at `proxy/certs/<name>.geowealth.int.{crt,key}`. mkcert installs a local CA into the host keychain, so the certs are trusted without browser warnings. Generate with `mkcert -cert-file proxy/certs/<name>.geowealth.int.crt -key-file proxy/certs/<name>.geowealth.int.key <name>.geowealth.int localhost 127.0.0.1`.

## P1 SAML federation flow

The login lifecycle, in short form. The dedicated deep-dive is `LOGIN.md` at the repo root.

1. User clicks a P1/Integrations sidebar link → browser opens `http://localhost:8898/realms/demo-realm/protocol/openid-connect/auth?client_id=demo-<name>-client&response_type=code&scope=openid&redirect_uri=https://<name>.geowealth.int:518X/&kc_idp_hint=p1&state=…`.
2. Keycloak sees `kc_idp_hint=p1`, skips its own login screen, emits a SAML AuthnRequest to P1's `/saml/idp/sso.do`.
3. P1's `IdpSsoAction` parses the AuthnRequest ID, reads the active P1 session, builds a signed Response with `InResponseTo=ID`, posts back to Keycloak's broker endpoint.
4. Keycloak correlates by `InResponseTo`, runs first-broker-login (auto-link by email, silent), issues an OIDC code, redirects to `redirect_uri` (the domain SPA).
5. The SPA's `AuthProvider` runs `keycloak.init({ onLoad: 'check-sso' })`, picks up the code, exchanges it for a token, and renders the dashboard.

Pure IdP-init (P1 → Keycloak with unsolicited Response) does **not** work cleanly against an OIDC client target in Keycloak — the `/endpoint` path needs SP-init correlation, and the `/endpoint/clients/{id}` variant requires SAML protocol clients. SP-init via `kc_idp_hint` is the supported flow.

## 2FA gotchas

- **Email OTP runs only on the browser flow.** The realm's `browserFlow` is `demo-browser`, which chains `auth-username-password-form` then `demo-email-otp`. Direct-grant (`grant_type=password`) uses the stock `direct grant` flow and **bypasses OTP** — convenient for `curl` tests. In the live demo, identity comes through SAML federation, so the OTP step is also bypassed (P1 already authenticated the user; Keycloak's broker flow doesn't re-prompt).
- **OTP code lives in `AuthenticationSessionModel` notes.** `EmailOtpAuthenticator` stores `email-otp-code` + `email-otp-expires`; no Postgres schema. If a code expires, `action()` re-enters `authenticate()` and mints a new one in place (user stays on the OTP form).
- **Resend sandbox only delivers to the account owner.** `onboarding@resend.dev` → only the email address registered with the Resend account actually receives. Other addresses return `202 Accepted` from the API but aren't delivered. Mitigation: `EmailOtpAuthenticator.authenticate()` always logs `Email OTP for <email>: NNNNNN (valid 10min)` at INFO, so the demo is usable without inbox delivery (`podman compose logs keycloak | grep "Email OTP"`). Proper fix is to verify a domain at Resend and set `RESEND_FROM` to an address in that domain.
- **`RESEND_API_TOKEN` unset is a supported mode.** `ResendClient.send()` short-circuits with a calm INFO log if the token is empty — no exception, no stack trace — and login continues using the logged OTP. The token lives in `.envrc` (gitignored) and is read via `System.getenv()`; never baked into the image.
- **10-minute validity is for the *code*, not the session.** Once authentication completes, the realm's existing `accessTokenLifespan` / `ssoSessionIdleTimeout` / `ssoSessionMaxLifespan` apply as before. No forced logout at 10 minutes.
- **`jakarta.ws.rs-api` is a `compileOnly` dep.** `EmailOtpAuthenticator` imports `jakarta.ws.rs.core.Response` and `MultivaluedMap`; Keycloak's SPI jars don't transitively expose jakarta-ws-rs at compile time, so `keycloak-provider/build.gradle.kts` declares it. If you build a new authenticator that uses JAX-RS types, the build fails without this dep.
- **Authenticator ID is `demo-email-otp`.** If you rename it in `EmailOtpAuthenticatorFactory.PROVIDER_ID`, update the `"demo-browser forms"` flow in `realm-export.json` to match, or the flow won't load on fresh import.
- **OTP trust cookie skips the email step.** After a successful OTP, `EmailOtpAuthenticator.issueTrustCookie()` sets `KC_DEMO_OTP_TRUSTED` (HttpOnly, realm-scoped, SameSite=Lax). The cookie carries `userId.expiresAt.hmac` (HMAC-SHA256 with a process-local key). On the next `authenticate()`, a matching unexpired cookie short-circuits the flow via `context.success()` — no code generated, no email sent. Window is `OTP_TRUST_WINDOW_MINUTES` (default 60; `0` disables the feature and always requires OTP). The HMAC key is regenerated on Keycloak restart, which invalidates every outstanding trust cookie — fine for a demo, not fine for production.

## Running this on macOS + Podman

`./start.sh` is the normal way in — it auto-detects docker or podman and handles the macOS podman quirks below. Force one or the other with `CONTAINER_ENGINE=docker|podman ./start.sh`.

If you're using `podman compose` directly, two things to know:

1. **`DOCKER_HOST` socket path.** The compose CLI talks through a Docker-style socket; Podman uses a different socket path than the machine-default claims:

   ```bash
   export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
   ```

   The `.envrc` has a version of this, but if `podman compose` errors with "Cannot connect to the Docker daemon," export the path directly.

2. **`depends_on: condition: service_healthy` is ignored.** `podman compose` doesn't honor compose healthcheck gating, so a naive `podman compose up -d` starts `keycloak` before `user-service` is reachable and Keycloak's SPI registration fails. `start.sh` works around this by `up -d`-ing postgres + user-service first, polling their health, then bringing keycloak up, then everything else. If you skip `start.sh`, replicate the sequence by hand or wait long enough.

## Applying changes — what needs what

| Change | Minimum action |
|---|---|
| `domains/<name>/web/` source | `podman compose up -d --build --force-recreate demo-<name>` — its own image (`keycloak-demo-<name>-web`) built by `domains/<name>/web/Dockerfile`. No bind-mount, so a source edit needs the full build. Requires `/etc/hosts` entry `127.0.0.1 <name>.geowealth.int` on the host. |
| `domains/<name>/bff/` source | `podman compose up -d --build --force-recreate bff-<name>` — its own image (`keycloak-demo-<name>-bff`). |
| Add a new domain | follow the recipe in "Adding a new domain" above. |
| `user-service` source (incl. adding/removing demo users) | `podman compose up -d --build --force-recreate user-service` — only this image. Keycloak does not need a rebuild because the SPI didn't change. |
| `user-api/openapi.yaml` (contract edit) | rebuild **both** sides: `podman compose up -d --build --force-recreate user-service` **and** `podman compose build keycloak && podman compose up -d --force-recreate keycloak`. Both modules regenerate their half of the contract on the next `gradle` invocation. |
| Env var on a service | `podman compose up -d --force-recreate <service>` |
| `docker-compose.yml` structural change | `podman compose up -d` (compose picks up the diff) |
| `keycloak-provider/` SPI source | `podman compose build keycloak && podman compose up -d --force-recreate keycloak` |
| `keycloak/realm-export.json` | takes effect on fresh DB only; otherwise patch the live realm via admin API |
| New realm role needed for a running demo | POST `/admin/realms/demo-realm/roles` with admin token; also add to `realm-export.json` for future fresh installs |
| `EmailOtpAuthenticator` / `ResendClient` source | `podman compose build keycloak && podman compose up -d --force-recreate keycloak` — same as any SPI change |
| Auth flow edit in `realm-export.json` | wipe postgres volume (`down -v`) OR patch live flow via admin API — see note above |
| `geowealth-keycloak/` SPI source | `podman compose build keycloak && podman compose up -d --force-recreate keycloak`. The shadow JAR is rebuilt by `Dockerfile.keycloak` alongside the existing `keycloak-provider/`; both providers ship in the same image. |
| `keycloak/geowealth-realm-export.json` | takes effect on fresh DB only (`down -v` wipes the demo realm too); otherwise patch the live `geowealth-realm` via admin API on port 8898 |
| P1/Integrations link source (`~/geowealth/.../useIntegrationLinks.js`) | rebuild the GeoWealth Tomcat app — outside this repo. |

## Testing the stack quickly

Direct-grant against a domain BFF to confirm the JWT pipeline is alive (no OTP, no SAML; just sanity-check token validation + endpoint reachability):

```bash
TOKEN=$(curl -s -X POST "http://localhost:8898/realms/demo-realm/protocol/openid-connect/token" \
  -d "client_id=demo-billing-client&grant_type=password&username=demouser&password=123" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')

curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8084/api/summary"  | python3 -m json.tool
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8085/api/portfolio" | python3 -m json.tool
```

Reminder: direct-grant uses the realm's "direct grant" flow and bypasses OTP. The browser flow (what real users hit, via SAML) does not exercise OTP either, because P1 already authenticated them.

You can also poke `user-service` directly on port 8090 — it's exposed for inspection (`GET /users/{username}`, `GET /users?email=...`, `POST /users/verify-credentials`, `GET /health`). Inside the compose network Keycloak reaches it as `http://user-service:8080`.

## Commit style

Recent commits use plain prose bodies, no trailers other than `Co-Authored-By: Claude ...`. Prefer one commit per logical change; match the existing style.

## Language: English for everything that lands in the repo

All persistent artifacts must be in English: markdown docs, code comments, identifiers, file names, commit messages. Conversational replies in chat can mirror whatever language the user is writing in (often Bulgarian), but the moment something is written to disk and committed it must be English. The repo is a public-shareable demo, and contributors / future readers won't necessarily read Bulgarian.

When the user dictates content in Bulgarian and asks for it to be saved, translate as you write rather than transcribing. Filenames stay in kebab-case English.
