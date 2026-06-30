# Third-Party Firm SAML Login — Approach Report & Risk Analysis

Companion to `saml-firm-integration-plan.md`. This document explains **why** the
proposed approach was chosen, inventories the **existing SAML assets** in the
GeoWealth ("P1") codebase, and gives a structured **risk analysis**.

---

## 1. Existing SAML assets in `~/nodejs/geowealth`

The legacy platform already contains four distinct SAML flows. Knowing all four
matters: the integration only ports **one** of them (inbound firm login), and
must avoid disturbing the other three. OpenSAML version in P1 is **5.1.6**
(`build.gradle.kts:301-303`).

| # | Flow | P1's SAML role | Direction | Touched by this plan? |
|---|---|---|---|---|
| 1 | **Firm login** (firm IdP → P1) | **SP** | inbound | **Yes — this is what we port** |
| 2 | P1 → Keycloak (`com.geowealth.neo.saml.idp.*`) | IdP | outbound | No (this IS the demo's current `p1` broker) |
| 3 | P1 → 55ip (`agent.fiftyfiveip.*`) | IdP | outbound, server-to-server | No |
| 4 | P1 → iCapital (`agent.subdocintegration.icapital.*`) | IdP | outbound, server-to-server | No |

### 1.1 The flow being ported (inbound firm login — the SP side)

- Endpoint `/ssoAuth.do` (`SSOAction.authenticate`), IdP-initiated unsolicited
  `SAMLResponse` POST. No `AuthnRequest`, no `InResponseTo` correlation.
- Per-firm `FirmSSOConfig` (in `FIRM_SSO_TBL`): `entityId`, `ssoUrl`, IdP
  metadata, `primaryCertificate` (+ unused `secondaryCertificate`),
  `roleCapabilityMappings`, enforcement level.
- Validation in `SamlValidator` (hand-rolled OpenSAML): signature vs configured
  cert, issuer match, `NotBefore`/`NotOnOrAfter`, audience contains
  `geowealth.com`.
- Identity: `NameID` (= email) → `NEntityDAO.findByLoginAndFirm`; active
  non-admin adviser only.
- Roles: resolved from P1's own DB via `SsoRoleTranslator`, **not** from SAML
  attributes — firms only supply the NameID.

### 1.2 The outbound flows (context only — not changed)

- **P1 → Keycloak**: P1 is the IdP behind the demo's `p1` broker. Emits SAML
  attributes `personId`, `email`, `firstName`, `lastName`, `firmCd`,
  `memberships`, `roles`; Keycloak maps them with `saml-user-attribute-idp-mapper`
  + `saml-role-idp-mapper`. **This is the exact mapper machinery the firm-login
  plan reuses** — the demo realm already proves the pattern end-to-end.
- **P1 → 55ip / iCapital**: agent-driven, server-to-server signed Responses via
  the shared `AbstractSamlAuthenticationResponseBuilder` (signing-credential
  mtime cache). Independent of user login; out of scope.

---

## 2. The approach and why

### 2.1 Chosen approach: model each firm as a Keycloak SAML IdP (broker)

Keycloak becomes the SP/broker; **each external firm = one SAML Identity
Provider instance** in `demo-realm`. Login is SP-initiated via
`kc_idp_hint=<firm-alias>`; first-broker-login auto-links by email; per-IdP
mappers translate firm attributes → realm roles + `firmCd`. Downstream
(token-handler, SPAs, BFFs) is unchanged.

### 2.2 Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| **A. Port P1's bespoke SP into the demo** (re-implement `SSOAction`/`SamlValidator`) | Rejected | Re-introduces hand-rolled SAML crypto, the Firm-1 fallback, and the dead secondary-cert path. High security surface, no reuse of KC. |
| **B. Keep P1 as the only IdP; firms chain firm-IdP → P1 → KC** | Rejected | Double-hop SAML, two trust links to maintain per firm, P1 stays on the critical path. Defeats the auth-extraction goal. |
| **C. Keycloak brokers each firm IdP directly** (chosen) | **Selected** | Standards-based, KC validates signatures/conditions, per-firm isolation via IdP alias, reuses the mapper shape already shipping for `p1`, zero new infra per firm. |

### 2.3 Why C wins

- **Less code, less crypto risk.** Signature/condition/audience validation moves
  from hand-written `SamlValidator` to Keycloak's audited broker.
- **Proven in-repo.** The `p1` IdP + `saml-role-idp-mapper`×8 +
  `firm-cd-claim` already demonstrate attribute/role brokering into the exact
  downstream the firms need.
- **Operationally cheap.** Add-a-firm = one IdP instance + mappers via admin API
  (idempotent script, like `apply-cross-subdomain-sso.sh`), no new client, no new
  token-handler, no redeploy. Sessions stay in Redis; nobody is logged out.
- **Fixes legacy defects by construction.** SP-init correlation replaces the
  uncorrelated unsolicited POST; unknown-firm errors instead of Firm-1 fallback;
  KC's two-cert support replaces the secondary-cert that was never validated.

---

## 3. RISK ANALYSIS

Severity = likelihood × impact on a 1–5 scale; **Sev** column is the headline
rating (Low / Med / High).

### 3.1 Security risks

| ID | Risk | Sev | Mitigation |
|---|---|---|---|
| S1 | **Unsigned / forged assertions accepted.** A firm IdP misconfigured (or an attacker) submits an unsigned or wrongly-signed Response. | High | `validateSignature=true` + `wantAssertionsSigned=true` on every firm IdP; pin `signingCertificate`; never enable `validateSignature=false`. KC rejects by default — verify per-IdP, don't inherit. |
| S2 | **Email-linking account takeover.** First-broker-login links by email; a firm that can assert an arbitrary email could impersonate a user in another firm. | High | Scope linking by IdP: do **not** allow cross-IdP auto-link to an existing federated identity from a different alias. Constrain each firm IdP to emails it owns (domain check in a first-broker-login authenticator), or use a firm-prefixed username. Mirror the legacy `findByLoginAndFirm` *firm scoping* — never link by bare email across firms. |
| S3 | **NameID format drift.** Two in-repo precedents disagree: legacy firm→P1 used NameID==email; the demo's `p1` broker uses `nameid-format:persistent` (UUID `P-tim`) + email-as-attribute (`IdpSsoAction.java:71`). Assuming email-NameID can break linking or create duplicates if a firm uses persistent/transient. | Med | **Recommended:** mirror `p1` — link on a stable NameID / `principalAttribute` and map email from an attribute, not a bare email NameID. Per firm, confirm NameID format at onboarding. (`p1`'s policy is not in `realm-export.json` — set it in the onboarding script.) |
| S4 | **Audience / replay.** Legacy required audience `geowealth.com`; KC must enforce its own SP entityId as audience and reject replay. | Med | KC validates `Destination`/audience against the broker endpoint automatically; confirm clock skew tolerance is sane and assertions are short-lived. |
| S5 | **Certificate expiry / rotation gap.** Firm signing cert expires → all that firm's users locked out; or stale cert lingers after firm rotates. | Med | Two-cert `signingCertificate` (overlap window); generalise `sync-saml-cert.sh` per alias; monitor `notAfter` and alert ahead of expiry. |
| S6 | **Firm-1 fallback re-introduced.** If host→firm routing is sloppy, an unknown firm could resolve to a default tenant. | Med | Explicit error on unknown `kc_idp_hint` / unknown host; no silent default. Drop the legacy fallback entirely (already a non-goal). |
| S7 | **Open redirect / RelayState abuse** during broker round-trip. | Low | KC validates redirect against `demo-shared-client` `redirectUris`; keep that list tight per host. |
| S8 | **Secrets in config.** Legacy partner keystores ship plaintext passwords in `etc/*.properties` (`GeoWealth2024`, `password`). Don't repeat in the demo. | Med | Firm IdP signing certs are public (no secret). KC admin creds and any client secret go through the existing env/secret mechanism, not committed files. |

