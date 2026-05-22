# P1 ↔ keycloak-demo SSO — migration plan

Single source of truth synthesizing `p1-sso-integration-research.md` (solution selection — Solution 2 SAML Identity Brokering) and `p1-sso-architecture.md` (full auth lifecycle — login / logout / expired session / 4 entry methods) into a sequenced, cross-repo implementation plan.

Whitelabel track is **already shipped** (Phase 4 + Phase 5 of that plan closed in `HANDOFF.md`). This plan picks up the second of the two parallel tracks called out in `p1-sso-architecture.md` § "Връзката с white-labeling track-а" — `P1 SSO` is independent in code but dependent on whitelabel for visual brand on the Keycloak login screen the few moments it's visible (SP-init flow, federation errors, expired P1 session).

**Date:** 2026-05-23
**Repos:**
- `keycloak-demo` branch `petarnenov/geowealth-whitelabel-poc` — Keycloak realm config, SPI provider, login template
- `geowealth` branch `team/petarnenov/keycloak-whitelabel-poc` — P1 SAML IdP module, sidebar entry

---

## 1. Goal in one paragraph

P1 (legacy Struts/Tomcat session-based app) gets a new sidebar item "Demo MFE-BFF". A user already authenticated in P1 clicks it; the browser lands inside the keycloak-demo shell at `localhost:5173/` logged in as **the same 1:1 user**, with no second username/password prompt. The bridge is SAML 2.0 Identity Brokering: P1 acts as a SAML IdP that emits a signed `<samlp:Response>`; Keycloak acts as a SAML SP that consumes it via the standard `/realms/demo-realm/broker/p1/endpoint` ACS. Real production user, real role mappings, real audit trail — not a demo-account alias.

## 2. Solution choice (recap from research doc)

Four candidates were analyzed in `p1-sso-integration-research.md` § 2. Solution 2 (SAML Identity Brokering with P1 as IdP) wins not because it's cheapest but because it's the only one that simultaneously satisfies:

1. **1:1 user mapping** — each P1 user is a distinct Keycloak user
2. **Production grade** — audit log, key rotation, SLO, hardened crypto
3. **Back-channel reachability** — Keycloak in a container reaches P1 on host via `host.docker.internal`
4. **Reuses existing P1 OpenSAML 5.1.6 code** — `AbstractSamlAuthenticationResponseBuilder`, `OpenSamlUtils`, `SamlValidator`, `SamlMetadata` are battle-tested via the FireLight / 55IP / iCapital integrations; only the HTTP endpoints publishing them as a SAML IdP are missing

Solutions 1 (OIDC IdP from scratch in Struts), 3 (RFC 8693 token exchange), and 4 (custom Keycloak Authenticator SPI) each fail one or more of those constraints — full comparison table in the research doc.

## 3. Two sub-flows to implement (recap from architecture doc § 2.3)

Both routes through the same SAML pipeline. The user enters one of them depending on where they started:

### C1 — IdP-initiated (from the P1 sidebar)

```
P1 (Tomcat :8080)            Browser              Keycloak (:8898)        Shell (:5173)
─────────────────            ───────              ─────────────────       ─────────────
User logged in,              click "Demo MFE-BFF"
JSESSIONID active            ────────────────────►
                             GET /saml/idp/sso.do?RelayState=keycloak-demo
                             ────────────────────►
                                                  P1 checks session
                                                  builds signed Response
                                                  with NameID + attrs
                             ◄──────────────── HTML auto-submit POST form
                                                  POST SAMLResponse →
                                                  /realms/demo-realm/broker/p1/endpoint
                                                  ──────────────────────►
                                                                          Keycloak validates sig
                                                                          first-broker-login flow
                                                                          mints session, redirects
                             ◄──────────────────────────────────────── 302 ?code=…&state=…
                                                                                      ─────►
                                                                                            keycloak-js
                                                                                            exchanges code
                                                                                            for tokens
```

User flow: click → branded login (skipped) → shell. No password prompt; Email OTP skipped (trust delegated to P1).

### C2 — SP-initiated (from the Keycloak login screen)

User lands on `localhost:5173/` without P1 context. The shell shows the Keycloak login form — **branded per firm via the whitelabel track**. User clicks "Login with P1":

1. Browser → `GET /realms/demo-realm/broker/p1/login?…`
2. Keycloak builds `<samlp:AuthnRequest>`, POSTs to P1 `/saml/idp/sso.do`
3. P1 checks session:
   - Active → silent build of Response, same as C1 from step 4
   - No session → P1 redirects to its own `/react/login.do`; user logs in; P1 redirects back to `/saml/idp/sso.do` which now sees an active session and builds the Response
