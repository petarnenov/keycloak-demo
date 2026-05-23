# P1 SAML IdP — "Not logged into P1" gap report

> **Status — implemented (Phase 12, 2026-05-23).** See WIP-SSO.md
> § Phase 12 for the verification matrix and deploy recipe.
>
> Two divergences from this document's "Recommended fix (sketch)":
>
> 1. Instead of a new session key + a `LoginAction` patch, the fix
>    reuses P1's existing `REDIRECT_MAPPING` post-login plumbing
>    (`LoginInterceptor` stash + `ReactIndexAction.login` resume).
>    Zero `LoginAction` modifications needed.
> 2. The redirect target defaults to
>    `http://localhost:8888/#login` (webpack-dev-server), not
>    `/react/indexReact.do#login` on Tomcat. In dev the React bundle
>    is served only by webpack-dev-server — Tomcat hosts the JSP
>    shell but `/react/build/js/app.min.js` 404s. Redirecting to
>    Tomcat directly produced a white screen because the JSP loaded
>    but its bundle URLs were dead. Override
>    `P1_IDP_LOGIN_REDIRECT_URL` per environment (prod uses the
>    Tomcat-relative path with the bundle baked into the WAR).
>
> The "Recommended fix (sketch)" section is kept for historical
> context.

## Symptom

On the Keycloak login page (`http://localhost:8898/realms/demo-realm/...`)
the user clicks **"Sign in with P1"**. Keycloak emits a signed
`<samlp:AuthnRequest>` and (per realm config `postBindingAuthnRequest=true`)
POSTs it to P1's IdP SSO endpoint:

    http://localhost:8080/saml/idp/sso.do

When the user has **no active P1 session**, the browser lands on a bare
HTTP 401 page with the literal text:

> Not logged into P1. Visit /react/login.do then retry.

Expected behaviour (per SAML SP-init contract): the IdP should silently
redirect the user to its own login screen, carry the inbound SAMLRequest
+ RelayState across the login, and after successful authentication
resume the SSO response back to Keycloak. The user never sees the gate.

## Root cause

`IdpSsoAction.execute()` in P1
(`~/geowealth/src/main/java/com/geowealth/saml/idp/IdpSsoAction.java:128-138`)
short-circuits on the no-session branch with a hardcoded 401:

```java
LoggedUser logged = getLoggedUser();
if (logged == null || logged.getUser() == null) {
    // No active P1 session — for the SP-init flow this should
    // redirect to /react/login.do with a return-here hint;
    // for IdP-init flow it's user error (clicked link without
    // session). Keep it simple at scaffold level: 401 + a
    // pointer to the login page. The next iteration adds the
    // login-then-resume dance.
    LOG.info("IdpSsoAction: no active P1 session — returning 401");
    return writeText(res, 401, "Not logged into P1. Visit /react/login.do then retry.");
}
```

The class Javadoc (lines 41-46) already documents the intended
behaviour:

> SP-initiated (Keycloak → "Sign in with P1"): Keycloak builds
> `<samlp:AuthnRequest>`, POSTs to this endpoint. P1 still requires
> an active session — **falls through to its existing
> `/react/login.do` if not authenticated, then returns to this
> endpoint via Struts session redirect chain.**

So the contract is documented but the **"falls through … then returns"**
half has not been written yet. The inline comment at line 130-135 is
explicit: this branch is a scaffold placeholder waiting for the
"login-then-resume dance" to be implemented.

## Why it currently behaves as the user observed

The 401 was deliberately accepted for the POC because:

- Phase 8 (the working end-to-end flow today) exercises only the
  **already-logged-in** SP-init path, and Phase 3 exercised the
  **IdP-init** path (sidebar link) — also already-logged-in.
- The "log into P1 first, then come back" path is the third corner of
  the matrix and was not exercised by any phase that actually shipped.
  See WIP-SSO.md line 320:
  `sso.do (no session) → "Not logged into P1" text   ✅` — the test
  matrix treats the plain-text gate as expected output (the JUnit
  `SamlIdpEndpointsIT` even asserts on the string).

So the user's intuition is correct: the production SAML behaviour
**should** show the P1 login page, but the demo IdP doesn't do that
yet — it returns a plain-text gate by design, with a TODO inline.

## What a proper SP-init no-session handler must do

A SAML 2.0 SP-init flow with an unauthenticated browser at the IdP
follows this sequence:

1. **Stash the inbound SAML state** in the HTTP session:
   `SAMLRequest`, `RelayState`, and the binding (POST vs Redirect).
   Keycloak depends on `InResponseTo` correlation, so the
   AuthnRequest ID must survive the login round-trip — the simplest
   way is to keep the raw base64 payload in session and re-parse it
   after login.

