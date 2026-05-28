# Cross-domain SSO — silent identity resolution per target audience

A physical person holds **two separate P1 identities**, one per business domain (e.g. `petar.biller` for `billing.geowealth.int`, `petar.trader` for `trading.geowealth.int`). They are already authenticated on one domain via P1. When they click a link to the sibling subdomain, they must end up authenticated **as the identity bound to the target**, with that target's roles and audit trail, **without a login screen** — provided the IdP can resolve the binding silently. Only when no binding exists does the flow fall back to a login prompt.

This is the **linked-accounts / account-aliasing** pattern. Standards used: OIDC Core 1.0 §3.1 (audience parameter), SAML 2.0 §2.7.3 (Subject confirmation), SAML 2.0 §3.4.1.4 (AuthnContext), RFC 9470 (step-up). Vendor reference implementations: Auth0 Account Linking, Okta Linked Objects, Microsoft Entra Alternate IDs, Ping Identity Account Linking. The pattern itself is industry-standard and SOC2-defensible **with the right compensating controls** documented in §6.

The previous revision of this document recommended `ForceAuthn` / `prompt=login` on every cross-domain click. That guaranteed SoD by re-prompting credentials but rejected the silent UX the firm wants. This revision keeps the SoD guarantee while delivering the silent UX, by moving identity resolution into the IdP and leaning on administrative provisioning of the bindings.

---

## 1. The problem, precisely

```
   Physical person P
   ┌──────────────────────────────────────────────────────────┐
   │  Two P1 identities, administratively provisioned:         │
   │    • petar.biller   → roles for billing.*    → firmCd A   │
   │    • petar.trader   → roles for trading.*    → firmCd B   │
   │  Different usernames, different role sets, different      │
   │  audit subjects. Binding is provisioned by IT, not by     │
   │  the end user.                                            │
   └──────────────────────────────────────────────────────────┘
                            │
              P1 (IdP, p1.geowealth.int)
                            │
              SAML to Keycloak (auth.geowealth.int)
                            │
        ┌───────────────────┴───────────────────┐
   billing.geowealth.int                trading.geowealth.int
   (logged in as                         (silent SSO as
    petar.biller)                         petar.trader on click,
                                          no login screen)
```

Concrete requirement:

- Tab 1 is on `billing.geowealth.int`, BFF session is for `sub=petar.biller`.
- User clicks a link to `trading.geowealth.int/<page>`.
- **No login screen.** P1 silently resolves "this physical person ↔ trading audience" → `petar.trader`, emits a SAML assertion for `petar.trader`, KC issues OIDC tokens for `petar.trader`, trading BFF authenticates the user as `petar.trader`.
- **If no binding exists** (the physical person has no provisioned identity for the trading audience) → redirect to P1's login screen. The user types credentials or sees a "no access" page (firm policy decides).
- The two BFF sessions live in parallel and are independent of each other.

The whole design hinges on P1 implementing a binding table that maps `(physical-person, audience) → identity`, and `IdpSsoAction` consulting it before emitting the SAML Response.

---

## 2. What's still NOT acceptable

The silent UX changes the default flow but does not change what's structurally forbidden. The anti-patterns from any cross-domain context still apply:

### 2.1 Silent assertion of the SOURCE identity into the TARGET audience

```
billing tab (petar.biller session in P1)
  └─► click link to trading
        └─► KC SAML AuthnRequest to P1, aud=demo-trading-client
              └─► P1 sees existing session, emits SAML Response with sub=petar.biller
                    └─► KC tokens with sub=petar.biller for aud=demo-trading-client
                          └─► trading BFF authenticates user as petar.biller
                                ✗  WRONG IDENTITY — SoD breached
```

This is the failure mode the binding table exists to prevent. P1 MUST resolve the audience to the bound identity (`petar.trader`) and emit `petar.trader` as Subject, even though its HttpSession's `LOGGED_USER` is still `petar.biller`. The SAML `Subject` is **not** automatically the current `LoggedUser` — it's whatever `IdpSsoAction` writes into the Response.