4. Same C1 from step 4 onward

The Keycloak login screen is the moment whitelabel matters most for SSO traffic. C2 is comparatively rare in normal traffic but is the visible failure-recovery path when P1 sessions expire.

## 4. Critical path: phase sequencing

Five phases. Phases 1 and 2 are independent — they can land in parallel from different developers. Phases 3, 4, 5 are sequential and gate on 1+2.

### Phase 1 — Keycloak SAML SP config (this repo, ~2 days)

**Repo:** `keycloak-demo` branch `petarnenov/geowealth-whitelabel-poc`

| Deliverable | File |
|---|---|
| Add `p1` SAML Identity Provider to `demo-realm` | `keycloak/realm-export.json` |
| First Broker Login flow → auto-link by email | Same |
| Attribute mappers (email, firstName, lastName, firmCd, roles) | Same |
| "Login with P1" button on the login template | `keycloak/themes/mfe-shell/login/login.ftl` (or whichever theme demo-realm uses; the geowealth-wl theme covers `geowealth-realm`) |
| Optional Keycloak SP signing keystore | Generated at realm-import time or pinned in repo |
| docker-compose `extra_hosts` for `host.docker.internal` on Linux/Podman | `docker-compose.yml` — already present for the whitelabel work |

Self-contained: the SP config can exist before P1 IdP code is written. Validation in this phase is structural — the Identity Provider entry must persist across a realm reimport, the SAML metadata Keycloak emits must be importable by an external SAML debugger, the Login screen must render the new button.

### Phase 2 — P1 SAML IdP module (geowealth repo, ~7 days)

**Repo:** `geowealth` branch `team/petarnenov/keycloak-whitelabel-poc`

Five new Java files under `src/main/java/com/geowealth/saml/idp/`:

| File | Purpose | Effort |
|---|---|---|
| `IdpSsoAction.java` | Struts action mapped at `/saml/idp/sso.do`. Supports SP-init (decode AuthnRequest, validate against Keycloak SP cert if signed) and IdP-init (active P1 session → build Response). Always emits an HTML auto-submit POST form to Keycloak's ACS. | 2 d |
| `IdpMetadataAction.java` | `/saml/idp/metadata.do` — emits `EntityDescriptor` XML (entityId, IDPSSODescriptor, X509Certificate, SingleSignOnService endpoint, SLO endpoint). Static-mostly; the certificate comes from `IdpKeyStore`. | 0.5 d |
| `IdpSloAction.java` | `/saml/idp/slo.do` — accepts `<samlp:LogoutRequest>` from Keycloak, validates Keycloak SP signature, destroys the P1 Tomcat session (mirrors `LogOutAction` behavior — clears `LOGGED_ADVISER`, `LOGGED_USER`), returns signed `<samlp:LogoutResponse>`. | 1.5 d |
| `KeycloakSamlResponseBuilder.java` | Extends the existing `AbstractSamlAuthenticationResponseBuilder` for Keycloak's audience. NameID = P1 user UUID (stable across email changes — see § 5 decision below). AttributeStatement: email, firstName, lastName, firmCd, roles. | 0.5 d |
| `IdpKeyStore.java` | **Production blocker resolver:** replaces hardcoded `firelight.pfx` + password `"1234"` with a configurable keystore. Reads location from env var (`P1_IDP_KEYSTORE_PATH`) and password from env var (`P1_IDP_KEYSTORE_PASSWORD`) at startup; supports both file-based PKCS#12 and DB-backed (for prod). Generates a self-signed key on first run if no keystore is configured, with a loud warning. | 1 d |
| `struts-platformOne.xml` or `struts-tiles.xml` | Mappings for the three new actions | 0.25 d |
| AttributeStatement mapper | Reuse `AbstractAttributeStatementMapper`. Inherits the pattern FireLight/55IP/iCapital already use. | 1 d |

Reuses `OpenSamlUtils.buildSAMLObject()`, the entire `AbstractSamlAuthenticationResponseBuilder` signing pipeline, and `SamlMetadata` for the metadata endpoint. No OpenSAML version bump needed (already 5.1.6).

### Phase 3 — P1 sidebar entry (geowealth repo, ~0.75 days)

One entry in `WebContent/react/app/src/pages/PlatformOne/sidebar/_hooks/useIntegrationLinks.js`:

