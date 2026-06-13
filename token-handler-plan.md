# Token Handler extraction — implementation + verification plan

## Goal
Run the shared OIDC/session/logout logic as a **separate, independently-deployable
Token Handler** service, so a shared-auth fix deploys by redeploying only the
Token Handler — **never the domain data apps (bff-billing/bff-trading)** — and
**without logging users out**.

## Why this is achievable cheaply here
1. The BFF entrypoint is already `demo.bff.core.Bff` (Micronaut) — the "domain"
   part of a BFF is only its `<Domain>Controller` + `DemoAuthz`. A Token Handler
   is therefore "a BFF with no domain controller": pure `bff-core`.
2. **Sessions already live in Redis (B2)** and are keyed by the session cookie.
   So the Token Handler and the domain data app can **share the same session**:
   same Redis, same cookie name, same OIDC client → the Token Handler writes the
   session on login, the data app reads it for `/api/**` authz.
3. Refresh-token **reuse detection is off** (`revokeRefreshToken=false`), so both
   services refreshing the same session is benign (no clean-up needed for v1).

## Architecture (v1 — shared Redis session)

```
                         billing.geowealth.int (nginx)
   /oauth/* /auth/* /logout ─────────►  token-handler-billing   (bff-core only:
                                         login, callback, /auth/me, /auth/logout,
                                         token refresh, /backchannel-logout, Redis)
   /api/billing/* ───────────────────►  bff-billing             (domain data +
                                         @Secured + Tier 2/3; reads the SAME
                                         BSESSION session from Redis — unchanged)
                         shared:  Redis (sessions + sid registry),  demo-billing-client,  BSESSION cookie
```

A shared-auth ENDPOINT fix (login/callback/logout/backchannel/`/auth/me`) →
rebuild + redeploy **token-handler-billing** only; nginx routes those paths there,
so the fix is live while `bff-billing` is untouched and every session survives in
Redis (no re-login).

## Implementation steps
1. **New `token-handler/` Micronaut module** — `bff-core` via composite build,
   `mainClass = demo.bff.core.Bff`, shadow jar, own `Dockerfile`. Generic,
   fully env-driven `application.yml` (client, secret, issuer, Redis,
   **`SESSION_COOKIE_NAME`**, P1 SLO url). No `<Domain>Controller`/`DemoAuthz`;
   `SubdomainRequirement` left inert (it only gates `/api/**`, which this service
   does not serve).
2. **docker-compose** — add `token-handler-billing` (and later `-trading`):
   the new image, env = the domain's client/secret/cookie + `REDIS_URI`, on
   `keycloak-network`, depends on `redis` + `keycloak`.
3. **nginx (billing web)** — route `^/(oauth|auth)/` and `= /logout` to
   `token-handler-billing:8080`; keep `/api/billing/` → `bff-billing:8080`.
4. **Realm** — repoint `demo-billing-client` `backchannel.logout.url` →
   `http://token-handler-billing:8080/backchannel-logout` (live + seed). The sid
   registry is Redis-backed (B2), so the Token Handler's invalidate is visible to
   `bff-billing`'s next `/api` read.
5. Repeat 2–4 for trading (`token-handler-trading`, `TSESSION`, demo-trading-client).

## Verification plan
- **V1 — flow still works (no regression):** full e2e suite with billing's auth
  routed through `token-handler-billing` — token-handler invariants, claims,
  api-authorization, logout fan-out, silent-first all green.
- **V2 — the headline goal:** make a visible shared-auth ENDPOINT change (e.g. a
  marker log/behaviour in `AuthController`), rebuild + `--force-recreate`
  **only token-handler-billing** (assert `bff-billing` image id unchanged), then:
  (a) an already-logged-in session keeps working with **no re-login** (`/auth/me`
  still 200, same cookie); (b) the new behaviour is live (login/logout exercised).
- **V3 — session continuity across a token-handler restart:** log in, restart
  `token-handler-billing`, confirm `/auth/me` still 200 (session in Redis).
- **V4 — cross-service session read:** confirm a session created by the Token
  Handler is honored by `bff-billing` for `/api/billing/**` (e2e api-authorization).

## Verification — RESULTS (2026-06-13)
- **V1 / V4 — no regression + cross-service session:** full e2e suite with billing's
  auth routed through `token-handler-billing` → **24 passed, 2 failed, 2 skipped**;
  the 2 failures are the pre-existing flaky firm-5 spec and a P1-establish flake
  (passed on retry). All billing auth/api/logout specs green — login via the Token
  Handler, `/api/billing` via bff-billing reading the **same Redis session**, logout
  + back-channel all work. Both domains then re-verified through their token-handlers.
- **V2 — the headline goal (live proof):** added a `/auth/th-version` marker to
  bff-core, rebuilt + `--force-recreate` **only `token-handler-billing`**. Result:
  `bff-billing` image id, container id and start time **all unchanged** (not rebuilt,
  not recreated, not restarted); `/auth/th-version` went **401 → 200 `{"marker":"v2-demo"}`**
  (the fix is live via the Token Handler).
- **V3 — no re-login:** a `BSESSION` captured **before** the redeploy still returned
  **`/auth/me` → 200** afterwards (session survived in Redis).

## Out of scope for v1 (documented, not done)
- **Forward-auth / header-injected identity** (data app fully auth-unaware, so
  EVERY auth fix — including filters — is token-handler-only). v1 still leaves the
  refresh filter + session-read code in the data app; only endpoint fixes are
  fully decoupled. Forward-auth (nginx `auth_request` → `/auth/verify` → inject
  `X-Auth-*`) is the next step.
- Distributed in-flight refresh lock (v1 relies on `revokeRefreshToken=false`).
- P1 (Tomcat) session clustering — separate (B2-P1 remnant).
