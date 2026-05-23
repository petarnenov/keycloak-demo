# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Survival notes for working in this repo. The **README.md** is the user-facing doc; this file only captures things that matter when editing the code and that you can't reliably infer from a first read.

## What this is

A Keycloak SSO demo with a **container-per-MFE microfrontend front end**: a React shell at `:5173` signs the user in once against a single OIDC client (`mfe-shell-client`) and lazy-loads three role-gated MFEs at runtime through **Module Federation** (`@originjs/vite-plugin-federation`). Each MFE is its own container — `mfe-client:5181`, `mfe-ops:5182`, `mfe-admin:5183` — built and previewed independently, each exposing an `assets/remoteEntry.js` that the shell fetches at runtime. Each MFE pairs with its own BFF container: `bff-client:8081`, `bff-ops:8082`, `bff-admin:8083`. The source still lives in one npm-workspaces monorepo under `frontend/`, but the workspace symlinks only matter at build time — production is deployable as seven independent images (shell + 3 MFE + 3 BFF).

End-user identity lives in a **standalone Micronaut REST service** (`user-service/`); the Keycloak User Storage SPI in `keycloak-provider/` is a thin REST client of it. Keycloak's Postgres only holds realm metadata and sessions.

The REST contract between the SPI and `user-service` is defined OpenAPI-first in `user-api/openapi.yaml`. Both sides — the SPI's HTTP client and the user-service's controller/model stubs — are **generated** from that one file at build time. Edits to a generated class are blown away on the next build; change the spec instead.

Three demo users (hardcoded in `user-service/src/main/java/demo/userservice/UserController.java`) — identified by email because `loginWithEmailAllowed=true`. Username still works as an alternate identifier:

| Username | Email | Password | Roles |
|---|---|---|---|
| democlient | nikiiv.linococo@gmail.com | 123 | `client` |
| demouser   | nikolay.ivanchev@gmail.com | 123 | `user` |
| demoadmin  | nikolai.ivanchev@gmail.com | 123 | `admin`, `user` |

After username/password, Keycloak requires a 6-digit email OTP (see "2FA gotchas" below).

Role gating lives in the BFFs (source of truth). The shell calls `GET /api/whoami` on `bff-client`, which returns `{ username, email, roles, allowedMfes }`; `useWhoAmI` feeds it into `<RoleGate>` (refuses to mount unauthorized MFEs) and `<Nav>` (greys out their links). Each MFE then makes its own data calls against its own BFF — `mfe-ops` hits `/api/ops/*` which the shell's Vite proxy strips to `/api/*` and forwards to `bff-ops`, which enforces `APP_ALLOWED_ROLES` on every request. So the per-MFE access check is double-gated: the shell denies the route, and the BFF would deny the call anyway.

Role → MFE mapping in `bff/UserController.java#whoami`:

| User | client | ops | admin |
|---|---|---|---|
| democlient (`client`) | ✓ | ✗ | ✗ |
| demouser (`user`) | ✓ | ✓ | ✗ |
| demoadmin (`admin, user`) | ✓ | ✓ | ✓ |

## Layout and where to make common changes

- `frontend/` — **npm workspaces monorepo**. Root `package.json` declares `apps/*` and `packages/*`. Each workspace ships its own `vite.config.ts` and builds independently. Federation glue lives in those configs.
  - `apps/shell/` — the React + React Router + TanStack Query shell. Owns `keycloak-js` (`auth/AuthProvider.tsx` — singleton-gated `keycloak.init()`), the `QueryClient`, the layout (`components/Layout.tsx` + `Nav.tsx`), the federation-aware MFE router (`components/MfeOutlet.tsx` — uses `lazy(() => import('mfeClient/Mfe'))`), the `RoleGate`, the `useWhoAmI` hook, and the shared `styles.css`. `vite.config.ts` configures federation as the *host* (declares remotes + shared deps) and sets up the per-MFE `/api/*` proxy. Module-federation remote names (`mfeClient/Mfe` etc.) are declared as TS modules in `src/types/federation.d.ts`.
  - `apps/mfe-client/`, `apps/mfe-ops/`, `apps/mfe-admin/` — three default-exported `MfeComponent`s. Each is a **federation remote** with its own `vite.config.ts`: name=`mfeClient`/`mfeOps`/`mfeAdmin`, `filename=remoteEntry.js`, `exposes={'./Mfe': './src/index.tsx'}`. Build produces a `dist/assets/remoteEntry.js` that the shell fetches at runtime. Each MFE wraps its content in `<div data-theme="a|b|c">` so the shell's CSS variables resolve to that MFE's palette. None import `keycloak-js` directly; they get a `ShellHost` prop with `auth.getToken()` for talking to their own BFF. A small `BffStatus` widget per MFE pings `/api/<mfe>/user` to demonstrate the per-MFE BFF wiring.
  - `packages/shell-api/` — TypeScript-only contract package. Exports `ShellHost`, `ShellAuth`, `MfeComponent`, `MfeProps`, `MfeKey`, `WhoAmI`. **The single source of truth for the shell ↔ MFE wire format.**