```js
{
  label: "Demo MFE-BFF",
  linkUrl: "/saml/idp/sso.do?RelayState=keycloak-demo",
  absoluteUrl: true,
  id: "demo-mfe-bff",
  hidden: !permissionsHelper.hasPermissionDemoMfeBff(loggedUser),
  "data-testid": "sidebar-link-demo-mfe-bff",
}
```

Permission helper is a new flag — `hasPermissionDemoMfeBff` — that an admin toggles on a per-firm or per-user basis. For the initial deploy, the simplest gate is `loggedUser.firmCd in [list of pilot firms]`.

### Phase 4 — Keycloak realm config refinements (this repo, ~1 day)

After Phase 2 produces a working P1 IdP endpoint, the Keycloak side gets a second pass:

| Item | Detail |
|---|---|
| Pin the P1 validating cert in `realm-export.json` | The X509 cert from `IdpKeyStore.getPublicCertificate()`, base64-encoded |
| Configure NameID Policy Format | `urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress` if we go with email-as-NameID; or `urn:oasis:names:tc:SAML:2.0:nameid-format:persistent` if we go with UUID-as-NameID. Section 5 § "NameID format" picks one. |
| First Broker Login flow | "Auto-link by email" — silent if a Keycloak user with the same email already exists, else auto-create |
| Email OTP bypass for federated users | Already works by default — federated logins go straight to the realm session without hitting the `demo-email-otp` authenticator that lives in `auth-username-password-form`'s subflow |
| `mfe-shell-client` flow override | Optional — `kc_idp_hint=p1` query param on every redirect from the shell, vs only when the user clicked the sidebar entry |

### Phase 5 — Production hardening (~5 days)

| Item | Detail |
|---|---|
| TLS everywhere | Browser ↔ Shell, Browser ↔ Keycloak, Browser ↔ P1, Keycloak ↔ P1 back-channel SLO |
| Dedicated IdP signing key + rotation pipeline | 60-day rotation; metadata advertises old + new key during transition; deployment via `IdpKeyStore` env-var or DB |
| InResponseTo replay protection | Track issued AuthnRequest IDs + IssueInstant window (Redis or DB); reject duplicates within 10 min |
| Front-channel SLO | Keycloak `/logout` → SAML LogoutRequest to P1 → P1 session killed → Keycloak completes local logout |
| Audit logging | `SECURITY_EVENT: SAML_ISSUED user=<uuid> firm=<cd> target=<sp> remote=<ip>` on every Response emission; mirror the existing P1 `SECURITY_EVENT: User Logout` pattern |
| BFF JWT clock skew | Add `micronaut.security.token.jwt.signatures.jwks.clockSkew=30s` to `bff/src/main/resources/application.yml` |
| E2E test | Drive C1 + C2 flows end-to-end in JUnit / Playwright |

## 5. Open decision points (must resolve before Phase 4)

1. **NameID format.** Email is the simplest path because Keycloak's First Broker Login flow can auto-link to an existing local user by email. But if a P1 user's email changes, Keycloak's broker-link table sees them as a new user. UUID-as-NameID is stable but requires the email to be carried as a SAML attribute and mapped on the Keycloak side. **Recommendation: UUID-as-NameID** (production-correct); email as attribute. The migration cost is small (~0.25 d) and the future debugging cost of NameID drift is large.

2. **`kc_idp_hint=p1` default.** Two options: always (every login redirected through P1) or only-on-sidebar-entry (the normal Keycloak login screen still lets users sign in with `democlient/123`). **Recommendation: only-on-sidebar-entry** for the POC, with the option to flip to always once the SSO flow is battle-tested.

3. **Role mapping.** P1 roles vs Keycloak realm roles (`client`, `user`, `admin`). The simplest path is a 1:1 map maintained as a Keycloak Identity Provider Mapper of type "Hardcoded Role" or "Attribute → Role". **Recommendation: emit the role set as a `roles` SAML attribute from P1, mapped via Keycloak's "Attribute to Role" mapper.** The mapper is configured in `realm-export.json`.

4. **Keycloak production topology.** The POC runs Keycloak in a Docker container with `host.docker.internal:8080` reaching the P1 Tomcat. Production will deploy Keycloak on a real host with a real DNS name — at which point P1's SAML SSO URL needs to change from `host.docker.internal:8080` to the production hostname. **Recommendation: make the SSO URL a realm attribute / config var, not hardcoded.**

5. **SLO scope in MVP.** Front-channel SLO between Keycloak and P1 is a real piece of work because session-kill order matters. The MVP can defer it — logout in the keycloak-demo shell tears down only the Keycloak session, not the P1 Tomcat session. **Recommendation: defer to Phase 5.** Document explicitly that logout from the shell does not also log the user out of P1 until SLO ships.

