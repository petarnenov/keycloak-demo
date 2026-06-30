# GEO-28804 — Identity Service / SSO Foundation: Requirement Coverage Report

**Ticket:** [GEO-28804](https://geowealth.atlassian.net/browse/GEO-28804) — *Identity Service – Portfolio Studio Authentication with SSO Foundation*
**Type:** Story · **Status:** Open · **Component:** P1 / Operations · **Reporter/Assignee:** Albina Urmat
**Scope (Phase 1):** Replace the existing auth framework with a centralized **Identity Service** for **GW admins**, preserving the current login UX/functionality for GW admins, advisors and clients; establish an **SSO foundation** across GeoWealth apps starting with **Portfolio Studio**. Multi-firm firm-switching is explicitly **Phase 2**.

Two codebases assessed:
- **P1** = `~/nodejs/geowealth` (the existing platform — current login/auth implementation).
- **IdS** = `~/keycloak-demo` (the Keycloak-based Identity Service / SSO tier POC).

Legend: **✅ Covered** · **🟡 Partial** · **🔴 Gap/Absent** · **❔ Needs verification**

---

## 1. Coverage matrix — story-level requirements

| # | Requirement (from GEO-28804) | Status | Where it lives today | Gap / action needed |
|---|---|---|---|---|
| R1 | Centralized **Identity Service** replacing the auth framework (GW admins) | ✅ | IdS: Keycloak `demo-realm` + multi-tenant `token-handler` (BFF/Token-Handler pattern) + `user-service` User-Storage SPI | Productionize (real certs, env URLs, secret mgmt); roll GW-admin cutover |
| R2 | Preserve current **login page look & feel** | ✅ | IdS: `auth-spa/theme/login/login.ftl` mirrors P1 `LoginTemplate1` (floating labels, GW brand `#307492`, Lato) | Per-firm whitelabel of the **KC login form** still missing (theme is realm-level) — see R-W |
| R3 | Preserve **landing page** experience | ✅ | P1 unchanged for non-GW-admin; SPA bounces straight to app | — |
| R4 | Preserve **password authentication** | ✅ | P1: `LoginAction`/`Login.java` → `AuthenticationManager`, `SHAPassword` (SHA/SSHA). IdS: KC delegates credential verify to `user-service` SPI | Confirm SPI verify path uses the same SHA store/policy as P1 |
| R5 | Preserve **MFA** | 🟡 | P1: email-OTP fully built (`NEntity.mfaToken*`, `TokenGenerator`, `LoginAction` MFA stages, device recognition `MfaUtil`) | **Not yet wired into the centralized KC login** — demo theme is username/password only. MFA must be surfaced through the IdS for GW-admin login |
| R6 | Preserve **forgot-password / reset** workflow | ✅ | P1: `LostPasswordAction` (email link). IdS: theme forgot-password link → KC `resetPasswordAllowed` reset flow | Decide single source of truth (P1 reset vs KC reset) to avoid two reset paths |
| R7 | Preserve **password expiration / 90-day reset** | 🟡 | P1: `passwordExpirationDate` field + React `ExpirationWarningModal`/`UpdatePasswordModal` | Enforcement on the **centralized** login path not demonstrated; must enforce expiry + force-reset via IdS |
| R8 | Preserve **all current security controls** | ✅ | IdS: httpOnly/Secure/SameSite=Lax `GWSESSION`, CSRF `state`, JWKS verify, back-channel logout, idempotent logout | Security review against P1's existing control set (lockout, etc.) |
| R9 | **No change** to login UX for GW admin / advisor / client; non-GW-admin unchanged | ✅ | Role distinction preserved: `isEmployee`, `gwAdminFlag`, `WHO_IS_LOGGED_IN_KEY`; Phase-1 only touches GW-admin path | — |
| R10 | **SSO foundation** across GeoWealth apps, starting **Portfolio Studio** | 🟡 | IdS: one KC SSO session + `GWSESSION` + Redis store + back-channel logout (`SidSessionRegistry`) proven across billing/trading | Portfolio Studio not yet onboarded as a concrete app (billing/trading are the analogs) |
| R11 | Portfolio Studio ⇄ Advisor Portal ⇄ Platform One, **no re-login (bidirectional)** | 🟡 | P1→IdS: `SilentSsoAction` establish round-trip (`kc_idp_hint=p1`). IdS→P1: `OidcCallbackAction` restores P1 session. Cross-app = shared KC session | **Model drift:** IdS repo retired the P1 **SAML broker** (Phase 5) for the `user-service` SPI; P1 still has SAML-era establish code. Reconcile to one SSO mechanism end-to-end |
| R12 | Future **multi-firm access** (GW admin firm-switch) — *Phase 2* | 🟡 | Infra present: `firmCd` claim, `memberships[]`, `OidcCallbackAction` `gwAdminCrossFirm`, `PersonRegistry`, firm-type tenants | Firm-switching **UI/flow** absent (lives in P1 `PersonRegistry`, not wired). Correctly deferred to Phase 2 |
| R13 | Architecture **scalable, secure, backward compatible** | ✅ | IdS: stateless replicas, Redis sessions (survive redeploy), per-Host multi-tenancy, one shared client | — |
| R14 | Foundation for **future external IdP** integration | 🟡 | KC natively brokers external SAML/OIDC IdPs; `p1-first-broker-login` flow still seeded | **Not configured** — SAML broker retired; needs an explicit add-IdP path. (See `saml-firm-integration-plan.md` for the per-firm broker design) |
| R15 | Foundation for **additional GeoWealth apps** | ✅ | IdS: "Adding a domain" recipe — one `app.tenants.<slug>` + host on shared client, no new infra | — |
| R16 | **WEG Firm-167** must behave identically under the new Identity Service (comment) | ❔ | Firm-specific; not represented in either repo | Verify WEG-167 login/SSO/branding parity on the new IdS before cutover |

---

## 2. Sub-task coverage

| Sub-task | Status (Jira) | Implementation today | Coverage |
|---|---|---|---|
| GEO-29007 — [FE] Investigate FE implementation & POC | In Progress | IdS SPA + theme POC exists; billing/trading prove the FE/BFF pattern | ✅ POC exists |
| GEO-29199 — Password Authentication | To Do | P1 `LoginAction`+`SHAPassword`; IdS via `user-service` SPI | ✅ exists, needs IdS wiring/verify |
| GEO-29200 — Forgot Password | To Do | P1 `LostPasswordAction`; IdS KC reset flow | ✅ exists (dedupe paths) |
| GEO-29201 — Password Expiration / 90-Day Reset | To Do | P1 field + React modals | 🟡 enforce on IdS path |
| GEO-29202 — Multi-Factor Authentication | To Do | P1 email-OTP complete | 🟡 surface through IdS login |

---

## 3. Key gaps to close for Phase 1

1. **MFA on the centralized login (R5 / GEO-29202)** — email-OTP exists in P1 but the IdS login form is password-only. Must route GW-admin MFA through Keycloak (SPI required-action or KC OTP).
2. **Password-expiry enforcement on the IdS path (R7 / GEO-29201)** — field + warnings exist; the 90-day force-reset must fire on the centralized login, not only in the P1 React shell.
3. **One coherent SSO mechanism (R11)** — the IdS repo replaced the P1 **SAML broker** with the `user-service` SPI, while P1 retains SAML-era establish/callback code. Pick one (SPI-direct vs broker) and make the Portfolio Studio ⇄ P1 round-trip consistent.
4. **Dedupe reset/forgot paths (R6)** — P1 reset vs KC reset can diverge; choose the system of record.
5. **Portfolio Studio onboarding (R10)** — instantiate it as a real tenant (billing/trading prove the recipe).
6. **WEG Firm-167 parity (R16)** and **per-firm KC login whitelabel (R2 residual)** — verify/implement before GW-admin cutover.

---

## 4. Bottom line

The **identity-service foundation is largely in place**: centralized Keycloak + token-handler, shared SSO session with Redis persistence, back-channel logout, the new-app onboarding recipe, and a login theme that matches P1's. **Password auth, forgot-password, role distinction, logout and session lifecycle are covered.** The **real Phase-1 work** concentrates in three areas the matrix flags 🟡: **MFA** and **password-expiry** must move onto the centralized login, and the **P1⇄IdS SSO mechanism** must be reconciled (SAML-broker-retired vs SPI) before Portfolio Studio is wired in. Multi-firm (R12) and external-IdP (R14) are correctly future-phase, with infrastructure already seeded.
