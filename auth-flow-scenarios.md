# Login / Logout flow coverage report — P1 ↔ domains via Keycloak

Comprehensive walk-through of every authentication path through the
demo's identity tier, with what works, what doesn't, and why. Written
against branch `petarnenov/domains-refactor` (keycloak-demo) and
`team/petarnenov/keycloak-whitelabel-poc` (geowealth), state at
2026-05-24 after SSO Phase 15 (silent OIDC re-auth on cold P1 load).

## 0. Actors and where state lives

| Actor | Origin | State held | Cookie / storage |
|---|---|---|---|
| **P1 React + Tomcat** | `localhost:8888` (Node proxy → Tomcat 8080) | `LoggedUser` in `HttpSession` (key `loggedAdviser`) | `JSESSIONID` (HttpOnly, session-scoped — clears on browser close) |
| **Keycloak** | `auth.geowealth.int:5180` (nginx → keycloak:8080) | SSO user session + federated identity link | `KEYCLOAK_IDENTITY`, `KEYCLOAK_SESSION` (persistent, `Max-Age = ssoSessionMaxLifespan`) |
| **Trading SPA** | `trading.geowealth.int:5185` | OIDC token in memory + keycloak-js singleton | Trading-domain cookies are non-material; tokens live in JS memory |
| **Billing SPA** | `billing.geowealth.int:5184` | Same as trading | Same |

Realm timing knobs (`keycloak/realm-export.json`):

| Setting | Value | Effect |
|---|---|---|
| `accessTokenLifespan` | 300s (5 min) | OIDC access token TTL — SPA renews via refresh token transparently |
| `ssoSessionIdleTimeout` | 1800s (30 min) | KC kills session after this much idle time across all clients |
| `ssoSessionMaxLifespan` | 36000s (10h) | Hard ceiling on KC session lifetime; also the persistent-cookie `Max-Age` |
| Tomcat `session-timeout` | 25200s (7h) | P1's `HttpSession` idle timeout |

The mismatch (KC 10h cap, P1 7h cap, KC idle 30 min) is **why the
Phase 15 silent-SSO fix exists**: sessions don't expire in lockstep.

## 1. Login scenarios

### L1. Cold open of a domain SPA (trading or billing)

Entry: user navigates to `https://trading.geowealth.int:5185/` with
no cookies anywhere.

```
Browser                       Trading SPA              Keycloak                P1 (localhost:8888)
─────────                     ───────────              ────────                ───────────────────
GET https://trading… ────────►
                              keycloak.init({ check-sso })
                              hidden iframe → KC
                              ────────────────────────►
                                                       no KC session, iframe responds "unchanged"
                              ◄────────────────────────
                              init() resolves false
                              ↓
                              keycloak.login({ idpHint: 'p1' })
                              ↓
                              redirect to KC /auth?kc_idp_hint=p1
                              ────────────────────────►
                                                       sees kc_idp_hint=p1 → SAML AuthnRequest
                                                       302 to P1 /saml/idp/sso.do?SAMLRequest=…
                                                                              ───────────────────►
                                                                              getLoggedUser() == null
                                                                              REDIRECT_MAPPING stashed
                                                                              302 to /#login
                              ◄──────────────────────────────────────────────────
                              user types tim1 + password
                              POST /react/login.do
                                                                              ───────────────────►
                                                                              LoginAction → LoggedUser
                                                                              REDIRECT_MAPPING set
                                                                              meta sent back to SPA
                              window.location.href = /saml/idp/sso.do?…
                                                                              ───────────────────►
                                                                              IdpSsoAction: has LoggedUser
                                                                              builds signed SAML Response
                                                                              auto-submit form to KC ACS
                                                       ◄────── browser POSTs
                                                       first-broker-login (auto-link by email)
                                                       issues OIDC code → trading redirect_uri
                              ◄────────────────────────
                              code → token exchange → token in memory → dashboard
```

**End state**: P1 session ✓, KC session ✓, Trading token ✓.

**Code touched**: `AuthProvider.tsx` `init({ onLoad: 'check-sso' })` →
`keycloak.login({ idpHint: 'p1' })` → KC `auth` endpoint → P1
`IdpSsoAction.redirectToLogin` (stashes `REDIRECT_MAPPING`) → user form
→ `LoginAction.loginUser` → `ReactIndexAction.login()` returns
`DownloadMetaDataJTO` → `appService.js#login` `namespace === '/saml/idp'`
branch → `window.location.href` → `IdpSsoAction` 2nd pass → KC ACS →
`AuthProvider`'s init catch-block or success path.

