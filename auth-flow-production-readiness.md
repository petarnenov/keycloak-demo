# Auth Flow — Production-Readiness Report

Audit of the login + logout flow across the keycloak-demo stack
(SPAs ↔ Micronaut BFFs ↔ Keycloak ↔ P1 SAML IdP) against industry
standards and production-readiness criteria. Companion to
[`p1-auth-flow.md`](p1-auth-flow.md) (authorization model) and
[`sso-role-mapping.md`](sso-role-mapping.md) (role/tenancy vocabulary).

## Architecture in one diagram

```
Browser  →  https://<domain>.geowealth.int:518X  (SPA in nginx container)
           ├─ /            → React SPA (static)
           ├─ /api/*       → BFF (Micronaut)
           ├─ /auth/*      → BFF
           └─ /oauth/*     → BFF (micronaut-security-oauth2)
                              │
                              │  PKCE (S256), state, nonce, secure session
                              ▼
                        Keycloak 26 (OIDC issuer https://auth.geowealth.int:5180)
                              │
                              │  kc_idp_hint=p1 → SAML AuthnRequest
                              ▼
                              P1 SAML IdP (http://localhost:8888)
```

The BFF keeps access/refresh/id tokens in a server-side `InMemorySession`;
the browser only ever carries the httpOnly `BSESSION` / `TSESSION` /
`USESSION` cookie. The OIDC code flow runs server-side; SAML federation
to P1 is brokered through Keycloak.

---

## ✅ What follows industry standards

| Standard / pattern | Implementation in this repo |
|---|---|
| **IETF "OAuth 2.0 for Browser-Based Apps" (BFF / Token Handler)** | Tokens never reach the browser. SPAs hold no `keycloak-js`, no token in `localStorage`/`sessionStorage`. The only browser-side identity material is the httpOnly session cookie. |
| **OIDC Authorization Code Flow with PKCE (S256)** | Visible in the `Location` header of `/oauth/login/keycloak` — `code_challenge_method=S256` plus a fresh `code_challenge` per request. |
| **State + nonce, server-side persistence** | `application.yml` has `nonce.persistence: session` + `state.persistence: session` — neither leaks to a client cookie, so the OIDC callback validates cleanly regardless of SameSite/third-party rules. |
| **httpOnly + Secure + SameSite=Lax cookies** | All three BFF session cookies (`BSESSION`/`TSESSION`/`USESSION`) carry these flags; the SPA can't read them from JS. |
| **OIDC Back-Channel Logout 1.0** | `LogoutTokenValidator` (RS256 + iss + aud + `events` claim + `logout+jwt` typ verifier), `BackchannelLogoutController`, and `SidSessionRegistry` to correlate KC's `sid` to the BFF's session. |
| **Server-side token refresh before expiry** | `TokenRefreshFilter` runs after the security filter; if the access token is within 60s of expiry it does a `refresh_token` grant against KC and rebuilds the `Authentication` (with the same shape as `KeycloakAuthenticationMapper`) inline. |
| **SAML 2.0 federation** | P1 is configured as an external SAML IdP in Keycloak with signed `AuthnRequest`s, signed assertions (`wantAssertionsSigned`/`wantAuthnRequestsSigned`/`validateSignature`), and X.509 signing cert validation. |
| **Defense in depth at the BFF** | `@Secured` on every controller + `intercept-url-map` floor in `application.yml` (`/api/** → isAuthenticated()`). |
| **Tier 2/3 fine-grained authz scaffolding** | `P1AuthzClient` for the `<objectTypeCd>_<permissionCd>` permission map with TTL cache, fail-closed behaviour, and opt-in via `app.authz.fine-enabled`. |
| **Reactive 401 → login redirect** | The SPA's `api.ts` treats any 401 as "no BFF session" and bounces the browser to `/oauth/login/keycloak`. A `sessionStorage` loop guard prevents infinite redirects. |
| **CORS discipline** | Each BFF allows only its own SPA origin (`CORS_ORIGIN: https://<domain>:518X`). |
| **OIDC RP-Initiated Logout (workaround)** | Currently routed through P1's IdP-initiated SLO because of a P1-side bug (see §"What's not production-ready" #9). The end-state is correct, the route is non-standard. |

---

## ❌ What's NOT production-ready

Ordered by severity.

### 🔴 Critical — security blockers

1. **`micronaut.http.client.ssl.insecure-trust-all-certs: true`** in all three BFFs' `application.yml`.
   - The BFF trusts **any** TLS certificate on server-to-server calls to KC (and to P1, in the trading/billing wiring).
   - In production: MITM is trivial; the BFF will happily accept a forged KC.
   - **Fix:** remove the flag, import the org's CA into the JRE truststore (the Dockerfile already does this for the mkcert CA — swap the source for the real CA).