## 6. Production blockers (must address before shipping)

Three items that block a production deploy regardless of how much MVP work has landed:

1. **`firelight.pfx` with password `"1234"` cannot become the IdP signing key.** The `IdpKeyStore` work in Phase 2 must give the IdP a dedicated key with a rotation pipeline. Reusing FireLight's keystore conflates two trust domains (FireLight as SP for insurance, Keycloak as SP for federation) and exposes the same key to two security boundaries.

2. **NameID stability** — see open decision § 1. Without UUID-as-NameID the moment an admin updates a user's email in P1, Keycloak third-party-broker-links them as a brand new user, losing their role assignments and OTP trust history.

3. **firmCd in multi-tenant context.** keycloak-demo BFFs currently authorize by realm role only (`/api/whoami` → `allowedMfes` from roles, no firm filter). If the production deploy needs firm-level isolation (firm A's user must not see firm B's data), the BFFs must read `firmCd` from the JWT claims and apply it as a query filter. Document the gap; don't ship cross-tenant blind.

## 7. Cross-reference to whitelabel track

White-labeling is a separate code base + branch with no shared production code, but they ship together because:

- C1 (IdP-init) **does not show the Keycloak login screen** — P1 returns a signed Response, the browser POSTs it, Keycloak validates and redirects, user lands in the shell. No login form, no brand visible. White-labeling does not matter here.
- C2 (SP-init) **does show the Keycloak login screen** — when a user lands on `localhost:5173/` with no P1 context and clicks "Login with P1". This is the path where white-labeling matters most: a ChangePath user must see a ChangePath-branded screen, not a generic GeoWealth screen, or the B2B2C provider relationship leaks.
- Federation errors (P1 down, SAML signature mismatch, expired P1 session in the middle of C2) cause the Keycloak error / login screen to appear. White-labeling is the difference between "professional error page" and "obvious tech-debt".

In `p1-sso-architecture.md` § 2.3 Method C explicitly says: "Email OTP се пропуска за federated users — trust-ът е делегиран към P1. Но визуалният бранд не може да се пропусне." The user can see Keycloak in edge cases; the brand has to be right then.

## 8. Total effort estimate

| Bucket | Days | Repo |
|---|---|---|
| Phase 1 — Keycloak SAML SP config | 2 | keycloak-demo |
| Phase 2 — P1 SAML IdP module | 7 | geowealth |
| Phase 3 — P1 sidebar entry | 0.75 | geowealth |
| Phase 4 — Keycloak realm refinements | 1 | keycloak-demo |
| Phase 5 — Production hardening | 5 | both |
| Merge coordination + manual QA | 0.5 | both |
| **Total** | **~15 days (3 sprints)** | |

MVP (skipping the 5-day hardening): ~10 days.

## 9. This-session scope

Out of the 15-day plan, the deliverables we land in this session:

| In scope | Out of scope |
|---|---|
| Phase 1 — Keycloak SAML SP config (realm-export.json + login template button) | Phase 2 — full P1 IdP module (separate session; ~7 days work) |
| Phase 2 scaffolding — file skeletons + Struts XML wiring + a working `IdpKeyStore` shell that returns a dev self-signed key | Phase 3 — sidebar entry (depends on Phase 2 endpoint being live) |
| Phase 4 partial — attribute mappers in realm-export | Phase 5 — production hardening |
| Documentation — this plan + decision-point recommendations | Real production keystore work |

The session goal is to get the **structural plumbing** in both repos so the next developer (or session) can fill in the OpenSAML body of `KeycloakSamlResponseBuilder` and the `IdpSsoAction` dispatch logic without first having to find where things should live. Phase 1 is fully ship-able; Phase 2 lands as a compiling stub plus tests pending a wired-up signing key.

## 10. References

- `p1-sso-integration-research.md` — solution selection + four-option analysis + P1 SAML deep audit
- `p1-sso-architecture.md` — full auth lifecycle (login / logout / expired session / 4 methods) and edge-case matrix
- `LOGIN.md` — current keycloak-demo login flow, preserved as baseline
- `CLAUDE.md` — keycloak-demo architecture survival notes
- `HANDOFF.md` — whitelabel-track stakeholder summary; this track is the second of two parallel tracks
- `PRODUCTION-RISK.md` / `PRODUCTION-HARDENING.md` — patterns we'll reuse for the SSO hardening checklist
- `TEST-SCENARIOS.md` — JUnit suite pattern we'll mirror for SSO E2E tests