- `bff/` — single Micronaut codebase, **deployed three times** as `bff-client`/`bff-ops`/`bff-admin`. Same image `keycloak-demo-bff:latest`, different env: each gets its own `APP_SOURCE` (the value `BffStatus` echoes back) and its own `APP_ALLOWED_ROLES`. `src/main/java/demo/UserController.java` has both endpoints: `/api/whoami` (called only on `bff-client` by the shell) computes `allowedMfes` from JWT roles; `/api/user` is the role-gated endpoint each MFE pings to confirm wiring.
- `user-service/` — standalone Micronaut REST service that owns the demo users. `src/main/java/demo/userservice/UserController.java` has the hardcoded `USERS` map + password verify; it extends `AbstractUsersController`, which is **generated** under `build/generated/openapi/` from the contract. Fat-jar image `keycloak-demo-user-service:latest`.
- `user-api/openapi.yaml` — the single hand-written description of the SPI ↔ user-service wire format. Both `keycloak-provider/` (client) and `user-service/` (server stubs) regenerate from this on every build.
- `keycloak-provider/` — standalone Gradle project shipping **two** SPIs in one jar: the User Storage provider (`DemoUserStorageProvider` + `UserServiceClient` + adapter) and the Email-OTP Authenticator (`EmailOtpAuthenticator` + factory + `ResendClient`). Two service files under `src/main/resources/META-INF/services/` register them. The OTP form lives in `src/main/resources/theme-resources/templates/email-otp.ftl`. The generated REST client (`com.example.keycloak.client.*`) lives only in `build/`; never edit it. To add or modify a user, see `user-service/` above — **not** this module.
- `keycloak/realm-export.json` — realm seed: clients, realm roles, default-role composite, SPI component registration, `demo-browser` flow with the OTP step.
- `docker-compose.yml` — seven application services: `shell` (5173, dev mode for HMR), `mfe-client`/`mfe-ops`/`mfe-admin` (5181/5182/5183, **build + `vite preview`** because federation expose only works after build), `bff-client`/`bff-ops`/`bff-admin` (8081/8082/8083), plus Keycloak (8898), `user-service` (8090), Postgres. Every frontend container mounts the same `./frontend` to `/workspace`, runs `npm install` against the root workspace, then runs the script for *its* workspace only. Federation remote URLs live in env vars on the `shell` service (`VITE_MFE_*_URL`) — these are baked into the shell bundle at config-time and must be **browser-reachable** (so `localhost:518X`, not `mfe-client:5181`). BFF URLs on the same service (`BFF_*_URL`) drive the in-network Vite proxy, so they use compose hostnames.
- `Dockerfile.keycloak` — multi-stage: stage 1 runs `gradle shadowJar` on `keycloak-provider/` (which first generates the REST client from `user-api/openapi.yaml`, then fat-jars it with relocated Jackson). Stage 2 copies the jar into `/opt/keycloak/providers/` and runs `kc.sh build`. Build context is the **repo root** so the build can read `user-api/`. SPI changes need a full image rebuild, not just a container restart.
- `start.sh` — the canonical entrypoint. Auto-detects docker or podman, sources `.envrc`, tears the stack down (preserving volumes), rebuilds all three custom images, and brings everything back up. On the podman path it explicitly waits for postgres + user-service + keycloak healthchecks before starting the rest, because `podman compose` ignores `depends_on: condition: service_healthy`. Override engine selection with `CONTAINER_ENGINE=docker|podman`.

