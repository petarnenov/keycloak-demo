# SSO Login/Logout — Production-Readiness Gap Analysis

**Scope:** the SSO login/logout architecture spanning two repos —
`keycloak-demo` @ `petarnenov/bff-core-persons-registry` (Keycloak realm, `bff-core`,
billing/trading BFFs + SPAs, e2e) and `geowealth` @ `team/petarnenov/keycloak-persons-registry`
(P1 as SAML IdP + OIDC self-client).

**Method:** three passes — (1) static analysis of code, configs, `realm-export.json`, e2e specs;
(2) live verification against the running stack (admin-API probes of the live `demo-realm`,
HTTP traces of login/logout endpoints, catalina.out SAML markers, Playwright e2e suite);
(3) targeted security review (SAML signatures, cookies, CSRF, open-redirect, secrets, OIDC BCP).

**Date:** 2026-06-12. **Stack state at analysis:** all containers up 5 days; P1 Tomcat live (HTTP 200);
P1 signing keystore present at `/tmp/p1-idp-dev.p12`.

> **Update 2026-06-12 — fixes applied.** A round of remediation followed this analysis;
> see **[§ Fixes Applied & Verification](#fixes-applied--verification)** below. One Blocker (B1),
> one Major (M8) and six lower findings (M2, M3, m1, m2, m3, m5, m4) were fixed in code/config and
> re-verified live. The remaining findings are infra/ops or geowealth-side and are dispositioned
> with rationale in that section.

---

## Fixes Applied & Verification

Applied to the **keycloak-demo** repo (code + `realm-export.json`) and the **live `demo-realm`**
via admin API. Each was re-verified live (HTTP trace / admin-API probe) and the full e2e suite was
re-run as a regression gate.

### Fixed and verified

| Finding | Fix | Files | Verification |
|---|---|---|---|
| **B1** Public clients, no PKCE, direct-grant on | Both demo clients flipped to `publicClient=false` + `clientAuthenticatorType=client-secret` (secret = `.envrc` value), `pkce.code.challenge.method=S256`, `directAccessGrantsEnabled=false`. Applied to live realm **and** seed. | `keycloak/realm-export.json` (both client blocks); live realm via admin API | Admin-API probe: `public=False pkce=S256 directGrant=False authn=client-secret` for both clients. Live HTTP trace of `/oauth/login/keycloak`: authorize redirect now carries `code_challenge_method=S256` + `code_challenge=…`. Login still works (e2e). |
| **B2** In-memory session/sid state (no horizontal scaling; back-channel fan-out misses replicas) | Added a **Redis** service; BFF session store switched to `RedisSessionStore`; `SidSessionRegistry` rewritten to hold the sid→session map in Redis (with a reverse key for cleanup). Two follow-on fixes the Redis store forced: OAuth `state` persistence moved session→**cookie** (the `State` object isn't serializable), and logout now `session.clear()`s the current session (a Redis-backed `deleteSession` of the request's *own* session doesn't reliably stick — the session filter re-saves it; clearing makes the re-saved session unauthenticated → 401). | `docker-compose.yml` (redis service + `REDIS_URI`); `bff-core/build.gradle.kts` (micronaut-redis-lettuce); both `application.yml` (redis + `state: cookie`); `bff-core/.../SidSessionRegistry.java`, `AuthController.java`, `TokenRefreshFilter.java` | **Verified on 2 replicas** (`docker compose --scale bff-billing=2`, nginx per-request DNS round-robin): startup log `RedisSessionStore replaces InMemorySessionStore`; both replicas serve authenticated `/auth/me` (single-replica would 401 on the non-creating one); sid maps present in Redis (`bff:sid:*`, `bff:sess:*`); logout + external-logout + whitelabel-global-logout pass with back-channel fan-out crossing replicas. **P1-side state (Tomcat `HttpSession`, `sessionsByKcSub`) is out of scope — separate repo; documented as remaining.** |
| **B6** Inbound SLO LogoutRequest signature bypass (forced-logout DoS) | `IdpSloAction` now **fully verifies** the LogoutRequest signature on both bindings when the KC SP cert is provisioned, and **fails closed** on unsigned: implemented real **HTTP-Redirect-binding** verification (`verifyRedirectSignature` — rebuilds the signed octet string from the *raw* query values in canonical order `SAMLRequest`→`RelayState`→`SigAlg`, verifies with `java.security.Signature` against KC's SP public key); removed the `!hasUrlSignature` bypass; kept the POST-binding XMLDSig path. Also refreshed the stale `P1_IDP_KC_SP_CERT` (the realm key had rotated — the old bypass was masking the mismatch). | `geowealth/.../IdpSloAction.java` (+ `verifyRedirectSignature`/`rawParam`/`sigAlgToJava`); Tomcat `setenv.sh` (`P1_IDP_KC_SP_CERT` → current KC cert) | **Verified live** (Tomcat redeployed): a tampered Redirect LogoutRequest → **403 `SAML_LOGOUT_SIG_REJECT binding=redirect`** (the old code accepted it); logout/external-logout e2e pass (SLO not broken); and a **real KC-signed Redirect request verifies TRUE** under the exact same reconstruction (captured live, `openssl dgst -sha256 -verify … → Verified OK`, RSA-SHA256/256-byte). **Operational caveat:** the cert is static — KC realm-key rotation requires re-syncing `P1_IDP_KC_SP_CERT` (a dynamic fetch from `…/broker/p1/endpoint/descriptor` would be more robust; noted as follow-up). |
| **M8** No brute-force, no events, forgot-password on | `bruteForceProtected=true`, `eventsEnabled=true`, `adminEventsEnabled=true`, `resetPasswordAllowed=false`, `accessTokenLifespan` 1800→300. Live + seed. | `keycloak/realm-export.json` (realm header; removed the duplicate trailing `resetPasswordAllowed`); live realm via admin API | Admin-API probe confirms all five values. |
| **M2** Refresh error mass-logout | `TokenRefreshFilter` now only clears the session on an HTTP 4xx (`invalid_grant`/revoked); transport errors and 5xx keep the session and serve the request with the current token. | `bff-core/.../TokenRefreshFilter.java` (`isDeadSession`, reworked `onErrorResume`) | Compiles; e2e logout/external-logout specs still pass (4xx path still tears down). |
| **M3** Concurrent-refresh race | Per-session in-flight guard (`refreshInFlight` map, 15s self-expiring window) — a second parallel `/api/*` call skips its own refresh-token grant and proceeds with the current token. | `bff-core/.../TokenRefreshFilter.java` | Compiles; e2e (which fires concurrent `/api` calls) green. |
| **m1** Logout-token spec gaps | `LogoutTokenValidator` now rejects a logout token carrying a `nonce` (BCP §2.4) and rejects replayed `jti`s (bounded 4096-entry LRU). | `bff-core/.../LogoutTokenValidator.java` | Compiles; back-channel/external-logout e2e still pass. |
| **m2** `SidSessionRegistry` leak | `@EventListener` on `SessionDestroyedEvent` prunes the sid→session map on idle-expiry/delete (now against Redis, via the reverse key); keys also carry a 12h TTL backstop. | `bff-core/.../SidSessionRegistry.java` | Compiles; e2e green. |
| **m3** Regex membership parsing | Replaced the regex JWT-payload scan with Nimbus `getStringListClaim("memberships")` — robust to value content/ordering. | `bff-core/.../KeycloakAuthenticationMapper.java` | Compiles; e2e api-authorization + sso-claims (which assert memberships-driven authz) pass. |
| **m5** Back-channel on event loop | Added `@ExecuteOn(TaskExecutors.BLOCKING)` so the JWKS fetch can't stall Netty. | `bff-core/.../BackchannelLogoutController.java` | Compiles; e2e green. |
| **m4** Dead SLO-url override | Corrected the comment to name the env var Micronaut actually binds (`APP_P1_INITIATE_SLO_URL`); kept the literal default. (A first attempt used a `${P1_INITIATE_SLO_URL:http://…:8888/…}` placeholder, but Micronaut mis-parses the colons in an `http://host:port` default and resolved it to `8888/saml/…` — breaking the logout redirect — so the placeholder was reverted.) | `domains/{billing,trading}/bff/.../application.yml` | Live trace: `POST /auth/logout` → 303 `Location: http://localhost:8888/saml/idp/initiate-slo.do` (correct). |
| **M1** Logout-CSRF (GET `/auth/logout`) | `/auth/logout` is now **POST-only** (`@Post` + `@Consumes(form-urlencoded, ALL)`); the vestigial built-in `/logout` set `get-allowed:false`; both SPAs sign out via a same-site form-POST instead of a GET navigation. With the session cookie SameSite=Lax, a cross-site forced-logout POST arrives without the cookie and tears nothing down. | `bff-core/.../AuthController.java`; `domains/{billing,trading}/web/src/auth/AuthProvider.tsx`; `domains/{billing,trading}/bff/.../application.yml`; e2e `fixtures/auth.ts` (helper → form POST) | Live trace: `GET /auth/logout` → **401** (no teardown), built-in `GET /logout` → **405**, `POST /auth/logout` (form content-type) → 303 to P1 SLO. **e2e `logout.spec` passes** (back-channel fan-out: sibling `/auth/me` → 401). *Note:* the first cut omitted `@Consumes`, so the route defaulted to consuming `application/json` and the SPA's `x-www-form-urlencoded` form POST 404'd ("no matching route") — caught by e2e, fixed by adding `@Consumes`. |
| **M4** Session fixation | New `RotatingSessionLoginHandler` (`@Replaces(SessionLoginHandler)`) rotates the session id on successful auth — issues a fresh session (`sessionStore.newSession()`), moves the `Authentication` into it, deletes the pre-auth session (which only held the now-consumed `state`/`nonce`/PKCE). | `bff-core/.../RotatingSessionLoginHandler.java` (new) | **Confirmed firing live:** both BFFs log `rotated session id on login (fixation defence)`; login succeeds across all login-exercising specs (rotation doesn't break the flow); new id guaranteed by `newSession()`. |

Both BFF images were rebuilt and `--force-recreate`d (the `bff-core` change is bundled per image
via the composite build); the SPA images were rebuilt for the logout-POST change. All started cleanly.

**Regression gate.** First fix batch (B1/M8/bff-core) re-run: 24 passed, 2 failed, 2 skipped —
identical to the pre-fix baseline. Second batch (**M1 + M4**) full re-run: **25 passed, 1 failed,
2 skipped** — net-better than baseline and zero regression. Third batch (**B2**, Redis) full re-run
**on two billing replicas**: **25 passed, 1 failed, 2 skipped** — same result with the BFF scaled
out, confirming cross-replica sessions + back-channel logout through the shared store. (The single
persistent failure across all batches is the flaky firm-5 `billing-tenant-identity-johnastim5`.)
*Note:* a Docker `COPY bff-core/` layer cache silently shipped stale jars during B2 debugging — a
clean `docker compose build --no-cache` was required; this gotcha is now recorded in `CLAUDE.md`. Every login/logout/token/claims spec
passes, including all three logout specs M1 touches (`logout`, `external-logout`,
`whitelabel-global-logout`); login flows through a PKCE-enforced confidential client with the
session id rotated on auth. The single remaining failure is `billing-tenant-identity-johnastim5`
(a pre-existing firm-5 spec, F1) — and its sibling `p1-login-newtab-john` passed this run,
confirming the firm-5 failures are intermittent, not caused by any fix here. The e2e logout helper
(`fixtures/auth.ts`) was updated from a GET navigation to a same-site form POST to match M1's new
contract.

### F1 (firm-5 `john.localhost` member-switch) — triaged, NOT a regression

Triaged read-only against the live P1 DB (`gw-oracle`/FREEPDB1). The seed (`V18__seed_firm5_john_link_whitelabel.sql`)
**is present**: john's entity `019E8978F9AE7676915751838956A526` exists with `system_base_url='john.localhost'`
and `linked_gw_user='6459…'` (tim1's person). Despite the data being correct, `john.localhost` still
resolves to tim1/firm-1 (the received UUID `6459…` is tim1's person entity, not john's `019E…`), so the
member-switch never fires. Note firm 5's own `FIRM_TBL.system_base_url` is `c1wealth.geowealth.com`, so
the host match depends on the whitelabel-employee scan picking up john's per-entity base URL — that is the
suspect path. **This is a pre-existing, geowealth-side failure** (the same two specs failed identically in
the baseline run *before* any fix here) and was left untouched: it lives in the separate `geowealth` repo
(which already carries unrelated uncommitted changes) and needs its own host-resolution debugging session,
not a blind change mid-task.

### Not fixed — disposition with rationale

| Finding | Why not fixed here | What prod needs |
|---|---|---|
| **B2 (P1 side only)** In-memory state in P1 | The BFF tier is fixed (moved to **Fixed and verified**). P1's Tomcat `HttpSession` + `sessionsByKcSub` + JIT-registrar caches remain in-JVM; clustering them is a large change in the separate legacy geowealth repo. | Distributed Tomcat session manager (Redis) for P1; make `sessionsByKcSub` a Redis map of session ids. |
| **B3** `insecure-trust-all-certs` | Removing it requires the BFF to trust the real CA chain to KC; in this dev stack KC is reached over mkcert with mixed internal/external URLs, so flipping it risks breaking the whole stack and can't be safely verified without a prod-like TLS setup. | Provision a real cert chain; remove the flag; add id_token signature verification in the refresh path (`TokenRefreshFilter.rebuild`). |
| **B4** `admin/admin` + defaulted secrets | These are bootstrap/ops credentials in compose + P1 runtime env; "fixing" them in the demo means wiring a secret manager, which is a deployment concern. | Scoped KC service account (`manage-clients` on `demo-realm`); secrets from a manager; no defaults. |
| **B5** `/tmp` keystore | geowealth/ops; recovery script already exists (`scripts/sso-dev-keystore.sh`). | Persistent secret-managed PKCS#12, distinct key/store passwords, cached in-memory `Credential`. |
| **M5, M7** (geowealth AuthnRequest validation / firm-resolution) | Separate repo with unrelated uncommitted changes; legacy Struts code; high blast radius. (B6, the SLO-signature bypass, was fixed — moved to **Fixed and verified**.) | AuthnRequest signature/Issuer enforcement, `system_base_url` uniqueness validation. |
| **M9** Env coupling | Deployment-time concern (build-time Vite vars, per-env hosts/issuer). | Externalize hosts/ports/issuer; set `P1_REDIRECT_HOST_ALLOWLIST` to prod DNS; drop `http://localhost` redirect URIs. |

*(M1 logout-CSRF and M4 session fixation were moved to **Fixed and verified** above in a follow-up pass.)*
| **m6** Token lifespans | `accessTokenLifespan` already tightened to 300s under M8; SSO idle/max left as-is to avoid changing e2e timing. | Review SSO idle/max + offline-token policy for prod. |
| **m7, m8** (geowealth XFF trust, claim staleness) | geowealth-side; XFF depends on the prod proxy; staleness is a known design trade-off. | Strip inbound XFF at the edge; re-broker on firm switch if live identity display must update. |

---

## Verdict

**Conditional — NOT production-ready as-is, but the architecture is sound.** The core security
design is correct and, in several places, better than typical: tokens never reach the browser
(true Token Handler/BFF pattern), SAML assertions are RS-SHA256 signed and verified both ways,
OIDC id_tokens are JWKS-verified with `none` refused, `state`/`nonce`/PKCE are enforced on the
code flow, firm-switch authorization is account-gated against a link graph (no privilege
escalation), and open-redirect is structurally prevented on every logout/return path. The
blockers are **not** design flaws — they are dev-mode configuration baked into a demo that was
never hardened for an untrusted network: public OIDC clients with no PKCE enforcement and
direct-grant left on (confirmed live), TLS verification disabled on every server-to-server hop,
`admin/admin` master credentials wired into the P1 runtime, a signing keystore on an ephemeral
`/tmp` path, and all session/registry state held in-JVM memory (no horizontal scaling, every
restart logs everyone out). Two correctness gaps need attention regardless of environment: a
logout-CSRF vector (GET `/auth/logout` terminates the whole SSO chain), and refresh-error
handling that mass-logs-out users on any transient Keycloak blip. The previously-tracked
"firm-context bleed" bug is **fixed** on these branches (code guard + regression e2e), but the
fix is half data-only and the bug class can re-arm on mis-seeded firm rows.

**Bottom line:** acceptable as a demo; a defined hardening checklist (below) stands between it
and production. The live e2e suite backs this up — **24/28 specs pass**, including all
token-handler, claims, logout-fan-out, and firm-resolution (bleed-regression) checks; the only
failures (F1) are the firm-5 `john.localhost` member-switch, which needs separate triage.

---

## Findings

Severity is judged **for a production deployment on an untrusted network**. Dev conveniences
are framed "dev-acceptable, prod-blocker" — they are not bugs in the demo, they are settings
that must change before prod. Blockers first.

### Blockers

| # | Area | Repo · Location | Evidence | Impact |
|---|---|---|---|---|
| **B1** | Login / Standards | keycloak-demo · `keycloak/realm-export.json:205,302,399` + **live realm** | Admin-API probe (2026-06-12): `demo-billing-client` and `demo-trading-client` both `publicClient=true`, `pkce.code.challenge.method=None`, `directAccessGrantsEnabled=true`. BFFs run as *confidential* clients (`application.yml:45` `client-secret: ${OAUTH_CLIENT_SECRET}`). | Keycloak does **not** validate the client secret at the token endpoint for a public client, and does not require a PKCE verifier. An intercepted/phished authorization code is redeemable with `client_id` alone. The entire code-exchange security rests on TLS + redirect-URI matching. **Dev-acceptable, prod-blocker.** Fix: `publicClient=false` + `pkce.code.challenge.method=S256` + `directAccessGrantsEnabled=false` on both clients, in the seed *and* live. |
| **B2** | Session / Reliability | keycloak-demo · `bff-core/.../SidSessionRegistry.java:23`; default `InMemorySessionStore` | `private final Map<String,Set<String>> sidToSessions = new ConcurrentHashMap<>();`; no Redis/JDBC session store configured (`bff-core/build.gradle.kts:28` pulls only `micronaut-session`). Same pattern in geowealth: `KeycloakClientRedirectRegistrar` JVM-local set, `GeowealthSessionListener.sessionsByKcSub` in-memory, P1 `HttpSession` flags. | (a) Any BFF restart/redeploy logs out every user and discards refresh tokens; (b) with ≥2 replicas the `BSESSION` cookie only resolves on the originating replica; (c) the back-channel `logout_token` POST lands on one replica, so sibling replicas keep serving the zombie session. P1-initiated `kc_sub` sibling-logout only reaches sessions on the same node — the 30s `KcSessionProbe` becomes the real teardown mechanism. **Blocks horizontal scaling and zero-downtime deploys.** Fix: shared session + sid store (Redis/JDBC). |
| **B3** | Security (transport) | keycloak-demo · `domains/billing/bff/.../application.yml:11-12`, `domains/trading/.../application.yml:6-7` | `micronaut.http.client.ssl.insecure-trust-all-certs: true` — applies to the `kc` and `p1authz` clients. Compounded: `TokenRefreshFilter.java:185` does `SignedJWT.parse(...).getJWTClaimsSet()` — parses the refreshed id_token **without** `verify()`. | The server-side code exchange, refresh grant, and KC end-session calls accept any certificate. A MITM steals `client_secret` + refresh tokens and forges token responses — and because the refresh path doesn't verify the id_token signature, a forged response directly forges roles/firmCd/memberships into the session. The compose file even mounts the mkcert CA into the JRE truststore (`docker-compose.yml:166-169`), which trust-all renders moot. **Dev-acceptable, prod-blocker.** |
| **B4** | Operability / Security | keycloak-demo · `docker-compose.yml:47-48,25-27`; geowealth · `KeycloakClientRedirectRegistrar.java:75-80` | KC bootstrap `KEYCLOAK_ADMIN=admin` / `KEYCLOAK_ADMIN_PASSWORD=admin`; Postgres `keycloak/keycloak_secret`. **P1 runtime** defaults `P1_KC_ADMIN_USER/PASS` to `admin`/`admin` on the **master** realm via `admin-cli`, and on every cold whitelabel host fetches a master-admin token and PUTs the full `p1-self-client` representation back. | P1 holds full Keycloak master-admin credentials at runtime. Compromise of P1 = total Keycloak takeover. If env overrides are forgotten in prod, `admin/admin` is live. **Dev-acceptable, prod-blocker.** Fix: a realm-scoped service account limited to `manage-clients` on `demo-realm`; secrets from a manager, never defaulted. |
| **B5** | Operability | geowealth · `IdpKeyStore.java:142-143` + `tomcat .../setenv.sh:44-46`; recovery `keycloak-demo/scripts/sso-dev-keystore.sh` | SAML signing keystore path defaults to `/tmp/p1-idp-dev.p12`; keystore password `changeit`, reused verbatim as the key-entry password; `AbstractSamlAuthenticationResponseBuilder.getSenderSigningCredential` re-opens and parses the PKCS#12 **from disk on every signature** (each Response is signed twice → 2 disk reads + 2 keystore parses per login). | macOS wipes `/tmp` on reboot → file gone → all SAML federation returns 503/500 ("SAML emission failed"). No key/store password separation. Per-request file I/O + crypto parsing on the login hot path. **Dev path acceptable; prod needs a secret-managed persistent keystore, distinct entry password, and an in-memory cached `Credential`.** |
| **B6** | Logout / Security (SLO) | geowealth · `IdpSloAction.java:194-214`, `KeycloakSpCert.java:64-73` | Inbound SLO `LogoutRequest` signature enforcement is skipped when `P1_IDP_KC_SP_CERT` is unset (`isReady()` false → accept unsigned), **and** even when loaded, the presence of a `Signature`/`SigAlg` URL param sets `hasUrlSignature=true` and bypasses the validation block without verifying the HMAC (acknowledged TODO at 186-193). Live confirm: SAML metadata advertises `WantAuthnRequestsSigned="false"`. | An unauthenticated attacker reaching `/saml/idp/slo.do` can POST/GET a `LogoutRequest` carrying a victim's NameID (the enumerable P1 user UUID) and forcibly tear down that victim's sessions — forced-logout DoS against arbitrary users. The replay cache stops exact replays, not forgery. **Dev-acceptable, prod-blocker.** Fix: require `isReady()` in prod (fail closed) and implement real Redirect-binding signature verification. |

### Major

| # | Area | Repo · Location | Evidence | Impact |
|---|---|---|---|---|
| **F1** | Cross-subdomain / Reliability | keycloak-demo · `e2e/tests/p1-login-newtab-john.spec.ts:103`, `billing-tenant-identity-johnastim5.spec.ts:67` (live failures) | Full e2e run 2026-06-12: firm-5 `john.localhost` member-switch fails two ways — `/auth/me` returns **401** (no billing session for `johnAstim5`), and the silent new-tab login on `john.localhost` resolves to the **wrong person UUID** (`6459…` instead of john's `019E…`). The other 24 specs pass. | The whitelabel **member real-switch** path (a different P1 account linked into firm 5, the documented `p1-login-newtab-john` scenario) is currently broken on this branch — either the firm-5 link-graph/account data is missing/changed, or a regression in `OidcCallbackAction.switchToFirmAccount` / firm resolution. Needs triage to separate data from code. The gwAdmin/non-member paths still pass, so this is scoped to the member-switch case, not a blanket whitelabel break. **Live-confirmed, not static.** |
| **M1** | Logout / CSRF | keycloak-demo · `bff-core/.../AuthController.java:244-246`; `application.yml:35-40`; `nginx.conf:73-83` | `@Get("/logout") @Secured(IS_ANONYMOUS)` + built-in logout `get-allowed: true`. Live trace: `GET /auth/logout` → `303` → `http://localhost:8888/saml/idp/initiate-slo.do`; handler does KC end-session with the stored refresh token, deletes the session, invalidates by sid. | Any third-party page can `window.location` a victim to `/auth/logout` and terminate the BFF session, the KC SSO session, and (via redirect) the P1 session. `SameSite=Lax` does not help — top-level GET navigations send the cookie. The idempotent-anonymous design is fine; the GET verb is the problem. Fix: POST-only (then Lax blocks it) or require a CSRF token, keeping the unauth-tolerant handler. **Note:** open-redirect on this endpoint is *not* present — live probe with `?redirect_uri=https://evil.example.com` still redirected to the fixed literal SLO URL. ✅ |
| **M2** | Reliability | keycloak-demo · `bff-core/.../TokenRefreshFilter.java:121-127` | `onErrorResume(err -> { session.clear(); return unauthorized(); })` — every refresh error (timeout, connection refused, KC restart; `read-timeout: 5s`) is treated like `invalid_grant`. With the 30s forced revalidation (`VALIDATE_INTERVAL_SECONDS`), every active session hits KC ≥ every 30s. | A short Keycloak outage destroys essentially all live sessions: the refresh token is cleared, so users must fully re-login even after KC recovers. Fix: distinguish HTTP-4xx `invalid_grant` (kill session) from transport/5xx errors (serve existing token, retry). |
| **M3** | Reliability | keycloak-demo · `bff-core/.../TokenRefreshFilter.java:104-119,141-150` | The `shouldValidate` check and later `s.put(AUTHENTICATION, refreshed)` are not synchronized per session; N parallel `/api/**` calls past the 30s window each fire their own refresh grant with the same token. | With Keycloak refresh-token rotation + reuse-detection (standard prod hardening), the second concurrent use is a reuse event → KC revokes the session, in-flight requests 401, session cleared (via M2's path). Needs a per-session in-flight-refresh mutex/dedup. |
| **M4** | Session / Security | keycloak-demo · micronaut-security-session `SessionLoginHandler` (no rotation); geowealth · `OidcCallbackAction.java:405-409` | BFF: the pre-auth session (created to hold `state`/`nonce`/PKCE, `application.yml:50-53`) is **reused** after login — no session-ID rotation → session fixation. P1: `p1-self-client` is a **public** OIDC client (live confirm: `publicClient=true`); refresh token held in `HttpSession` and replayed with `client_id` only. | BFF: an attacker who can plant a `BSESSION` cookie (the multi-subdomain `.geowealth.int` design makes a `Domain=`-scoped cookie from a sibling host realistic) fixes a session ID and rides the authenticated session. P1: a public client + long-lived refresh token lowers the bar for token theft → token minting / forced logout. Fix: rotate session ID on auth success; make `p1-self-client` confidential + PKCE. |
| **M5** | Login / Standards | geowealth · `IdpSsoAction.java:207-225`; metadata `WantAuthnRequestsSigned="false"` (live) | Inbound SAML AuthnRequest is parsed only to echo its ID into `InResponseTo`; no signature check, no Issuer allowlist, no Destination check, replay cache not wired into the SSO path (only SLO). | Login-CSRF (a third party can drive an authenticated browser to mint+POST an assertion) and no issuer pinning. **Mitigated** (hence Major not Blocker): the Response is always posted to the server-fixed `KEYCLOAK_ACS_URL` (env, not request-derived), so classic assertion-exfiltration to an attacker ACS is closed. Fix: validate AuthnRequest signature + Issuer/Destination allowlist; run the ID through the replay cache. |
| **M6** | Login / Security | keycloak-demo · `realm-export.json:486` (`trustEmail:true`), `:122-145` (`idp-confirm-link`/verification DISABLED) | First-broker-login auto-links an existing KC user by asserted email with zero verification; roles + `firmCd` come from the SAML assertion (`syncMode=FORCE`), entirely P1's authority. | If a second IdP is ever added, any account whose email can be set to a victim's silently links to the victim → account takeover. **Acceptable only** because P1 is the sole brokered IdP and owns/verifies email authoritatively. This is a load-bearing assumption that must be documented and enforced; the geowealth side does avoid email-based user resolution (`OidcCallbackAction.resolveUser` uses UUID/`preferred_username` only ✅), so the duplicate-email auto-link bug is **not** on this path. |
| **M7** | Reliability (data) | geowealth · `AuthorizationManagerTrait.java:976-1001` (guard at `:70`) | Host→firm match = first hit of `appURL.indexOf("/" + firmUrl)` over `getAllActiveFirms()`. The "firm-context bleed" fix (commit `e9e8f642261`, ancestor of HEAD) added `isMatchableBaseUrl()` rejecting `"/"`-degenerate base URLs — but only those. | The firm-context bleed bug class is **half-closed**. Any two firms with overlapping/duplicate `system_base_url` values (operator data entry) silently re-create cross-firm context bleed — first match wins, no uniqueness validation, no multi-match warning. The decisive part of the 2026-05-31 fix was a one-off JDBC data patch (`FIRM_TBL.system_base_url`) that lives in no repo, so the bug re-arms on any mis-seeded environment. |
| **M8** | Operability | keycloak-demo · `docker-compose.yml:40` | KC runs `start-dev --import-realm --verbose`; realm has `bruteForceProtected=false`, `eventsEnabled=false`, `adminEventsEnabled=false`, `resetPasswordAllowed=true` (all confirmed live). | `start-dev` disables prod hardening (caching, hostname strict checks, HTTP allowed). No brute-force lockout, no login/admin audit trail, a forgot-password flow on a realm with no local credentials (enumeration/spam vector). Fix: `start` (prod mode) + explicit hardening; enable events; `resetPasswordAllowed=false`. |
| **M9** | Operability | both repos · hardcoded `*.geowealth.int`, `:5180/:5184/:5185/:8084/:8085`, `http://localhost:8888` | Vite build-time host/port bake-in; realm redirect URIs include `http://localhost:5184/*` etc. (live confirm); geowealth env vars exist for KC URLs but default to dev values; `P1_REDIRECT_HOST_ALLOWLIST` defaults to `^(localhost|[a-z0-9-]+\.localhost)$`. | Environment coupling: a new whitelabel host needs realm redirect-URI registration (the JIT `KeycloakClientRedirectRegistrar` covers this at runtime but carries the whole security boundary in one env var — if not overridden to the prod DNS pattern, prod whitelabels break or KC becomes an open redirector). Plaintext `http://localhost` redirect URIs on live clients are a code-leak channel combined with B1. |

### Minor

| # | Area | Repo · Location | Evidence | Impact |
|---|---|---|---|---|
| **m1** | Standards (BCP) | keycloak-demo · `bff-core/.../LogoutTokenValidator.java:63-79` | Verified solid: RS256 sig vs KC JWKS, `iss`/`aud`/`events`/`sid` checked. Missing per OIDC Back-Channel Logout 1.0: the "MUST reject if `nonce` present" guard, a `jti` replay cache, `iat`/`exp` required (only validated if present), and `typ` accepts `JWT`/absent not only `logout+jwt`. | Exploit value limited to forced logout; endpoint not exposed through nginx (live: `POST /backchannel-logout` → 405 via nginx — internal docker network only). Cheap to tighten. |
| **m2** | Reliability | keycloak-demo · `SidSessionRegistry.java:31-36` | `register()` called on every `/auth/me`; entries removed only by `invalidateBySid`. Idle-expired and direct-deleted sessions are never pruned. | Unbounded slow map growth + stale sid→dead-session mappings on a long-lived process. Add a session-destroyed listener / TTL eviction. |
| **m3** | Security | keycloak-demo · `bff-core/.../KeycloakAuthenticationMapper.java:101-134` | `memberships` (the firm-membership authz input) is extracted by regex `"\"memberships\"\\s*:\\s*\\[([^\\]]*)\\]"` from the decoded JWT payload, not a JSON parser. | KC-signed so not injectable (modulo B3), but brittle: any claim value containing `]`/quotes or reordering with nested arrays silently changes authorization. Use the parsed Nimbus claim set (the refresh path already does, `TokenRefreshFilter.java:206`). |
| **m4** | Operability | keycloak-demo · `application.yml` billing:124-129 / trading:109-111 | `initiate-slo-url: http://localhost:8888/...` is a literal; the comment says "override via `P1_INITIATE_SLO_URL`" but no `${...}` placeholder exists — only the implicit `APP_P1_INITIATE_SLO_URL` mapping works. | Every logout lands on plain-HTTP localhost; the documented override name silently doesn't work — operator trap. |
| **m5** | Reliability | keycloak-demo · `BackchannelLogoutController.java:35-47` | No `@ExecuteOn(BLOCKING)`; `validator.validateAndGetSid` can trigger a blocking JWKS fetch on the Netty event loop on first use / key rotation. | Event-loop stall during back-channel logout key rotation. |
| **m6** | Session lifetimes | keycloak-demo · `realm-export.json:6-8` (live confirm) | `accessTokenLifespan=1800` (30 min), `ssoSessionIdleTimeout=25200` (7 h), `ssoSessionMaxLifespan=36000` (10 h); `revoke.offline.tokens=false`. | 30-min access token is long for a token forwarded server-side to P1 for Tier 2/3 authz; 7-h idle is generous. Tighten for prod. |
| **m7** | Security | geowealth · `SilentSsoAction.java:258-272`, `IdpSsoAction.java:523-536` | First `X-Forwarded-For` hop trusted for the per-IP silent-SSO rate limiter and audit `remote=`, with a comment that the front proxy "must strip inbound XFF." | If the proxy doesn't strip XFF, the rate limiter is bypassable and audit IPs are forgeable. Depends on proxy config (not assessed). |
| **m8** | Reliability | geowealth · `OidcCallbackAction` claim staleness | `firmCd`/`memberships` KC attributes update only on an interactive SAML broker pass; silent `prompt=none` re-auth and token refresh reuse the existing KC session. | A P1-side firm switch (whitelabel entry / gwAdmin override) is not propagated to live BFF sessions — `tenantIdentity`/`firmCd` reflect the last interactive login until the next one. Memberships are the full set so authz is unaffected; identity display/scoping can be stale. |
| **m9** | Perf | geowealth · `AuthorizationManagerTrait.java:974` | `firmDAO.getAllActiveFirms()` full scan per anonymous `IdentifyFirmByUrlMsg`, then linear probe; falls through to `loadAllActiveEmployeesWithWitelabel(null)`. | Hot path on every anonymous landing doing two full-table loads — the repo already has a history of exactly this N+1 class (getWhitelabelFirmsList, 72-107s → ~9s). |

---

## What is solid (verified, with evidence)

These passed review and, where possible, live verification — the report is a baseline, not just
a defect list.

**Token Handler / browser security**
- **Tokens never reach the browser.** No `keycloak-js` dependency in either `web/package.json`;
  the SPA's "who am I" is a cookie call to `/auth/me`; access/refresh/id tokens live only in
  server-side session attributes (`KeycloakAuthenticationMapper.java:90-92`).
- **Session cookies correct.** Live trace: `kc_silent_attempt=1; Max-Age=120; Path=/; Secure;
  HttpOnly; SameSite=Lax`. Session cookies `BSESSION`/`TSESSION` are `httpOnly` (library-hardcoded),
  `Secure`, `SameSite=Lax`, host-only (no `Domain`), UUID/SecureRandom IDs.
- **PKCE + state + nonce on the code flow**, persisted server-side (`nonce.persistence: session`,
  `state.persistence: session`), nonce + id_token signature validated at exchange.
- **No open redirect anywhere.** Every redirect target in `bff-core` is a fixed literal or
  boot-time config; no `redirect_uri`/`return_to`/`post_logout` request parameter is read.
  Live confirm: `/auth/logout?redirect_uri=https://evil.example.com` still redirected to the
  fixed SLO literal. P1 side: `return_to` is always prefixed with the server-derived
  `publicBaseUrl()`, so an attacker absolute URL becomes a malformed same-host path.

**SAML / OIDC crypto**
- **SAML signing is RS-SHA256** (no SHA-1) with exclusive C14N; **both** Assertion and Response
  signed for the KC broker; `Conditions`/`SubjectConfirmation` well-formed (300s window,
  `Recipient`=ACS, audience = realm root).
- **Keycloak validates SAML signatures** hard-on: `wantAssertionsSigned`,
  `wantAuthnRequestsSigned`, `validateSignature` all `true`, `RSA_SHA256`, embedded signing cert,
  `postBindingResponse` (Response off the URL).
- **OIDC id_token cryptographically verified** (RS256 only, `none` refused, `kid`-pinned JWKS with
  rotation, `iss`/`aud`/`exp`/`nbf`+skew) on both the callback and back-channel-logout paths.
- **Back-channel logout token** RS256-pinned to KC JWKS, `iss`+`aud`+`events` checked, `sid`
  required, `Cache-Control: no-store`; endpoint not publicly reachable (nginx 405).

**Authorization / tenancy**
- **Firm-switch authz is account-gated** against the `LINKED_GW_USER` link graph — a user can only
  switch into a firm they hold an account in; `gwAdmin` cross-firm is an explicit server-flag-gated
  override; else `denyAccess` (no deny-loop). No privilege escalation path found.
- **Roles/firmCd are server-derived**, not user-supplied (read from `ENTITY_ROLE_TBL` / the
  gwAdmin DB flag); NameID = link-graph `personId`. A user cannot inject their own tenant identity.
- **User resolution avoids the duplicate-email auto-link bug** (`resolveUser` uses UUID /
  `preferred_username` only — email lookup deliberately omitted).
- **Fail-closed fine authz** when P1 is unreachable (empty perms / `false` / empty subset).
- **Defence-in-depth floor:** `/api/** → isAuthenticated()` kept under the filters; clean 401-vs-403
  split (the documented re-login-loop fix), live-verified by `no-relogin-loop.spec.ts`.

**Flow correctness**
- **Gap-6 establish round-trip is live and working** — catalina.out shows
  `establish(kc_idp_hint=p1)` markers (latest 2026-06-12 09:51), one per credential login, and the
  round-trip does not swap the authenticated identity (`OidcCallbackAction.java:219-240`).
- **Establish + `silent_failed` loop guards are server-side** and distinguish app `replace()` from
  a genuine user reload via the navigation-type check.
- **Firm-context bleed bug is FIXED** on these branches: code guard (`isMatchableBaseUrl`) is an
  ancestor of HEAD, regression specs `whitelabel-firm-resolution.spec.ts` +
  `whitelabel-cross-host-sso.spec.ts` assert distinct per-host firm context. (Residual data risk → M7.)
- **JIT redirect registrar works live:** `p1-self-client.redirectUris` now holds `localhost`,
  `john.localhost`, `c1wealth.localhost` callbacks (registered at runtime).

---

## E2E coverage matrix

Suite: `keycloak-demo/e2e/` (Playwright, 14 specs, `workers:1`, 90s timeout). Prereqs:
`./e2e/scripts/disable-mfa-for-tim1.sh` (one-time), live stack + P1 Tomcat + SamlManager agent.

| Spec | Covers |
|---|---|
| `api-authorization.spec.ts` | BFF Tier-1 `@Secured` gate through the full cookie→session→token chain |
| `billing-tenant-identity-johnastim5.spec.ts` | `/auth/me tenantIdentity` = real LDAP_UID, not the symbolic alias |
| `external-logout.spec.ts` | Out-of-band ends: P1 IdP-initiated SLO + KC admin force-revoke → back-channel fan-out |
| `logout.spec.ts` | SPA `/auth/logout` → KC end-session → back-channel kills siblings → P1 SLO |
| `no-relogin-loop.spec.ts` | 401-vs-403 split + 10s loop guard |
| `p1-login-newtab-john.spec.ts` | Credential login → establish → new tab on `john.localhost` silent login as firm-5 linked account |
| `p1-relogin-silent-recovery.spec.ts` | Logout-everywhere → re-login → reload retries silent SSO; no firm bleed |
| `personid-stability.spec.ts` | `personId` stable across re-login / fresh first-broker-login |
| `silent-first-flow.spec.ts` | Cold-start upgrade on `login_required` vs warm-start no-round-trip |
| `sso-claims.spec.ts` | Person-stable `personId` across subdomains, coherent claims, no role bleed |
| `token-handler.spec.ts` | No OP tokens in the browser; httpOnly/Secure/SameSite cookie only |
| `whitelabel-cross-host-sso.spec.ts` | Host-dynamic redirect_uri; localhost=firm1 vs c1wealth=firm1123 (**bleed regression**) |
| `whitelabel-firm-resolution.spec.ts` | Anonymous host→firm resolution; guards `isMatchableBaseUrl` |
| `whitelabel-global-logout.spec.ts` | Whitelabel SLO → KC sessions 0, BFF 401, sibling P1 torn down |

**Live e2e run (2026-06-12, full serial suite, 10.3 min):** **24 passed, 2 failed, 2 skipped.**
Both failures are in the **firm-5 `john.localhost` whitelabel member-switch path** (see F1 below):

- `billing-tenant-identity-johnastim5.spec.ts:67` — `/auth/me` returned **401** (expected 200): the
  firm-5 login chain never established a billing BFF session for the `johnAstim5` account.
- `p1-login-newtab-john.spec.ts:103` — the new `john.localhost` tab **was** silently established
  (a session exists), but as the **wrong person**: expected john's entity UUID
  `019E8978F9AE7676915751838956A526`, received `6459DFB4414B47DE9BFE9AC06205BD43`. The silent
  login resolved to the wrong firm-5 account.

The 24 passing specs cover the core flows cited under "What is solid" (token-handler invariants,
SSO claims, personId stability, silent-first, logout fan-out, whitelabel firm-resolution, the
bleed regression, no-relogin-loop). The 2 failures are isolated to the firm-5 member real-switch.

**Flow areas with NO e2e coverage:**
- JIT redirect-URI registration (`KeycloakClientRedirectRegistrar`) — no spec exercises a cold host
  being registered, nor the allowlist rejection path. `green.localhost` is seeded in the realm but
  referenced by no spec/fixture (orphaned seed).
- Non-admin deny path (`access_denied=1` from `denyAccess`) — no spec; "construction-verified" only.
  gwAdmin cross-firm override has no dedicated assertion (tim1 is gwAdmin, runs implicitly).
- Logout-CSRF (M1), session fixation (M4), refresh-error mass-logout (M2), concurrent-refresh
  race (M3) — none covered.
- Claim staleness after a firm switch (m8), token/session time-based expiries.
- Stale `?code=` after browser restart, multi-tab logout race, forged-token audience validation
  (flagged 🟡 in the suite's own `COVERAGE.md`).

---

## Production hardening checklist (derived from the blockers/majors)

1. **Keycloak clients** (B1, M4): `publicClient=false` + client secret per env, `pkce S256`
   required, `directAccessGrantsEnabled=false` — both demo clients **and** `p1-self-client`; in
   the seed and live.
2. **Shared state** (B2): Redis/JDBC session store + sid registry; remove the in-JVM assumption
   across all four registries.
3. **TLS** (B3): remove `insecure-trust-all-certs`; verify the chain; add id_token signature
   verification in the refresh path.
4. **Secrets** (B4): scoped KC service account (`manage-clients` on `demo-realm`), no `admin/admin`,
   no defaulted passwords; Postgres + keystore + client secrets from a manager.
5. **Keystore** (B5): persistent secret-managed PKCS#12, distinct key/store passwords, cached
   in-memory `Credential`.
6. **SLO signature** (B6): require `P1_IDP_KC_SP_CERT` in prod (fail closed); implement real
   Redirect-binding signature verification; sign outbound AuthnRequests (M5).
7. **Logout verb** (M1): make `/auth/logout` POST-only or CSRF-token-gated.
8. **Refresh resilience** (M2, M3): distinguish `invalid_grant` from transport errors; per-session
   refresh mutex.
9. **Keycloak prod mode** (M8): `start` not `start-dev`; brute-force, events, admin-events on;
   `resetPasswordAllowed=false`.
10. **Env decoupling** (M9): externalize all hosts/ports/issuer; set `P1_REDIRECT_HOST_ALLOWLIST`
    to the prod DNS pattern; remove `http://localhost` redirect URIs from live clients.
11. **Firm-resolution robustness** (M7): add `system_base_url` uniqueness validation + multi-match
    warning; capture the data fix as a migration, not a one-off JDBC patch.

---

## Coverage statement — what was NOT examined / could not be exercised

- **Production edge posture** — real TLS termination, HSTS/CSP headers, and proxy XFF stripping
  (m7) live at an edge that doesn't exist in this dev stack; the mkcert + Vite-preview/nginx setup
  is dev-only, so prod header posture and rate-limit integrity can't be judged here.
- **Live exploitability of session fixation (M4)** and the logout-CSRF (M1) was reasoned from code
  + a single live trace, not demonstrated with a full cross-site PoC.
- **Real downstream tenant isolation** — all `/api/**` data is hardcoded stubs; the claims plumbing
  to scope by `firmCd`/memberships exists, but whether a real service enforces it can't be judged.
- **Database state** behind the firm-resolution fix (M7) — `FIRM_TBL.system_base_url` on the live
  Oracle DB was not inspected; if re-seeded since 2026-05-31 the bleed could be live despite the
  code guard.
- **Realm drift** — the worktree holds `realm-export.json.bak-tenants` / `.bak-users` (in-flight
  migration); the live realm was probed directly (findings above use live values), but the seed
  file and live realm differ (e.g. seed still public — which matches live here).
- **`KcSessionProbe` / `IdpInitiateSloAction` internals** were confirmed present and shaped as
  described but not line-audited.
- **The full e2e suite** was started live; per-test P1 logins are slow (~45-90s each, serial), so
  results are appended below rather than blocking this report.
