# Third-Party Firm SAML Login — Integration Plan

Status: Draft / proposal. Target architecture: the Keycloak SSO tier in this
repo (`demo-realm` + multi-tenant `token-handler` + per-domain SPAs). Source of
the legacy behaviour being ported: the GeoWealth "P1" platform at
`~/nodejs/geowealth`.

---

## 1. Problem statement

In the GeoWealth ("P1") platform, **external client firms log in with their own
corporate SAML Identity Provider**. P1 acts as a SAML **Service Provider (SP)**:
the firm's IdP posts a signed SAML Response to P1, P1 validates it and starts a
session. Each firm is onboarded with a per-firm configuration record
(`FirmSSOConfig`) holding the firm's IdP entity id, signing certificate, and a
role→capability mapping.

The current repo (`keycloak-demo`) brokers **exactly one** upstream IdP — `p1`
(P1 itself, as a SAML IdP) — via Keycloak, and every login funnels through
`kc_idp_hint=p1`. There is no notion of "log in with *firm X's* IdP".

**Goal:** bring the third-party-firm SAML login capability into this Keycloak
architecture, so that an arbitrary number of external firm IdPs can be onboarded
and used to log a user into the domain SPAs (billing, trading, …) — without
hand-rolling SAML validation the way P1 does.

---

## 2. How firms logged in before (legacy P1 — reference)

Reverse-engineered from `~/nodejs/geowealth`. Full detail in the companion
report; the load-bearing facts:

| Aspect | Legacy P1 implementation |
|---|---|
| SAML role | P1 is the **SP**; the firm is the **IdP** |
| Binding / flow | **IdP-initiated**, unsolicited SAML Response, HTTP-POST to `/ssoAuth.do` (`SSOAction.authenticate`) — no `AuthnRequest` is ever sent |
| Firm detection | URL → firmCd via `AuthorizationManager.identifyFirmByUrl` (whitelabel host); **falls back to Firm 1 if undetected** ⚠ |
| Validation | OpenSAML 4.x: Response/Assertion signature vs the firm's configured cert, issuer == `entityId`, `NotBefore`/`NotOnOrAfter`, `AudienceRestriction` must contain `geowealth.com` |
| Identity | `NameID` (= email / LDAP UID) → `NEntityDAO.findByLoginAndFirm(nameId, firmCd)`; user must be an active, non-admin adviser |
| Per-firm config | `FIRM_SSO_TBL` → JSON `FirmSSOConfig`: `entityId`, `ssoUrl`, IdP metadata (url/xml), `primaryCertificate` (+ optional `secondaryCertificate` for rotation), `roleCapabilityMappings` |
| Role mapping | `SsoRoleTranslator`: global coarse caps (`client`/`advisor`/`admin`/`gwAdmin`) + per-firm `role → [capabilities]` |
| Onboarding | Self-service admin UI (`FirmSsoConfigAction`): paste IdP metadata URL/XML → extract cert → save → set enforcement (`off`/`optional`/`mandatory`) |
| Known defects | secondary-cert validation reads the *primary* cert (rotation cert never really checked); Firm-1 fallback; `metadataManual` declared but rejected at runtime; retired IdP-mode dead code in `struts-saml-idp.xml` |

The legacy design is a **bespoke SP**: every firm = one row + custom OpenSAML
parsing. There is no standards-based broker; signature/condition validation is
all hand-written in `SamlValidator`.

---

## 3. Target architecture — Keycloak as the per-firm SAML broker

Keycloak is purpose-built for exactly the legacy use case: brokering an
arbitrary number of **external SAML IdPs**. The integration models **each firm
as one Keycloak SAML Identity Provider instance** in `demo-realm`, with Keycloak
acting as the SP/broker. Everything downstream (token-handler, SPAs, BFFs) is
unchanged — they keep consuming OIDC tokens from `demo-shared-client`.