## Persistence model — what survives a restart

| Lives in | Persists across | Wiped only by |
|---|---|---|
| Keycloak realms, native users, federated identities, sessions, role mappings, live admin-API edits | `docker compose restart`, `docker compose down` + `up`, `./start.sh`, image rebuild + `--force-recreate` | `docker compose down -v` or **`./start.sh --reset`** |
| Postgres data backing all of the above | same | same |
| `keycloak/data` (import sources, KeyStore, exported state) | same | same |
| `user-service` user store (the `democlient` / `demouser` / `demoadmin` map) | always  it's a hardcoded `Map.of(...)` in `UserController.java` | source edit + rebuild |

Practical consequences:

- **A SAML-brokered login through P1 writes a federated identity to Keycloak's Postgres on first sign-in.** That identity survives every routine `restart` / `down+up` / image rebuild. The next time the same P1 user lands on the broker flow, Keycloak finds the existing record and skips the first-broker-login flow.
- **A user created through the admin UI or self-registration** is a native user in the realm's Postgres tables. Same persistence guarantees as brokered identities.
- **The three `user-service` demo users** are not persisted because they don't need to be  they're code-defined and re-appear on every container start. To add a new demo user, edit `user-service/src/main/java/demo/userservice/UserController.java`, then `podman compose up -d --build --force-recreate user-service`. The Keycloak side picks the change up on the next login (the SPI is `NO_CACHE`).
- **`./start.sh` is non-destructive.** It runs `down` (without `-v`) before rebuilding images, so the Postgres volume stays in place. Use `./start.sh --reset` for an explicit, opt-in wipe  it's the only path in the repo that destroys user data.

To verify persistence end-to-end:

```bash
TOKEN=$(curl -s -X POST 'http://localhost:8898/realms/master/protocol/openid-connect/token' \
  -d 'client_id=admin-cli&grant_type=password&username=admin&password=admin' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')
curl -s -X POST "http://localhost:8898/admin/realms/demo-realm/users" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"username":"persist-check","enabled":true}'
docker compose restart keycloak
# re-issue the admin token, then list users  persist-check is still there.
```

## Non-obvious runtime gotchas