Silent identity *swap* (this design): OK. Silent identity *reuse* (the failure above): not OK.

### 2.2 Cookie-domain widening, tokens in URLs, postMessage token sharing, BFF token reuse across audiences

All four are forbidden for the same reasons as in any cross-domain context — see the previous revisions of this doc and any OIDC / SAML best-practice reference (RFC 9700 OAuth Security BCP, OIDC Core §3.1.2.5). They do not become acceptable in the silent-swap model; if anything, the silent UX makes a leak harder for the user to notice.

### 2.3 End-user-modifiable bindings

A user logged in as `petar.biller` MUST NOT be able to add a binding from `petar.biller` to a new target identity. Bindings are provisioned administratively. This is what makes the silent swap safe — see §6.

---

## 3. The design — per-audience binding at the IdP

### 3.1 Data model — the binding table in P1

The minimal addition to P1's schema:

```
LINKED_IDENTITY_TBL
  physical_person_id  UUID/INT     -- the human; new entity or reuses employee_id
  audience            VARCHAR      -- SAML entity ID or OIDC client_id of the target
  user_id             UUID         -- FK to USER_TBL, the identity for that audience
  mfa_required        BOOLEAN      -- whether silent swap also requires fresh MFA
  active              BOOLEAN
  provisioned_by      UUID         -- audit: which admin created the binding
  provisioned_at      TIMESTAMP
  PRIMARY KEY (physical_person_id, audience)
```

A row says: "physical person X has identity `user_id` for audience `audience`, provisioned by admin Y on date Z, with MFA policy M". This is precisely the SCIM-style **linked accounts** primitive used by every major IdP product.

Two practical model decisions:

- **`physical_person_id`** can be a new column on `USER_TBL` (every existing P1 user gets one; users belonging to the same physical person share the value), or a new entity table referenced by `USER_TBL`. The latter is cleaner; the former is one migration.
- **`audience`** is the canonical SP identifier. In the demo it equals the Keycloak OIDC client ID propagated as SAML `Issuer` / `AudienceRestriction` (`demo-billing-client`, `demo-trading-client`). P1 reads this from the inbound `AuthnRequest`.

The binding is read-only from end-user code paths. CRUD happens through P1's admin UI under tighter access controls.

### 3.2 The silent flow, end to end

```
[1] Trading SPA initialises auth (cold start in trading tab):
    keycloak.init({ onLoad: 'check-sso', idpHint: 'p1' })
    No trading session yet → redirect to KC authorize.
    NO `prompt=login`. NO `ForceAuthn`. Plain authorize.

[2] KC builds SAML AuthnRequest to P1:
      Issuer:             demo-realm
      AssertionConsumer:  https://auth.geowealth.int:5180/realms/demo-realm/broker/p1/endpoint
      AudienceRestriction: demo-trading-client
    POSTs to P1.

[3] P1's IdpSsoAction:
    a. Reads existing HttpSession → LOGGED_USER = petar.biller
    b. Reads AudienceRestriction (or Issuer + AudienceRestriction combo)
       → aud = demo-trading-client
    c. Resolves physical_person:
       petar.biller.user_id → linkedIdentity.physical_person_id = PP-42
    d. Looks up binding:
       SELECT user_id, mfa_required FROM linked_identity_tbl
         WHERE physical_person_id = PP-42 AND audience = 'demo-trading-client'
         AND active = true
       → user_id = petar.trader, mfa_required = false
    e. (Optional) If mfa_required = true, redirect to MFA challenge,
       then return here. See §3.4.
    f. Build LoggedUser for petar.trader by loading from P1's DB
       (NOT by mutating the existing HttpSession; see §3.5).
    g. Emit SAML Response:
         Subject/NameID:    petar.trader's NameID
         AudienceRestriction: demo-trading-client
         AuthnStatement/AuthnContext/AuthnContextClassRef:
             custom URI, e.g. urn:geowealth:linked-identity:from-prior-session
             (see §3.4 — communicates "this is a swap, not a fresh auth")
         Attributes: firmCd, roles, email — all from petar.trader's record
         InResponseTo: the AuthnRequest ID
       Sign. POST to KC.

[4] KC's broker flow:
    a. Validates SAML signature.
    b. Finds the federated identity link for petar.trader's NameID → KC user for petar.trader.
    c. (First time only) first-broker-login creates the KC user, auto-linked by email.
    d. Issues OIDC code → 302 to trading SPA with the code.

[5] Trading SPA / BFF:
    a. SPA forwards the code to its BFF via the OIDC callback URL.
    b. BFF performs code exchange against KC, gets access/refresh/id tokens
       with sub=<KC-id-for-petar.trader>, roles=trading-*.
    c. BFF establishes BSESSION cookie for trading.geowealth.int (host-scoped).
    d. SPA's /auth/me returns username=petar.trader, roles=[...].

[6] User sees the trading UI, authenticated as petar.trader. No login screen.

Total cost: one extra DB lookup on P1's side (step [3.d]). No extra round-trip
to the browser. Latency budget: ~10-20ms.
```

