# P1 ↔ keycloak-demo: Архитектура и Workflows

Детайлен архитектурен репорт за пълния auth lifecycle между P1 (legacy Tomcat session) и keycloak-demo (OIDC + MFE) — login, logout, expired session, всички 4 поддържани метода за вход.

**Дата:** 2026-05-21
**Свързани документи:**
- [`p1-sso-integration-research.md`](./p1-sso-integration-research.md) — solution selection (Solution 2: SAML Identity Brokering)
- [`LOGIN.md`](./LOGIN.md) — current keycloak-demo login flow (preserved as baseline)
- [`CLAUDE.md`](./CLAUDE.md) — keycloak-demo architecture survival notes

---

## 1. Резюме на архитектурата

### 1.1 Поддържани методи за вход

| # | Метод | Identifier | Credential source | Federation? | MFA |
|---|---|---|---|---|---|
| **A** | Local credentials | Username | user-service (P1 user store via Keycloak User Storage SPI) | Не | Email OTP |
| **B** | Local credentials | Email | user-service (`loginWithEmailAllowed=true`) | Не | Email OTP |
| **C** | OAuth federated — P1 | (SAML NameID) | P1 Tomcat session | Да — SAML Identity Brokering | Inherited from P1 |
| **D** | OAuth federated — Social | (provider sub) | Google / Microsoft / GitHub | Да — OIDC Identity Brokering | Inherited from provider |

**Default = A.** Email/Password (B) е fallback на същия flow. C се избира когато user стигне до Keycloak с `kc_idp_hint=p1` (например от sidebar в P1). D се избира с бутон "Login with Google" на login screen-а.

### 1.2 Компонентен diagram

```
┌──────────────────────────────────────────────────────────────────────┐
│                          Browser                                       │
│                                                                        │
│  ┌────────────┐    ┌─────────────────────────────────────────┐        │
│  │  P1 Page   │    │  React Shell (localhost:5173)            │        │
│  │  Tomcat    │    │   ─ keycloak-js (singleton)              │        │
│  │  Session   │    │   ─ AuthProvider + ShellHost             │        │
│  └─────┬──────┘    │   ─ TanStack QueryClient (shared)        │        │
│        │           │   ─ MFE federation runtime                │        │
│        │           └─────────┬─────────────────────────┬──────┘        │
└────────┼─────────────────────┼─────────────────────────┼───────────────┘
         │                     │                         │
         │ "Demo MFE-BFF"       │ Auth Code + PKCE        │ /api/<mfe>/*
         │ button → SAML        │                         │
         ▼                     ▼                         ▼
┌────────────────┐    ┌─────────────────┐    ┌──────────────────────────┐
│  P1 (Tomcat)   │    │  Keycloak       │    │  BFFs (3x Micronaut)     │
│   :8080        │◄──►│   :8898         │    │   bff-client :8081       │
│                │    │   demo-realm    │    │   bff-ops    :8082       │
│  SAML IdP      │    │                 │    │   bff-admin  :8083       │
│  endpoints:    │    │  IdPs:          │    │                          │
│  /saml/idp/sso │    │   ├ p1 (SAML)   │    │  micronaut-security-jwt  │
│  /saml/idp/    │    │   ├ google      │    │  validates via JWKS      │
│    metadata    │    │   └ microsoft   │    │                          │
│  /saml/idp/slo │    │                 │    │                          │
│                │    │  User store SPI │    │                          │
│  Plus:         │    │   ↓             │    │                          │
│   login.do     │    │  user-service   │    │                          │
│   logout.do    │    │   :8090         │    │                          │
└────────────────┘    └─────────────────┘    └──────────────────────────┘
                                              ▲
                                              │ JWKS fetch
                                              │ (every BFF, on JWT validate)
                                              └─ http://keycloak:8080/realms/demo-realm
                                                   /protocol/openid-connect/certs
```

### 1.3 Tokens, cookies, и session artifacts

| Artifact | Owner | Where it lives | TTL (default) | Cleared by |
|---|---|---|---|---|
| `KEYCLOAK_IDENTITY` (realm session cookie) | Keycloak | Browser, HttpOnly, SameSite=Lax | SSO Max (10h) | Logout, expiry |
| `AUTH_SESSION_ID` (realm auth session) | Keycloak | Browser, HttpOnly | SSO Max | Logout, expiry |
| `KC_DEMO_OTP_TRUSTED` (OTP trust cookie) | Keycloak SPI | Browser, HttpOnly, HMAC-signed | 60 min | Keycloak restart, expiry |
| Access token (JWT) | keycloak-js | Shell process memory | 5 min | Logout, refresh, page reload |
| Refresh token | keycloak-js | Shell process memory | 30 min | Logout, page reload, rotation |
| ID token | keycloak-js | Shell process memory | 5 min | Logout, page reload |
| JSESSIONID (P1) | Tomcat | Browser, HttpOnly | 420 min (web.xml) | P1 logout, SAML SLO |
| MFE TanStack query cache | Shell | Process memory | Until logout or page reload | `queryClient.clear()` on logout |

