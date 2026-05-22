# Login & Logout — full flow, design rationale, prod-security audit

This is the dedicated walkthrough of how authentication moves through the
stack — from the moment a user lands on the shell to the moment they sign
out — and what would have to change before the same flow is safe for
production. It assumes you've read the architecture overview in
[`README.md`](./README.md) and the in-edit notes in [`CLAUDE.md`](./CLAUDE.md).

## TL;DR

The browser sees **one origin** (`http://localhost:5173`, the React shell).
The shell owns the only `keycloak-js` instance in the page and handles the
OIDC Authorization Code + PKCE flow against `mfe-shell-client` in Keycloak's
`demo-realm`. After username/password Keycloak runs a custom Email-OTP
authenticator (codes generated in the SPI, delivered by Resend, also logged
for the sandbox fallback) and drops an HMAC-signed trust cookie that skips
OTP for an hour. Tokens stay in shell memory; MFEs receive an explicit
`ShellHost` prop and never touch `keycloak-js`. Logout calls
`keycloak.logout()` and also clears the shared TanStack `QueryClient`, so
every cached MFE-visible query (whoami, profile, BFF responses) refetches
anonymous in the same render tick.

```
                          ┌──────────────────────────────────────┐
                          │  Keycloak (demo-realm)               │
                          │   ─ mfe-shell-client (PKCE)          │
                          │   ─ demo-browser flow:               │
                          │       cookie / IdP / forms-subflow   │
                          │           ├── username/password      │
                          │           └── demo-email-otp (SPI)   │
                          │   ─ Resend (SPI client)              │
                          │   ─ User-Storage SPI ─ REST ─ user-svc
                          └──────────────────────────────────────┘
                                       ▲
                                       │ Auth-code + PKCE
                                       │ /token, /userinfo, /logout
                                       │
┌───────────┐      shell @ :5173       │      ┌──────────────────┐
│  Browser  │ ─────────────────────────┴──────│  React Shell     │
│           │                                 │   ─ keycloak-js  │
│           │  Federation remoteEntry.js     │   ─ AuthProvider  │
│           │ ◄────────────────────────────── │   ─ QueryClient  │
│           │  per-MFE :5181/2/3              │   ─ MfeOutlet    │
│           │                                 │   ─ RoleGate     │
│           │  /api/<mfe>/* (Bearer)          └──────┬───────────┘
│           │ ───────────► Vite proxy ───────────►   │
└───────────┘                                        ▼
                                              ┌──────────────────┐
                                              │  bff-client/ops/ │
                                              │  admin (Micronaut│
                                              │  Security: JWKS) │
                                              └──────────────────┘
```

## Full flow — login

The numbered steps below match what you'd see opening DevTools' Network tab.

### 1. Page load → silent SSO

