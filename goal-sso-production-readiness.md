# Goal: SSO login/logout production-readiness gap analysis (keycloak-demo + geowealth)

Produce a **gap-analysis report** assessing whether the SSO login/logout architecture spanning
two repos is production-ready. **Analysis only — do not modify any code in either repo.**

## Scope

Two codebases, on their current branches (verify before starting; stop and ask if they differ):

- `/Users/petarnenov/keycloak-demo` @ `petarnenov/bff-core-persons-registry` — Keycloak realm
  (`demo-realm`), `bff-core` shared library, billing/trading domain BFFs + SPAs, e2e suite.
- `~/geowealth` @ `team/petarnenov/keycloak-persons-registry` — P1 as SAML IdP:
  `IdpSsoAction`, `SilentSsoAction`, SLO endpoints, `KcSessionProbe`, `appService.js`
  login/establish/silent-failed logic, PersonRegistry / person-linking.

Flows to cover (all four):

1. **Login** — P1 credential login + the post-login "establish round-trip" (Gap 6),
   SAML-brokered login via `kc_idp_hint=p1`, silent-first auth (`/oauth/login/silent` +
   cookie marker), first-broker-login auto-link by email.
2. **Logout** — RP-initiated SLO, OIDC back-channel logout (`LogoutTokenValidator`,
   `SidSessionRegistry`), idempotent `/auth/logout`, whitelabel logout fan-out,
   P1 `KcSessionProbe` liveness, P1 IdP-initiated logout vs open SPA tabs.
3. **Session / token lifecycle** — Token Handler invariants: tokens server-side only,
   httpOnly cookies, `TokenRefreshFilter`, expiry/refresh behavior, claims correctness
   (`firmCd`, `personId`, `tenant_identity`, `active_tenant`).
4. **Cross-subdomain / whitelabel SSO** — silent SSO across hosts, firm-switch authz,
   per-host `redirect_uri` coverage, and explicitly re-verify the **known OPEN
   firm-context bleed bug** noted during the whitelabel global-logout work.

## Method (three passes, all required)

1. **Static analysis** — read the auth-relevant code, `keycloak/realm-export.json`,
   compose/Vite/Micronaut configs, and existing docs/specs (`sso-role-mapping.md`,
   flow diagrams, `e2e/` specs) in both repos.
2. **Live E2E verification** — bring the stack up (`./start.sh`, P1 Tomcat + required
   agents) and drive the real flows in a browser (Playwright / Chrome DevTools MCP),
   capturing evidence: redirects, cookies, token claims, KC session state, catalina.out
   markers (e.g. `establish(kc_idp_hint=p1)`). Run the existing `e2e/` suite and treat
   failures as findings. Static review alone is not sufficient for any "verified" claim.
3. **Security review** — SAML Response signature/`InResponseTo` validation, assertion
   replay, cookie attributes (httpOnly/Secure/SameSite/Domain), CSRF on state-changing
   BFF endpoints, open-redirect on login/logout `redirect_uri`/`return_to`, logout-token
   validation rigor, secrets and dev credentials baked into configs/images
   (admin/admin, mkcert keys, keystore handling, `/tmp` keystore), auto-link-by-email
   account-takeover surface on first-broker-login.

## Production-readiness criteria (weigh all four)

- **Security** — token storage, cookie attributes, CSRF, SAML signatures/certs, secrets.
- **Reliability** — race conditions, idempotency, loop guards (`silent_failed`,
  establish-once), error paths, known open bugs.
- **Operability** — logging/observability of auth failures, prod-configurability
  (hardcoded `*.geowealth.int` hosts, ports, `https://auth.geowealth.int:5180` issuer,
  env vars vs baked values), TLS/cert management, recovery procedures
  (`sso-dev-keystore.sh` class of failures), single-instance assumptions
  (in-memory `SidSessionRegistry`, P1 `HttpSession` flags) vs horizontal scaling.
- **Standards compliance** — OAuth2/OIDC Browser-Based App BCP (BFF/Token Handler
  pattern), OIDC back-channel logout spec, SAML Web Browser SSO profile correctness.

## Deliverable

A single English report `sso-production-readiness-gap-analysis.md` in the keycloak-demo
repo root containing:

- **Verdict** — production-ready: yes/no/conditional, one paragraph.
- **Findings table** — each finding: severity (**Blocker / Major / Minor**), affected
  repo + file:line, flow area, criterion, evidence (what was observed — code excerpt,
  HTTP trace, test output), and impact. Blockers first.
- **What is solid** — explicitly list what passed verification with evidence, so the
  report is a trustworthy baseline, not just a defect list.
- **Coverage statement** — what was NOT examined or could not be exercised live, so
  gaps in the analysis itself are visible.

No fixes, no refactors, no config changes — findings only. The demo-vs-prod distinction
matters: flag dev-only conveniences (admin/admin, mkcert, /etc/hosts, /tmp keystore) as
findings with a "dev-acceptable, prod-blocker" framing rather than treating them as bugs
in the demo.