---

## 2. Login workflows

### 2.1 Метод A — Username + Password (default)

#### Steps (numbered to DevTools Network tab)

1. **Page load** `GET http://localhost:5173/`
   - Shell bundle loads.
   - `AuthProvider.tsx` стартира `keycloak.init({ onLoad: 'check-sso', pkceMethod: 'S256' })`.
   - Silent SSO iframe: `GET http://localhost:8898/realms/demo-realm/protocol/openid-connect/auth?...&prompt=none`.
   - No realm cookie → iframe returns with `?error=login_required`. `authenticated = false`.

2. **User clicks "Sign in"**
   - `useAuth().login()` → `keycloak.login({ redirectUri: ... })`.
   - Browser navigates: `GET /realms/demo-realm/protocol/openid-connect/auth?client_id=mfe-shell-client&response_type=code&scope=openid&code_challenge=...&code_challenge_method=S256`.

3. **Login screen**
   - Keycloak renders `mfe-shell` theme login form (`keycloak/themes/mfe-shell/login/`).
   - Form fields:
     - **Username or email** (single input — `loginWithEmailAllowed=true` accepts both)
     - **Password**
     - "Login with P1" button (`kc_idp_hint=p1` link)
     - "Login with Google" / "Login with Microsoft" buttons (only ако IdPs са конфигурирани)

4. **Username/password submission**
   - `POST /realms/demo-realm/login-actions/authenticate`
   - Behind the scenes (`auth-username-password-form` authenticator):
     ```
     Keycloak                                user-service
        │  DemoUserStorageProvider.getUserByUsername(...)
        │  ──────────────────────────────────────────►  GET /users/{username}
        │  ◄────────────────────────────────────────── 200 {username, email, roles[]}
        │  DemoUserStorageProvider.isValid(input)
        │  ──────────────────────────────────────────►  POST /users/verify-credentials
        │  ◄────────────────────────────────────────── 200 {valid: true}
     ```
   - Fail-closed: всеки non-200 → false → "Invalid credentials".

5. **Email OTP step (`demo-email-otp` authenticator)**
   - **Trust cookie check first:**
     - HMAC-verified `KC_DEMO_OTP_TRUSTED` cookie present и не e expired → `context.success()`, skip към step 7.
   - **No valid trust cookie:**
     - 6-digit `SecureRandom` code generated, stored in `AuthenticationSessionModel`.
     - `ResendClient.send()` POSTs email (sandbox-fallback: code logged at INFO).
     - User enters code в `email-otp.ftl` form.
     - `EmailOtpAuthenticator.action()` validates → `issueTrustCookie()` → `context.success()`.

6. **Authorization code → token exchange**
   - `302 → http://localhost:5173/?code=...&state=...`.
   - keycloak-js detects params, POSTs:
     ```
     POST /realms/demo-realm/protocol/openid-connect/token
        grant_type=authorization_code
        client_id=mfe-shell-client
        code=...
        redirect_uri=http://localhost:5173/
        code_verifier=...     (PKCE)
     ```
   - Response: `{ access_token, id_token, refresh_token, expires_in, refresh_expires_in }`.
   - Tokens stored in keycloak-js **memory only**. URL cleaned via `history.replaceState`.

7. **Shell hydration**
   - `AuthProvider` resolves init promise → `setAuthenticated(true)`, `setReady(true)`.
   - `useWhoAmI(true)` triggers → `GET /api/whoami` → `bff-client` → `{ username, email, roles, allowedMfes }`.
   - Nav greys out unreachable MFE links. RoleGate begins mounting authorized MFEs.

#### Sequence diagram (Метод A)

```
Browser              Shell              Keycloak           user-service
   │                   │                   │                   │
   │ load /            │                   │                   │
   │──────────────────►│                   │                   │
   │                   │ check-sso iframe  │                   │
   │                   │──────────────────►│                   │
   │                   │ ◄── login_required│                   │
   │                   │                   │                   │
   │ click Sign In     │                   │                   │
   │──────────────────►│                   │                   │
   │                   │ login() redirect  │                   │
   │ ◄─────────────────│                   │                   │
   │ /auth?...&PKCE    │                   │                   │
   │──────────────────────────────────────►│                   │
   │ ◄── login form HTML                   │                   │
   │                                       │                   │
   │ POST username+password                │                   │
   │──────────────────────────────────────►│ getUserByUsername │
   │                                       │──────────────────►│
   │                                       │ ◄── user JSON     │
   │                                       │ verify-creds      │
   │                                       │──────────────────►│
   │                                       │ ◄── {valid:true}  │
   │                                       │                   │
   │ ◄── email-otp.ftl form                │                   │
   │ POST otp code                         │                   │
   │──────────────────────────────────────►│                   │
   │                                       │ issue trust cookie│
   │ ◄── 302 /?code=...&state=...          │                   │
   │                                       │                   │
   │ shell loads code                      │                   │
   │──────────────────►│                   │                   │
   │                   │ POST /token (PKCE)│                   │
   │                   │──────────────────►│                   │
   │                   │ ◄── tokens        │                   │
   │                   │ hydrate, fetch    │                   │
   │                   │ /api/whoami       │                   │
```