**Covered**: ✓ Standard path, well-trodden.

### L2. Cold open of a domain SPA when KC session already alive

Entry: user already authenticated in this browser earlier today (KC
persistent cookies survived browser restart, or a sibling SPA created
the session a minute ago). Trading SPA newly mounted.

```
Browser → Trading SPA → keycloak.init({ check-sso })
SPA hidden iframe → KC
KC sees KEYCLOAK_IDENTITY cookie → "session alive, here's the token"
SPA gets token silently, dashboard renders. NO P1 hop.
```

**End state**: KC ✓, Trading ✓, P1 may or may not have a session
(depends on whether earlier flow created one).

**Code touched**: keycloak-js only, no server-side hop.

**Covered**: ✓ Native keycloak-js behavior.

### L3. Cold open of P1 directly (`localhost:8888`)

Entry: user types `localhost:8888` in the browser, no P1 session, no
KC session.

```
Browser ──► localhost:8888/
            React mounts, calls /react/isUserLoggedIn.do
            P1: no LoggedUser → objectType: "redirect"
            React (Phase 15 path): hash has no silent_failed=1
            ↓
            window.location.replace('/saml/idp/silent-sso.do?return_to=/')
            ↓
            SilentSsoAction: stash state, redirect to KC prompt=none
            ↓
            KC: no session → error=login_required → callback
            ↓
            OidcCallbackAction: error param → redirect to /#login?silent_failed=1
            ↓
            React reads silent_failed=1 → skips silent SSO retry → shows login form
            user types creds → standard LoginAction path → platformOne (NO SAML resume,
              no REDIRECT_MAPPING because nothing stashed it)
```

**End state**: P1 ✓, KC ✗ (since LoginAction is purely P1-local, KC
stays sessionless until the user visits a domain SPA).

**Covered**: ✓ The empty-state credential path works; silent SSO
correctly bows out via the loop-guard.

### L4. P1 sidebar link to a domain (`/saml/idp/sso.do?RelayState=…`)

Entry: user clicks the "Demo Billing" or "Demo Trading" sidebar tile
in P1's platformOne UI.

```
Browser ──► localhost:8888/saml/idp/sso.do?RelayState=…  (P1 has session)
            IdpSsoAction: LoggedUser present
            ↓
            builds signed SAML Response → auto-submit form to KC ACS
            ↓
            KC creates session for OIDC client → 302 to SPA redirect_uri
            ↓
            SPA receives code → exchange → token → dashboard
```

**End state**: P1 ✓ (already was), KC ✓ (created), Trading/Billing ✓.

**Code touched**: `useIntegrationLinks.js` (outside this repo) builds
the URL with `kc_idp_hint=p1` and proper `redirect_uri`; KC handles
SP-init brokering; `IdpSsoAction` SAML-Responds.

**Covered**: ✓ Standard IdP-init-like path.

### L5. Cold P1 visit, **P1 session dead, KC session alive** (Phase 15 target)

Entry: user logged into trading SPA earlier in the day; trading still
works (KC session alive); P1's `JSESSIONID` was a session cookie that
the browser dropped on close, OR P1's `HttpSession` timed out after 7h,
OR Tomcat got redeployed.

```
Browser ──► localhost:8888/
            React → /react/isUserLoggedIn.do → "redirect"
            ↓
            window.location.replace('/saml/idp/silent-sso.do?return_to=/')
            ↓
            SilentSsoAction → KC prompt=none
            ↓
            KC: KEYCLOAK_IDENTITY cookie sent → session alive → returns code
            ↓
            OidcCallbackAction: token exchange → id_token → preferred_username=tim1
            ↓
            UserManager.lookupByUsername("tim1") → tim1 User
            ↓
            put LOGGED_USER + LOGGED_ADVISER + ConversationManager + firm props in HttpSession
            ↓
            redirect to /  → React re-runs isUserLoggedIn → returns LoggedUserJTO
            ↓
            platformOne renders. NO credential form shown.
```

**End state**: P1 ✓ (silently restored), KC ✓ (was already), domain
SPAs unaffected.