- **`Authentication.getRoles()` returns `Collection<String>`, not `List<String>`.** The Micronaut type is `Collection`; don't assign to `List` in `UserController.java`.
- **Realm imports are `IGNORE_EXISTING` by default.** `--import-realm` seeds an empty Postgres only. To apply a `realm-export.json` edit on a running stack, either `./start.sh --reset` (destructive — wipes all users) or change the live realm via admin API (fast, preserves sessions). Always update both files if you want reproducibility.
- **SPI auto-creates realm roles.** `DemoUser.getRoleMappingsInternal()` calls `realm.addRole(name)` if a referenced role is missing, so roles named by user-service records that aren't in `realm-export.json` still work at runtime — but a fresh-DB install would lack them until the first login. Keep BFF-gated role names declared in the JSON too.
- **`defaultRoles` is deprecated in Keycloak 26.** The realm JSON no longer has `"defaultRoles"`; the old effect (every user gets `user`) lived in the `default-roles-demo-realm` composite, from which we removed `user`. Don't add `defaultRoles` back — it won't behave as you expect.
- **SPI is `NO_CACHE`.** The realm component config sets `cachePolicy: NO_CACHE`, so every Keycloak lookup hits `user-service` over REST. Changes in `user-service` are visible on the next request (good for the demo) but every login is at least 3 round-trips over the compose network — don't be surprised by the latency.
- **`UserServiceClient` is fail-closed.** Any non-200 / transport error on `/users/verify-credentials` returns `false`. If `user-service` is down, a `grant_type=password` request returns `invalid_grant` rather than hanging. Lookups (`getUserByUsername`, etc.) similarly map any error to `null`. This is intentional: never authenticate when the user store is unreachable.
- **Generated code lives in `build/`, not `src/`.** `openApiGenerate` writes `keycloak-provider/build/generated/openapi/` and `user-service/build/generated/openapi/`. Both are gitignored and regenerated every build. Editing them is pointless. `user-service/openapi-generator-ignore` suppresses the generator's sample controller stub so only the hand-written `UserController` compiles.
- **The provider jar ships shaded Jackson.** `shadowJar` in `keycloak-provider/build.gradle.kts` relocates `com.fasterxml.jackson` → `com.example.keycloak.shaded.jackson` so the in-JVM SPI client cannot collide with the Jackson Keycloak already loads on the provider classpath. If you add a new dependency to the provider, think about classloader clashes.
- **BFF and user-service run as baked fat jars, not `gradle run`.** Both Micronaut apps are built once into their own image (`bff/Dockerfile`, `user-service/Dockerfile`) using the `com.gradleup.shadow` plugin → `eclipse-temurin:17-jre` base. PID 1 is `java -jar /app/<svc>.jar`; there is no source tree, no Gradle cache, and no bind mount. A source edit needs a `--build --force-recreate` (see the table below). `--force-recreate` matters: after a rebuild, the `*:latest` tag points to a new image ID, but existing containers still hold the *old* ID at creation time. `up -d` alone won't notice; `--force-recreate` unconditionally destroys + recreates so they bind to the new image.
- **Shadow plugin is declared explicitly in `bff/build.gradle.kts` and `user-service/build.gradle.kts`.** Micronaut 4.4's application plugin doesn't register `shadowJar` on its own (it prefers its own `buildLayers`/`dockerBuild` flow); both modules add `id("com.gradleup.shadow") version "8.3.5"` so `gradle shadowJar` produces `build/libs/*-all.jar` for the Dockerfiles to copy. All Gradle modules use the Kotlin DSL — `build.gradle.kts`.
- **Build context for the Keycloak and user-service images is the repo root**, not their own subdirectories. Both Dockerfiles read `user-api/openapi.yaml` as a sibling of their module, so the compose `build.context` is `.` with an explicit `dockerfile:` path. Don't rewrite these to use a subdirectory context — generation will fail to find the spec.
- **Keycloak admin API is on port 8898 (not 8080).** Get a token at `/realms/master/protocol/openid-connect/token` with `client_id=admin-cli&grant_type=password&username=admin&password=admin`. Admin endpoints live under `/admin/realms/demo-realm/...`.
- **`keycloak-js` must be ≥ 26.x.** Older versions validate a `nonce` claim that Keycloak 26 no longer emits.
- **Wiping `dist/` on the host kills the running MFE.** Each MFE container `vite preview`-serves `/workspace/apps/mfe-X/dist`, which is bind-mounted from `./frontend/apps/mfe-X/dist`. `rm -rf` on the host removes the directory the preview server is serving from, so the running container starts replying 404 even though it logs "built in N s" because the build *did* happen — and then was nuked from the host. Either don't `rm` the dist while a container is up, or `--force-recreate` the MFE afterwards so it rebuilds.

## Login: shell-orchestrated vs vanilla per-app

The login lifecycle changed shape with the React + Module Federation rewrite. The vanilla TS era treated each of the three apps as an independent OIDC consumer; the MFE era folds them into one. The dedicated deep-dive is `LOGIN.md` at the repo root — full flow, rationale, vanilla diff, and a prod-security audit. The short version for in-edit reference:

| Concern | Vanilla TS era (`web-a/b/c`, gone) | Current MFE era |
|---|---|---|
| `keycloak-js` instances | One per origin (3 pages, 3 instances) | One total — only `apps/shell/src/auth/keycloak.ts` |
| `keycloak.init()` calls | One per page load | One per process, gated by `initPromise` in `AuthProvider` (StrictMode-safe) |
| OIDC clients | `app1/2/3-client` | `mfe-shell-client` only (the old three are disabled in `realm-export.json` but kept for rollback) |
| Token store | Per-origin keycloak-js memory | Shell's keycloak-js memory; MFEs never see the instance |
| MFE → token | (no MFEs existed) | `host.auth.getToken()` calls `keycloak.updateToken(30)` then returns the token |
| Logout fan-out | Each tab notices on its own next `check-sso` | `keycloak.onAuthLogout` → `queryClient.clear()` → every MFE query refetches anonymous in the same tick |

When changing auth, the load-bearing files are `apps/shell/src/auth/AuthProvider.tsx` (lifecycle + context), `apps/shell/src/auth/useShellHost.ts` (mapping to `ShellHost`), and `packages/shell-api/src/index.ts` (the contract). MFEs only consume `host.auth.*` — they have no other entry point into Keycloak.