```
 Firm employee's browser
        │  (SP-init: kc_idp_hint=<firm-alias>, OR Home-Realm Discovery by email domain)
        ▼
 Keycloak demo-realm  ── SAML AuthnRequest ──►  Firm corporate IdP
   (SP / broker)       ◄── signed SAML Response ──
        │  first-broker-login: auto-link by email, run IdP mappers
        │  (firm SAML attrs → realm roles + firmCd user attribute)
        ▼
   OIDC code → demo-shared-client → token-handler session (GWSESSION) → SPA
```

### What changes vs. what stays

| Layer | Legacy P1 | Target (this repo) |
|---|---|---|
| SAML validation | hand-rolled OpenSAML (`SamlValidator`) | **Keycloak built-in** SAML broker |
| Per-firm config | `FIRM_SSO_TBL` JSON | **one KC SAML IdP instance per firm** (alias = firm slug) + IdP mappers |
| Cert / metadata | stored in `FirmSSOConfig`, parsed by `SamlMetadata` | KC IdP config (`importFrom` metadata URL/XML, `signingCertificate`, `validateSignature=true`) |
| Identity linking | `findByLoginAndFirm` | `p1-first-broker-login`-style flow: **auto-link by email** |
| Role/firmCd mapping | `SsoRoleTranslator` | per-IdP `saml-role-idp-mapper` (+ `saml-user-attribute-idp-mapper` for `firmCd`), `syncMode=FORCE` — same shape already used for `p1` |
| Downstream | P1 servlet session | unchanged: OIDC → token-handler → SPA |
| Onboarding | P1 admin UI | admin-API script (mirrors `apply-cross-subdomain-sso.sh` / `sync-saml-cert.sh`), one IdP + mappers per firm |

### Key design decisions

1. **Flow: SP-initiated, not IdP-initiated.** The legacy unsolicited-POST flow
   is the weak part (no `InResponseTo` correlation, Firm-1 fallback). Keycloak's
   supported, correlated path is SP-init via `kc_idp_hint`. The SPA already does
   `keycloak.login({ idpHint })` — we parametrise the hint per firm instead of
   hard-coding `p1`. (Pure IdP-init against an OIDC client target does **not**
   work cleanly in KC — same caveat already documented in CLAUDE.md.)

2. **Firm selection / Home-Realm Discovery.** Three options, pick per UX need:
   - explicit `kc_idp_hint=<firm-alias>` from a firm-branded entry URL (closest
     to the legacy per-firm whitelabel host), **recommended**;
   - email-domain → IdP discovery on the KC login screen
     (`firstBrokerLogin` / org domains), if a generic login page is wanted;
   - host → firm mapping in the token-handler tenant map, reusing the existing
     `Host`-based tenancy.
   Do **not** replicate the Firm-1 silent fallback — unknown firm = explicit error.

3. **Account linking = email, FORCE sync.** Reuse the `p1-first-broker-login`
   pattern: auto-create + auto-link by email on first sign-in; `syncMode=FORCE`
   so roles/firmCd are re-asserted from the firm IdP on every login (no stale
   local grants). NameID format stays `emailAddress` to match the legacy
   NameID-is-email contract.