**Code touched**: `appService.js` Phase 15 branch, `SilentSsoAction`,
`OidcCallbackAction`, realm `p1-self-client`.

**Covered**: ✓ The reason Phase 15 exists.

### L6. Cold P1 visit, both P1 and KC sessions dead

Entry: same browser as L5 but KC also timed out (idle > 30 min and
nobody touched KC).

Same flow as L5, except KC returns `error=login_required` instead of a
code. `OidcCallbackAction` redirects to `/#login?silent_failed=1`,
React skips the silent retry and shows the credential form.

**Covered**: ✓ Graceful degradation.

### L7. First-time login for a brand-new user

Entry: a P1 user who has never been federated through KC before.

Same as L1 mechanics, but at the KC step Keycloak runs the
`p1-first-broker-login` flow: auto-link by email if a matching KC user
exists, otherwise create a fresh KC user and link.

**Covered**: ✓ Realm export configures the flow to auto-link silently
(`updateProfileFirstLoginMode: off`, no required actions).

### L8. SPA-to-SPA cross-app (trading → billing same browser)

Entry: user is logged into trading SPA (KC session alive). Opens
`https://billing.geowealth.int:5184/` in a new tab.

```
Billing SPA → keycloak.init({ check-sso })
hidden iframe → KC sees session → token returned silently
Billing dashboard renders. NO P1 hop, NO user interaction.
```

**Covered**: ✓ This is the canonical "single sign-on" payoff.

### L9. P1 direct login, then visit domain SPA

Entry: user logged into P1 via credential form (L3 path), so P1 ✓ but
KC ✗. Opens `https://trading.geowealth.int:5185/`.

Trading SPA → keycloak.login({ idpHint: 'p1' }) → KC has no session →
SAML AuthnRequest → P1 sees `LoggedUser` (alive) → emits SAML Response
→ KC creates session → SPA gets token. Identical to L4 but initiated
SP-side instead of from P1's sidebar.

**Covered**: ✓ SP-init recovery path.

## 2. Logout scenarios

### O1. Sign out from a domain SPA

Entry: user clicks "Sign out" in trading's footer.

```
SPA → keycloak.logout({ redirectUri: '/' })
   ↓
keycloak-js builds /protocol/openid-connect/logout?id_token_hint=…&post_logout_redirect_uri=…
   ↓
Browser navigates to KC logout endpoint (top-level GET, no confirmation
  because id_token_hint is present and the post-logout URI matches
  realm-export's "post.logout.redirect.uris")
   ↓
KC: invalidates KC session
   ↓
KC fans out logout to associated clients:
  - p1 IdP: sends SAML LogoutRequest to singleLogoutServiceUrl
    = http://localhost:8888/saml/idp/slo.do (via the browser, Redirect-binding)
  - other OIDC clients with backchannelLogoutUri: backchannel POST (we
    have none configured, so this is a no-op for sibling SPAs)
   ↓
P1 IdpSloAction: parses LogoutRequest, invalidates P1 HttpSession,
  returns LogoutResponse to KC
   ↓
KC: returns LogoutResponse aggregation, then 302 to post_logout_redirect_uri
   ↓
Trading SPA loads at /. keycloak.init({ check-sso }) → no session →
  redirects to login again (per L1 cold path).
```

**End state**: KC ✗, P1 ✗, Trading token cleared. Other SPAs (if open
in other tabs) still hold an in-memory token until the next refresh or
API call exposes the 401.

**Covered**: ✓ End-to-end SLO works for trading. Same for billing.

### O2. Logout from P1 (`Logout` link in platformOne)

Entry: user clicks Logout while at `localhost:8888`.

```
appService.js:
  window.location.href = '/saml/idp/initiate-slo.do'
   ↓
IdpInitiateSloAction:
  reads getLoggedUser() (still alive — we navigate, don't AJAX-call /react/logout.do first)
  invalidates HttpSession
  builds signed SAML LogoutRequest
  returns auto-submit POST form targeting KC broker SLO endpoint
   ↓
Browser POSTs LogoutRequest to /realms/demo-realm/broker/p1/endpoint
   ↓
KC: validates signature, finds the brokered session, kills it
   ↓
KC: emits LogoutResponse back to P1 slo.do (Redirect-binding, via the browser)
   ↓
IdpSloAction sees SAMLResponse param → short-circuits to localhost:8888/#login
```

