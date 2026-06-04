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

---

## 0. Actors and where state lives

| Actor | Origin | State held | Cookie / storage |
|---|---|---|---|
| **P1 Tomcat** (Struts + Akka agents) | `localhost:8888` (webpack-dev-server → Tomcat :8080) | `LoggedUser`, `LoggedAdviser`, `firmInfo`, `ConversationManager`, `UrlWhitelabelInformation` in `HttpSession` | `JSESSIONID` (HttpOnly, session-scoped) |
| **P1 React SPA** | `localhost:8888` (whitelabel hosts: `c1wealth.localhost`, `john.localhost`, …) | login-guard flags | URL hash `#login?silent_failed=1` (no sessionStorage — Gap 6 loop prevention is server-side) |
| **Keycloak** | `auth.geowealth.int:5180` (nginx → keycloak:8080) | SSO user session, federated identity link, per-client `client-session` for `p1-self-client`, `demo-billing-client`, `demo-trading-client` | `KEYCLOAK_IDENTITY`, `KEYCLOAK_SESSION` (persistent, `Max-Age = ssoSessionMaxLifespan`) |
| **Billing SPA** | `billing.geowealth.int:5184` | `me` payload (BFF's `/auth/me` projection) in React state, login-guard flag in sessionStorage | `BSESSION` cookie (HttpOnly, Secure, SameSite=Lax) — set by Micronaut on the BFF |
| **Trading SPA** | `trading.geowealth.int:5185` | Same shape as billing | `BSESSION` (per BFF) |
| **`bff-billing`, `bff-trading`** | container-internal :8080 (behind Vite preview on each SPA host) | Server session keyed by `BSESSION` holding `access_token`, `refresh_token`, `id_token`, `sid`, last-refresh timestamp; `SidSessionRegistry` mapping `sid → session-ids` | Sessions live in Micronaut's in-memory `SessionStore`; sid lookup is per-process |

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
  └── 3 OIDC clients (demo-billing-client, demo-trading-client, p1-self-client)
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
   KC authorize?client_id=demo-billing-client&kc_idp_hint=p1&prompt=none&...
     │
     ├── KC has SSO session ────►  silent OIDC code → 302 to BFF callback ─►
     │                              code exchange → KeycloakAuthenticationMapper
     │                              builds Authentication → Micronaut writes
     │                              BSESSION cookie → 302 back to SPA `/`.
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
                                      KeycloakAuthenticationMapper → BSESSION
                                      cookie set → 302 to SPA `/`.
```

End state: KC ✓, the SPA's BFF ✓ (sessionful, holding access/refresh/id
tokens server-side; `sid` recorded in `SidSessionRegistry`). The SPA's
`/auth/me` returns 200 with the Token-Handler-projected identity
(`personId`, `tenantIdentity`, `firmCd`, `memberships`, `roles`).

Why two clients for one user identity? `demo-billing-client` and
`demo-trading-client` each get their own KC client-session; the user-level
KC session is shared. That's why opening trading right after billing
completes silently with no IdP UI.

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
  ?client_id=demo-billing-client
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
- KC creates session + `demo-billing-client` client-session → 302 to
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
| `GET /auth/logout` on a demo BFF host | SPA "Sign out" button (`window.location.assign(LOGOUT_URL)`) | bff-core `AuthController.logout` |
| `GET /saml/idp/initiate-slo.do` on localhost:8888 | P1 React UI Logout link (`appServices.logout()`) | `IdpInitiateSloAction` |
| `POST /backchannel-logout` on a BFF | KC back-channel from a sibling logout | `BackchannelLogoutController` |
| `POST /saml/idp/back-channel-logout.do` on P1 | KC back-channel logout token | `BackChannelLogoutAction` |
| KC admin `users/<id>/logout` or `clients/<id>/logout-all` | Admin API | KC kicks everyone, fans out as above |

### ⑥ Logout from a demo SPA (Sign out button)

```
SPA: AuthProvider.logout = () => window.location.assign('/auth/logout')
                       │
                       ▼
GET /auth/logout  (browser top-level navigation, BSESSION cookie attached)
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
     in-memory `sid → session-id-set` map, deletes each session from
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
| BFF holds tokens — SPA never sees access/refresh/id_token | `BSESSION` cookie HttpOnly+Secure; KC's `code` is delivered to the BFF callback, not the SPA route. `/auth/me` projects only sub-identity fields. |
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