## 2FA gotchas

- **Email OTP runs only on the browser flow.** The realm's `browserFlow` is `demo-browser`, which chains `auth-username-password-form` then `demo-email-otp`. Direct-grant (`grant_type=password`) uses the stock `direct grant` flow and **bypasses OTP** — convenient for `curl` tests, but means the role-matrix script below doesn't exercise the second factor.
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

   The `.envrc` has a version of this, but if `podman compose` errors with "Cannot connect to the Docker daemon," export the path directly. Every Bash call that uses `podman compose` in this repo must have `DOCKER_HOST` set.

2. **`depends_on: condition: service_healthy` is ignored.** `podman compose` doesn't honor compose healthcheck gating, so a naive `podman compose up -d` starts `keycloak` before `user-service` is reachable and Keycloak's SPI registration fails. `start.sh` works around this by `up -d`-ing postgres + user-service first, polling their health, then bringing keycloak up, then everything else. If you skip `start.sh`, replicate the sequence by hand or wait long enough.

## Applying changes — what needs what

| Change | Minimum action |
|---|---|
| Shell source | nothing — the `shell` container runs `npm run -w shell dev` and Vite HMR picks it up. |
| MFE source | restart that MFE's container so it rebuilds: `podman compose up -d --force-recreate mfe-client` (or `mfe-ops` / `mfe-admin`). Federation expose only works against a built `dist/`, so source changes need a rebuild — there is no HMR across federation boundaries. |
| Add a new MFE | new workspace under `apps/`, new federation config, register it in `shell/vite.config.ts` remotes, `shell/src/types/federation.d.ts`, `MfeOutlet`, `Nav`, and the BFF's `/api/whoami` mapping. Then restart shell + the new MFE container. |
| New workspace dep | every Vite container re-installs on next start, so `podman compose up -d --force-recreate shell mfe-client mfe-ops mfe-admin`. |
| BFF source | `podman compose up -d --build --force-recreate bff-client bff-ops bff-admin` — one build, three recreates (they share the image). `--force-recreate` is the belt-and-suspenders bit: without it, some compose implementations don't notice the image ID moved and leave the old containers running. |
| `user-service` source (incl. adding/removing demo users) | `podman compose up -d --build --force-recreate user-service` — only this image. Keycloak does not need a rebuild because the SPI didn't change. |
| `user-api/openapi.yaml` (contract edit) | rebuild **both** sides: `podman compose up -d --build --force-recreate user-service` **and** `podman compose build keycloak && podman compose up -d --force-recreate keycloak`. Both modules regenerate their half of the contract on the next `gradle` invocation. |
| Env var on a service | `podman compose up -d --force-recreate <service>` |
| `docker-compose.yml` structural change | `podman compose up -d` (compose picks up the diff) |
| `keycloak-provider/` SPI source | `podman compose build keycloak && podman compose up -d --force-recreate keycloak` |
| `keycloak/realm-export.json` | takes effect on fresh DB only; otherwise patch the live realm via admin API |
| New realm role needed for a running demo | POST `/admin/realms/demo-realm/roles` with admin token; also add to `realm-export.json` for future fresh installs |
| `EmailOtpAuthenticator` / `ResendClient` source | `podman compose build keycloak && podman compose up -d --force-recreate keycloak` — same as any SPI change |
| Auth flow edit in `realm-export.json` | wipe postgres volume (`down -v`) OR patch live flow via admin API — see note above |
| `geowealth-keycloak/` SPI source (LoginFormsProvider, BrandingService, BrandCss, theme assets) | `podman compose build keycloak && podman compose up -d --force-recreate keycloak`. The shadow JAR is rebuilt by `Dockerfile.keycloak` alongside the existing `keycloak-provider/`; both providers ship in the same image, both invalidate together. |
| `keycloak/geowealth-realm-export.json` | takes effect on fresh DB only (`down -v` wipes the demo realm too); otherwise patch the live `geowealth-realm` via admin API on port 8898 |
| White-labeling branding-API contract (`contracts/branding-api.openapi.yaml`) | hand-written contract, no code generation yet; update the client at `geowealth-keycloak/src/main/java/com/geowealth/keycloak/branding/BrandingApiClient.java` and rebuild keycloak. The real provider is the `BrandingApiServlet` in the geowealth repo; when it's unreachable, the SPI falls back to `BrandRegistry`'s hardcoded defaults  there is no in-repo fake to keep in lockstep. |