**End state**: P1 ✗, KC ✗, sibling SPA tokens dangling in memory until
the user reloads them.

**Code path**: `appService.js#logout`, `IdpInitiateSloAction`,
`IdpSloAction.execute` (short-circuit branch for inbound SAMLResponse).

**Covered**: ✓ Zero-click — no Keycloak "are you sure?" confirmation
page because Phase 14 replaced the OIDC `/logout` redirect with an
explicit SAML LogoutRequest carrying our own NameID.

### O3. KC idle timeout (server-only)

Entry: nobody touched KC for >30 min (`ssoSessionIdleTimeout`).

KC silently invalidates the session server-side. No client gets
notified (back-channel logout would require a configured URL; we
don't ship one for the demo clients).

**Effect**:
- Browser still has `KEYCLOAK_IDENTITY` cookie until it expires; KC
  treats it as orphaned on next contact.
- Trading/Billing SPAs continue with their in-memory token. On the
  next refresh-token attempt (token has 5-minute TTL), KC will reject
  → keycloak-js fires `onTokenExpired`/`onAuthRefreshError` →
  AuthProvider `startLogin()` catch handler runs → SP-init flow.
- P1 keeps its independent `HttpSession` until P1's own timeout (7h)
  hits. Disconnect: P1 thinks the user is signed in even though KC
  doesn't.

**Covered**: ⚠️ **Partially**. The mismatch is one of the targeted
cases for Phase 15 silent SSO when going from P1 → KC, but the
**reverse** (P1 still alive, KC dead) is not — see Gaps §1.

### O4. P1 session timeout (server-only)

Entry: P1's `HttpSession` exceeds Tomcat's 7h idle.

Tomcat fires its standard session-destroyed event. Nothing else gets
notified — no SAML LogoutRequest to KC, no domain SPA awareness.

**Effect**:
- P1 user appears signed out next time they touch P1.
- KC session keeps running.
- Domain SPAs unaffected (they live on KC, not P1).
- Phase 15 silent SSO recovers P1 transparently on next visit.

**Covered**: ✓ Symmetric to L5 — the bug Phase 15 was built for.

### O5. Browser restart / reopened tab

Entry: user closes the browser window or quits the browser, then comes
back.

| Cookie | Cleared? |
|---|---|
| `JSESSIONID` (P1) | Yes — Set-Cookie didn't specify `Max-Age` / `Expires` |
| `KEYCLOAK_IDENTITY` / `KEYCLOAK_SESSION` (KC) | No — KC sets `Max-Age = ssoSessionMaxLifespan` |
| SPA in-memory token | Yes — JS heap is gone |
| SPA `sessionStorage` keycloak-js state | Yes — sessionStorage doesn't persist past tab close |

So after restart: P1 ✗, KC ✓, SPAs ✗ (in-memory tokens gone). Same
state as L5/L8 — Phase 15 silent SSO restores P1; SPAs re-init via
keycloak-js check-sso and pull a fresh token from KC silently.

**Covered**: ✓ Phase 15 handles P1; keycloak-js handles SPAs natively.

### O6. KC admin force-logout (admin clicks "Logout all sessions" or per-user revoke)

Entry: an admin invalidates the user's KC session via the KC admin
console.

KC fan-out runs same as O1, including SAML LogoutRequest to P1's
`/saml/idp/slo.do`. P1 HttpSession dies. SPAs continue with cached
tokens until refresh fails, then fall through to login.

**Covered**: ✓ End-to-end propagation via the IdP-broker SLO chain.

### O7. Server-side P1 admin invalidates session manually

Entry: an operator drops the user's `HttpSession` via Tomcat manager,
DB cleanup, or `kill -9` of Tomcat.

Same as O4 effect-wise.

**Covered**: ✓ Phase 15 recovers on next visit.

## 3. Edge cases / failure modes

### E1. Stale `?code=…` URL after browser restart

Entry: user got a code-grant redirect URL from a prior session,
sessionStorage was cleared, browser navigates to
`https://trading.geowealth.int:5185/?code=…&state=…`.

`keycloak.init()` fails with state mismatch (state cookie was wiped).
Handled in `AuthProvider.tsx` lines 54-61: catch-block fires
`keycloak.login({ idpHint: 'p1' })`, kicking a clean SP-init flow.

**Covered**: ✓ Defensive fallthrough in place.

### E2. KC reachable but JVM truststore doesn't trust mkcert CA

Entry: P1 server calls KC token endpoint over TLS, JVM PKIX path
building fails.

`OidcCallbackAction.exchangeCodeForIdToken` returns null on any
connection error, log emits a warning, `failToLogin` redirects to
`/#login?silent_failed=1`. The credential form still works.

**Covered**: ✓ Doc'd in OidcCallbackAction class comment; user runs
`sudo keytool -importcert …` once to fix.

### E3. KC returns `code` but P1 cannot resolve the user

Entry: KC `sub`/`preferred_username`/`email` claims don't map to any
P1 user (e.g., user was deleted from P1 while KC still had a session).

`OidcCallbackAction.resolveUser` returns null after trying all three
attributes. Log: `no P1 user matches claims: …`. User → login form.

**Covered**: ✓ Graceful fallback.

### E4. SAML signature on SLO mismatch (rotated P1 SP cert)

Entry: Tomcat's SAML signing key was regenerated but the realm's
`signingCertificate` still points to the old key.

KC rejects the LogoutRequest with `Logout request signature mismatch`.
The logout never completes server-side — user sees an error page.

**Covered**: ⚠️ Manual operator intervention required — update the
realm via admin API with the new cert. There's no auto-rotation. Doc:
`CLAUDE.md` §"Non-obvious runtime gotchas".

### E5. Multi-tab race on logout

Entry: trading and billing both open in separate tabs, user signs out
from trading.

Trading: O1 flow completes, KC kills session, P1 SLO runs.
Billing: still holds its in-memory token. The token is valid until its
5-minute exp clock runs out. Any API call from billing during this
window will succeed using the dangling token — KC's session being
dead doesn't synchronously invalidate already-issued JWTs.

After the access token expires, keycloak-js attempts refresh, refresh
fails, billing fires its `startLogin()` catch handler → SP-init flow.

**Covered**: ⚠️ **Partial**. There's a 0-5 minute window of stale
access from sibling SPAs. Real fix: configure
`backchannelLogoutUrl` on each SPA client AND deploy a BFF endpoint
that nukes the SPA's local state. Not currently shipped — this is a
demo-tier accepted compromise.

### E6. Incognito / different browser profile

Entry: user opens P1 in an incognito window (no cookies anywhere).

Phase 15 silent SSO triggers, KC returns `login_required` (no cookie),
fallback to credential form. Identical to L6.

**Covered**: ✓ Graceful — incognito by design is isolation, so this
is the correct behavior, not a regression.

### E7. Cross-device

Entry: user logged into trading on a laptop, opens P1 on a phone.

Different browsers, different cookie jars → KC sees a different
device, no session. Silent SSO → `login_required` → credential form.

**Covered**: ✓ Correct behavior for a cookie-bound SSO. Cross-device
SSO would need an external IdP with device-independent sessions
(Okta, Auth0, an OAuth proxy with shared session store).

## 4. Coverage matrix

| # | Scenario | Path | Status |
|---|---|---|---|
| L1 | Cold SPA, no sessions | Standard SP-init | ✓ |
| L2 | Cold SPA, KC session alive | keycloak-js check-sso | ✓ |
| L3 | Cold P1, no sessions | silent SSO → login_required → form | ✓ |
| L4 | P1 sidebar to SPA | IdpSsoAction emit | ✓ |
| L5 | Cold P1, KC alive | Phase 15 silent SSO restore | ✓ |
| L6 | Cold P1, both dead | silent SSO → form | ✓ |
| L7 | First federated login | `p1-first-broker-login` auto-link | ✓ |
| L8 | Trading → Billing same browser | keycloak-js silent | ✓ |
| L9 | P1-direct login → SPA | SP-init via P1 session | ✓ |
| O1 | SPA sign-out | OIDC logout + SAML SLO to P1 | ✓ |
| O2 | P1 logout | IdpInitiateSloAction + SAML | ✓ |
| O3 | KC idle timeout | server-side, P1 desync | ⚠️ |
| O4 | P1 idle timeout | Phase 15 restores on next visit | ✓ |
| O5 | Browser restart | KC persistent + Phase 15 | ✓ |
| O6 | KC admin force-logout | SLO fan-out | ✓ |
| O7 | P1 admin/server kill | Phase 15 restores | ✓ |
| E1 | Stale `?code=` URL | AuthProvider catch-block | ✓ |
| E2 | JVM trust missing | Graceful → form | ✓ |
| E3 | KC user not in P1 | Graceful → form | ✓ |
| E4 | Stale P1 SP cert | Operator action needed | ⚠️ |
| E5 | Multi-tab race | 0-5 min stale window | ⚠️ |
| E6 | Incognito | Correct by design | ✓ |
| E7 | Cross-device | Correct by design | ✓ |

## 5. Known gaps

### Gap 1 — KC dies before P1 (O3)

When KC's session goes (idle timeout, admin revoke) but P1 hasn't
heard about it, P1's `HttpSession` survives. Next time the user
touches P1, they're "logged in" per P1 but cannot federate out to a
SPA because KC has no session and SAML round-trip will trigger fresh
login.

Operator visibility: P1 logs show user is active; KC dashboard says
session ended. Confusing.

**Fix path**: configure back-channel logout from KC to P1 — when KC
kills a session, POST to `localhost:8888/saml/idp/back-channel-logout.do`
with `logout_token` (an OIDC back-channel logout JWT). New action
parses, finds the P1 session by sub, invalidates it. Not built yet.

### Gap 2 — Stale SAML SP signing cert (E4)

When the P1 SP key rotates, the realm needs a manual `PUT` to update
the `signingCertificate`. No automation.

**Fix path**: write a `keystore-rotation` script that compares the
on-disk cert to the realm config and re-uploads via admin API when
they diverge. Could be a `start.sh` post-up hook.

### Gap 3 — Sibling-SPA stale access after logout (E5)

A SPA whose tab is open during another SPA's logout keeps using its
cached token for up to one access-token lifespan (5 min).

**Fix path A** — front-channel logout: set
`frontchannelLogout: true` on each OIDC client + a `Logout URL` per
client. KC will embed an iframe pointing at each client's logout URL,
each SPA receives the signal and nukes its in-memory state. Adds a
per-SPA logout HTML page.

**Fix path B** — back-channel logout to a BFF: configure
`backchannelLogoutUrl` on each OIDC client pointing at the
domain BFF. BFF blacklists the JWT's `jti` claim or `sid`. SPA-side
calls then 401 on the BFF, which propagates the logout. More moving
parts but no browser involvement.

Neither is shipped — both add complexity beyond what the demo needs.

### Gap 4 — KC `accessTokenLifespan` = 5 min creates noticeable refresh churn

Realm export sets `accessTokenLifespan = 300`. Every 5 minutes the
SPA's keycloak-js silently rotates. If the network blips during one
of those rotations and recovers shortly after, the SPA may log out
prematurely.

**Fix path**: bump to 900 (15 min) for the demo, or implement a
refresh retry policy in keycloak-js. The default is already 5 min, so
this is a Keycloak default rather than a demo choice — leaving as-is.

### Gap 5 — `KC_HOSTNAME` mismatch in deployed envs

KC is configured for `https://auth.geowealth.int:5180`. The realm's
SAML IdP descriptor references `http://localhost:8888` for P1 endpoints.
If anything in the deployment changes (port shift, dockerized P1,
public hostname), every URL in the realm needs an audit.

**Fix path**: parameterize via `P1_IDP_*` and `P1_OIDC_*` env vars
(already done for the new code) and add a smoke-test action that
verifies each registered URL responds.

## 6. Identified production blockers (when this graduates from demo)

- **JVM truststore** must trust whatever cert KC presents — either
  publicly-signed or a private CA imported at the OS / JVM level
- **P1's `HttpSession` cookie should be persistent** (set `Max-Age` in
  Tomcat) to align with KC's persistent identity cookie; otherwise
  Phase 15 fires on every browser restart instead of only on real
  session-loss events
- **Back-channel logout** wiring (Gap 1, Gap 3) — currently sessions
  drift between identity tiers
- **SAML cert rotation tooling** (Gap 2) — manual process today
- **Replace `lookupByUsername(preferred_username)` fallback in
  `OidcCallbackAction`**. KC's `preferred_username` is the SAML
  NameID = P1 user UUID by design, but KC users CAN have their
  username manually changed in the KC admin. Add an explicit
  protocol-mapper claim `p1_uuid` on `p1-self-client` that pulls from
  the federated identity record, so the cross-link doesn't depend on
  whatever `preferred_username` happens to be
