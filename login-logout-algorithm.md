# Login / Logout — full algorithm

End-to-end description of every authentication path implemented across two
repos:

- `keycloak-demo` — branch `petarnenov/bff-core-persons-registry`. Holds the
  Keycloak realm seed, every demo SPA (billing, trading) and their BFFs
  (`bff-core` + `bff-billing` + `bff-trading`).
- `~/nodejs/geowealth` — branch `team/petarnenov/keycloak-persons-registry`,
  plus its server-side counterpart at `~/AppServer/geowealth` on the same
  branch. Holds P1's React frontend (`WebContent/react/app`) and the Struts
  Actions that back it.

Last update: **2026-06-04** — covers Gap 6 (post-login KC establish
round-trip), cross-host silent SSO recovery on user reload, and the BFF
`/auth/logout` idempotency fix.

> **Architecture update (2026-06-13).** The OIDC / SAML protocol sequences below
> are still accurate, but *where* the auth code runs and a few names have changed
> — read these deltas alongside the diagrams:
> - **Auth is no longer in the per-domain BFFs.** It runs in a separate **Token
>   Handler** service; the data BFFs (`bff-billing`/`bff-trading`) are now
>   **auth-unaware** (forward-auth: nginx `auth_request` → `/auth/verify` → inject
>   `X-Auth-*` headers, cookie dropped). So "the BFF does login/refresh/logout" below
>   means **the Token Handler**.
> - **One multi-tenant Token Handler** fronts every domain (not one per BFF): one
>   shared OIDC client **`demo-shared-client`**, one **`GWSESSION`** cookie
>   (host-scoped — replaces `BSESSION`/`TSESSION`), per-domain authz resolved by
>   `Host` from `app.tenants.*`.
> - **Sessions live in Redis** (B2), not the in-memory `SessionStore` the actor
>   table still names — so any replica serves any session and a redeploy doesn't
>   log users out.
> - Authoritative current refs: `CLAUDE.md` (Layout → `token-handler/`),
>   `token-handler-plan.md`, and `k8s/README-multitenant-k8s-plan.md`.

---

## 0. Actors and where state lives