### 3.3 The fallback — no binding exists

If step [3.d] returns nothing — the physical person has no provisioned identity for `demo-trading-client` — the silent path cannot continue. Two acceptable behaviours, firm-policy decision:

**(a) Redirect to P1's login form with the AuthnRequest preserved.**

P1 stores the pending SAML request ID in the HttpSession + uses the SAML `RelayState` for round-trip safety, then redirects to its login. The user authenticates with the **target identity's** credentials. After login, `IdpSsoAction` resumes the original request, but now `LOGGED_USER` is the target identity and the binding can be auto-created or the response can be built from this fresh login.

Practical caveat: the active P1 HttpSession will be overwritten by the new login unless P1 grows parallel session support (`p1-auth-flow.md` §1.4 — today it doesn't). For the demo, single-session-per-browser with last-login-wins is acceptable.

**(b) Show a "no access" page** ("You don't have a `<target>` identity provisioned. Contact your administrator.")

This is the SOC2-tightest variant — end users cannot bootstrap their own access to other audiences. Bindings are 100% admin-provisioned, and a user who doesn't have one is simply told to ask.

Recommendation: **(b)** is the default. **(a)** is acceptable for self-service-onboarding firms but introduces a self-binding risk that needs additional controls (e.g. binding is only auto-created on a hard-rule match like same employee email + active-employee flag).

### 3.4 AuthnContext — distinguishing "fresh auth" from "linked swap"

When P1 emits the SAML Response after a silent swap, the assertion looks identical to a fresh authentication unless the AuthnContext makes the difference visible. The convention:

- Fresh credential entry → `AuthnContextClassRef = urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport` (or `MultiFactor` if MFA was satisfied).
- Linked-identity swap → custom class, e.g. `urn:geowealth:ac:classes:linked-identity-from-prior-session`. Optionally annotate via `AuthnContextDecl` what the prior session's class was.

KC propagates `acr` from the SAML AuthnContext into the OIDC `id_token`. The BFFs can then read `id_token.acr` and decide whether to enforce step-up on sensitive endpoints — "if this session was created via a linked-identity swap and the user is about to wire money, prompt for MFA now."

This is RFC 9470 step-up, applied conditionally based on how the session originated. The mechanism is standard; the AuthnContext URI vocabulary is a firm-internal choice.

### 3.5 Sessions — don't mutate the source, do isolate the target

A subtle but important rule: `IdpSsoAction` MUST NOT replace `LOGGED_USER` in the HttpSession with the target identity during the silent swap. The flow needs to *emit a SAML assertion for `petar.trader`* without *changing what the user is "currently logged in as in P1"* (which is still `petar.biller` for the billing tab).

```
Bad:  LOGGED_USER := loadUser(petar.trader)   // overwrites; billing tab is now broken
Good: targetUser := loadUser(petar.trader)
      response   := buildSamlResponse(targetUser, audience)
      // LOGGED_USER untouched
```

The target BFF gets its own session on its own host (`trading.geowealth.int`, cookie `BSESSION`, distinct from billing's). P1's HttpSession remains the source identity's session.

If parallel P1 sessions are added later (multi-account model — see `p1-auth-flow.md` §1.4 critique), the rule becomes "create a new P1 session entry, don't overwrite". Same intent.

### 3.6 KC-side configuration — keep the broker dumb

KC's role in the silent flow is intentionally minimal:

- KC's `p1` IdP definition: **`Force Authentication = false`**. This is the demo's existing setting; do not change.
- KC's `p1` IdP: **`Sync Mode = FORCE`** on identity-provider mappers (already set). This ensures KC re-syncs role attributes from each SAML Response, so a binding-side role change shows up on the next click without admin intervention.
- KC has **two KC users**, one per identity: `petar.biller` linked to P1 NameID-A, `petar.trader` linked to P1 NameID-B. Each gets its realm roles via the SAML role-IdP-mappers. No KC-side schema change needed.

All the per-audience routing logic lives in P1. KC just translates a SAML Response for `petar.trader` into OIDC tokens for `petar.trader` — exactly what it does today for any brokered SAML user.

---

## 4. Recommended implementation

Phased. Each phase is shippable and testable on its own.

### 4.1 Phase 1 — provision the two-identity test case manually

Before any code change, prove the model works end-to-end with manual data:

1. Create two P1 users for the same human tester: `qa.biller` + `qa.trader`, each with the appropriate firm + roles.
2. Create the matching KC users via the existing first-broker-login flow (cold-login each one once via its own SPA → P1 → KC).
3. Verify each works in isolation: log into billing as `qa.biller` → roles `billing-admin`. Separate browser profile, log into trading as `qa.trader` → roles `trading-trader`.

This validates the demo's two-user data model before the silent-swap logic is added.

### 4.2 Phase 2 — `linked_identity_tbl` and `IdpSsoAction` resolver

In the P1 repo:

1. Schema migration adding `linked_identity_tbl` (§3.1) and (if needed) a `physical_person_id` column on `USER_TBL`.
2. Admin API + UI to CRUD bindings. Scope to gw-admin or firm-admin roles.
3. `IdpSsoAction.handle(AuthnRequest req)` extension:
   ```java
   String aud = req.getAudienceRestriction();   // or req.getIssuer() depending on KC's mapping
   User sourceUser = getSessionLoggedUser();
   UUID pp = sourceUser.getPhysicalPersonId();
   LinkedIdentity binding = linkedIdentityDao.find(pp, aud);

   User targetUser = (binding != null && binding.isActive())
       ? userDao.load(binding.getUserId())
       : null;

   if (targetUser != null) {
       buildAndSendSamlResponse(targetUser, req);   // §3.2 step [3.f-g]
   } else {
       handleNoBindingFallback(req);                // §3.3 (a) or (b)
   }
   ```
4. Audit-log every silent swap with both `source_user_id` and `target_user_id` (§5, CC7.2).

The "build SAML Response from a `User` who is NOT the HttpSession `LOGGED_USER`" branch is the one to review carefully — make sure no helper accidentally reads from the session.

### 4.3 Phase 3 — fallback policy

Implement §3.3 (b) — "no access" page — as the default. Wire `kc_idp_hint=p1` + `RelayState` correctly so that if (a) is later enabled, the round-trip works.

### 4.4 Phase 4 — AuthnContext + step-up on sensitive targets

Emit `AuthnContextClassRef = urn:geowealth:ac:classes:linked-identity-from-prior-session` from the silent-swap path; fresh-login paths keep their existing class.

In the trading / billing BFFs, add `acr`-aware authorization for sensitive endpoints: if `id_token.acr` is the linked-identity class, require step-up before allowing the call (returns 401 with `WWW-Authenticate: Bearer error="insufficient_user_authentication", acr_values="urn:oasis:...:MultiFactor"`). The SPA reacts by triggering a `prompt=login acr_values=mfa` re-authorize.

### 4.5 Phase 5 — audit correlation

The standard SOC2 audit-trail recipe:

- Source BFF: `event=cross_app_navigation_initiated, source_sub, target_aud`
- KC: brokered authorize/SAML logged at INFO already; no change needed
- P1: `event=linked_identity_swap, source_user_id, target_user_id, audience, binding_id, mfa_required, mfa_satisfied`
- Target BFF: `event=session_established, sub, acr`

Five log lines, correlated by timestamp + browser-session ID, fully reconstruct any silent identity switch for an auditor.

---

## 5. SOC2 mapping

| Trust Services Criterion | How this design satisfies it |
|---|---|
| **CC1.3 (segregation of duties)** | Two distinct identities, two distinct audit trails, two distinct role sets. Cross-identity SSO is mediated by a binding table provisioned **administratively** (not self-service), so a user with credentials for one identity cannot create their own access to another. Audit trail per identity remains intact. |
| CC6.1 (logical access) | Access to each target audience is gated by the existence of an active binding. Binding revocation (set `active=false`) instantly closes the silent-swap path; next click → fallback to §3.3. |
| CC6.2 (access provisioning) | Bindings are CRUDed by gw-admin / firm-admin via P1's admin UI. End users cannot self-bind. Provisioning events are themselves audited (`provisioned_by`, `provisioned_at`). |
| CC6.6 (transmission integrity) | No tokens in URLs. No cross-host cookies. Standard SAML AuthnRequest / Response with signed assertion. The binding lookup is server-side at P1; only the resulting Subject + attributes cross the wire. |
| CC6.7 (no token leak) | Each BFF holds its own session tokens in its own server-side store. Tokens for `petar.trader` never reach `billing.geowealth.int`'s BFF; tokens for `petar.biller` never reach `trading.geowealth.int`'s BFF. |
| CC7.2 (monitoring) | Five-line audit correlation (§4.5) captures every silent swap with source + target identities. Linked-identity events are queryable independently of regular logins. |
| CC7.3 (anomaly response) | Independent revocation: deactivating one identity (KC user disable, P1 user deactivate, binding deactivate) does not affect the other. Suspicious cross-identity activity is detectable by SIEM rules on the `linked_identity_swap` event stream. |
| CC8.1 (change management) | All controls are data + standard protocol params. Bindings are version-controllable through DB migrations + admin actions; AuthnContext URIs are stable identifiers; KC IdP config is JSON-exportable. |

---

## 6. Security trade-offs and compensating controls

The silent UX trades a fresh credential check for a faster click. This is a real reduction in authentication assurance compared with the `ForceAuthn`-everywhere model; the trade-off must be made consciously.

The risks, and the controls that compensate:

| Risk | Compensating control |
|---|---|
| Compromise of the source identity automatically yields all bound identities' authority. | (a) Bindings are administratively provisioned (not user-driven). (b) Step-up MFA at the swap for high-sensitivity targets (§3.4 + §4.4). (c) `mfa_required` flag per binding row in `linked_identity_tbl` — admin chooses which bindings demand fresh MFA on each swap. |
| The source session's age "carries" into the target with no fresh signal. | AuthnContext class explicitly identifies linked-identity swaps (§3.4). BFFs can refuse to operate at that class on sensitive endpoints. |
| Silent swap might bypass per-identity MFA policies. | The binding row carries `mfa_required`; when true, P1 challenges MFA before emitting the SAML Response — even though no password is asked for. |
| User cannot tell which identity is active. | SPA must display the active username + audience prominently in the chrome (top-right "Logged in as petar.trader • Trading"). Non-negotiable UX requirement. |
| Cross-firm or cross-tenant bindings could become an SoD bypass. | DB constraint: `linked_identity_tbl` can carry a `firmCd` consistency check, or admin UI refuses to create bindings that cross firm boundaries unless explicitly approved. Firm policy. |
| End-user-modifiable binding would silently expand access. | Schema-level enforcement: `linked_identity_tbl` write privilege is exclusively the gw-admin/firm-admin role. P1's existing role-permission framework already gates this — but the binding-management endpoints must be wired to it. |

The bottom line: the silent UX is safe **iff** bindings are admin-provisioned, swap events are audited, and sensitive targets re-challenge MFA via AuthnContext-aware step-up. Skip any of those three and the model collapses into "single-credential authority across all linked identities" — which is the SoD failure the original two-identity setup was designed to prevent.

---

## 7. P1-side prerequisites

None of these exist on `master` today. Each is a separable, testable change:

1. **`physical_person_id`** on `USER_TBL` (or `physical_person_tbl` entity). Population strategy: a one-time migration that groups existing `USER_TBL` rows by `(firmCd, email)` for an initial guess, then admin curation. Without this, the binding table has nothing to key on.
2. **`linked_identity_tbl`** schema (§3.1) + DAO + admin UI for CRUD.
3. **`IdpSsoAction.handle(AuthnRequest)`** extension to do binding lookup before building the SAML Response (§4.2).
4. **`AudienceRestriction` parsing** — IdpSsoAction must reliably extract the target audience from the inbound request. SAML `AudienceRestriction` is on the conditions block of the *response* the IdP emits; for the *request*, the equivalent is the `Issuer` + the SP entity ID. KC's SAML AuthnRequest carries the SP context in the `Issuer` element.
5. **AuthnContext propagation** (§3.4) — IdpSsoAction emits the right `AuthnContextClassRef` per code path (fresh login vs. linked swap).
6. **Admin gating on `linked_identity_tbl` mutations** — wire P1's authorization layer (`PolicyRuleManager`, see `p1-auth-flow.md` §1.3) to require gw-admin or firm-admin for any binding write.
7. **Audit logging hooks** (§4.5) — the `linked_identity_swap` event must be a first-class audit event, not buried in generic SAML logs.

---

## 8. Open questions before implementation

1. **Physical-person modelling.** Is a `physical_person_id` already implicit anywhere in P1 today (HR-side employee ID, advisor primary key, …)? If yes, reuse it; if no, decide whether to introduce a new entity or stretch an existing one. Affects the migration path more than the runtime.
2. **Bootstrap policy.** When a user has no binding for an audience and clicks through to it, do we (a) redirect to login and let them authenticate fresh (§3.3 (a) — implicit "I will create the binding on this side") or (b) refuse silently with a "no access" page (§3.3 (b) — admin must provision)? Firm SoD posture decides.
3. **MFA on linked swap.** Is MFA required by default on every swap, or only for some bindings? `mfa_required` per row gives flexibility, but the default value matters.
4. **Cross-firm bindings.** Allowed at all? If yes, what extra approval workflow? Most SoD models forbid these outright.
5. **Logout semantics with linked identities.** If the user logs out of trading, should KC's back-channel logout also tear down billing's session? The OIDC back-channel logout the BFFs already implement assumes one session per browser. With two parallel identities, the correct default is "logout is per-audience" — but it needs to be confirmed against the firm's expected UX.
6. **Discoverability.** Should billing's SPA hide the trading link if no binding exists? (Pre-flight lookup against P1, or surface in the OIDC userinfo claim.) Eliminates the "click → no access" UX entirely for unprovisioned users.

---

## 9. Summary

The silent cross-domain SSO works by moving identity resolution **into the IdP**. P1 maintains a binding table that maps `(physical-person, audience) → identity`. When a SAML AuthnRequest arrives for a target audience, P1 consults the table and emits the assertion as the bound identity — even though its HttpSession holds a different identity. No login screen, one extra DB lookup, one explicit SAML AuthnContext that says "this was a linked swap, not a fresh login".

Fallback when no binding exists: redirect to login (or a "no access" page — firm policy).

The mechanism is the standard linked-accounts / account-aliasing pattern shipped by every major IdP vendor. SOC2-defensibility (§5) holds **iff** bindings are administratively provisioned, every swap is audited with source + target subject, and sensitive targets enforce step-up MFA via the AuthnContext signal.

The previous revision recommended `ForceAuthn` on every click, which guaranteed SoD by re-prompting credentials but did not deliver the silent UX. This revision moves the SoD enforcement into the binding table + audit + AuthnContext-aware step-up — preserving the SoD guarantee while delivering the silent click-through experience.