2. **`publicClient: true` + empty `OAUTH_CLIENT_SECRET`** on the demo realm clients.
   - Realm export marks all three clients as public; the demo works because PKCE compensates.
   - For a server-side BFF the canonical shape is a **confidential** client with a secret.
   - **Fix:** flip `publicClient: false`, generate a real secret, source it from a vault (HashiCorp Vault / AWS Secrets Manager / k8s External Secrets Operator), and rotate.

3. **`InMemorySession` for everything that matters**.
   - Micronaut's default `SessionStore` is JVM-heap. `SidSessionRegistry` is also a `ConcurrentHashMap` in heap.
   - In production: 2+ BFF replicas can't share state without sticky sessions. A BFF restart logs **every** user out.
   - **Fix:** Redis-backed session store (`micronaut-redis-session` or a custom `SessionStore` backed by Lettuce). Migrate `SidSessionRegistry` to a Redis Set with TTL aligned to the KC SSO session lifespan.

4. **mkcert-issued TLS certificates** under `proxy/certs/`.
   - Locally trusted by the user's dev machine only.
   - **Fix:** real CA-issued certs (Let's Encrypt / ACM / org CA).

5. **`http://localhost:8888` hardcoded** as the P1 base URL in all three BFFs' `application.yml`.
   - In production P1 lives elsewhere; the URL is per-environment.
   - **Fix:** required env var with validation; a ConfigMap in k8s; `@Value` without a default so startup fails when the value isn't set.

6. **`/backchannel-logout` is `@Secured(IS_ANONYMOUS)`** with no IP allowlist and no mTLS.
   - Anyone on the network can `POST` here. `LogoutTokenValidator` checks the JWT but there's no rate-limit on the JWKS-fetch path it triggers.
   - An attacker can flood forged logout tokens and either DoS the JWKS endpoint or grind through cache invalidations.
   - **Fix:** at the nginx layer, restrict source to Keycloak's pod / VNet, or require an mTLS client cert.

### 🟠 High — operational risk

7. **The SPA's login-loop guard** is a `sessionStorage` timestamp with a 10s window.
   - Brittle: a real auth failure that takes >10s gives a false negative; a slow refresh gives a false positive.
   - The realm already maps `redirect.login-failure: /?login_error=true` but the SPA doesn't read that query param.
   - **Fix:** an explicit state machine, with an error page wired to `?login_error=true` and proper retry semantics.

8. **`kc_idp_hint=p1` is forced by `IdpHintFilter`** on every authorize redirect.
   - The demo realm has no native users; if P1 is down, nobody can log in.
   - **Fix:** make the hint configurable per request (e.g. `?idp_hint=local`) so a break-glass account / fallback IdP path exists.

9. **Sign-out routes through `http://localhost:8888/saml/idp/initiate-slo.do`** (P1's IdP-initiated SLO).
   - This is a workaround for `GeowealthSessionListener.java`'s documented bug: a SAML LogoutRequest received over the cross-port hop loses the `JSESSIONID` cookie, so P1's standard SAML SLO endpoint can't identify which session to invalidate and silently does nothing.
   - The current workaround works but lands the user on P1's own UI (`/#platformOne`) rather than back on the domain they signed out from.
   - **Fix:** at the P1 side, accept back-channel SAML SLO (SOAP binding) or correlate the session by SAML `NameID` in the database, not by browser cookie. Then KC's standard RP-initiated logout starts working and the BFF can drop the workaround.

10. **No CSRF protection** on state-changing endpoints.
    - SameSite=Lax + httpOnly covers most of it, but multipart `POST` paths like `createUpdateUser` are still reachable on a top-level navigation, which Lax allows.
    - **Fix:** double-submit CSRF token (Micronaut has built-in support), or move every state-changer behind `SameSite=Strict`.

11. **No rate limiting** anywhere.
    - `/oauth/login/keycloak`, `/auth/me`, `/api/*`, `/backchannel-logout` — all unbounded.
    - **Fix:** nginx `limit_req_zone` for the login endpoints; Bucket4j + Caffeine inside the BFF for per-IP and per-user limits.

12. **No HTTP security headers** at the SPA nginx.
    - Missing: `Content-Security-Policy`, `X-Frame-Options`, `Referrer-Policy`, `Permissions-Policy`, `X-Content-Type-Options`.
    - `Strict-Transport-Security` is set on the KC reverse proxy (`max-age=31536000; includeSubDomains`) but **not** on the SPA hosts.
    - **Fix:** add a shared `security-headers.conf` snippet to each `domains/*/web/nginx.conf`.

13. **No audit logging** for auth events.
    - Login / logout / refresh land in the BFF's DEBUG log only, mixed with framework noise.
    - For SOC2 / ISO 27001 you need a signed/immutable audit trail.
    - **Fix:** explicit `AuthEvent` emission to Kafka / Logstash / SIEM with a fixed schema.

14. **No explicit idle-session timeout** in the BFF.
    - Micronaut defaults to 30 min, but it isn't declared in `application.yml`, and the BFF session lifespan isn't aligned with the KC realm's `ssoSessionMaxLifespan`.
    - **Fix:** `micronaut.session.http.max-inactive-interval: PT30M` (or whatever the policy is), kept in sync with the realm setting.

### 🟡 Medium — UX & operability

15. **Sign-out UX trade-off**. Documented but ugly: after the user signs back in at P1, they land on P1's UI and have to navigate back to the demo domain via P1's sidebar. Fixing #9 collapses this discontinuity.

16. **`TokenRefreshFilter` runs `nearExpiry()` on every request** to `/api/**` and `/auth/**`.
    - Each call parses the JWT and compares a `Date` — not fatal, but pure overhead on the hot path.
    - **Fix:** memoize the decision per request scope, or coalesce checks to once-per-N-seconds at filter level.

17. **`SidSessionRegistry` has no TTL eviction.**
    - The `ConcurrentHashMap<String, Set<String>>` grows without bound. Fine for the demo, leaks in production.
    - **Fix:** Caffeine cache with `expireAfterWrite` = session lifespan; or hand the responsibility to the Redis store from #3.

18. **The login-guard is per-tab (`sessionStorage`)**.
    - Multi-tab UX is jagged: each tab has its own guard timestamp; a successful login in one tab doesn't unblock another mid-redirect.
    - **Fix:** `BroadcastChannel('auth')` for cross-tab coordination on login too (the frontchannel-logout flow already uses it on the logout side).

19. **The realm export is stale.**
    - `CLAUDE.md` flags that `--import-realm` runs in `IGNORE_EXISTING` mode and live admin-API edits don't flow back into `keycloak/realm-export.json`.
    - **Fix:** automation (`scripts/kc-realm-export.sh`) run before every release; the export is the truth for fresh installs.

### 🟢 Low — cosmetic / minor

20. **`stop` file in repo root** — accidental duplicate of `stop.sh`; not committed but kept showing up in `git status`.
21. **Default `logback-classic` config** logs at DEBUG. In a production image you want INFO and structured JSON output.
22. **React `StrictMode` is back on.** Fine for production builds (no double-mount), but a dev `npm run dev` invocation would double-fire `AuthProvider`'s `/auth/me`. Harmless now — flagged for posterity.

---

## 🛑 Must-fix before production

If you only act on one section: this one. The minimum bar to take the
current flow off "demo grade" and into anything customer-facing:

1. **TLS trust** — set `insecure-trust-all-certs: false`, ship real
   CA-issued certs, and import the org CA into the BFF JRE truststore.
2. **Confidential OAuth clients** — flip `publicClient: false`, source
   the secret from a vault (not an env var), rotate.
3. **Shared session store** — Redis for BFF sessions AND for
   `SidSessionRegistry`; without this, HA + zero-downtime deploys are
   impossible.
4. **CSRF tokens** on state-changing endpoints.
5. **Rate limiting** on `/oauth/login/keycloak`, `/api/*`, and
   `/backchannel-logout`.
6. **Security headers** in nginx — CSP (with nonces for inline scripts),
   HSTS, `X-Frame-Options`, `Referrer-Policy`, `X-Content-Type-Options`.
7. **Audit logging** — auth events emitted to SIEM with a signed,
   versioned schema.
8. **Fix P1's SAML SLO** to not rely on cross-port `JSESSIONID`. That
   lets the BFF drop the IdP-initiated SLO workaround and use Keycloak's
   standard RP-initiated logout; sign-out will then land back on the
   domain SPA instead of P1's UI.
9. **Protect `/backchannel-logout`** with an IP allowlist (KC only) or
   mTLS.
10. **A fallback login path** — make `kc_idp_hint=p1` conditional, not
    hardcoded by a filter. There must be a recovery flow when P1 is
    down.

---

## Bottom line

**Architecturally**, the flow is modern and correct: IETF Token Handler
pattern (BFF), OIDC with PKCE, SAML federation behind Keycloak, OIDC
Back-Channel Logout 1.0. That foundation is good.

**At the detail level** there are enough security gaps that the current
code is **demo-grade, not production-grade**. Without `insecure-trust-all-certs: false`,
shared session storage, CSRF tokens, and rate limiting, this stack
cannot ship — even if the realm config itself were enterprise-ready.

**Sign-out specifically** is a workaround for a P1-side bug (SAML SLO
can't see the user's `JSESSIONID` over the cross-port hop). Fixing P1
removes the need for the IdP-initiated SLO trick and restores the clean
RP-initiated logout where the user lands back on the SPA they signed
out from.