| Actor | Origin | State held | Cookie / storage |
|---|---|---|---|
| **P1 Tomcat** (Struts + Akka agents) | `localhost:8888` (webpack-dev-server → Tomcat :8080) | `LoggedUser`, `LoggedAdviser`, `firmInfo`, `ConversationManager`, `UrlWhitelabelInformation` in `HttpSession` | `JSESSIONID` (HttpOnly, session-scoped) |
| **P1 React SPA** | `localhost:8888` (whitelabel hosts: `c1wealth.localhost`, `john.localhost`, …) | login-guard flags | URL hash `#login?silent_failed=1` (no sessionStorage — Gap 6 loop prevention is server-side) |
| **Keycloak** | `auth.geowealth.int:5180` (nginx → keycloak:8080) | SSO user session, federated identity link, per-host `client-session` for the one shared `demo-shared-client` (+ `p1-self-client`; the per-domain `demo-billing-client`/`demo-trading-client` still exist but are **vestigial**) | `KEYCLOAK_IDENTITY`, `KEYCLOAK_SESSION` (persistent, `Max-Age = ssoSessionMaxLifespan`) |
| **Billing SPA** | `billing.geowealth.int:5184` | `me` payload (Token Handler's `/auth/me` projection) in React state, login-guard flag in sessionStorage | `GWSESSION` cookie (HttpOnly, Secure, SameSite=Lax, **host-scoped**) — set by the multi-tenant Token Handler |
| **Trading SPA** | `trading.geowealth.int:5185` | Same shape as billing | `GWSESSION` (one cookie name, host-scoped → distinct per host) |
| **`token-handler`** (multi-tenant; one instance fronts all domains) | container-internal :8080 (reached via each SPA host's nginx) | Server session holding `access_token`, `refresh_token`, `id_token`, `sid`, last-refresh timestamp; `SidSessionRegistry` mapping `sid → session-ids` | Sessions + sid map live in **Redis** (shared store, B2) — any replica serves any session; a redeploy does not log users out |
| **`bff-billing`, `bff-trading`** (data BFFs) | container-internal :8080 | **Auth-unaware (forward-auth):** no session, no token refresh, no security filters — read identity from `X-Auth-*` headers nginx injects after `/auth/verify`; serve data + the Tier-3 list `refine` | none (nginx drops the session cookie before proxying) |

Realm timing knobs (`keycloak/realm-export.json`):

| Setting | Value | Effect |
|---|---|---|
| `accessTokenLifespan` | 300 s | OIDC access token TTL — `TokenRefreshFilter` renews via refresh token transparently |
| `ssoSessionIdleTimeout` | 1800 s | KC kills session after this idle time across all clients |
| `ssoSessionMaxLifespan` | 36000 s | Hard ceiling on KC session lifetime; also `Max-Age` of persistent cookies |
| Tomcat `session-timeout` | 25200 s | P1's `HttpSession` idle timeout |

The three lifetimes don't expire in lock-step — that mismatch is the reason
Phase 15 silent SSO + Gap 6 establish round-trip exist.

---

## 1. The pieces in code

### 1.1 keycloak-demo

```
keycloak/realm-export.json
  └── OIDC clients: demo-shared-client (the one multi-tenant login client) + p1-self-client
      (demo-billing-client / demo-trading-client still seeded but vestigial)
      + the `p1` SAML IdP (signing cert baked in)
      + 8 SAML role-IdP mappers (one per realm-level capability role)
      + the first-broker-login flow `p1-first-broker-login` (silent email-link)

bff-core/src/main/java/demo/bff/core/
  ├── Bff.java                          shared Micronaut entrypoint
  ├── AuthController.java               /auth/me, /auth/logout, /auth/login-failed
  ├── BackchannelLogoutController.java  /backchannel-logout (KC → BFF logout token)
  ├── LogoutTokenValidator.java         verifies KC's logout_token JWT
  ├── SidSessionRegistry.java           sid → BFF-session-ids map
  ├── SilentLoginController.java        /oauth/login/silent (silent-first entry)
  ├── IdpHintFilter.java                pins kc_idp_hint=p1 on /oauth/login/keycloak Location
  ├── KeycloakAuthenticationMapper.java sub/firmCd/personId/memberships → Authentication
  ├── TokenRefreshFilter.java           per-request access-token freshness gate
  ├── P1AuthzClient.java                Tier 2/3 authorisation lookups against P1
  ├── Tier23Gate.java + AuthClaims.java per-domain authorisation helpers
  └── SubdomainRequirement(Filter).java the host the SPA was loaded from

domains/billing/web/src/                billing SPA (React + Vite)
  ├── auth/AuthProvider.tsx             fetches /auth/me, drives startLogin / logout
  └── api.ts                            startLogin(LOGIN_URL='/oauth/login/silent'),
                                        reactive-401 → loop-guarded redirect

domains/trading/web/src/                trading SPA — same shape, different roles
domains/{billing,trading}/bff/          per-domain Micronaut module (composite-build pulls bff-core)

e2e/                                    Playwright specs (12+)
  └── tests/p1-relogin-silent-recovery.spec.ts   the recovery scenario from 2026-06-04
```

### 1.2 nodejs/geowealth + AppServer/geowealth (branch `team/petarnenov/keycloak-persons-registry`)

```
WebContent/react/app/src/app/_services/appService.js
  ├── checkUserLoggedIn        (silent-SSO probe on isUserLoggedIn=redirect)
  ├── loginPassword            (POST /react/login.do, then Gap 6 establish bounce)
  └── logout                   (deleteCookie + abortAllStartedRequests + initiate-slo.do)

AppServer/geowealth/src/main/java/com/geowealth/saml/idp/
  ├── SilentSsoAction.java     /saml/idp/silent-sso.do — KC authorize with prompt=none
  │                              OR establish=true (kc_idp_hint=p1, no prompt=none)
  ├── OidcCallbackAction.java  /saml/idp/oidc-callback.do — code exchange,
  │                              UserManager lookup, host-firm switch (gwAdmin
  │                              or PersonRegistry-linked-account)
  ├── IdpSsoAction.java        /saml/idp/sso.do — SP-init SAML Response builder
  │                              (also: silent-sso & sidebar entry deep-link)
  ├── IdpInitiateSloAction.java /saml/idp/initiate-slo.do — P1-initiated SAML
  │                              LogoutRequest to KC broker endpoint
  ├── IdpSloAction.java        /saml/idp/slo.do — inbound SLO endpoint
  ├── BackChannelLogoutAction  /saml/idp/back-channel-logout.do — KC → P1 logout_token
  └── PersonRegistry.java      P1 person-id ↔ per-firm entity mapping

AppServer/geowealth/src/main/java/com/geowealth/web/common/action/
  ├── LoginAction.java         /react/login.do — credentials → LoggedUser → SUCCESS
  └── LogOutAction.java        /react/logout.do — HttpSession invalidate (legacy)
```

---

## 2. Login algorithm

### Entry surfaces

| URL | Who hits it | Lands in |
|---|---|---|
| `https://billing.geowealth.int:5184/` (or trading) | SPA AuthProvider mount | BFF Token-Handler flow ② |
| `http://localhost:8888/` | Direct browser visit | P1 React `checkUserLoggedIn` ③ |
| `http://c1wealth.localhost:8888/` (whitelabel host) | Tab open after P1 login | P1 cross-host silent SSO ④ |
| `…/saml/idp/sso.do?RelayState=…` | P1 sidebar tile | SP-init deep-link ⑤ |

### ① BFF Token-Handler initial round (any demo SPA)

```
                              ┌─────────────────────────┐
Browser ───── GET / ─────────►│  Vite preview + SPA     │
                              │  AuthProvider mounts    │
                              └────────────┬────────────┘
                                           │
                              fetchMe()  /auth/me  credentials:'include'
                                           ▼
   ┌─────────────────────────────────────────────────────────┐
   │ bff-billing (or trading)  — AuthController.me()         │
   │ @Get("/me")  @Secured(IS_AUTHENTICATED)                 │
   └─────────────────────────────────────────────────────────┘
                                           │
                              ┌────────────┴───────────────────┐
                  no session                            session present
                       │                                       │
                       ▼                                       ▼
              HTTP 401                                JSON: { username, email,
                       │                                      firmCd, personId,
   api.ts get():                                              memberships, roles }
   if (status === 401) startLogin();                  AuthProvider sets ready=true,
   startLogin() → window.location.assign(             SPA renders.
                  '/oauth/login/silent')
                       │
                       ▼
   bff-core SilentLoginController.start():
     • sets short-lived browser cookie `bff_silent_attempt = <traceId>`
       (a cookie, not a session attr, so it survives the
        anonymous-→authenticated session rotation Micronaut performs
        in the callback step)
     • 302 to  /oauth/login/keycloak?silent=1
                       │
                       ▼
   IdpHintFilter sees Location: https://auth.geowealth.int:.../auth?...
     rewrites Location → appends &kc_idp_hint=p1
     If the request had silent=1, OAuth2RedirectUrlBuilder added &prompt=none
                       │
                       ▼ (browser follows)
   KC authorize?client_id=demo-shared-client&kc_idp_hint=p1&prompt=none&...
     │
     ├── KC has SSO session ────►  silent OIDC code → 302 to BFF callback ─►
     │                              code exchange → KeycloakAuthenticationMapper
     │                              builds Authentication → Micronaut writes
     │                              GWSESSION cookie → 302 back to SPA `/`.
     │
     └── KC has NO session ────►  prompt=none → error=login_required
                                  KC redirects to redirect.login-failure
                                  configured to `/auth/login-failed` ────►
                                  AuthController.loginFailed() inspects
                                  bff_silent_attempt cookie:
                                    – silent attempt → 302 /oauth/login/keycloak
                                      (no prompt=none this time) → IdpHintFilter
                                      adds kc_idp_hint=p1 → KC drives SAML
                                      AuthnRequest to P1's `/saml/idp/sso.do`
                                                       │
                                                       ▼
                                    – P1 IdpSsoAction:
                                        if LoggedUser → builds signed SAML
                                          Response with InResponseTo, POSTs to
                                          KC broker endpoint via auto-submit
                                          form ✱
                                        else → 302 to /#login?silent_failed=1
                                          (user must credential-login)
                                                       │
                                                       ▼
                                    KC validates SAML → first-broker-login
                                      auto-link by email → creates user
                                      session → issues OIDC code → 302 to BFF
                                      callback → token exchange →
                                      KeycloakAuthenticationMapper → GWSESSION
                                      cookie set → 302 to SPA `/`.
```

End state: KC ✓, the SPA's BFF ✓ (sessionful, holding access/refresh/id
tokens server-side; `sid` recorded in `SidSessionRegistry`). The SPA's
`/auth/me` returns 200 with the Token-Handler-projected identity
(`personId`, `tenantIdentity`, `firmCd`, `memberships`, `roles`).

Why does opening trading right after billing complete silently? All domains
now log in through the one shared `demo-shared-client`, but each host gets its
own `GWSESSION` (cookies are host-scoped) and its own KC client-session under
the shared user-level KC session — so the second host rides the existing SSO
session with no IdP UI.

### ② Cold P1 visit at `localhost:8888` (no P1 session, no KC session)

```
Browser ─── GET / ──► P1 Tomcat ROOT
                       │
                       ▼
       webpack-dev-server proxy → Tomcat
       returns React shell + bootstrap JS
                       │
                       ▼
   React mounts → checkUserLoggedIn → GET /react/isUserLoggedIn.do
                       │
                       ▼
   ReactBasicResponceAction sees no LOGGED_USER →
   returns { objectType: "redirect", success: true }
                       │
                       ▼
   appService.checkUserLoggedIn (this branch is the Phase 15 path):
     const hash = window.location.hash || '';
     const navEntry  = performance.getEntriesByType('navigation')[0];
     const isUserReload = navEntry?.type === 'reload';
     const silentFailed = hash.includes('silent_failed=1') && !isUserReload;
     if (!silentFailed && objectType === 'redirect') {
       window.location.replace(
         '/saml/idp/silent-sso.do?return_to=' + encodeURIComponent(pathname));
     }
                       │
                       ▼
   SilentSsoAction.execute() (NOT establish branch):
     • rate-limit by remote IP, 2 s window. Over-limit → 200 "rate_limited"
     • stash state in HttpSession
     • redirect to KC: /protocol/openid-connect/auth?
                         client_id=p1-self-client
                         &response_type=code
                         &scope=openid
                         &prompt=none                       ← key bit
                         &state=<state>
                         &redirect_uri=http://<host>:8888/saml/idp/oidc-callback.do
                         &return_to=<original-path>
                       │
                       ▼
   KC: no session → error=login_required → 302 to
   redirect_uri ?error=login_required&state=<state>
                       │
                       ▼
   OidcCallbackAction.execute():
     • reads state, matches stash
     • sees `error` param → falls back: 302 to /#login?silent_failed=1
                       │
                       ▼
   React mounts on /#login?silent_failed=1
     hash has silent_failed=1, isUserReload=false → silentFailed=true
     → SKIP silent-SSO retry, render credential form.
                       │
                       ▼  user types creds + Login button
   POST /react/login.do?reactRequest=true   (form-urlencoded)
                       │
                       ▼
   LoginAction.execute() → loginUser() → new LoggedUser():
     PolicyRuleManager.canLoggedUserExecuteBalanceSheet(...) ← Akka ask
     UserManager.loadUserDetails(...)
     puts LOGGED_USER + LOGGED_ADVISER + LoggedUserJTO in HttpSession
   Returns Struts forward to ReactIndexAction.login() →
     JSON { objectType: "loggedUser", … }
                       │
                       ▼
   React loginPassword success branch — **Gap 6 establish round-trip**:
     window.location.replace(
       '/saml/idp/silent-sso.do?establish=true&return_to=%2F');
     return;          ← do not dispatch login-success yet
     // No client-side loop guard — server is the single source of
     // truth. A previous attempt at sessionStorage.kc_sso_established
     // could be silently sticky on a long-lived tab and stop the
     // round-trip from ever running again; removed for that reason.
                       │
                       ▼
   SilentSsoAction.execute() (establish branch — `setEstablish(true)`):
     • one-shot session guard: if SESSION_KEY_ESTABLISH_DONE → log + 302 return_to
     • else: redirect to KC: ?...&kc_idp_hint=p1
                       (NO prompt=none — we want a fresh KC session,
                        not a silent probe of an existing one)
     • mark SESSION_KEY_ESTABLISH_DONE=true on P1 HttpSession
     • the flag is cleared automatically when SLO invalidates this
       HttpSession, so a future credential login on the same browser
       gets a fresh establish run.
                       │
                       ▼
   KC: no session, kc_idp_hint=p1 → SAML AuthnRequest to P1 IdpSsoAction
                       │
                       ▼
   IdpSsoAction sees LoggedUser (just minted) → signed SAML Response with
     InResponseTo → auto-submit POST to KC broker endpoint
                       │
                       ▼
   KC: validates → first-broker-login (silent auto-link by email) →
     creates user session and p1-self-client client-session →
     issues OIDC code → 302 to oidc-callback.do
                       │
                       ▼
   OidcCallbackAction: code → token exchange → id_token →
     preferred_username=tim1 → UserManager.lookupByUsername()
     (LoggedUser is already there; this is idempotent) →
     302 to return_to=/
                       │
                       ▼
   React mounts at `/`, isUserLoggedIn → loggedUser → home page renders.
```

End state: P1 ✓, KC ✓, every demo-* OIDC client session ready to be silently
brokered on first visit. **Without Gap 6 the chain stopped at `loggedUser`
and KC stayed sessionless — cross-host silent SSO downstream would have
failed with `login_required`.**

### ③ Cold P1 visit, KC SSO already alive (Phase 15 main case)

User signed in via the BFF flow ① earlier, KC has the user session; P1
Tomcat got recycled or `JSESSIONID` was a session cookie the browser
dropped. Same flow as ②, but the KC `?prompt=none` step succeeds:

- `SilentSsoAction` → `?prompt=none` → KC has session → returns code
- `OidcCallbackAction` → code exchange → id_token claims include
  `preferred_username`, `firmCd`, `personId`, `memberships` →
  `UserManager.lookupByUsername(preferred_username)` → builds `LoggedUser` →
  writes the same `LOGGED_USER`/`LOGGED_ADVISER`/firm-info bundle the
  credential form would have written.
- 302 to `return_to=/`. No credential UI ever shown.

End state: P1 ✓ (silently restored), KC ✓ (was already), no clicks.

### ④ Cross-host silent SSO from a P1 whitelabel host

Tab 1 has the canonical P1 host (`localhost:8888`) logged in as `tim1`,
KC has the SSO session (from ② Gap 6 or ③ silent restore). User opens
`http://c1wealth.localhost:8888/` in tab 2.

```
React boots on c1wealth.localhost:8888 → isUserLoggedIn → redirect
                       │
                       ▼
window.location.replace('/saml/idp/silent-sso.do?return_to=/')
                       │
                       ▼
SilentSsoAction.execute() (probe branch, NOT establish):
  redirect_uri = http://c1wealth.localhost:8888/saml/idp/oidc-callback.do
  →  KC authorize?prompt=none&...
                       │
                       ▼
KC has tim1 session (browser sends KEYCLOAK_IDENTITY cookie, which is on
`auth.geowealth.int` — set with SameSite=Lax, sent on top-level
navigation) → issues code → 302 to c1wealth-host oidc-callback.do
                       │
                       ▼
OidcCallbackAction:
  • token exchange → id_token claims for tim1
  • call AuthorizationManager.identifyFirmByUrl(requestUrl) → resolver
    walks FIRM_TBL.SYSTEM_BASE_URL → CLIENT_PORTAL_BASE_URL → advisor
    whitelabel → topSubDomain==firm.code (the four-step search documented
    in AuthorizationManagerTrait L956-1058). Returns UrlWhitelabelInformation
    with `firmCd=5` (CreativeOne).
  • Two follow-ups depending on the home-vs-host firm relationship:
      (a) tim1.firmCd == 5         → no switch; just log in as tim1 in firm 5
      (b) tim1 has a person-linked  → switchToFirmAccount(homeUser, hostFirmCd)
          account in firm 5             via PersonRegistry → swap LoggedUser to
                                        the firm-5 account; log "firm switch
                                        user=... → account=<uuid> firm=5"
      (c) tim1 is gwAdmin and no    → keep tim1 identity but mark host firm 5;
          linked account            log "gwAdmin cross-firm entry user=...
                                        home=1 → host firm=5"
  • Put LoggedUser + firm context in this host's HttpSession
  • 302 to return_to=/
                       │
                       ▼
React mounts at `/` → isUserLoggedIn → loggedUser → c1wealth-branded
home page renders, displaying the (possibly switched) firm-5 user.
```

**Recovery on user reload (2026-06-04):** if Step 3's `?prompt=none` fails
(KC session vanished — idle timeout, logout, restart), `OidcCallbackAction`
redirects to `/#login?silent_failed=1`. Without my fix, F5 on that URL
would just re-render the login form (the hash flag was unconditionally
terminal). After the fix, `appService.checkUserLoggedIn` consults
`performance.getEntriesByType('navigation')[0].type` — a value of `reload`
(genuine user F5/Cmd-R) bypasses the loop guard and re-fires the probe,
so a single F5 silently recovers tim1/firm-5 the moment KC has a session
again. App-initiated `window.location.replace(...)` produces type
`navigate`, NOT `reload`, so the loop guard still prevents infinite
silent-SSO bounces.

### ⑤ P1 sidebar deep-link into a demo SPA

Tile in P1's `useIntegrationLinks.js` is `kcAuthorize(...)`-built:

```
https://auth.geowealth.int:5180/realms/demo-realm/protocol/openid-connect/auth
  ?client_id=demo-shared-client
  &response_type=code
  &scope=openid
  &redirect_uri=https://billing.geowealth.int:5184/
  &kc_idp_hint=p1
  &state=…
```

- KC sees `kc_idp_hint=p1` → skips its login UI → SAML AuthnRequest to
  `http://localhost:8888/saml/idp/sso.do` (`p1` IdP config in realm export).
- Browser navigates to P1 (has session) → `IdpSsoAction` builds signed SAML
  Response → POSTs back to KC broker endpoint.
- KC creates session + `demo-shared-client` client-session → 302 to
  `redirect_uri` with code.
- The billing SPA's `AuthProvider` mounts, calls `/auth/me` → BFF processed
  the code on a prior visit? Actually no — for an SP-init from P1, the URL
  carries `code` directly. Micronaut OAuth2 callback consumes it (BFF
  callback wires `/oauth/callback/keycloak` automatically); cookie is set;
  302 to `/`. `AuthProvider` then fetches `/auth/me` as in ①.

---

## 3. Logout algorithm

### Entry surfaces

| URL | Initiator | Reaches |
|---|---|---|
| `POST /auth/logout` on a demo domain host | SPA "Sign out" button (top-level form POST — CSRF defence, see §7 Q13) | bff-core `AuthController.logout` (in the Token Handler) |
| `GET /saml/idp/initiate-slo.do` on localhost:8888 | P1 React UI Logout link (`appServices.logout()`) | `IdpInitiateSloAction` |
| `POST /backchannel-logout` on a BFF | KC back-channel from a sibling logout | `BackchannelLogoutController` |
| `POST /saml/idp/back-channel-logout.do` on P1 | KC back-channel logout token | `BackChannelLogoutAction` |
| KC admin `users/<id>/logout` or `clients/<id>/logout-all` | Admin API | KC kicks everyone, fans out as above |

### ⑥ Logout from a demo SPA (Sign out button)

```
SPA: AuthProvider.logout = () => postLogout()   // builds + submits a top-level form POST to /auth/logout
                       │
                       ▼
POST /auth/logout  (top-level form POST, GWSESSION cookie attached)
                       │
                       ▼
bff-core AuthController.logout()  (2026-06-04: @Secured(IS_ANONYMOUS), @Nullable Authentication)
  ┌──────────────────────────────────────────────────────────────────┐
  │ Step A — endSessionAtKeycloak(refreshToken)                      │
  │   if refresh_token present:                                      │
  │     POST {issuer}/protocol/openid-connect/logout                 │
  │       form: client_id=<this-bff-client>                          │
  │             &client_secret=<…>                                   │
  │             &refresh_token=<…>                                   │
  │   KC's RP-initiated logout path:                                 │
  │     – kills the SSO user session                                 │
  │     – POSTs `logout_token` (a JWT) to every other OIDC client's  │
  │       `backchannel.logout.url` in this session — that's how the  │
  │       sibling BFFs find out (see ⑦)                              │
  │     – returns 204; no UI                                         │
  │                                                                  │
  │ Step B — local cleanup                                           │
  │   sessionStore.deleteSession(session.id)                         │
  │   registry.invalidateBySid(sid)         (a no-op fan-out for     │
  │                                          sessions that already   │
  │                                          got step A's fan-out)   │
  │                                                                  │
  │ Step C — 303 SEE_OTHER                                           │
  │   Location: app.p1.initiate-slo-url                              │
  │     = http://localhost:8888/saml/idp/initiate-slo.do             │
  └──────────────────────────────────────────────────────────────────┘
                       │
                       ▼
Browser navigates to P1's initiate-slo (cross-origin, top-level)
   → flow continues as in ⑦ logout-from-P1 below, but starting
     from a P1 that may not have its own HttpSession yet on this host
     (in which case IdpInitiateSloAction short-circuits to the home page).
```

**Idempotency hardening (2026-06-04):** the controller no longer requires
an authenticated caller. A double-click, an already-expired BFF session, or
a back-channel race (KC's `logout_token` from a sibling tab killed our
session a tick before the user clicked Sign out) used to surface as a
`401` HATEOAS error JSON. Now all the cleanups are null-safe and the
controller still emits the `303 SEE_OTHER` to P1 SLO unconditionally —
the worst case is best-effort cleanup with nothing left to clean.

### ⑦ Logout from P1 (Logout link in platformOne)

```
NavigationLeftComponent.logoutUser → appServices.logout()
                       │
                       ▼
appService.js  logout():
   this.service.auth.deleteCookie();             ← local: drop cached cookie copy
   this.service.abortAllStartedRequests();
   // No sessionStorage cleanup — the Gap 6 establish-done flag lives
   // on the P1 HttpSession (SilentSsoAction.SESSION_KEY_ESTABLISH_DONE).
   // The SLO chain below invalidates the HttpSession, which drops it.
   window.location.href = '/saml/idp/initiate-slo.do';
                       │
                       ▼
IdpInitiateSloAction.execute():
   • reads getLoggedUser() (still alive — we navigate, no AJAX call to
     /react/logout.do first)
   • invalidates HttpSession
   • builds signed SAML LogoutRequest:
       <Issuer>http://localhost:8888/saml/idp</Issuer>
       <NameID>tim1's persistent NameID (= P-tim, the multi-username
               PersonRegistry stable ID)</NameID>
       <SessionIndex>tim1's KC SAML session-index</SessionIndex>
   • returns an auto-submit POST form pointing at KC broker SLO endpoint:
       https://auth.geowealth.int:5180/realms/demo-realm/broker/p1/endpoint
                       │
                       ▼
KC validates the LogoutRequest signature, finds the brokered session, and
   terminates it. **But KC's SAML LogoutRequest path does NOT fan back-channel
   logout to OIDC clients — that's KC issues #17318 / #21770.**
   So the sibling BFFs (billing, trading) are NOT notified here.
                       │
                       ▼
KC emits a SAML LogoutResponse back to P1 via the Redirect binding (browser
   navigates).
                       │
                       ▼
IdpSloAction.execute() sees inbound SAMLResponse → short-circuits → 302 to
   localhost:8888/#login. P1 React loads the login form.
```

**Sibling BFFs.** Because step ⑦ alone doesn't fan logout to OIDC clients,
P1-initiated logout on its own would leave billing/trading's BFF sessions
alive (with stale refresh tokens). The hand-off is:

- When the user signs out from a SPA (⑥), Step A is the RP-initiated POST
  to `/protocol/openid-connect/logout` with `refresh_token`. THAT path
  fans `logout_token` POSTs to every other OIDC client's
  `backchannel.logout.url` — which is how billing learns about a trading
  logout and vice versa.
- For a P1-initiated logout (⑦) the same is achieved by the
  `BackChannelLogoutAction` in P1 itself: KC also POSTs `logout_token` to
  P1 (`backchannel.logout.url = http://host.docker.internal:8888/saml/idp/back-channel-logout.do`),
  which on resolving runs the same SidSessionRegistry.invalidateBySid
  dance the BFFs do.

### ⑧ Back-channel logout into a BFF

```
KC end-session for some user (any path above)
                       │
                       ▼ (server-to-server, no browser involved)
POST <bff-host>/backchannel-logout
   Content-Type: application/x-www-form-urlencoded
   logout_token=<JWT>
                       │
                       ▼
BackchannelLogoutController.logout(@Nullable @Body("logout_token") String t)
  @Secured(IS_ANONYMOUS)
   • t blank → 400
   • LogoutTokenValidator.validateAndGetSid(t):
       parse JWT header → kid → fetch demo-realm JWKS via the configured
         `jwks.url` (it points at the in-cluster keycloak:8080 service,
          a separate route from the SPA-facing public auth.geowealth.int)
       verify signature, exp, aud=this-client, iss=issuer, sid present, events
         contains `http://schemas.openid.net/event/backchannel-logout`
       return sid
   • registry.invalidateBySid(sid) → walks SidSessionRegistry's
     Redis `sid → session-id-set` map (key `bff:sid:<sid>`), deletes each session from
     SessionStore, logs the killed count.
   • 200 OK
                       │
                       ▼
Next call from the SPA in that BFF process hits AuthController.me with no
session → 401 → SPA api.ts → startLogin() (loop-guarded) → silent-SSO →
KC says login_required → SPA shows login UI (or the failed-login page).
```

### ⑨ KC admin force-logout (or KC idle / max-lifespan)

`POST /admin/realms/demo-realm/users/<id>/logout` or
`/clients/<uuid>/logout-all` end the user's KC SSO session(s). KC then
fans `logout_token` to every OIDC client that has a back-channel URL
configured. Effect = ⑧ for every sibling BFF; for P1 = ⑧-equivalent via
`BackChannelLogoutAction`.

KC idle timeout (30 min unless touched, see `ssoSessionIdleTimeout`) =
exactly the same fan-out — the user simply isn't the one initiating it.

### ⑩ P1 HttpSession timeout (server-only)

Tomcat reaper invalidates P1's `HttpSession` after 7h idle. P1 has no
notification path back to KC for this. Next time the user touches P1, the
flow degrades to ③ (cold P1 visit with KC session alive) which silently
restores P1 from the still-valid KC session. So timeouts heal themselves
as long as KC's session is younger than P1's idle limit.

---

## 4. Cross-cutting invariants

| Invariant | Where enforced |
|---|---|
| Token Handler holds tokens — SPA never sees access/refresh/id_token | `GWSESSION` cookie HttpOnly+Secure (host-scoped); KC's `code` is delivered to the Token Handler callback, not the SPA route. `/auth/me` projects only sub-identity fields. Tokens live server-side in Redis. |
| Every login is brokered through P1 SAML — no native KC users | Realm has no users in the local store; `IdpHintFilter` pins `kc_idp_hint=p1` on every BFF-initiated authorize; the demo SPA's login URL is `/oauth/login/silent`, never `/oauth/login/keycloak` directly. |
| Token rolling refresh on the BFF side, no SPA-side keepalive | `TokenRefreshFilter` refreshes when `< 60 s` of access-token lifetime remain; failure invalidates the session and the SPA's next call goes through `startLogin()`. |
| Loop guards everywhere prevent navigation storms | `sessionStorage.bff:lastLoginRedirect` (10 s window) on the BFF SPA; `SilentSsoAction.SESSION_KEY_ESTABLISH_DONE` (per-HttpSession, server-side) on P1 prevents Gap 6 looping; `SilentSsoAction` 2 s rate-limit by IP. |
| `sid` is the back-channel correlator | `KeycloakAuthenticationMapper` writes `sid` from id_token claims into the session; `AuthController.me` registers `sid → session.id` in `SidSessionRegistry`; `BackchannelLogoutController` looks up by `sid`. |
| `personId` is stable across subdomains for the same physical person | Set on tim1/tim5/tim10 by `PersonRegistry.resolvePersonId(homeUser)`; emitted by KC as a top-level claim via the OIDC user-attribute mapper. |
| Cross-host silent SSO depends on a KC session existing | Gap 6 round-trip after credential login. Loop prevention is server-side only (`SESSION_KEY_ESTABLISH_DONE` on the P1 HttpSession); the silent_failed loop guard honours a user reload (`isUserReload`). Both 2026-06-04 fixes. |

---

## 5. Code map — fast lookup table

| Concern | File |
|---|---|
| SPA mount, `/auth/me`, startLogin, logout button | `domains/<d>/web/src/auth/AuthProvider.tsx`, `domains/<d>/web/src/api.ts` |
| Silent-first entry on the BFF | `bff-core/.../SilentLoginController.java` |
| `kc_idp_hint=p1` injection on the authorize Location | `bff-core/.../IdpHintFilter.java` |
| Server-side OIDC end-session + idempotent logout | `bff-core/.../AuthController.java` |
| Back-channel logout receiver (KC → BFF) | `bff-core/.../BackchannelLogoutController.java`, `LogoutTokenValidator.java`, `SidSessionRegistry.java` |
| Token freshness | `bff-core/.../TokenRefreshFilter.java` |
| Identity projection (`sub`, `firmCd`, `personId`, `memberships`, `roles`) | `bff-core/.../KeycloakAuthenticationMapper.java` |
| P1 React silent-SSO probe + Gap 6 establish + reload-as-retry | `WebContent/react/app/src/app/_services/appService.js` (`checkUserLoggedIn`, `loginPassword`, `logout`) |
| P1 KC-driven silent-SSO entry (Tomcat) | `AppServer/.../saml/idp/SilentSsoAction.java` |
| P1 OIDC code receiver + host-firm switch + person-link lookup | `AppServer/.../saml/idp/OidcCallbackAction.java`, `PersonRegistry.java` |
| P1 SAML SP entry for sidebar deep-link / SP-init | `AppServer/.../saml/idp/IdpSsoAction.java` |
| P1 SLO outbound + inbound short-circuit | `AppServer/.../saml/idp/IdpInitiateSloAction.java`, `IdpSloAction.java` |
| P1 back-channel logout receiver | `AppServer/.../saml/idp/BackChannelLogoutAction.java` |
| End-to-end coverage of the recovery scenario | `e2e/tests/p1-relogin-silent-recovery.spec.ts` |

---

## 6. Surface area that broke in production-like settings (2026-06-04)

| Symptom | Root cause | Fix |
|---|---|---|
| Tab on `c1wealth.localhost` (or `billing.geowealth.int`) lands on credential form even though the user just logged in canonically | P1 credential login never minted a KC session (Gap 6 missing); `?prompt=none` from the other host returns `login_required` | Post-login `establish=true` round-trip in `appService.js`. Loop prevention is on the **server** (`SilentSsoAction.SESSION_KEY_ESTABLISH_DONE`) — no client flag, no way for a stale value to suppress the round-trip on a re-login. |
| After logout + re-login (as the same person via a different username — `johnastim5` linked to `tim1` via PersonRegistry), the second-tab silent SSO still fails | First attempt at Gap 6 used a `sessionStorage.kc_sso_established` one-shot. A value left from a pre-fix session silently suppressed the round-trip permanently | Removed the client-side flag entirely; server-side flag is the only loop guard and resets on HttpSession invalidation |
| F5 on `#login?silent_failed=1` still shows credential form even when KC session is healthy again | Loop guard was unconditional on the hash flag | `appService.checkUserLoggedIn` honours `performance.getEntriesByType('navigation')[0].type === 'reload'` as a retry signal |
| Intermittent `401 Unauthorized` on BFF `/auth/logout` | `@Secured(IS_AUTHENTICATED)` — a back-channel logout race or expired session turned the Sign out click into a 401 | `@Secured(IS_ANONYMOUS)` + `@Nullable Authentication`; all internal cleanup is null-safe; always 303 to P1 SLO |

---

## 7. Questions & Answers — every SSO / SLO login & logout scenario

A scenario-by-scenario FAQ. Each answer gives the algorithm (the *why* and the
ordered steps) and the concrete code that implements it. Where the code shown is
trimmed, the file path points at the authoritative source. Unless stated
otherwise, "the BFF" / "Token Handler" means the single multi-tenant
`token-handler` service (`bff-core` with `mainClass = demo.bff.core.Bff`), and
"the data BFF" means the auth-unaware `bff-billing` / `bff-trading`.

### 7.1 Login

#### Q1 — Cold open of a domain SPA, no sessions anywhere. What happens end-to-end?

**Algorithm.** The SPA holds no tokens. On mount it asks the Token Handler "who
am I?" over the host-scoped `GWSESSION` cookie. With no session that returns
`401`, and the SPA navigates the browser to the **silent-first** login entry.
The Token Handler bounces through Keycloak with `prompt=none`; with no realm SSO
session KC answers `error=login_required`, which the Token Handler upgrades to an
**interactive** login: KC sees `kc_idp_hint=p1`, emits a SAML `AuthnRequest` to
P1, the user authenticates at P1, P1 posts a signed SAML `Response` to KC's
broker endpoint, KC runs first-broker-login (auto-link by email), issues an OIDC
code, the Token Handler exchanges it for tokens server-side, stashes them in a
Redis session, and the browser is handed only the `GWSESSION` cookie.

**Step 1 — SPA mount asks `/auth/me`** (`domains/billing/web/src/auth/AuthProvider.tsx`):

```tsx
const r = await fetchMe();                 // GET /auth/me, credentials:'include'
if (r.status === 'ok') { clearLoginGuard(); setMe(r.me); setReady(true); }
else { const redirecting = startLogin();   // 401/403 → start BFF login
       if (!redirecting) { setAuthError(true); setReady(true); } }
```

**Step 2 — a 401 on any call starts the login route** (`domains/billing/web/src/api.ts`):

```ts
const LOGIN_URL = '/oauth/login/silent';   // silent-first, NOT /oauth/login/keycloak
export function startLogin(): boolean {
  let last = 0;
  try { last = Number(sessionStorage.getItem(LOGIN_GUARD_KEY) ?? '0'); } catch {}
  if (Date.now() - last < LOGIN_RETRY_WINDOW_MS) return false;   // 10s storm guard
  try { sessionStorage.setItem(LOGIN_GUARD_KEY, String(Date.now())); } catch {}
  window.location.assign(LOGIN_URL);
  return true;
}
```

**Step 3 — the silent entry sets a cookie and bounces to KC with `silent=1`** (`SilentLoginController.java`); the rest (`prompt=none`, the `login_required`
upgrade) is Q2. On the interactive leg KC drives SAML to P1, and on success the
`KeycloakAuthenticationMapper` (Q5) builds the session.

**End state:** KC ✓, Token Handler session ✓ (tokens server-side, `sid` recorded),
SPA renders from the `/auth/me` projection.

#### Q2 — How does "silent-first" SSO work, and why a browser cookie rather than a session attribute?

**Algorithm.** Going straight to interactive login would flash the P1 IdP on
*every* subdomain. Instead the SPA hits `/oauth/login/silent`, which (1) drops a
short-lived browser cookie marking "this is a silent attempt", and (2) redirects
to `/oauth/login/keycloak?silent=1`. `IdpHintFilter` then appends `prompt=none`
to the KC authorize URL. If KC has a live SSO session it returns a code with no
IdP UI; if not, KC returns `error=login_required` to the callback, micronaut-
security follows `redirect.login-failure: /auth/login-failed`, and
`AuthController.loginFailed` reads the cookie and upgrades to a real interactive
login.

The marker **must be a cookie, not a session attribute**: at this point the user
is anonymous and has no Token Handler session, and micronaut-security rotates the
session on the actual `/oauth/login/keycloak` call — a session attribute would be
lost across that rotation; a cookie survives it.

```java
// SilentLoginController.java — set the marker, bounce with silent=1
@Get @Secured(SecurityRule.IS_ANONYMOUS)
public HttpResponse<?> startSilent() {
  MutableHttpResponse<Object> response = HttpResponse.status(HttpStatus.SEE_OTHER);
  response.getHeaders().add(HttpHeaders.LOCATION, "/oauth/login/keycloak?silent=1");
  response.cookie(Cookie.of(SILENT_ATTEMPT_COOKIE, "1")   // "kc_silent_attempt"
      .maxAge(120).path("/").httpOnly(true).secure(true).sameSite(SameSite.Lax));
  return response;
}
```

```java
// AuthController.java — login_required came back; upgrade silent → interactive
@Get("/login-failed") @Secured(SecurityRule.IS_ANONYMOUS)
public HttpResponse<?> loginFailed(HttpRequest<?> request) {
  boolean wasSilent = request.getCookies().findCookie(SilentLoginController.SILENT_ATTEMPT_COOKIE)
      .map(c -> "1".equals(c.getValue())).orElse(false);
  if (wasSilent) {
    MutableHttpResponse<Object> resp = HttpResponse.status(HttpStatus.SEE_OTHER);
    resp.getHeaders().add(HttpHeaders.LOCATION, "/oauth/login/keycloak");  // no prompt=none now
    resp.cookie(Cookie.of(SilentLoginController.SILENT_ATTEMPT_COOKIE, "")
        .maxAge(0).path("/").httpOnly(true).secure(true).sameSite(SameSite.Lax));  // clear → no loop
    return resp;
  }
  return HttpResponse.<Void>status(HttpStatus.SEE_OTHER).header(HttpHeaders.LOCATION, "/?login_error=true");
}
```

#### Q3 — How is `kc_idp_hint=p1` (and `prompt=none`) forced onto the Keycloak authorize URL?

**Algorithm.** The realm has **no native users** — every login must broker
through the `p1` SAML IdP. micronaut-security builds the authorize redirect
without the hint, so a `Location`-rewriting filter on `/oauth/login/keycloak`
appends `&kc_idp_hint=p1` (idempotently) and, when the request carried `silent=1`,
also `&prompt=none`.

```java
// IdpHintFilter.java — rewrite the 302 Location micronaut-security produced
boolean silent = request.getParameters().get("silent", String.class)
    .map(v -> v.equals("1") || v.equalsIgnoreCase("true")).orElse(false);
return Flux.from(chain.proceed(request)).map(response -> {
  int code = response.getStatus().getCode();
  if (code < 300 || code >= 400) return response;
  String location = response.getHeaders().get(HttpHeaders.LOCATION);
  if (location == null) return response;
  if (idpHint != null && !idpHint.isBlank() && !location.contains("kc_idp_hint="))
    location = appendParam(location, "kc_idp_hint", idpHint);
  if (silent && !location.contains("prompt="))
    location = appendParam(location, "prompt", "none");
  response.getHeaders().set(HttpHeaders.LOCATION, location);
  return response;
});
```

#### Q4 — Cross-subdomain SSO: KC already has a session (user signed in on a sibling domain). What happens?

**Algorithm.** Same silent-first entry as Q2, but the `prompt=none` probe
*succeeds*: KC sees its `KEYCLOAK_IDENTITY` cookie (set on
`auth.geowealth.int`, `SameSite=Lax`, sent on the top-level navigation), issues
an OIDC code immediately with **no P1 round-trip and no UI**, the Token Handler
exchanges it, writes this host's `GWSESSION`, and the second domain renders
silently. This is the single-sign-on payoff: the first domain does the full
interactive login; every later domain within the SSO lifetime completes
silently. `demo-shared-client` gets a per-host KC client-session under one shared
user-level KC session.

#### Q5 — How does the Token Handler turn OIDC tokens into a session, and what does the SPA actually receive?

**Algorithm.** After the code exchange, `KeycloakAuthenticationMapper` (which
`@Replaces` the default) builds the `Authentication` micronaut-security stores in
the **server-side Redis session**. The access / refresh / id tokens are stashed
in the authentication attributes (never sent to the browser) so the filter can
refresh and logout can end-session. The OIDC `sid` is kept to correlate KC's
back-channel logout. The multi-valued `memberships` claim is read straight from
the JWT (the framework's `OpenIdClaims.get` drops array claims) and packed into a
single delimited String because session-backed `Authentication` round-trips
scalars but drops `List` attributes.

```java
// KeycloakAuthenticationMapper.java
List<String> memberships = membershipsFromJwt(tokenResponse.getIdToken());
if (memberships.isEmpty()) memberships = membershipsFromJwt(tokenResponse.getAccessToken());
Map<String, Object> attrs = new HashMap<>();
attrs.put("sub", claims.getSubject());
attrs.put("email", str(claims.get("email")));
attrs.put("firmCd", str(claims.get("firmCd")));
attrs.put("sid", str(claims.get("sid")));
attrs.put("personId", str(claims.get("personId")));
attrs.put("memberships", String.join(AuthClaims.MEMBERSHIPS_DELIM, memberships));  // "\n"
attrs.put("accessToken", tokenResponse.getAccessToken());
attrs.put("refreshToken", tokenResponse.getRefreshToken());
attrs.put("idToken", tokenResponse.getIdToken());
return Publishers.just(AuthenticationResponse.success(username, roles, attrs));
```

The SPA only ever sees the `/auth/me` **projection** — identity facts, no tokens.
For a firm-bound subdomain it surfaces that firm's identity (resolved from
`memberships`), not the login account:

```java
// AuthController.me() — per-host identity projection
SubdomainRequirements.ResolvedRequirement req = requirements.effectiveFor(host, requirement);
if (req.isFirmType() && req.firmCd() != null) {
  out.put("firmCd", req.firmCd().toString());
  out.put("tenantIdentity", usernameForFirm(memberships, req.firmCd()));
  out.put("activeTenant", "firm-" + req.firmCd());
} else { /* resource/ungated → login account's own identity */ }
out.put("roles", authentication.getRoles());
```

`/auth/me` also (re)records the `sid → session.id` mapping for back-channel logout:

```java
Object sid = authentication.getAttributes().get("sid");
if (session != null && sid != null) registry.register(sid.toString(), session.getId());
```

#### Q6 — How does ONE Token Handler serve multiple domains with one OIDC client and one cookie name?

**Algorithm.** Multi-tenancy rides on host-scoping. One cookie *name* `GWSESSION`
is reused on every domain, but cookies are host-scoped so `billing.geowealth.int`
and `trading.geowealth.int` get distinct cookies. One OIDC client
`demo-shared-client` is used everywhere; the `redirect_uri` is derived per-host
from the proxied request (nginx forwards `X-Forwarded-Host` / `X-Forwarded-Port`).
Per-domain authorization is resolved by the request `Host` against the
`app.tenants.*` map. **Adding a domain = one config entry + the host on the shared
client — no new Deployment, no image rebuild.**

```yaml
# token-handler/application.yml
micronaut.session.http.cookie-name: ${SESSION_COOKIE_NAME:GWSESSION}   # host-scoped
micronaut.security.oauth2.clients.keycloak.client-id: ${OAUTH_CLIENT_ID:demo-shared-client}
app:
  tenants:
    billing: { host: billing.geowealth.int, type: resource, object-type: 59, permission: 5 }
    trading: { host: trading.geowealth.int, type: resource, object-type: 5,  permission: 5 }
```

#### Q7 — How does nginx gate `/api/<domain>` so the data BFF can be auth-unaware?

**Algorithm.** The industry-standard nginx `auth_request` / forward-auth pattern.
Before proxying `/api/billing/*` to `bff-billing`, nginx fires an internal
subrequest to the Token Handler's `GET /auth/verify`, forwarding the session
cookie and the original host. The Token Handler validates+refreshes the session,
runs coarse + the per-host Tier-2 gate, and on `200` returns the identity as
`X-Auth-*` headers. nginx copies those onto the upstream request, **drops the
session cookie**, and proxies. So the data BFF reads identity from trusted
headers and runs no auth at all.

```nginx
# domains/billing/web/nginx.conf
location /api/billing/ {
  set $upstream "bff-billing:8080";
  auth_request               /th-verify;
  auth_request_set $auth_user  $upstream_http_x_auth_username;
  auth_request_set $auth_firm  $upstream_http_x_auth_firm_cd;
  auth_request_set $auth_token $upstream_http_x_auth_access_token;
  rewrite ^/api/billing/(.*)$ /api/$1 break;
  proxy_pass         http://$upstream;
  proxy_set_header   Cookie            "";            # no session cookie to the data BFF
  proxy_set_header   X-Auth-Username     $auth_user;
  proxy_set_header   X-Auth-Firm-Cd      $auth_firm;
  proxy_set_header   X-Auth-Access-Token $auth_token;  # still needed for Tier-3 refine
}
location = /th-verify {
  internal;
  proxy_pass               http://token-handler:8080/auth/verify;
  proxy_pass_request_body  off;
  proxy_set_header         X-Forwarded-Host $http_host;  # pick the right tenant
  proxy_set_header         Cookie           $http_cookie;
}
```

```java
// ForwardAuthController.java — 401 (no session, via @Secured) / 403 (authz) / 200 + identity
@Get("/verify") @Secured(SecurityRule.IS_AUTHENTICATED)
public HttpResponse<?> verify(Authentication auth, HttpRequest<?> request) {
  String host = request.getHeaders().get("X-Forwarded-Host");
  if (host == null || host.isBlank()) host = request.getHeaders().get(HttpHeaders.HOST);
  try { authorizer.authorize(auth, host); }
  catch (HttpStatusException e) { return HttpResponse.status(e.getStatus()); }   // 403
  MutableHttpResponse<?> r = HttpResponse.ok();
  header(r, "X-Auth-Username", auth.getName());
  header(r, "X-Auth-Firm-Cd", AuthClaims.firmCd(auth));
  header(r, "X-Auth-Memberships", String.join(",", AuthClaims.memberships(auth)));
  header(r, "X-Auth-Roles", String.join(",", auth.getRoles()));
  header(r, "X-Auth-Access-Token", str(auth.getAttributes().get("accessToken")));
  return r;
}
```

#### Q8 — How is the per-domain authorization decision made (firm membership vs Tier-2 permission)?

**Algorithm.** `SubdomainAuthorizer` resolves the gate by host (multi-tenant) or
the single configured requirement (per-domain). A `firm`-type subdomain requires
a `memberships` entry for that `firmCd`; a `resource`-type subdomain delegates to
the Tier-2 P1 permission gate. An unconfigured host in multi-tenant mode **fails
closed** (403).

```java
// SubdomainAuthorizer.java
public void authorize(Authentication auth, @Nullable String host) {
  Gate g = resolve(host);                               // host map, or single fallback; unknown host → 403
  if (g.type == null || g.type.isBlank()) return;        // no gate configured
  if (isFirm(g.type)) {
    if (!hasFirmMembership(auth, g.firmCd))
      throw new HttpStatusException(HttpStatus.FORBIDDEN, "...no account in firm " + g.firmCd);
  } else if (isResource(g.type) && g.objectType != null && g.permission != null) {
    gate.require(auth, g.objectType, g.permission);      // Tier-2 P1 permission → 403 if missing
  }
}
private static boolean hasFirmMembership(Authentication auth, Integer firmCd) {
  String prefix = firmCd + ":";
  for (String e : AuthClaims.memberships(auth)) if (e != null && e.startsWith(prefix)) return true;
  return false;
}
```

#### Q9 — First-time federated user. How is the KC account created without a profile form?

**Algorithm.** On the first SAML brokered login KC runs the
`p1-first-broker-login` flow configured in `realm-export.json`: auto-link by
email if a matching KC user exists, otherwise create one and link — silently
(`updateProfileFirstLoginMode: off`, no required actions). The federated identity
is written to KC's Postgres and survives every routine restart, so the next login
for that P1 user skips first-broker-login. No code in this repo runs at this step;
it is entirely realm configuration.

#### Q10 — P1 sidebar deep-link straight into a domain SPA. How does that differ from the cold path?

**Algorithm.** The sidebar tile (`useIntegrationLinks.js`, outside this repo)
builds the KC authorize URL with `kc_idp_hint=p1` and the domain `redirect_uri`
already on it. KC skips its login UI, SAML-`AuthnRequest`s to P1 (which has a
session), `IdpSsoAction` posts a signed SAML `Response` back, KC creates the
client-session and 302s to the domain carrying `code` directly. micronaut-
security's callback (`/oauth/callback/keycloak`) consumes the code, sets
`GWSESSION`, and the SPA proceeds exactly as in Q1 from `/auth/me` onward. The
only difference vs Q1 is that the IdP-hint and redirect_uri were supplied by P1,
not synthesized by `IdpHintFilter`.

#### Q11 — Is the session id rotated on login? (session-fixation defence)

**Algorithm.** Yes. The pre-auth session only held the OAuth `state`/`nonce`/PKCE
transients. `RotatingSessionLoginHandler` (`@Replaces` the stock
`SessionLoginHandler`) issues a brand-new session on successful authentication,
binds it to the request so the session filter emits a fresh `Set-Cookie`, and
deletes the old one — so a pre-planted cookie can't be ridden post-login.

```java
// RotatingSessionLoginHandler.java
@Override public MutableHttpResponse<?> loginSuccess(Authentication a, HttpRequest<?> request) {
  rotateSession(request);                  // new session id, delete old; state/nonce already consumed
  return super.loginSuccess(a, request);   // parent stores Authentication in the fresh session
}
```

### 7.2 Logout / SLO

#### Q12 — Sign out from a domain SPA. What is the full algorithm?

**Algorithm.** Two halves run on every `POST /auth/logout`:
1. **Server-side OIDC end-session to KC** with the stored `refresh_token`. This
   is the *only* KC path that reliably fans `backchannel.logout.url` POSTs out to
   every OIDC client in the SSO session — i.e. the sibling domains. It runs on a
   **background thread** (Q14) and the browser is not involved, so no "Do you
   want to log out?" interstitial.
2. **Browser redirect to P1's IdP-initiated SLO**. P1 invalidates its
   `HttpSession` (same-origin, `JSESSIONID` reaches it), SAML-`LogoutRequest`s to
   KC, and lands the user on the P1 login form.

Local cleanup (clear+delete the session, invalidate by `sid`) happens inline and
the `303` returns immediately.

```java
// AuthController.logout() — POST-only, idempotent, @Secured(IS_ANONYMOUS)
@Post("/logout")
@Consumes({MediaType.APPLICATION_FORM_URLENCODED, MediaType.ALL})
@Secured(SecurityRule.IS_ANONYMOUS)
public HttpResponse<?> logout(@Nullable Authentication authentication, @Nullable Session session) {
  Object sid = authentication != null ? authentication.getAttributes().get("sid") : null;
  Object refreshToken = authentication != null ? authentication.getAttributes().get("refreshToken") : null;
  final Object rt = refreshToken;
  blockingExecutor.submit(() -> endSessionAtKeycloak(rt));   // half 1, off the request thread
  if (session != null) {
    session.clear();                                          // drop Authentication FIRST (Redis re-save race)
    try { sessionStore.deleteSession(session.getId()); } catch (Exception ignored) {}
  }
  if (sid != null) registry.invalidateBySid(sid.toString());
  return HttpResponse.<Void>status(HttpStatus.SEE_OTHER)
      .header(HttpHeaders.LOCATION, p1InitiateSloUrl);        // half 2, browser → P1 SLO
}
```

#### Q13 — Why is logout `POST`-only and submitted as a real form, not `fetch`?

**Algorithm.** Logout is state-changing (it ends the KC SSO session and every
sibling session), so it must not be triggerable by a third-party top-level `GET`.
With `GWSESSION` set `SameSite=Lax`, the browser attaches it to a same-site
top-level navigation but **not** to a cross-site POST — so a forged cross-site
logout arrives cookieless and tears nothing down. The SPA submits a real form
POST (not `fetch`) so the browser *follows* the `303` to P1's SLO as a top-level
same-origin navigation, carrying P1's `JSESSIONID`.

```tsx
// AuthProvider.tsx
function postLogout(): void {
  const form = document.createElement('form');
  form.method = 'POST';
  form.action = '/auth/logout';
  document.body.appendChild(form);
  form.submit();
}
```

#### Q14 — Why is the KC end-session fired on a background thread instead of inline?

**Algorithm.** KC's RP-initiated logout fans a back-channel POST to *every* client
in the session — one leg of which targets this same Token Handler
(`demo-shared-client`'s own `backchannel.logout.url`). Done inline on a single
replica it deadlocked: the request thread blocked on KC while KC blocked on the
self-directed back-channel POST, which needed a free thread — starving `/health`
until the liveness probe killed the pod (502). Detaching to the blocking executor
frees the request thread immediately; the 303 returns at once while the
end-session + sibling fan-out finish in the background (well within the seconds a
client polls `/auth/me`). The call authenticates by `refresh_token` (not
`id_token_hint`), so KC returns 204 and renders nothing.

```java
private void endSessionAtKeycloak(Object refreshToken) {
  if (!(refreshToken instanceof String) || ((String) refreshToken).isEmpty()) return;
  StringBuilder body = new StringBuilder()
      .append("client_id=").append(URLEncoder.encode(clientId, StandardCharsets.UTF_8))
      .append("&refresh_token=").append(URLEncoder.encode((String) refreshToken, StandardCharsets.UTF_8));
  if (clientSecret != null && !clientSecret.isEmpty())
    body.append("&client_secret=").append(URLEncoder.encode(clientSecret, StandardCharsets.UTF_8));
  HttpRequest<?> req = HttpRequest.POST(logoutEndpoint, body.toString())
      .contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
  kc.toBlocking().exchange(req);   // best-effort; failures logged, never surfaced
}
```

#### Q15 — Why do we need BOTH the KC end-session AND the P1 SLO redirect?

**Algorithm.** They cover disjoint halves. When KC receives a SAML
`LogoutRequest` from a brokered IdP it terminates the SSO session but does **not**
fan back-channel POSTs to OIDC clients (Keycloak issues
[#17318](https://github.com/keycloak/keycloak/issues/17318) /
[#21770](https://github.com/keycloak/keycloak/issues/21770)) — it logs "Some
clients have not been logged out" and proceeds. So the P1 SLO half tears down P1
+ KC, but the *sibling domains* would survive. The RP-initiated end-session half
(Q14) runs KC's `LogoutEndpoint`, which **does** POST `logout_token` to every
client's `backchannel.logout.url`. End state with both: P1 + KC + every domain
session gone.

#### Q16 / Q17 — How does the Token Handler receive a back-channel logout and kill the right session?

**Algorithm.** KC POSTs an OIDC Back-Channel Logout 1.0 `logout_token` (server-to-
server, no browser). The receiver validates it and destroys every Token Handler
session mapped to that `sid`. The next `/auth/me` from a still-open sibling tab
then 401s and that SPA signs out.

Validation (`LogoutTokenValidator.java`) checks, per spec: RS256 signature
against KC's JWKS (fetched from the **in-cluster** KC, bounded to 3s, warmed at
startup), `iss == issuer`, `aud` contains our client id, the `events` claim
carries the backchannel-logout event, **no** `nonce`, and a `jti` replay guard:

```java
JWTClaimsSet c = processor.process(logoutToken, null);
if (!issuer.equals(c.getIssuer())) return null;
if (c.getAudience() == null || !c.getAudience().contains(clientId)) return null;
Object events = c.getClaim("events");
if (!(events instanceof Map) || !((Map<?,?>) events).containsKey(BACKCHANNEL_LOGOUT_EVENT)) return null;
if (c.getClaim("nonce") != null) return null;                              // §2.4/2.6: must not be present
String jti = c.getJWTID();
if (jti != null && seenJtis.putIfAbsent(jti, Boolean.TRUE) != null) return null;  // replay defence
return c.getStringClaim("sid");
```

Receiver (`BackchannelLogoutController.java`):

```java
@Post @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
@Secured(SecurityRule.IS_ANONYMOUS) @ExecuteOn(TaskExecutors.BLOCKING)
public HttpResponse<?> logout(@Nullable @Body("logout_token") String logoutToken) {
  if (logoutToken == null || logoutToken.isBlank()) return HttpResponse.badRequest();
  String sid = validator.validateAndGetSid(logoutToken);
  if (sid == null) return HttpResponse.badRequest();
  int killed = registry.invalidateBySid(sid);
  return HttpResponse.ok().header("Cache-Control", "no-store");
}
```

The `sid → session-ids` map lives in **Redis** (not JVM memory) so a POST landing
on any replica can tear down a session created on another replica
(`SidSessionRegistry.java`):

```java
public int invalidateBySid(String sid) {
  Set<String> ids = redis.smembers(SID_KEY_PREFIX + sid);   // "bff:sid:<sid>"
  int n = 0;
  for (String id : ids) {
    try { sessionStore.deleteSession(id); n++; } catch (Exception ignored) {}
    try { redis.del(SESS_KEY_PREFIX + id); } catch (Exception ignored) {}
  }
  redis.del(SID_KEY_PREFIX + sid);
  return n;
}
```

#### Q18 — Logout initiated from P1 (not a SPA). How do the domains learn?

**Algorithm.** P1's `IdpInitiateSloAction` invalidates the `HttpSession` and SAML-
`LogoutRequest`s to KC's broker endpoint; KC kills the SSO session and SAML-
`LogoutResponse`s back to P1, which lands on `/#login`. But (Q15) that SAML path
does **not** fan back-channel to OIDC clients — so the domains learn via KC also
POSTing `logout_token` to P1's own `backchannel.logout.url`
(`/saml/idp/back-channel-logout.do`) and to each domain's receiver (Q17). The
domain teardown is identical regardless of who initiated logout.

#### Q19 — KC admin force-logout, KC idle timeout, KC max-lifespan. Effect?

**Algorithm.** All three end the user's KC SSO session server-side and fan
`logout_token` to every client with a back-channel URL — i.e. the Q17 path for
every domain plus the P1 equivalent. The user is simply not the initiator. Realm
knobs: `ssoSessionIdleTimeout=1800s`, `ssoSessionMaxLifespan=36000s`.

#### Q20 — P1 `HttpSession` idle timeout (server-only). Does anything break?

**Algorithm.** Tomcat reaps P1's session after 7h idle; P1 has no notification
path to KC. Next time the user touches P1, the flow degrades to "cold P1 visit
with KC session alive" and silently restores P1 from the still-valid KC session
(the Phase-15 silent-SSO path). So as long as KC's session is younger than P1's
idle limit, the timeout self-heals with no user interaction.

#### Q21 — Why is `/auth/logout` `@Secured(IS_ANONYMOUS)` with a `@Nullable Authentication`?

**Algorithm.** Idempotency. A double-click, an already-expired session, or a
back-channel race (a `logout_token` from a sibling tab killed our session a tick
before the user clicked Sign out) used to surface as a `401` HATEOAS error. Now
every cleanup is null-safe and the controller always emits the `303` to P1 SLO —
worst case is best-effort cleanup with nothing left to clean. The session is
`clear()`-ed before the delete because, with the Redis store, a plain mid-request
`deleteSession` of the current session does not reliably stick (the session
filter re-saves it); clearing guarantees the next `/auth/me` finds an empty
session → 401.

### 7.3 Session lifecycle & token refresh

#### Q22 — There is no SPA-side keepalive. How does the access token stay fresh?

**Algorithm.** `TokenRefreshFilter` runs right after the security filter on
`/api/**` + `/auth/**`. When the stored access token is within 60s of expiry it
uses the refresh token to mint a fresh set at KC, rebuilds the `Authentication`
(same shape as the mapper) and writes it back to both the session and the current
request attribute — so even the controller handling *this* request sees the fresh
token. It **never** refreshes on `/auth/logout` (a refresh would re-persist the
session the logout handler is trying to delete).

```java
// TokenRefreshFilter.doFilter (trimmed)
if (request.getPath().endsWith("/auth/logout")) return chain.proceed(request);
Authentication auth = request.getAttribute(SecurityFilter.AUTHENTICATION, Authentication.class).orElse(null);
if (auth == null) return chain.proceed(request);
// ... if shouldValidate(accessToken, session) ...
return Mono.from(refresh((String) refreshToken)).flatMap(tokens -> {
  Authentication refreshed = rebuild(auth, tokens);
  request.setAttribute(SecurityFilter.AUTHENTICATION, refreshed);
  session(request).ifPresent(s -> { s.put(SecurityFilter.AUTHENTICATION, refreshed);
                                    s.put(LAST_VALIDATED_AT, System.currentTimeMillis()); });
  return Mono.from(chain.proceed(request));
});
```

#### Q23 — How is an out-of-band KC session end caught when the back-channel POST is missed?

**Algorithm.** Defence in depth on top of back-channel logout. Even when the
access token is not near expiry, the filter re-validates the KC session at least
every 30s by doing a refresh — a dead refresh token means the SSO session is gone
(P1 SLO, admin revoke), so the BFF stops serving a zombie session.

```java
private boolean shouldValidate(String accessToken, Session session) {
  if (nearExpiry(accessToken)) return true;          // < 60s of access-token life
  if (session == null) return true;
  Long lastValidated = session.get(LAST_VALIDATED_AT, Long.class).orElse(0L);
  return System.currentTimeMillis() - lastValidated > VALIDATE_INTERVAL_SECONDS * 1000;  // 30s
}
```

#### Q24 — Concurrent `/api/*` calls and refresh-token rotation: how is a "reuse" storm avoided, and a KC blip from mass-logout?

**Algorithm.** Two guards.
1. **Concurrent dedup (M3):** an in-flight refresh for a session suppresses a
   second refresh-token grant for ~15s; parallel calls proceed on the still-valid
   token until the in-flight refresh updates the session. Self-expiring so a
   crashed refresh can't wedge a session.
2. **Dead vs transient (M2):** a `4xx` from the token endpoint (`invalid_grant`
   / revoked) means the session is genuinely dead → clear it and `401`; a
   transport error / `5xx` means KC is momentarily unreachable → keep the session
   and serve this request on the current token. Otherwise a KC blip would
   mass-log-out every active session.

```java
// M3 — in-flight guard
Long startedAt = refreshInFlight.get(sessionId);
if (startedAt != null && now - startedAt < INFLIGHT_GUARD_MILLIS) return chain.proceed(request);
refreshInFlight.put(sessionId, now);
// ... .onErrorResume(err -> {  // M2
if (isDeadSession(err)) { session(request).ifPresent(Session::clear); return Mono.just(HttpResponse.unauthorized()); }
return Mono.from(chain.proceed(request)); });   // transient → keep session
// isDeadSession: HttpClientResponseException with 400 <= code < 500
```

#### Q25 — How does the SPA distinguish "signed out" (401) from "forbidden" (403)?

**Algorithm.** Critical to avoid a re-login loop. `401` means no session → start
login once (loop-guarded). `403` means authenticated-but-not-authorized →
**never** re-login (that would loop login → callback → 403 → login); instead throw
a typed error the UI renders as access-denied.

```ts
// api.ts get()
if (res.status === 403) throw new ForbiddenError(path);     // show access-denied, do NOT re-login
if (res.status === 401) {
  if (startLogin()) throw new Error(`${path} → 401 (signed out, redirecting to login)`);
  throw new Error(`${path} → 401 (login loop suppressed; please reload)`);
}
```

### 7.4 Edge cases & failure modes

#### Q26 — Multi-tab: tab A signs out while tab B is open on another domain. What does tab B see?

**Algorithm.** The sign-out's KC end-session (Q15) fans a `logout_token` to tab
B's domain, whose receiver (Q17) deletes the Redis session immediately. Tab B
finds out **reactively**: its next `/auth/me` or `/api/*` call returns 401 (the
`auth_request` to `/auth/verify` fails), and `api.ts` starts login. There is no
client polling/keepalive — the AuthProvider asks once on mount and relies on the
reactive 401. Because sessions are server-side in Redis, there is no
dangling-JWT-in-the-browser window (the SPA never held a token).

#### Q27 — What stops silent-SSO / reactive-401 navigation storms?

**Algorithm.** Layered loop guards:
- SPA: `sessionStorage['bff:lastLoginRedirect']` with a 10s window
  (`startLogin`), cleared on a successful `/auth/me` so a later genuine logout can
  start login afresh.
- Token Handler: the silent-attempt cookie is *cleared* on the interactive
  upgrade (Q2) so a real failure on the follow-up doesn't loop through
  `loginFailed`.
- P1 side (for the P1 flows): server-side `SESSION_KEY_ESTABLISH_DONE` per
  HttpSession, and the `silent_failed=1` guard that honours a genuine user reload
  as a retry signal.

#### Q28 — How is a replayed `logout_token` rejected?

**Algorithm.** A bounded LRU of seen `jti`s (4096) in `LogoutTokenValidator`; a
`jti` already acted on returns `null` (rejected). KC always sets `jti`; if absent
the teardown is idempotent anyway. Combined with the `nonce`-absent and
`events`-present checks, an ID token cannot be replayed as a logout token. See the
`seenJtis` snippet in Q17.

### 7.5 Quick scenario → code map

| Scenario | Entry | Key code |
|---|---|---|
| Cold SPA login, no sessions | `/auth/me` 401 → `/oauth/login/silent` | `AuthProvider.tsx`, `api.ts`, `SilentLoginController`, `IdpHintFilter` |
| Silent cross-subdomain SSO | `/oauth/login/silent` `prompt=none` ok | `IdpHintFilter`, KC SSO cookie |
| `login_required` upgrade | KC → `/auth/login-failed` | `AuthController.loginFailed` |
| Token → session | code exchange | `KeycloakAuthenticationMapper` |
| `/api/*` gate | nginx `auth_request` → `/auth/verify` | `nginx.conf`, `ForwardAuthController`, `SubdomainAuthorizer` |
| Sign out from SPA | `POST /auth/logout` | `AuthController.logout`, `endSessionAtKeycloak` |
| Back-channel logout in | `POST /backchannel-logout` | `BackchannelLogoutController`, `LogoutTokenValidator`, `SidSessionRegistry` |
| Logout from P1 | `/saml/idp/initiate-slo.do` | `IdpInitiateSloAction` (geowealth) + KC fan-out |
| Token refresh / revalidate | `/api/**` + `/auth/**` filter | `TokenRefreshFilter` |
| 401 vs 403 in SPA | reactive on every call | `api.ts` (`ForbiddenError`, `startLogin`) |
| Session-fixation defence | login success | `RotatingSessionLoginHandler` |