### 2.2 Метод B — Email + Password

Идентичен на A, освен **step 4**: user въвежда email в "Username or email" input. Keycloak's `auth-username-password-form` use-ва `user-service` lookup-а с email criterion (`GET /users?email=...`), останалото е същото.

**Защо няма отделен flow:** `loginWithEmailAllowed=true` в realm config-а прави един input достатъчен. Single input UX > radio-style "Username vs Email" tab.

### 2.3 Метод C — OAuth federated via P1 (SAML IdP Brokering)

Това е flow-ът дефиниран в `p1-sso-integration-research.md` Solution 2. Има **две sub-flows**:

#### C1 — IdP-initiated (от P1 sidebar)

1. User вече e logged in в P1 (има JSESSIONID).
2. User clicks **"Demo MFE-BFF"** в sidebar-а.
3. Browser → `GET /saml/idp/sso.do?RelayState=keycloak-demo`.
4. P1 `IdpSsoAction` checks active session, builds signed `<samlp:Response>` (assertion includes NameID + attributes: `email`, `firstName`, `lastName`, `firmCd`, `roles`).
5. P1 returns HTTP POST form auto-submit:
   ```html
   <form action="http://localhost:8898/realms/demo-realm/broker/p1/endpoint" method="POST">
     <input name="SAMLResponse" value="<base64>"/>
     <input name="RelayState" value="keycloak-demo"/>
   </form>
   <script>document.forms[0].submit()</script>
   ```
6. Browser POSTs to Keycloak's broker ACS.
7. Keycloak validates signature (against pre-configured P1 X509 cert), validates audience, time conditions.
8. **First Broker Login flow:**
   - Lookup user by NameID (email или UUID — TBD).
   - If found → link broker identity to existing Keycloak user.
   - If not found → auto-create Keycloak user from SAML attributes (per "Auto Create" / "Auto Link" config).
   - Email OTP **skipped** (federation = trust delegated to P1).
9. Keycloak mints session, redirects → `http://localhost:5173/?code=...&state=...&iss=...`.
10. Steps 6–7 от метод A (token exchange + hydration).

#### C2 — SP-initiated (от login screen в Keycloak)

1. User landed on login screen (e.g. opened `localhost:5173/` directly без P1 context).
2. Clicks **"Login with P1"** button (renders ако `p1` IdP is configured).
3. Browser → `GET /realms/demo-realm/broker/p1/login?session_code=...&client_id=mfe-shell-client&tab_id=...`.
4. Keycloak builds `<samlp:AuthnRequest>`, POSTs to P1 `/saml/idp/sso.do`.
5. P1 checks session:
   - **Active session:** silent — builds Response, returns auto-submit form (step 5 от C1).
   - **No session:** P1 redirects to its login page `/react/login.do` → user logs in via username/password → P1 redirects back to its own SAML endpoint → builds Response.
6. Same as C1 step 6+.

#### Sequence diagram (C1 — IdP-initiated)

```
Browser              P1                 Keycloak           Shell
   │                  │                   │                  │
   │ Click button     │                   │                  │
   │ /saml/idp/sso.do │                   │                  │
   │─────────────────►│                   │                  │
   │                  │ check JSESSIONID  │                  │
   │                  │ build SAML Resp   │                  │
   │ ◄── HTML form POST auto-submit       │                  │
   │                                      │                  │
   │ POST SAMLResponse → /broker/p1/endpoint                 │
   │─────────────────────────────────────►│                  │
   │                                      │ validate sig     │
   │                                      │ first-broker-login
   │                                      │ create/link user │
   │                                      │ mint session     │
   │ ◄── 302 localhost:5173/?code=...     │                  │
   │                                                          │
   │ load with code                                           │
   │─────────────────────────────────────────────────────────►│
   │                                                          │ POST /token
   │                                                          │──────────────►
   │                                                          │ ◄── tokens
```

### 2.4 Метод D — OAuth federated via Social Provider (Google / Microsoft)

1. User on Keycloak login screen, clicks "Login with Google".
2. Browser → `GET /realms/demo-realm/broker/google/login?...`.
3. Keycloak builds OAuth 2.0 authorization request, redirects to Google.
4. User completes Google login (потенциално 2FA в Google).
5. Google redirects back → `GET /realms/demo-realm/broker/google/endpoint?code=...&state=...`.
6. Keycloak exchanges code for Google tokens, fetches Google userinfo.
7. **First Broker Login flow** (same as C1 step 8): auto-link by email or auto-create.
8. Keycloak mints own session, redirects → `localhost:5173/?code=...`.
9. Steps 6–7 от метод A.