4. **Role & tenant mapping per firm.** For each firm IdP add:
   - N× `saml-role-idp-mapper` (firm's SAML role attribute value → realm role),
     covering the same vocabulary as `p1` (`billing-*`, `trading-*`, coarse
     `client/advisor/admin`). Firms that name roles differently get firm-specific
     value→role mappers — this is where legacy `roleCapabilityMappings` lands.
   - 1× `saml-user-attribute-idp-mapper` writing `firmCd` (so the BFF tenant
     scoping and the whitelabel brand resolution keep working unchanged).

5. **Certificate management & rotation.** Per-firm `signingCertificate` in the
   IdP config; `validateSignature=true`. Reuse `sync-saml-cert.sh` (generalise
   its `IDP_ALIAS` to take any firm alias) for rotation. Keycloak supports two
   certs in `signingCertificate` (newline-separated) — this is the *correct*
   replacement for the legacy secondary-cert that was never actually validated.

6. **No new OIDC client, no new token-handler instance.** Multi-tenant model is
   untouched: firms broker into the *same* `demo-shared-client`; the existing
   `app.tenants.*` host gate and `GWSESSION` cookie are reused. Adding a firm =
   add a KC SAML IdP + mappers (+ optionally a branded entry host), **not** new
   infra.

---

## 4. Implementation phases

### Phase 0 — spike (one hard-coded firm)
- Stand up a throwaway external SAML IdP (e.g. a second Keycloak realm, or
  `samltest.id`) to play "firm Acme".
- Create IdP alias `acme` in `demo-realm` via admin API: `providerId=saml`,
  import metadata, `validateSignature=true`, NameID/link key per Open Question #2
  (start with the stand-in's format; for the persistent path set
  `principalType=ATTRIBUTE`/`principalAttribute` + an email attribute mapper),
  `syncMode=FORCE`, `firstBrokerLoginFlowAlias=p1-first-broker-login`.
- Add `saml-user-attribute-idp-mapper` (email + firmCd). Roles come from the
  post-broker lookup (Open Question #1, confirmed), **not** from a role mapper —
  the firm assertion carries no roles.
- Drive login with `kc_idp_hint=acme` from a domain SPA; confirm token-handler
  session + SPA dashboard + correct roles/firmCd.
- **Exit criteria:** a firm-IdP user reaches the billing/trading dashboard with
  the right realm roles and `firmCd` claim.

### Phase 1 — productise onboarding
- Generalise `apply-cross-subdomain-sso.sh` into `onboard-firm-idp.sh <firm>`:
  POST the IdP instance + its mapper set idempotently (PUT-update on re-run),
  driven by a small per-firm descriptor (metadata URL/XML, role→role map,
  firmCd).
- Generalise `sync-saml-cert.sh` (`IDP_ALIAS=<firm>`) for rotation.
- Add the firm's branded entry URL (host or `?kc_idp_hint=<firm>` deep link) to
  the P1/Integrations link source where appropriate.
- Document "Adding a firm" in `CLAUDE.md` (parallel to "Adding a domain").

### Phase 2 — Home-Realm Discovery (optional, if a generic login page is needed)
- Configure email-domain → IdP mapping so a user typing `name@acme.com` is
  routed to the `acme` IdP without an explicit hint.
- Keep `kc_idp_hint` deep links as the fast path.

### Phase 3 — migration from legacy P1 FirmSSOConfig
- Export each `FIRM_SSO_TBL` row → generate one firm descriptor for
  `onboard-firm-idp.sh` (see mapping table below).
- Reconcile per-environment URLs via the existing `urls.<env>.env` +
  `reconcile-realm.sh` mechanism (firm IdP SSO URLs are env-specific data).
- Run both flows in parallel during cutover (legacy P1 SP + KC broker), migrate
  firm-by-firm, then retire `SSOAction` / `SamlValidator` / `FirmSSOConfig`.

### Phase 4 — decommission legacy
- Remove the bespoke SP path in P1 once all firms are brokered through KC.
- Delete already-dead IdP-mode code flagged in `struts-saml-idp.xml`.

---

## 5. FirmSSOConfig → Keycloak mapping table

| Legacy `FirmSSOConfig` field | Keycloak target |
|---|---|
| `entityId` | IdP config `idpEntityId` / metadata issuer |
| `ssoUrl` | IdP config `singleSignOnServiceUrl` |
| `metadataUrl` / `metadataXml` | `importFrom` (admin API metadata import) |
| `primaryCertificate.body` | IdP config `signingCertificate` (cert 1) |
| `secondaryCertificate.body` | IdP config `signingCertificate` (cert 2, newline-joined) — now actually validated |
| NameID | per firm: `emailAddress` (legacy default) **or** persistent + email-as-attribute (the `p1` precedent, recommended); link on the chosen stable key, not a bare email — see Open Question #2 |
| `roleCapabilityMappings[firmRole] → caps` | one `saml-role-idp-mapper` per (firmRoleValue → realm role) |
| firmCd (implicit per firm) | `saml-user-attribute-idp-mapper` → `firmCd` user attr → OIDC `firm-cd-claim` |
| `enforcementType` (off/optional/mandatory) | IdP `enabled` + whether the SPA offers a non-SSO path (here: always SSO) |
| alias / firm slug | KC IdP `alias` (e.g. `acme`) used in `kc_idp_hint` |

---

## 6. Non-goals / explicitly out of scope

- Replicating IdP-initiated unsolicited POST (intentionally dropped — SP-init via
  `kc_idp_hint` is the supported, correlated path).
- Replicating the Firm-1 silent fallback (security defect — unknown firm errors).
- P1's outbound IdP roles (P1→Keycloak, P1→55ip, P1→iCapital) — those are the
  reverse direction and are covered in the report's "existing SAML assets"
  section, not changed by this plan.

---

## 7. Open questions

1. ~~Do firms emit roles as a SAML attribute, or only NameID?~~ **RESOLVED — only
   NameID.** Verified in the legacy code: `SamlValidator` returns a
   `SamlValidationResult` carrying only `isValid()` + `nameId()`; there is **zero**
   `AttributeStatement` parsing anywhere in the inbound SP path
   (`agent/saml/*`, `SSOAction`). Roles are loaded from P1's **own DB** at
   `SamlManagerTrait.java:495` (`loadRolesForEntity(userId)`) and translated by
   `SsoRoleTranslator`. **Consequence:** firm IdP assertions carry no roles, so
   KC role mappers have nothing to map — role/`firmCd` MUST be resolved by a
   **post-broker lookup** (reuse the existing `user-service-spi`), exactly as P1
   does today. Do not design the firm IdPs around assertion-borne roles.
2. NameID stability — **two in-repo precedents disagree, decide explicitly.**
   The legacy firm→P1 SP flow used **NameID == email** (`findByLoginAndFirm`).
   But the demo's existing `p1` broker uses **`nameid-format:persistent`** (NameID
   = P1 user UUID `P-tim`, NOT email) with email/firmCd/roles carried as SAML
   **attributes** (`cross-subdomain-sso-implementation.md:58,99`;
   `IdpSsoAction.java:71`). **Recommendation:** mirror the `p1` precedent — link
   firms on a stable NameID (or a `principalAttribute`) and map email from an
   attribute, rather than linking on a bare email NameID. This survives a user's
   email changing and avoids the S2 cross-firm-email takeover surface. Per firm,
   confirm whether their IdP emits email vs persistent/transient NameID and pick
   the link key accordingly. (Note: `p1`'s NameID policy is **not** in
   `realm-export.json` — `identityProviders` is empty there; the IdP is seeded at
   runtime, so set the firm IdP's `nameIDPolicyFormat`/`principalType` in the
   onboarding script, not the export.)
3. ~~Per-firm host/branding vs. single login page with HRD?~~ **DECIDED — one
   branded host per firm (Option A).** Closest to the legacy `identifyFirmByUrl`
   model; reuses the existing host-based tenancy + whitelabel in token-handler;
   each host carries `kc_idp_hint=<firm>`. **Phase 2 (HRD) is dropped** unless a
   generic single login page is later requested.
4. ~~Enforcement levels — is `optional` (SSO *or* local login) needed?~~
   **DECIDED — always-SSO.** This realm has no native users (local login is
   non-functional by design), so the legacy `off`/`optional`/`mandatory` levels
   all collapse to "SSO only". No enforcement logic to build; documented in the
   report (M5).