### 3.2 Operational risks

| ID | Risk | Sev | Mitigation |
|---|---|---|---|
| O1 | **Per-firm onboarding toil / drift.** Manual admin-API edits drift across dev/qa/prod (the exact problem `reconcile-realm.sh` was built for). | Med | Idempotent `onboard-firm-idp.sh` driven by a per-firm descriptor; env URLs via `urls.<env>.env` + `reconcile-realm.sh`. Never hand-edit the live realm. |
| O2 | **Realm import is `IGNORE_EXISTING`.** A new firm added only to `realm-export.json` won't appear on a running realm. | Med | Always apply via admin API on running stacks (documented gotcha); keep `realm-export.json` in sync for fresh installs. |
| O3 | **Metadata URL fetched at runtime** could fail or be slow if KC re-fetches firm metadata. | Low | Prefer importing cert/endpoints statically into the IdP config over a live `metadataUrl`; treat metadata as onboarding-time input. |
| O4 | **Logout fan-out.** More IdPs = more SLO endpoints; a firm with no SLO support leaves stale sessions. | Med | Rely on token-handler/back-channel logout already in place; document that firm SLO is best-effort; session TTL in Redis bounds exposure. |
| O5 | **Observability.** Failures move from P1 logs to KC; ops must know where to look. | Low | Document KC broker login events + first-broker-login flow in the runbook; surface per-IdP login failures. |