**Конфигурация:** Add Identity Provider → OIDC v1.0 → Google. Client ID/Secret от Google Cloud Console. Същия pattern за Microsoft (Azure AD) или GitHub.

**Защо не Google's `One Tap`:** Изисква JS SDK на shell page, complicates CSP, и не дава нищо което standard OIDC redirect не дава. Skip.

---

## 3. Logout workflow

### 3.1 Local logout (Methods A, B)

1. User clicks **"Sign out"** в Nav.
2. `useAuth().logout()` → `keycloak.logout({ redirectUri: window.location.origin + '/' })`.
3. Browser navigates:
   ```
   GET /realms/demo-realm/protocol/openid-connect/logout
      ?post_logout_redirect_uri=http://localhost:5173/
      &id_token_hint=<id_token>
   ```
4. Keycloak:
   - Invalidates `AuthenticationSession`.
   - Clears realm cookies (`KEYCLOAK_IDENTITY`, `AUTH_SESSION_ID`).
   - **NOTE:** `KC_DEMO_OTP_TRUSTED` cookie remains (by design — represents trusted device, not session).
   - Back-channel logout token sent to clients with `backchannel_logout_uri` (none currently configured for `mfe-shell-client`).
5. Keycloak `302 → http://localhost:5173/`.
6. Shell catches event: `keycloak.onAuthLogout` → `setAuthenticated(false)` → `queryClient.clear()` (clears every cached query → all "authenticated" UI rewinds in same render tick).

### 3.2 Federated logout — Methods C, D

Aditional steps между 4 и 5 of section 3.1:

4a. Keycloak inspects the user's session for active broker linkages (i.e., user е дошъл през `p1` IdP).
4b. **For SAML IdP (P1):** Keycloak sends `<samlp:LogoutRequest>` to P1's `/saml/idp/slo.do`:
   ```
   POST http://host.docker.internal:8080/saml/idp/slo.do
   <samlp:LogoutRequest>
     <saml:NameID>user@example.com</saml:NameID>
     <samlp:SessionIndex>kc-session-id</samlp:SessionIndex>
   </samlp:LogoutRequest>
   ```
4c. P1 `IdpSloAction`:
   - Validates LogoutRequest signature (Keycloak SP cert pre-configured).
   - Destroys P1 Tomcat session (mimics `LogOutAction.java` behavior — clears `LOGGED_ADVISER`, `LOGGED_USER` keys).
   - Returns signed `<samlp:LogoutResponse>` to Keycloak.
4d. Keycloak proceeds with local logout (step 5).

**For OIDC social providers (Google):** RP-initiated logout — Keycloak calls Google's `/revoke` endpoint OR `/end_session_endpoint`. Note: not all providers support this; if not, the Google session persists на browser-а (this is OK — separate trust domain).

### 3.3 Multi-tab logout propagation

Currently: **NOT automatic.** `checkLoginIframe` is disabled (cross-origin iframe instability). Other tabs notice on next fetch:

- Tab A logs out → realm cookie cleared.
- Tab B's next `host.auth.getToken()` calls `keycloak.updateToken(30)` → POST `/token` with refresh token.
- Refresh token still valid? → returns new token. User remains "logged in" in Tab B until refresh token natural expiry (default 30 min).
- Refresh token revoked by Keycloak on logout? → `onAuthRefreshError` → Tab B redirects to login.

**To make instantaneous:**
- **Option 1:** `BroadcastChannel('auth')` API — Tab A broadcasts `{ type: 'logout' }`; Tab B `addEventListener('message', ...)` → calls `keycloak.clearToken()` and `queryClient.clear()`. Effort: ~2 hours. **Recommended.**
- **Option 2:** Re-enable `checkLoginIframe` + handle CSP. Effort: ~1 day. Not recommended.
- **Option 3:** Server-Sent Events from BFF → shell subscribes to `/api/auth/events`. Heavy for this concern.

### 3.4 Sequence diagram — full federated logout

```
Browser          Shell           Keycloak              P1
   │                │               │                   │
   │ click Sign Out │               │                   │
   │───────────────►│               │                   │
   │                │ logout()      │                   │
   │ ◄── 302 to /logout endpoint    │                   │
   │                                │                   │
   │ GET /logout?id_token_hint=...  │                   │
   │───────────────────────────────►│                   │
   │                                │ invalidate session│
   │                                │ POST LogoutRequest│
   │                                │──────────────────►│
   │                                │                   │ kill JSESSIONID
   │                                │ ◄── LogoutResponse│
   │                                │ clear cookies     │
   │ ◄── 302 localhost:5173/        │                   │
   │                                                    │
   │ shell loads anonymous          │                   │
   │───────────────►│ onAuthLogout                       │
   │                │ queryClient.clear()                │
   │                │ setAuthenticated(false)            │
```