2. **Redirect to the P1 login screen** — for this codebase that is
   the React SPA at `/react/indexReact.do#login` (or `/react/login.do`
   if that's the canonical action). Either:
   - `res.sendRedirect("/react/indexReact.do#login?continue=/saml/idp/sso.do")`
     so the React app knows it must navigate back after success, or
   - just `sendRedirect` to the login URL and rely on a session
     attribute (the `LoginAction` already supports returning to a
     stashed `forwardUrl`).

3. **Resume after login.** After `LoginAction` successfully creates
   the P1 session, it either:
   - redirects browser back to `/saml/idp/sso.do` (no query params —
     the SAMLRequest is in session and `IdpSsoAction` reads it from
     there instead of the request param), **or**
   - re-POSTs the original SAMLRequest + RelayState to
     `/saml/idp/sso.do` via an auto-submit form (mimicking the
     browser's first POST so the existing param-based code path works
     unchanged).

4. **Continue the existing happy-path code.** `getLoggedUser()` now
   returns non-null, `collectAttributes` runs, signed Response goes
   to Keycloak's ACS, browser lands in the keycloak-demo shell.

The smallest change that preserves the rest of `IdpSsoAction`
intact is option (a) in step 3 + adding a `session.getAttribute`
fallback at the top of `execute()` so a resumed request finds its
stashed params.

## Recommended fix (sketch)

Replace lines 128-138 of `IdpSsoAction.java` with:

```java
LoggedUser logged = getLoggedUser();
if (logged == null || logged.getUser() == null) {
    var req  = getServletRequest();
    var resp = getServletResponse();

    // Stash inbound SAML state for the resume step.
    if (SAMLRequest != null && !SAMLRequest.isBlank()) {
        req.getSession(true).setAttribute("saml.idp.SAMLRequest", SAMLRequest);
    }
    if (RelayState != null && !RelayState.isBlank()) {
        req.getSession(true).setAttribute("saml.idp.RelayState", RelayState);
    }
    // Tell LoginAction where to come back to once authenticated.
    req.getSession(true).setAttribute("loginRedirect", "/saml/idp/sso.do");

    LOG.info("IdpSsoAction: no P1 session — redirecting to login (stashed SAMLRequest={}, RelayState={})",
        SAMLRequest != null, RelayState);

    try {
        resp.sendRedirect("/react/indexReact.do#login");
    } catch (Exception e) {
        LOG.error("IdpSsoAction: redirect to login failed", e);
        return writeText(resp, 500, "P1 login redirect failed");
    }
    return NONE;
}
```

…and at the top of `execute()` (or in the Struts setter chain) read
the stashed values back when the inbound request has none:

```java
if (SAMLRequest == null) {
    SAMLRequest = (String) req.getSession().getAttribute("saml.idp.SAMLRequest");
    req.getSession().removeAttribute("saml.idp.SAMLRequest");
}
if (RelayState == null) {
    RelayState = (String) req.getSession().getAttribute("saml.idp.RelayState");
    req.getSession().removeAttribute("saml.idp.RelayState");
}
```

`LoginAction` on the P1 side already honours a `loginRedirect`
session attribute on successful login — confirm by reading
`LoginAction.execute()` before relying on the attribute name above;
adjust to whatever P1 uses (`forwardUrl`, `originalUrl`, etc.).

## Side considerations

- **POST → Redirect change of method.** After login P1 will issue a
  302 GET to `/saml/idp/sso.do`. Today `IdpSsoAction` is registered
  for both GET (IdP-init sidebar) and POST (SP-init inbound), so the
  method change is fine — but the resume path means the second
  invocation has no request-param `SAMLRequest`, hence the session
  read-back above.

- **AuthnRequest replay window.** Keycloak's AuthnRequest carries
  an `IssueInstant` and the broker enforces a tolerance (~5 min by
  default). If the user dawdles on the P1 login screen past that
  window, `InResponseTo` is stale and Keycloak rejects the resume
  with `invalid_saml_response`. Mitigation: refuse to resume if the
  stashed AuthnRequest is older than the realm tolerance (re-parse
  → check `IssueInstant`) and redirect the user back to the Keycloak
  login so the broker mints a fresh request.

- **Replay protection (Phase 6b).** `SamlRequestIdCache` is wired
  for `IdpSloAction` today. If we add it to `IdpSsoAction` the
  resume must not re-insert the same ID a second time — record the
  ID only on the first inbound, not on the resume.

- **CSRF / open-redirect on `loginRedirect`.** The stashed redirect
  target must be constrained to `/saml/idp/sso.do` (or a small
  whitelist). Letting an attacker craft a query-string that lands
  on an external URL after login would be a classic open-redirect.
  Hardcoding the constant in the setter is enough.

- **JUnit assertion churn.** `SamlIdpEndpointsIT` currently asserts
  the 401 + "Not logged into P1" text. After the fix it should
  assert a 302 to `/react/indexReact.do#login` and a session
  attribute `saml.idp.SAMLRequest` being present. The corresponding
  `WIP-SSO.md` verification matrix line also needs an update.

## Where to make the change

| File | Repo | Change |
|---|---|---|
| `src/main/java/com/geowealth/saml/idp/IdpSsoAction.java` | `~/geowealth` | replace lines 128-138 with the redirect block; add session read-back at top of `execute()` |
| `src/test/java/com/geowealth/saml/idp/SamlIdpEndpointsIT.java` | `~/geowealth` | flip the "no session" assertion from text-body to 302 + Location header |
| `WIP-SSO.md` | this repo | update the Phase 6 verification matrix row for `sso.do (no session)` |
| `LOGIN.md` (optional) | this repo | document the resume hop in the SAML overview |

No realm-export / Keycloak side change is needed — the broker
doesn't see the difference: it just sees its AuthnRequest eventually
answered with a valid signed Response. The user-visible improvement
is that the P1 login page renders instead of the plain-text gate.

## Summary

The behaviour you saw is intentional placeholder code, not a bug in
SAML wiring. The class Javadoc promises the redirect-to-login dance
but the inline scaffold returns a 401 instead, with an inline TODO
noting the next iteration would add the "login-then-resume" logic.
The full SP-init contract (Keycloak → P1 → P1 login → P1 → Keycloak
→ shell) needs the small redirect + session-stash patch outlined
above on the P1 side; the demo IdP is otherwise complete.
