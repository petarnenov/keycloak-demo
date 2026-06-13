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

## v2 — Forward-auth (DONE, 2026-06-13): EVERY auth fix is token-handler-only
The data BFFs are now **fully auth-unaware**. nginx `auth_request`s each
`/api/<name>` against the Token Handler's `GET /auth/verify` (which validates +
refreshes the session and runs coarse + the subdomain Tier-2 gate), then copies
the returned `X-Auth-*` identity onto the upstream request and **drops the session
cookie**. The data BFF reads identity from those headers (`HeaderIdentity`) — it
holds no session, runs no token refresh, runs no security filters.

- `bff-core`: `SubdomainAuthorizer` (decision extracted from the filter),
  `ForwardAuthController` (`/auth/verify` → 401/403 or 200 + `X-Auth-*`),
  `HeaderIdentity` (rebuild `Authentication` from headers).
- domain BFFs: `BillingController`/`TradingController` read `HeaderIdentity`,
  no `@Secured`, no `Authentication` injection; `application.yml` `/api/** ->
  isAnonymous` (nginx gates); the Tier-3 list `refine` stays here (it filters the
  domain's own rows — intrinsically next to the data).
- nginx (both webs): `/api/<name>` → `auth_request /th-verify` (→ token-handler
  `/auth/verify`) → inject `X-Auth-*`, no cookie. token-handlers run fine authz.

**Verified (2026-06-13):** full e2e on BOTH forward-auth domains → 25 passed, 1
failed (the pre-existing firm-5 flake), 2 skipped; all auth/api specs green. Proof
the BFF runs no auth: across a full e2e run, **bff-billing did 0 token refreshes**
while **token-handler-billing handled 492 `/auth` operations**. Anon `/api/<name>`
→ 401 (nginx gate); logged-in → data via `X-Auth-*` headers.

## Still out of scope (documented, not done)
- Distributed in-flight refresh lock (relies on `revokeRefreshToken=false`).
- The Tier-3 `refine` mechanism (`Tier23Gate`/`P1AuthzClient`) still runs in the
  data BFF — by necessity, it filters the domain's data rows, so it cannot live in
  the Token Handler. It is data-authz, not shared session/login auth.
- P1 (Tomcat) session clustering — separate (B2-P1 remnant).