---

## 4. Expired session workflow

### 4.1 Token lifetimes (production targets)

| Token / Cookie | Default | Recommended (prod) | Configurable at |
|---|---|---|---|
| Access token | 5 min | 5 min | Realm → Tokens |
| Refresh token | 30 min | 30 min (with rotation) | Realm → Tokens |
| ID token | 5 min | 5 min | Same as access |
| SSO Session Idle | 30 min | 30 min | Realm → Sessions |
| SSO Session Max | 10 hours | 8 hours | Realm → Sessions |
| Offline Session Idle | 30 days | Disable (not needed) | Realm → Sessions |
| OTP Trust Cookie | 60 min | 60 min или 0 (disabled) | `OTP_TRUST_WINDOW_MINUTES` env |
| P1 Tomcat session | 420 min | Match SSO Session Max | `web.xml` |
| JWKS cache (in BFFs) | 24h | 24h | Micronaut config |

### 4.2 Lazy access-token refresh (happy path)

Refresh is **not timer-driven**. It happens when a fetch is about to need the token:

```ts
// useShellHost.ts
async getToken() {
  await keycloak.updateToken(30);  // if expires in <30s, refresh now
  return keycloak.token;
}
```

`updateToken(30)`:
- If access token has >30s remaining → returns immediately, no network.
- If access token has ≤30s remaining → POST `/token` with refresh token:
  ```
  POST /realms/demo-realm/protocol/openid-connect/token
     grant_type=refresh_token
     refresh_token=<rt>
     client_id=mfe-shell-client
  ```
- Response: new `{ access_token, refresh_token (rotated, ако enabled), id_token, expires_in }`.
- keycloak-js replaces tokens in memory.

### 4.3 Refresh token expiry → forced re-auth

When refresh token itself is expired (>30 min idle):

1. Shell calls `host.auth.getToken()` to make a fetch.
2. `keycloak.updateToken(30)` POSTs refresh token.
3. Keycloak responds `400 invalid_grant`.
4. keycloak-js fires `onAuthRefreshError`.
5. `AuthProvider` catches:
   ```ts
   keycloak.onAuthRefreshError = () => {
     setAuthenticated(false);
     queryClient.clear();
   };
   ```
6. Shell renders anonymous UI. User вижда "Sign in" button.
7. User clicks → full login flow от section 2.

**Important UX nuance:** If the in-flight fetch was triggered by a user action (e.g. "Save changes"), that action is lost. Production-grade shells implement **action replay**: after re-login, retry the original action. Out of scope for this iteration.

### 4.4 Silent SSO on page reload (still within SSO max)

User reloads tab. Shell process memory is gone (no tokens). Но realm cookie still valid:

1. `AuthProvider` calls `keycloak.init({ onLoad: 'check-sso', silentCheckSsoRedirectUri: '/silent-check-sso.html' })`.
2. Hidden iframe loads:
   ```
   GET /realms/demo-realm/protocol/openid-connect/auth
      ?client_id=mfe-shell-client&response_type=code
      &redirect_uri=http://localhost:5173/silent-check-sso.html
      &prompt=none
      &code_challenge=...
   ```
3. Keycloak sees valid `KEYCLOAK_IDENTITY` cookie → returns `302` with auth code in iframe URL.
4. keycloak-js parent reads iframe URL via postMessage, exchanges code for tokens (PKCE).
5. `authenticated = true` без user interaction.

**If realm cookie expired (>SSO Session Idle):**
- Iframe returns `?error=login_required`.
- `authenticated = false`. User sees "Sign in".

### 4.5 SSO Session Max — hard limit

Even ако user is active, после 10h:
1. Keycloak invalidates session.
2. Next refresh attempt → `400 invalid_grant`.
3. Same flow as section 4.3.

This protects срещу **forever sessions** в случай на token leak (refresh token rotation would be invalidated when the realm session dies).

### 4.6 Idle timeout vs Max timeout

| Type | Trigger | Default | What it protects |
|---|---|---|---|
| **Idle** | No activity (no token refresh, no auth iframe ping) за X време | 30 min | Forgotten tabs, public computers |
| **Max** | Absolute time since login | 10h | Compromised long-lived credentials |

Refresh token use **resets idle clock** but doesn't extend max. Max is enforced regardless of activity.

### 4.7 Edge cases & failure modes