## Dev workflows (Level 1 / 2 / 3)

Three modes, each one command. **The default demo stays exactly as it is**; Level 2/3 are opt-in.

| Level | Single command | What you get |
|---|---|---|
| **1 — demo** | `./start.sh` | docker-compose.yml only. MFE containers run `build && preview`. Shell HMR; MFE source needs `--force-recreate mfe-X`; BFF source needs `--build --force-recreate`. |
| **2 — MFE rebuild-on-save** | `./start.sh --dev` | Adds docker-compose.dev.yml. MFE containers run `vite build` once, then `concurrently` runs `vite build --watch` + `vite preview`. Save in `apps/mfe-X/src/**` → ~1-2s incremental rebuild → hard-refresh browser. |
| **3 — BFF on the host** | `./dev-bff.sh client\|ops\|admin` (after `./start.sh --dev`) | Detects docker vs podman, stops the compose BFF for that role, recreates the shell with `BFF_<WHICH>_URL=http://host.docker.internal:<port>` (or `host.containers.internal` on Podman), then execs `./gradlew run -t --no-daemon` in foreground. Save in `bff/src/**` → ~3s Gradle incremental → Micronaut restart in place. |

Level 2 + Level 3 stack together — `dev-bff.sh` requires the dev compose overlay to be applied (otherwise the shell's `BFF_*_URL` env vars aren't overridable). It enforces this by passing `-f docker-compose.dev.yml` to every compose call it makes.

**Issuer note.** Browser-minted tokens carry `http://localhost:8898/realms/demo-realm` as the `iss` claim (that's where the browser hit Keycloak). The local host BFF must validate against the same URL — `dev-bff.sh` already sets `KEYCLOAK_AUTH_SERVER_URL=http://localhost:8898/realms/demo-realm`. The in-compose `http://keycloak:8080` URL reaches Keycloak but the issuer wouldn't match.

**Restoring the demo state.** After Level 3 work: `docker compose up -d bff-<which>` brings the compose BFF back; then either `./start.sh` (drops the host override on the shell) or `BFF_<WHICH>_URL= docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --force-recreate shell` keeps Level 2 active but un-overrides.

## Testing the role matrix quickly

```bash
# Grab tokens for all three users, then poll /api/whoami on the BFF.
for u in democlient demouser demoadmin; do
  T=$(curl -s -X POST "http://localhost:8898/realms/demo-realm/protocol/openid-connect/token" \
    -d "client_id=mfe-shell-client&grant_type=password&username=$u&password=123" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')
  curl -s -H "Authorization: Bearer $T" "http://localhost:8081/api/whoami" \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print(f"{d[\"username\"]:>11s} -> {d[\"allowedMfes\"]}")'
done
```

Expected:

```
 democlient -> ['client']
  demouser  -> ['client', 'ops']
 demoadmin  -> ['client', 'ops', 'admin']
```

Any deviation means either the role → MFE mapping in `bff/UserController.java#whoami` changed or the token roles aren't what you think — decode the JWT payload with `echo "$T" | cut -d. -f2 | base64 -d` to check. Reminder: this uses direct-grant, so the OTP step is **not** exercised.

You can also poke `user-service` directly on port 8090 — it's exposed for inspection (`GET /users/{username}`, `GET /users?email=...`, `POST /users/verify-credentials`, `GET /health`). Inside the compose network Keycloak reaches it as `http://user-service:8080`.

## Commit style

Recent commits use plain prose bodies, no trailers other than `Co-Authored-By: Claude ...`. Prefer one commit per logical change; match the existing style.

## Language: English for everything that lands in the repo

All persistent artifacts must be in English: markdown docs, code comments, identifiers, file names, commit messages. Conversational replies in chat can mirror whatever language the user is writing in (often Bulgarian), but the moment something is written to disk and committed it must be English. The repo is a public-shareable demo, and contributors / future readers won't necessarily read Bulgarian.

When the user dictates content in Bulgarian and asks for it to be saved, translate as you write rather than transcribing. Filenames stay in kebab-case English (e.g. `mfe-repo-strategy.md`, not Cyrillic).