### 3.3 Migration / project risks

| ID | Risk | Sev | Mitigation |
|---|---|---|---|
| M1 | **Roles aren't in the firm assertion — CONFIRMED.** Verified in legacy code: `SamlValidator` returns only `isValid()`+`nameId()`, no `AttributeStatement` parsing in the inbound SP path; roles come from P1's DB (`SamlManagerTrait.java:495` `loadRolesForEntity`) via `SsoRoleTranslator`. KC mappers will have no role attribute to map. | High | **Decided:** attach roles/`firmCd` via a **post-broker lookup** (the realm already has `user-service-spi`), as P1 does today. Do **not** design firm IdPs around assertion-borne roles. |
| M2 | **Big-bang cutover.** Switching all firms at once risks a wide outage. | High | Phase 3 runs legacy P1 SP and KC broker in parallel; migrate firm-by-firm; keep rollback per firm. |
| M3 | **Test IdP fidelity.** Spike uses a stand-in IdP; real firm IdPs differ (signing alg, NameID, attribute names). | Med | Validate against at least one real firm's metadata before declaring Phase 1 done; capture per-firm quirks in the descriptor. |
| M4 | **Scope creep into outbound flows.** Touching SAML tempts changes to 55ip/iCapital/P1-IdP. | Low | Explicit non-goal; outbound flows unchanged. |
| M5 | **Enforcement semantics.** Legacy `optional` allowed local login too; this realm has no local users. | Low | Always-SSO here; document that `optional`/`mandatory` collapse to "SSO only" in the demo. |

### 3.4 Residual risk after mitigations

With S1/S2/M1 addressed up front (signature enforcement, firm-scoped email
linking, and a decided role-source), residual risk is **Low–Medium**, dominated
by operational onboarding toil (O1) — itself bounded by the idempotent
scripting the repo already uses for `p1`.

---

## 4. Recommendation

Proceed with **Approach C** (Keycloak per-firm SAML broker). Start with the
Phase 0 spike against a stand-in IdP, and **resolve the three high-severity
items first**:

1. **M1 — role source:** confirm whether firm assertions carry roles, or wire a
   post-broker `firmCd`/role lookup.
2. **S2 — linking safety:** firm-scoped email linking (no cross-firm auto-link).
3. **S1 — signature enforcement:** `validateSignature` + `wantAssertionsSigned`
   on every firm IdP, verified, not assumed.

Everything else is well-trodden ground in this repo (the `p1` broker already
demonstrates the full attribute/role/firmCd path end-to-end).