| Scenario | Behavior | Mitigation |
|---|---|---|
| Network down during refresh | `updateToken` rejects with network error | Fall through to `onAuthRefreshError`; shell shows offline UI. Action replay if implemented. |
| Refresh token revoked by admin (force logout) | Same as section 4.3 | None needed; design works as intended |
| Refresh token reuse detected (rotation enabled) | Keycloak revokes **all** sessions in family, returns `400` | User re-logs in; security event logged |
| Federation IdP (P1) down during silent SSO | Silent check returns `login_required` | User sees "Sign in"; clicking does full login → if P1 still down, Keycloak login screen shows error |
| Two tabs both refresh simultaneously | Both POST `/token` with same refresh token; rotation enabled → second one fails | Use `BroadcastChannel` to dedupe; or accept brief flash of re-auth in one tab |
| Clock skew between Keycloak and BFF | JWT `exp`/`nbf` validation fails sporadically | Both NTP-synced; allow `clockSkew=30s` в BFF JWT validator |
| User logs out in P1 (not via Keycloak) | Keycloak session remains valid; tokens still mint | P1 SLO fires LogoutRequest to Keycloak's `/protocol/openid-connect/logout` endpoint — requires bi-directional SLO config |
| Browser blocks third-party cookies | `check-sso` iframe fails (iframe cookies blocked) | Same-origin only (Keycloak on same domain as shell в prod). Or use `prompt=none` redirect (not iframe). |
| Token clock-not-yet-valid (`nbf` in future) | BFF rejects with `JWT not valid yet` | Same as clock skew |

### 4.8 Expired session sequence diagram

```
Browser           Shell             Keycloak           BFF
   │                │                  │                │
   │ user action    │                  │                │
   │───────────────►│                  │                │
   │                │ host.auth.getToken()              │
   │                │ updateToken(30)  │                │
   │                │ POST /token (RT) │                │
   │                │─────────────────►│                │
   │                │ ◄── 400 invalid_grant             │
   │                │                  │                │
   │                │ onAuthRefreshError fired          │
   │                │ setAuthenticated(false)           │
   │                │ queryClient.clear()               │
   │                │                  │                │
   │ ◄── anonymous UI rendered         │                │
   │ user clicks Sign In               │                │
   │───────────────►│                  │                │
   │                │ keycloak.login() │                │
   │                │ (full flow from §2.1)             │
```

---

## 5. Login screen UX & decision tree

```
        ┌───────────────────────────────────┐
        │  Keycloak Login Screen            │
        │  (mfe-shell theme)                │
        │                                   │
        │  ┌─────────────────────────────┐  │
        │  │ Username or email           │  │
        │  └─────────────────────────────┘  │
        │  ┌─────────────────────────────┐  │
        │  │ Password                    │  │
        │  └─────────────────────────────┘  │
        │  [ Sign In ]                      │
        │                                   │
        │  ─────── or ───────               │
        │                                   │
        │  [ Login with P1     ]            │  ← Method C
        │  [ Login with Google ]            │  ← Method D
        │  [ Login with Microsoft ]         │  ← Method D
        └───────────────────────────────────┘
                       │
       ┌───────────────┼───────────────┐
       ▼               ▼               ▼
    Method A/B      Method C        Method D
    (local)         (P1 SAML)       (social OIDC)
       │               │               │
       │               │               │
   user+pass        SAML AuthnReq  OAuth authorize
       │           → P1               → Google
       │           ← SAML Resp        ← code
       │               │               │
       ▼               ▼               ▼
   Email OTP    First Broker     First Broker
   (or trust    Login flow       Login flow
    cookie skip)                       │
       │               │               │
       └───────────────┴───────────────┘
                       ▼
                  /token POST
                       │
                       ▼
              Shell hydration
                  done.
```

### 5.1 Account linking при collision

Scenario: User вече има local Keycloak account (`demoadmin@example.com`). Same email опитва login through P1 (Method C). What does First Broker Login do?

**Configured behavior (recommended):**
- Auto-link by email (silent).
- New broker identity (P1) added to existing Keycloak user record.
- User session contains both `realm_access.roles` (от local) и SAML attributes (от P1) — merge logic в claim mapper.

**Alternative — manual review:**
- Default Keycloak behavior: show "Account exists with same email — review identities" screen.
- User authenticates with **existing local password** to confirm.
- Then link.
- Better security, worse UX. Choose per threat model.

---

## 6. Сигурност и guards

### 6.1 OWASP / OIDC best practices applied

| Threat | Mitigation |
|---|---|
| Authorization code interception | **PKCE S256** (`code_challenge_method=S256`) |
| Token theft via XSS | Tokens **in memory only** (no localStorage). XSS still gives access during session window, но не survives page reload. |
| CSRF on Bearer endpoints | Bearer auth е stateless — not vulnerable. (CSRF concern only if migrating to cookie-based BFF sessions.) |
| Refresh token replay | **Refresh token rotation** enabled at realm; reuse detection revokes family. |
| Login redirect tampering | `state` parameter cryptographic random; verified on callback. |
| Federation SAML replay | P1 IdP tracks `InResponseTo` IDs + `IssueInstant` window (production hardening item). |
| Federation SAML signature stripping | Keycloak SAML SP enforces signature на assertion AND/OR response. |
| Email OTP brute force | Keycloak brute-force detector enabled at realm; lockout after 5 failures. |
| Session fixation | Keycloak rotates `KEYCLOAK_IDENTITY` on successful auth. |
| Clickjacking | `X-Frame-Options: DENY` on Keycloak; `frame-ancestors 'none'` CSP on shell. |
| Subresource integrity (federation chunks) | SRI hashes on `remoteEntry.js` (production hardening). |