Browser fetches `http://localhost:5173/`. The shell bundle loads;
`apps/shell/src/auth/AuthProvider.tsx` runs **one** `keycloak.init()` (gated
behind a module-level `initPromise` so React StrictMode's double-mount
doesn't double-init):

```ts
keycloak.init({
  onLoad: 'check-sso',
  silentCheckSsoRedirectUri: window.location.origin + '/silent-check-sso.html',
  silentCheckSsoFallback: false,
  checkLoginIframe: false,
  pkceMethod: 'S256'
});
```

`check-sso` mounts a hidden iframe that loads
`http://localhost:8898/realms/demo-realm/protocol/openid-connect/auth?...&prompt=none`.
Keycloak inspects the realm cookie (`KEYCLOAK_IDENTITY` / `AUTH_SESSION_ID`)
inside the iframe:

- **Cookie present + session live** → Keycloak redirects the iframe back to
  `silent-check-sso.html` with `?code=...`; keycloak-js auto-exchanges the
  code (PKCE) and the SPA receives a full token set. `authenticated = true`,
  no UI flashes login.
- **No cookie / no session** → Keycloak redirects the iframe with
  `?error=login_required`. The SPA stays anonymous.

### 2. Click "Sign in"

If anonymous, the Home page shows a button calling `useAuth().login()`,
which delegates to `keycloak.login({ redirectUri: window.location.origin + '/' })`.
The browser navigates to:

```
GET http://localhost:8898/realms/demo-realm/protocol/openid-connect/auth
    ?client_id=mfe-shell-client
    &response_type=code
    &scope=openid
    &redirect_uri=http://localhost:5173/
    &state=...random...
    &code_challenge=...sha256(verifier)...
    &code_challenge_method=S256
```

Keycloak renders the login page using the `mfe-shell` theme
(`keycloak/themes/mfe-shell/login/`) — same palette and typography as the
shell so the handoff is visually seamless.

### 3. Username/password

The user types email-or-username + password and submits. Keycloak's
`demo-browser` flow (configured in `keycloak/realm-export.json`) runs the
`auth-username-password-form` authenticator. Behind the scenes:

```
Keycloak                                    user-service
  │  DemoUserStorageProvider.getUserByUsername(username)
  │  ──────────────────────────────────────────►  GET /users/{username}
  │  ◄────────────────────────────────────────── 200 {username, email, roles[]}
  │  DemoUserStorageProvider.isValid(input)
  │  ──────────────────────────────────────────►  POST /users/verify-credentials
  │  ◄────────────────────────────────────────── 200 {valid: true}
```

If `valid:false` or any transport error → the SPI's `UserServiceClient`
returns `false` (fail-closed), Keycloak rejects login with "Invalid
credentials". No token is ever issued when `user-service` is unreachable.

### 4. Trust cookie short-circuit

The flow now enters `demo-email-otp`
(`keycloak-provider/src/main/java/com/example/keycloak/EmailOtpAuthenticator.java`).
The authenticator first inspects the browser's `KC_DEMO_OTP_TRUSTED` cookie:

```
value = base64url(userId) "." base64url(expiresAt) "." HMAC-SHA256(userId.expiresAt, key)
```

- HMAC verifies → `userId` matches the current user → `expiresAt` in the
  future → **`context.success()`** is called. **No code is generated, no
  email is sent.** Step 5 is skipped entirely.
- Anything off → fall through to step 5.

`OTP_TRUST_WINDOW_MINUTES` (default 60) controls the cookie lifetime; `0`
disables the feature.

### 5. Email OTP

When no trust cookie applies:

1. The authenticator generates a `SecureRandom` 6-digit code, stores it in
   the `AuthenticationSessionModel` as `email-otp-code` + `email-otp-expires`
   (10 minutes ahead). No Postgres schema needed.
2. Logs a banner at INFO with the code (this is the sandbox fallback —
   `docker compose logs keycloak | grep -A 4 "EMAIL OTP"`).
3. Calls `ResendClient.send()` (POSTs the HTML email to
   `https://api.resend.com/emails`). If `RESEND_API_TOKEN` is unset the
   client short-circuits with a calm log line — login keeps working.
4. Renders `theme-resources/templates/email-otp.ftl` (shipped inside the
   provider jar; uses the same `mfe-shell` styling because Keycloak loads
   theme resources from the realm theme + the SPI's theme-resources).

The user types the code → `EmailOtpAuthenticator.action()`:

- Empty / expired → re-enters `authenticate()`, mints a new code in place.
- Wrong → re-renders the form with `setError("Invalid code")`.
- Match → removes the auth notes, calls `issueTrustCookie()` (signed cookie
  written to the response), `context.success()`.

### 6. Authorization code → token

Keycloak issues an authorization code and 302s the browser to
`http://localhost:5173/?code=...&state=...`. The shell loads (now with the
code in the URL). `keycloak-js` notices the params, performs the PKCE token
exchange:

```
POST http://localhost:8898/realms/demo-realm/protocol/openid-connect/token
   grant_type=authorization_code
   client_id=mfe-shell-client
   code=...
   redirect_uri=http://localhost:5173/
   code_verifier=...
```

Response: `{ access_token, id_token, refresh_token, expires_in, ... }`.
keycloak-js keeps them **in memory** (not localStorage). The URL is cleaned
up (`history.replaceState`); the `?code=…` query is removed.

### 7. Shell hydrates auth context

`AuthProvider` resolves the init promise → `setAuthenticated(true)`,
`setReady(true)`. React re-renders. `useAuth()` consumers (Nav, RoleGate,
useShellHost) see `authenticated: true`, plus the synchronous claim mirrors
populated from `keycloak.tokenParsed` (`preferred_username`, `email`,
`realm_access.roles`).

### 8. First MFE call — whoami

The Nav calls `useWhoAmI(authenticated)` (a TanStack `useQuery`):

```
GET /api/whoami
   Authorization: Bearer <access_token>
```

Shell's Vite proxy rewrites `/api → http://bff-client:8080/api`.
`bff-client` (Micronaut + `micronaut-security-jwt`) validates the token
against Keycloak's JWKS at `KEYCLOAK_AUTH_SERVER_URL` and runs
`UserController#whoami`, returning
`{ username, email, roles, allowedMfes }`. `useWhoAmI` caches the response
in the shared `QueryClient`; `<Nav>` greys out unreachable MFE links and
`<RoleGate>` uses `allowedMfes` to decide whether to mount an MFE.

### 9. MFE mount — federation runtime

User clicks `Client` (`/client`). React Router matches `/client/*`,
`<MfeOutlet mfe="client">` runs. `RoleGate` passes (client allowed for
everyone). `lazy(() => import('mfeClient/Mfe'))` triggers
`@originjs/vite-plugin-federation` runtime, which fetches:

```
GET http://localhost:5181/assets/remoteEntry.js
```

The remote entry registers the MFE module with the shared dependencies
(React, ReactDOM, ReactRouterDOM, TanStack Query — all listed under
`shared` so only one copy lives in the page). The MFE renders, receiving
`host: ShellHost` from the shell — `host.auth.getToken()`,
`host.auth.loadProfile()`, `host.auth.roles`, etc.

### 10. MFE → its own BFF

When the user clicks "Ping my BFF" inside `mfe-client`:

```ts
const token = await host.auth.getToken();   // calls keycloak.updateToken(30)
fetch('/api/client/user', { headers: { Authorization: `Bearer ${token}` } });
```

`host.auth.getToken` runs `keycloak.updateToken(30)` first — if the access
token expires within 30 seconds, it transparently uses the refresh token to
get a new one. Then the fetch goes through shell's Vite proxy:

```
/api/client/user  →  http://bff-client:8080/api/user
/api/ops/user     →  http://bff-ops:8080/api/user      (APP_ALLOWED_ROLES=user,admin)
/api/admin/user   →  http://bff-admin:8080/api/user    (APP_ALLOWED_ROLES=admin)
```

Each BFF independently validates the JWT (its own JWKS fetch from
Keycloak), enforces `APP_ALLOWED_ROLES`, and returns either the user
payload or a 403 with the structured `reason: "not_allowed"` body.

## Full flow — logout

User clicks "Sign out" in the Nav. The shell calls
`useAuth().logout()` → `keycloak.logout()`. The browser is redirected to:

```
GET http://localhost:8898/realms/demo-realm/protocol/openid-connect/logout
    ?post_logout_redirect_uri=http://localhost:5173/
    &id_token_hint=<id_token>
```

Keycloak invalidates the realm session (clears the `KEYCLOAK_IDENTITY` /
`AUTH_SESSION_ID` cookies) and 302s back to `post_logout_redirect_uri`.
keycloak-js fires `onAuthLogout` in the shell, which `AuthProvider`
intercepts:

```ts
keycloak.onAuthLogout = () => {
  setAuthenticated(false);
  queryClient.clear();          // ← the cascade
};
```

`queryClient.clear()` invalidates every cached query in the page —
`useWhoAmI`, the MFE `Profile`'s `useQuery(['shell','profile'])`, the
Protected page's `useQuery(['mfe-client','protected'])`. Every component
subscribed to those queries re-renders, sees no data, and falls back to
its anonymous branch in the same render tick. The Nav re-greys every link.

**Note.** The `KC_DEMO_OTP_TRUSTED` trust cookie survives logout (by design
— it's about skipping OTP on a trusted device, not about session). Same
browser logging back in within the configured window won't have to re-OTP.
The HMAC key for it is regenerated on every Keycloak restart, which silently
invalidates every outstanding trust cookie.

## Token refresh

Lazy, not timer-driven.

- `host.auth.getToken()` in MFE code (and the shell's `fetchBff` helper)
  calls `keycloak.updateToken(30)` before returning the token. If the
  access token's remaining lifetime is under 30 seconds, keycloak-js POSTs
  the refresh token to `/token`, exchanges for a new pair, and returns
  the new access token. Otherwise it returns the current token immediately.
- If the refresh token has itself expired (default ~30 min in this realm),
  `updateToken` throws → `keycloak.onAuthRefreshError` fires →
  `AuthProvider` runs the same `setAuthenticated(false) + queryClient.clear()`
  it runs for logout. The user must sign in again.

There is **no background timer** in the page. Refresh happens only when a
fetch is about to need the token. This is intentional — saves a CPU/network
tick per minute and avoids races between multiple tabs in legacy setups.

## Why this shape

| Decision | Reason |
|---|---|
| **One OIDC client (`mfe-shell-client`)** | One client = one set of redirect URIs, one audience, one place to rotate secrets. Trying to mint per-MFE clients would force a token-exchange step on every MFE handover. |
| **Shell owns `keycloak-js`** | If three MFEs each integrated `keycloak-js`, you'd have three init flows, three token stores, three logout fan-outs to wire by hand. Putting it in the shell turns the entire authentication concern into a `ShellHost` prop the MFE sees as data. |
| **MFEs never see Keycloak** | Replacing the IdP becomes a `apps/shell/src/auth/` change. MFEs don't even import `keycloak-js`. Same MFE code could run against Auth0/Cognito by swapping the shell. |
| **`onLoad: 'check-sso'` + PKCE** | Silent-SSO via hidden iframe is browser-native (no extra deps). PKCE protects the auth code from interception even though the client is public (no client secret in a browser). |
| **Module-level `initPromise`** | React 18 StrictMode double-fires `useEffect` in dev. `keycloak.init()` must be called exactly once — singleton-via-promise is the safe way that also works in production. |
| **`onAuthLogout → queryClient.clear()`** | TanStack Query is the de-facto state cache for MFEs that talk to BFFs. Clearing the cache atomically rewinds every "authenticated" UI in the page without per-MFE bookkeeping. |
| **Email OTP, not TOTP** | One Keycloak SPI, no user enrolment step (you don't have to scan a QR or install an authenticator app). Demo-friendly. Real prod should prefer TOTP or WebAuthn — see below. |
| **HMAC-signed trust cookie** | Persisting "this browser already did OTP, don't ask again" on the browser side avoids a Postgres write per login. The HMAC keeps it tamper-resistant. |
| **Per-MFE BFF** | The role gate is a *BFF concern*, not a frontend concern. Three BFFs (same Micronaut image, different env) means a compromised shell or a hand-rolled token still can't bypass `APP_ALLOWED_ROLES`. |
| **`useWhoAmI` as UX hint only** | The shell's `<RoleGate>` refuses to mount unauthorized MFEs, but that's only to spare the user a useless page load. The actual security boundary is each BFF enforcing the role check on `/api/<mfe>/*`. Bypassing `<RoleGate>` (DevTools) still gets you a 403 from the BFF. |

## What changed vs the vanilla-TS era

The pre-rewrite stack ran the same `frontend/src/` three times under three
OIDC clients (`app1/2/3-client`) at three origins (5173/5174/5175). SSO
worked because the three keycloak-js instances all checked the same realm
cookie. That repo state lives at commits before `3a0ad28`.

| Aspect | Vanilla TS era | Current MFE era |
|---|---|---|
| **OIDC clients** | `app1-client`, `app2-client`, `app3-client` — one per origin | `mfe-shell-client` only. Old three left disabled for rollback. |
| **keycloak-js instances** | One per origin × number of tabs (3 origins = up to 3 simultaneous instances per realm session) | **One per page load** — only the shell |
| **`keycloak.init()` re-entrancy** | One init per JS context, naturally | Module-level `initPromise` gates StrictMode's double-mount |
| **Token storage** | Per-origin keycloak-js memory | Single in-memory store in shell. MFEs ask. |
| **SSO mechanic** | Implicit, cross-origin (realm cookie shared) | N/A — one origin |
| **Refresh strategy** | Each app polled / refreshed against its own client | Shell only; lazy `updateToken(30)` before each fetch |
| **MFE → BFF auth** | Not applicable | `host.auth.getToken()` + `Authorization: Bearer` → Vite proxy → matching BFF |
| **Profile lookup** | `keycloak.loadUserProfile()` in each app | `host.auth.loadProfile()` once, cached in shared `QueryClient` |
| **Auth state in components** | Direct read of each keycloak-js instance | React Context (`useAuth()`) in shell, `host.auth.*` in MFEs |
| **Logout fan-out** | Other tabs notice lazily on their next `check-sso` | `onAuthLogout` → `queryClient.clear()` → atomic, same tick |
| **Login theme** | Per-client login theme (`app-a/b/c`) | One realm-level theme (`mfe-shell`) for every client |

## Pros and cons

### Pros

- **DRY identity**: one Keycloak integration powers three MFEs and the shell.
- **MFE pluggability**: an MFE is just a `MfeComponent` consuming `ShellHost`.
  Replace the shell's IdP, and every MFE keeps working unmodified.
- **Atomic logout**: `queryClient.clear()` invalidates the entire app's
  cached server state without per-feature opt-in.
- **No token sprawl in localStorage**: keycloak-js keeps tokens in memory.
  Refreshing the tab triggers `check-sso` and silent re-auth from the realm
  cookie, instead of restoring a long-lived refresh token from disk.
- **StrictMode-safe init**: module-level promise.
- **Production-grade flow**: Authorization Code + PKCE + JWT BFF validation.
  No bespoke session-id-cookie reinvention.
- **Defense in depth**: `RoleGate` (UX), per-MFE BFF `APP_ALLOWED_ROLES`
  (security boundary), plus JWT audience check — three places before a
  hand-crafted token can hit a forbidden endpoint.

### Cons

- **Single point of failure**: a bug in the shell's `AuthProvider` breaks
  every MFE. The vanilla era's blast radius was one origin.
- **MFE testability**: an MFE needs a `ShellHost` mock to render in isolation.
- **Federation runtime cost**: ~250 KB of shared chunks (React, ReactDOM,
  RR, TanStack Query) shipped to every visitor. SSR-friendly architectures
  avoid this.
- **Trust cookie is process-local**: HMAC key in memory only. Keycloak
  restart logs out the "trusted device" feature for every user. Fine for
  a demo, not for HA.
- **Tab synchronisation depends on Keycloak iframe**: we disabled
  `checkLoginIframe` (it's iframe-unstable across origins). A token expiring
  in tab A doesn't notify tab B until tab B's next fetch fails.
- **Federation = `dist/` bind-mount risk**: the demo-acceptable shortcut of
  serving `vite preview` from a bind-mounted dir means any `rm -rf` on the
  host directly nukes the running server's content. Documented in CLAUDE.md.

## Production security analysis

### Known vulnerabilities / shortcuts taken for the demo

These are deliberate trade-offs for a runnable demo. Each is acceptable
*here*; each would block the same code from going to production.

1. **Plain HTTP everywhere.** Browser ↔ Keycloak, browser ↔ shell, browser
   ↔ MFE remotes, shell ↔ BFFs, SPI ↔ user-service — every leg is cleartext.
   Access tokens, the 6-digit OTP, and credentials all transit unencrypted.
2. **Plaintext password `123` in `user-service`.** `UserController.USERS` holds
   the password fields literally; `verifyCredentials` does a `.equals()`
   comparison. No salt, no hash.
3. **`directAccessGrantsEnabled: true` on `mfe-shell-client`.** The realm
   exposes `grant_type=password`. **Direct grant bypasses the OTP step**
   because the OTP authenticator only runs in the `demo-browser` flow.
   Anyone with `username:password` gets a working token via `curl` with no
   second factor.
4. **OTP code logged at INFO.** `EmailOtpAuthenticator` always writes
   `code: NNNNNN` to Keycloak's log. Anyone with read access to the
   container logs sees every active code. This is intentional (Resend
   sandbox doesn't deliver to most addresses), but logs are not a secure
   side-channel.
5. **Resend sandbox sender `onboarding@resend.dev`.** Only delivers to the
   one address registered with the Resend account; everyone else gets 202
   OK with no email. The OTP log line is the only working delivery.
6. **OTP trust-cookie HMAC key in memory only.** Generated once per
   Keycloak process, lost on restart. No HA — two Keycloak replicas would
   issue cookies the other can't validate.
7. **6-digit OTP, no rate limit.** Entropy = 10⁶. An online brute-force is
   feasible if Keycloak doesn't lock the auth session after N attempts (it
   does — but the lockout policy is the realm default, not tuned).
8. **No CSP, SRI, or COOP/COEP.** The shell loads federation chunks from
   `localhost:5181-5183` over CORS. A compromised MFE container injects
   arbitrary script into the shell's origin. No Subresource Integrity
   hashes — the runtime would happily load a tampered `remoteEntry.js`.
9. **Tokens live in shell-page JS memory.** XSS in *any* MFE = read access
   to the access token via the shell's context. The cascade is bounded by
   the shell's life cycle but the window is the entire authenticated session.
10. **No refresh-token rotation.** Default Keycloak refresh tokens are
    reusable up to their lifetime. A stolen refresh token has the full
    session window before it's noticed.
11. **No CSRF protection on BFFs.** Acceptable because they accept Bearer
    tokens, not cookies — but the moment you migrate to a session-cookie
    BFF (a sensible prod move), you need CSRF tokens.
12. **Admin credentials `admin/admin`.** Keycloak's master realm.
13. **Single audience for every MFE.** A token issued to `mfe-shell-client`
    is valid against every BFF. A token leaked from `mfe-admin`'s context
    works against `bff-client` and `bff-ops` too.
14. **`KEYCLOAK_AUTH_SERVER_URL` mismatch tolerance.** The compose BFFs are
    configured with `http://keycloak:8080/realms/demo-realm` (in-network),
    but tokens minted by the browser carry `http://localhost:8898/...` as
    `iss`. Micronaut's JWT validator currently tolerates this; a stricter
    validator would reject. Make them match in prod (which means a real
    public Keycloak URL).
15. **`docker-compose.dev.yml` redirects shell `/api/*` to the host gateway.**
    Useful for Level 3 dev, but the host gateway is the developer's machine
    — never enable this in shared environments.
16. **No logout from the BFF's perspective.** Logging out clears Keycloak's
    session and the shell's state, but a Bearer token already minted is
    valid until it expires. BFFs do **not** check Keycloak's session state
    on each request; they only validate signatures + claims locally.

### Must-fix before production

These are not opt-in. Anything labelled below would be a security defect on
the day the demo went live.

1. **TLS everywhere.** Edge ingress (Caddy / nginx / cloud load balancer),
   `Strict-Transport-Security`, secure cookies. Backend hops over mTLS or
   private network.
2. **Hash credentials.** Replace the `USERS` map with bcrypt/argon2 hashes
   in a real store; `verifyCredentials` compares hashes; the SPI never sees
   a plaintext password except in transit.
3. **Disable `directAccessGrantsEnabled`** on `mfe-shell-client` — or, if
   you keep direct-grant for machine-to-machine integrations, give it its
   own client with its own auth flow that *does* include MFA. The current
   `grant_type=password` on the shell client is a 2FA bypass.
4. **Strip the OTP-in-log statement** or redact it (`code generated, expires
   in 10min, sent to <hashed email>`). Logs are forwarded to many places.
5. **Verified Resend domain** and a real `RESEND_FROM`. Without this the
   OTP never reaches anyone.
6. **Persistent HMAC key** for the OTP trust cookie. Source from
   `OTP_TRUST_KEY` env var (mounted from a secret manager); generate one
   per environment, rotate periodically.
7. **OTP rate limiting and lockout.** Keycloak's brute-force detector is
   off-the-shelf — enable + tune per realm.
8. **Production realm settings**: change the master-realm `admin` password,
   tighten `accessTokenLifespan` (300s is fine; refresh shorter), enable
   refresh-token rotation, configure brute-force detection.
9. **Public Keycloak hostname**, set `KC_HOSTNAME` / `KC_HOSTNAME_STRICT`,
   so issuer URLs are stable and tokens are minted with the same `iss` the
   BFFs validate against.
10. **Sensible CORS allow-list** on the BFFs — currently each BFF allows
    only `http://localhost:5173`, which is correct for the demo. In prod,
    bind to the real shell origin and reject everything else.
11. **CSP on the shell** allowing only the federation remotes' origins to
    serve scripts, blocking inline scripts (`unsafe-inline` must go).
12. **Subresource Integrity** for federation `remoteEntry.js` or signed
    bundles — otherwise a compromised MFE container can swap in arbitrary
    JS that runs in the shell's auth context.
13. **No `admin/admin` in production**: change the bootstrap admin via
    `KEYCLOAK_ADMIN_PASSWORD` from a secret, then delete that account in
    favour of a federated admin login.

### Should-add (best-practice, not strictly required)

14. **BFF-mediated session.** Instead of the shell holding access tokens in
    JS memory, the BFF (or a thin "auth-BFF" sidecar) exchanges the auth
    code, stores tokens server-side, and gives the browser an HttpOnly
    `Secure` session cookie. The browser never sees a token. XSS goes from
    "session compromise" to "single-request CSRF" (defendable with
    `SameSite=Strict` or CSRF tokens).
15. **TOTP or WebAuthn** instead of email OTP. Email is a transport with a
    delivery dependency (your IdP needs SMTP). TOTP is offline; WebAuthn
    is phishing-resistant.
16. **Refresh-token rotation** enabled at the realm level. Token reuse is
    detectable and triggers session revoke.
17. **Audience-scoped tokens per MFE.** Use Keycloak's token exchange (or
    multiple clients with audience mappers) so `bff-admin` rejects a token
    whose `aud` is `mfe-client`. Today, a single audience covers all three.
18. **Logout endpoint on BFFs** that revokes the refresh token via Keycloak
    admin API. Otherwise a Bearer that was already minted stays valid until
    natural expiry even after the user clicked "Sign out".
19. **Structured audit log of OTP events.** Currently in the same INFO
    stream as everything else. A separate sink with retention rules helps
    investigate later.
20. **Replace direct `keycloak.tokenParsed as any` reads** in
    `AuthProvider` and `useShellHost` with proper JWT-claim typing. Today
    a malformed token surfaces as `undefined` chained reads; better to
    validate at the boundary.
21. **`onTokenExpired` handler** that logs out the user explicitly if the
    refresh attempt loses, rather than waiting for the next fetch to fail.

### Nice-to-have

22. **Front-channel logout** via Keycloak's `frontchannelLogout` setting on
    the client, so other open tabs across the same realm see logout
    immediately. Currently disabled because it relies on cross-origin
    iframes which don't pair well with a strict CSP.
23. **WebAuthn fallback in the same demo-browser flow** — a third
    authenticator after the OTP step, picked automatically by the user's
    enrolled credentials.
24. **Session-introspection middleware on BFFs** for very-high-sensitivity
    endpoints (e.g. `bff-admin`'s emergency-actions panel): introspect the
    token against Keycloak per request to catch revocations between
    minting and natural expiry.
25. **Step-up auth** for sensitive operations — even after login, require
    a fresh OTP before an admin lockdown action. `acr` claim plumbing.
26. **Token binding (DPoP)** so a stolen Bearer can't be used outside the
    original client.
27. **Real OIDC discovery wiring** for the BFFs — instead of hardcoding
    `KEYCLOAK_AUTH_SERVER_URL`, fetch the realm's
    `.well-known/openid-configuration` and let Micronaut's security
    auto-configure JWKS, issuer, and the rest.
28. **Federation remote signing.** Sign the MFE bundles at build time and
    have the shell verify the signature before instantiating the module.
29. **OTP delivery channels per user preference** (email / SMS / push) —
    each is an additional authenticator implementation.

## Files to look at when changing the flow

| If you want to change… | Open… |
|---|---|
| Init parameters, `check-sso` strategy, redirect URI | `frontend/apps/shell/src/auth/AuthProvider.tsx` |
| What `host.auth.*` exposes to MFEs | `frontend/packages/shell-api/src/index.ts` + `frontend/apps/shell/src/auth/useShellHost.ts` |
| Login styling | `keycloak/themes/mfe-shell/login/resources/css/theme.css` |
| OTP form HTML | `keycloak-provider/src/main/resources/theme-resources/templates/email-otp.ftl` |
| OTP generation / trust cookie | `keycloak-provider/src/main/java/com/example/keycloak/EmailOtpAuthenticator.java` |
| OTP email delivery | `keycloak-provider/src/main/java/com/example/keycloak/ResendClient.java` |
| Per-MFE role mapping (`allowedMfes`) | `bff/src/main/java/demo/UserController.java#whoami` |
| Per-MFE access enforcement | `bff/src/main/java/demo/UserController.java#getUser` + `APP_ALLOWED_ROLES` env on each BFF |
| Realm flow / client / mappers | `keycloak/realm-export.json` (and remember it's `IGNORE_EXISTING` — admin API for live changes) |
| User store | `user-service/src/main/java/demo/userservice/UserController.java` |