### 6.2 Cookie security flags (production)

| Cookie | HttpOnly | Secure | SameSite | Path | Reason |
|---|---|---|---|---|---|
| `KEYCLOAK_IDENTITY` | ✅ | ✅ | Lax | `/realms/demo-realm` | Standard Keycloak realm session |
| `AUTH_SESSION_ID` | ✅ | ✅ | Lax | `/realms/demo-realm` | Standard |
| `KC_DEMO_OTP_TRUSTED` | ✅ | ✅ | Lax | `/realms/demo-realm` | SPI sets these flags explicitly |
| `JSESSIONID` (P1) | ✅ | ✅ | Lax | `/` | Production must set Secure in Tomcat config |

**Lax vs Strict:** Lax allows cross-site GET navigation (needed за OIDC redirect dance). Strict breaks login flows from external links.

### 6.3 HTTPS requirements

| Hop | Production requirement |
|---|---|
| Browser ↔ Shell | TLS via edge (Caddy/nginx/ALB) |
| Browser ↔ Keycloak | TLS — Keycloak `KC_HOSTNAME_STRICT=true`, `KC_HTTPS_*` configured |
| Browser ↔ P1 | TLS — current `web.xml` should add `<user-data-constraint><transport-guarantee>CONFIDENTIAL</transport-guarantee>` |
| Shell ↔ BFFs | TLS via internal mesh OR private network |
| Keycloak ↔ P1 (back-channel SLO) | TLS, ideally mTLS |
| Keycloak ↔ user-service | TLS in production (currently HTTP в compose) |
| BFFs ↔ Keycloak JWKS | TLS |

---

## 7. Session-state lifecycle table

| State | Created when | Refreshed when | Cleared when |
|---|---|---|---|
| **P1 Tomcat session** | `LoginAction.execute()` succeeds | Each request (sliding timeout) | `LogOutAction`, JSESSIONID cookie expiry, SAML SLO from Keycloak |
| **Keycloak realm session** | Authenticator chain success | Token refresh, `check-sso` ping | `/logout` endpoint, SSO Idle/Max timeout, brute-force lockout |
| **Keycloak client session (mfe-shell-client)** | First `/token` exchange | Per refresh | When realm session dies |
| **Shell access token** | `/token` response | `updateToken(30)` if <30s left | `onAuthLogout`, `onAuthRefreshError`, page reload |
| **Shell refresh token** | Same | Rotated on each refresh (production) | Same as access |
| **TanStack QueryClient cache** | Each query first call | `staleTime` expiry → revalidate | `queryClient.clear()` on logout |
| **OTP trust cookie** | Successful OTP submission | Not refreshed | Cookie max-age expiry, Keycloak restart (HMAC key in memory) |
| **MFE federation runtime modules** | First MFE mount via `lazy(() => import('mfeX/Mfe'))` | Not refreshed within session | Page reload |

---

## 8. Production hardening checklist

Drawn from current keycloak-demo `LOGIN.md` "Must-fix before production" + new items for P1 federation:

### 8.1 Crypto / signing

- [ ] All hops over TLS (browser, back-channel, SPI ↔ user-service)
- [ ] Dedicated P1 IdP signing key (not reused `firelight.pfx` with password `"1234"`)
- [ ] Key rotation pipeline (60-day cadence; advertise both keys in metadata during transition)
- [ ] Persistent HMAC key for OTP trust cookie (env var, not in-memory)
- [ ] Refresh token rotation enabled at realm

### 8.2 Federation safety

- [ ] P1 SAML IdP enforces `InResponseTo` replay tracking (Redis or DB)
- [ ] P1 validates Keycloak SP signature on AuthnRequest (in C2 flow)
- [ ] Keycloak enforces `wantAssertionsSigned=true` AND `wantAssertionsEncrypted=true` (latter optional but recommended)
- [ ] First Broker Login flow set to **Auto Link by email** OR **Manual Review** explicitly (don't leave default)
- [ ] Audience restriction в P1's SAML Response = Keycloak's broker EntityID

### 8.3 Session & token

- [ ] SSO Session Max = 8h (down from default 10h)
- [ ] Access token = 5 min (default OK)
- [ ] Brute force detector enabled (5 failures → 5 min lockout, escalating)
- [ ] CORS allow-list on BFFs = only shell origin
- [ ] CSP on shell allows only federation remotes' origins + Keycloak
- [ ] BFFs validate `aud` claim (per-MFE audiences via token exchange — phase 2)

### 8.4 Audit & ops

- [ ] Every SAML emission в P1 logs `SECURITY_EVENT: SAML_ISSUED user=<uuid> firm=<cd> target=<sp>`
- [ ] Keycloak event listener configured (logs login/logout/broker events to durable sink)
- [ ] Failed-login alerts (>10/min from one IP → page on-call)
- [ ] Monitoring on JWKS endpoint availability (BFFs cache 24h, but startup fetches must succeed)

### 8.5 UX

- [ ] "Action replay" after re-login (preserve in-flight user action)
- [ ] `BroadcastChannel('auth')` for multi-tab sync
- [ ] Loading states за silent SSO check (don't show "Sign in" for 200ms before silent SSO completes)
- [ ] Clear error messaging for federation failures ("Login with P1 unavailable — please try local credentials")

---

## 9. Файлове по компонент

### Keycloak realm config

| File | Промяна |
|---|---|
| `keycloak/realm-export.json` | Add IdPs: `p1` (SAML), optional `google`/`microsoft` (OIDC). Configure First Broker Login flow. |
| `keycloak/themes/mfe-shell/login/login.ftl` | Add "Login with P1/Google/Microsoft" buttons (rendered when IdPs configured) |
| `keycloak-provider/src/main/java/com/example/keycloak/EmailOtpAuthenticator.java` | No change — federation paths skip this authenticator naturally |

### Shell (React)

| File | Промяна |
|---|---|
| `frontend/apps/shell/src/auth/AuthProvider.tsx` | Add `onAuthRefreshError` handler (already partial); optional `BroadcastChannel` listener for cross-tab logout |
| `frontend/apps/shell/src/auth/useShellHost.ts` | No change — `getToken()` already does `updateToken(30)` |
| `frontend/apps/shell/src/components/Nav.tsx` | No change — logout button works through existing `useAuth().logout()` |
| `frontend/apps/shell/silent-check-sso.html` | No change — existing |

### BFFs (Micronaut)

| File | Промяна |
|---|---|
| `bff/src/main/resources/application.yml` | Add `micronaut.security.token.jwt.signatures.jwks.clockSkew=30s` (production) |
| `bff/src/main/java/demo/UserController.java` | No change — `/whoami` derives `allowedMfes` from JWT roles (works for federated users too) |

### P1 (GeoWealth)

Виж `p1-sso-integration-research.md` section 4.3 — пълен списък на 5 нови Java файла + sidebar entry + Struts XML.

---

## 10. Open decision points

Carried forward from `p1-sso-integration-research.md` плюс нови от тази архитектура:

### Carried forward

1. **NameID format:** email vs P1 UUID
2. **`kc_idp_hint=p1` default:** every login OR only sidebar entry
3. **Role mapping:** P1 roles ↔ Keycloak `client/user/admin`
4. **Keycloak deployment topology** в production
5. **SLO scope** в MVP — front-channel SLO или skip

### New (from this architecture work)

6. **Account linking policy:** Auto-link by email (smooth UX) или Manual Review (stronger security)?
7. **Social IdPs:** включваме ли Google/Microsoft в MVP, или само P1?
8. **Refresh token rotation:** enable в realm на старт, или phase 2?
9. **Multi-tab sync:** `BroadcastChannel` в MVP, или live-with-it?
10. **Audience scoping per MFE:** еднa shared audience (current) или token exchange per MFE call (production)?
11. **Email OTP за federated users:** **skip винаги** (current design) или allow as second factor (e.g. `acr` claim "level2")?

---

## 11. Summary

| Параметър | Стойност |
|---|---|
| **Поддържани методи за вход** | A: username+password • B: email+password • C: SAML federation (P1) • D: OIDC social (Google/MS) |
| **MFA** | Email OTP за A/B (skip за C/D — trust delegated) |
| **Logout** | Federated: Keycloak `/logout` + SAML SLO до P1; tokens cleared in-memory; QueryClient cleared |
| **Expired session** | Lazy `updateToken(30)` refresh; full re-auth on refresh failure; silent SSO via `check-sso` iframe on page reload |
| **Session limits** | Access 5min · Refresh 30min · SSO Idle 30min · SSO Max 8h |
| **Production blockers** | TLS everywhere · Dedicated SAML signing key · Refresh rotation · Brute-force detector · Audit logging |
| **Multi-tab** | Best-effort: 30-min lag on logout (refresh-fail-triggered); recommended `BroadcastChannel` for instant sync |
| **Total effort за пълна архитектура** | ~15 дни (P1 IdP) + 2 дни (Keycloak config) + 3 дни (Shell hardening) + 5 дни (production hardening) = **~25 дни** |
