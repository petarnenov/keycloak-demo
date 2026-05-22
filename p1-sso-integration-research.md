# SSO Integration: P1 → keycloak-demo (MFE-BFF)

Research and architectural decision for adding a "Demo MFE-BFF" entry to the P1 sidebar that opens `http://localhost:5173/` (keycloak-demo) with automatic SSO from the P1 Tomcat session into Keycloak.

**Date:** 2026-05-21
**Sources:** `/home/petar/keycloak-demo/` + `/home/petar/nodejs/geowealth/`

---

## 1. Context

### 1.1 P1 (GeoWealth)

| Aspect | Value |
|---|---|
| BE framework | Struts 2 + Tomcat |
| Auth mechanism | Session-based (JSESSIONID cookie) |
| Session timeout | 420 minutes (`WebContent/WEB-INF/web.xml:149-152`) |
| Login endpoint | `/react/login.do` (`LoginAction.java:94-180`) |
| Logout endpoint | `/react/logout.do` (`LogOutAction.java:21-80`) |
| Current user endpoint | `/react/isUserLoggedIn.do` (`appService.js:13`) |
| Sidebar config | `WebContent/react/app/src/pages/PlatformOne/sidebar/_hooks/useSideBarLinks.js:27-1111` |
| Integration links hook | `WebContent/react/app/src/pages/PlatformOne/sidebar/_hooks/useIntegrationLinks.js:17-27` |
| OIDC/OAuth adapter | **None** |
| SAML — admin UI (SP configuration) | `WebContent/react/app/src/pages/PlatformOne/pages/Integrations/pages/SingleSignOn/` |
| SAML — runtime IdP code | **Yes, real OpenSAML 5.1.6** (see section 3) |

### 1.2 keycloak-demo

| Aspect | Value |
|---|---|
| FE | React shell at `localhost:5173` + 3 MFE federation remotes (`5181/5182/5183`) |
| Auth mechanism | Authorization Code + PKCE against Keycloak |
| Keycloak URL | `localhost:8898`, realm = `demo-realm` |
| OIDC client | `mfe-shell-client` |
| 2FA | Email OTP authenticator + HMAC trust cookie (60 min) |
| User store | Standalone Micronaut REST service (`user-service/`) + Keycloak User Storage SPI |
| Demo users | `democlient` (`client` role), `demouser` (`user`), `demoadmin` (`admin`, `user`) — password `123` |
| BFFs | `bff-client:8081`, `bff-ops:8082`, `bff-admin:8083` — JWKS-validating Micronaut services |
| Identity Brokering | **Not configured** (Keycloak natively supports it) |

### 1.3 User constraints

| # | Constraint | Resolution |
|---|---|---|
| 1 | **User mapping = 1:1** | Real P1 user ↔ Keycloak user (not aliased to `demoadmin`) |
| 2 | **Target = Production** | Not a demo; audit, key rotation, SLO, hardening required |
| 3 | **Back-channel reachability** | Keycloak in a container ↔ P1 on host via `host.docker.internal` |
| 4 | **Leverage existing P1 SAML code** | Reuse `AbstractSamlAuthenticationResponseBuilder` |

---

## 2. Initial four solutions (analyzed)

### Solution 1 — OIDC Identity Brokering (P1 as OIDC IdP)

**Idea:** P1 implements OIDC IdP endpoints (`/.well-known/openid-configuration`, `/authorize`, `/token`, `/userinfo`, `/jwks`). Keycloak adds P1 as an External OIDC Identity Provider.

| Parameter | Value |
|---|---|
| Effort | **15–20 days** (greenfield in Struts 2 — JWT signing, OAuth endpoints, PKCE storage, consent screen, discovery) |
| Industrial-standard | ⭐⭐⭐⭐⭐ |
| Scales | ✅✅✅ — N apps just add broker config |
| Pros | Native to Keycloak; refresh tokens, logout propagation, audience scoping out of the box; full audit trail |
| Cons | Highest effort; signing-key + JWKS rotation management; P1 must be reachable from Keycloak |
| Verdict | **Rejected for this iteration** — greenfield effort too large given existing P1 SAML code |

### Solution 2 — SAML Identity Brokering (P1 as SAML IdP)

**Idea:** P1 implements SAML IdP endpoints. Keycloak adds P1 as a SAML SP federating to P1. Sidebar button → P1 emits a signed SAML Response → browser POSTs to Keycloak ACS.

| Parameter | Value |
|---|---|
| Effort | **15 days (production-grade)** (see section 4) |
| Industrial-standard | ⭐⭐⭐⭐⭐ (battle-tested for a decade; Okta/ADFS/Ping pattern) |
| Scales | ✅✅✅ |
| Pros | Reuses existing P1 OpenSAML code; Keycloak native SAML SP support; back-channel friendly; future-proof |
| Cons | XML/XMLDSig debugging less convenient; signing cert rotation; production hardening required |
| Verdict | ✅ **SELECTED** — see section 4 |

### Solution 3 — OAuth 2.0 Token Exchange (RFC 8693)

**Idea:** P1 BE mints a subject_token (signed JWT). A browser handoff page calls Keycloak `/token` with `grant_type=urn:ietf:params:oauth:grant-type:token-exchange`.

| Parameter | Value |
|---|---|
| Effort | 5–7 days |
| Industrial-standard | ⭐⭐⭐⭐ (RFC 8693, native Keycloak feature) |
| Scales | ✅✅ |
| Pros | Standards-based middle ground; Keycloak event log records the exchange; short-lived JWT |
| Cons | `keycloak-js` does not expect pre-existing tokens (handoff trickier); external-issuer config docs are less mature; not a clean federation pattern for 1:1 production user mapping |
| Verdict | **Rejected** — half-way to OIDC IdP without the real federation benefits |

### Solution 4 — Custom Keycloak Authenticator SPI ("Magic Token" handoff)

**Idea:** Custom SPI in `keycloak-provider/` reads URL param `kc_p1_token=<UUID>`, makes a back-channel call to a P1 verify endpoint; on success → `context.success()` with the user.

| Parameter | Value |
|---|---|
| Effort | 4–6 days |
| Industrial-standard | ⭐⭐⭐ (custom plugin, not a federation standard) |
| Scales | ✅ (each new source = new authenticator) |
| Pros | Reuses existing SPI infra in `keycloak-provider/`; opaque token = single-use; no JWT/SAML overhead |
| Cons | Custom plugin = ops burden (every Keycloak upgrade = retest); not a federation standard; coupling between Keycloak and the P1 endpoint |
| Verdict | **Rejected for production** — acceptable for a PoC, not for production federation with 1:1 user mapping |

---

## 3. Deep audit of the P1 SAML implementation

### 3.1 Executive summary

P1 implements **both sides** of SAML 2.0:

1. **SP (Service Provider) mode (production-facing):** P1 *consumes* SAML Responses from external IdPs for firm-configured SSO. Customer firms configure their own IdP in P1's admin UI.
2. **IdP (Identity Provider) mode (domain-specific):** P1 *constructs, signs, and emits* SAML Responses to external SPs (FireLight, 55IP, iCapital).

**Conclusion:** Using P1 as an IdP for Keycloak is **technically possible** but requires new HTTP endpoint code. The signing infrastructure exists; the HTTP binding does not.

### 3.2 OpenSAML classes in P1

| File | Purpose |
|---|---|
| `src/main/java/com/geowealth/util/opensaml/AbstractSamlAuthenticationResponseBuilder.java` | **IdP builder abstraction** — constructs and signs SAML `Response` + `Assertion`. Loads signing keys from `.jks` keystores; RSA-SHA256 signing. `createSamlAuthorizationResponse()` (lines 103–220). |
| `src/main/java/com/geowealth/util/opensaml/OpenSamlUtils.java` | Utility factory for OpenSAML objects. `buildSAMLObject()` (line 9). |
| `src/main/java/com/geowealth/agent/saml/validator/SamlValidator.java` | **SP validator** — parses and validates incoming SAML Responses. `validate()` (lines 68–207) checks signature, issuer, time conditions, audience, X509 cert matching. |
| `src/main/java/com/geowealth/agent/saml/metadata/SamlMetadata.java` | **Metadata parser** — fetches and parses IdP metadata. `extractMetadataXMLDetails()` (lines 126–214). |
| `src/main/java/com/geowealth/service/insurance/SsoSAMLHelper.java` | **Insurance domain IdP** — builds SAML Responses for FireLight. `createSamlAssertion()` (lines 139–197), `createSamlResponse()` (lines 228–269). Hardcoded `firelight.pfx` + password `"1234"`. |
| `src/main/java/com/geowealth/agent/fiftyfiveip/FiftyFiveIpSamlAuthenticationResponseBuilder.java` | **55IP domain IdP** — extends `AbstractSamlAuthenticationResponseBuilder<>` for FiftyFiveIP enrollment. |
| `src/main/java/com/geowealth/agent/fiftyfiveip/FiftyFiveIpSamlManagerTrait.java` | **55IP IdP controller** — Atomatron agent trait, lines 77–104. |
| `src/main/java/com/geowealth/agent/subdocintegration/icapital/saml/ICapitalSamlAuthenticationResponseBuilder.java` | **iCapital domain IdP** — extends `AbstractSamlAuthenticationResponseBuilder<>`. |
| `src/main/java/com/geowealth/agent/fiftyfiveip/FiftyFiveIpAttributeStatementBuilder.java` | Maps FiftyFiveIP account data → SAML attribute statements. |
| `src/main/java/com/geowealth/agent/subdocintegration/icapital/saml/ICapitalAttributeStatementBuilder.java` | Maps iCapital proposal data → SAML attribute statements. |
| `src/main/java/com/geowealth/util/opensaml/AbstractAttributeStatementMapper.java` | Abstract mapper for domain → `AttributeStatement`. |

### 3.3 HTTP endpoints

**SP Mode (firm SSO — consumes Responses):**
- URL: `/ssoAuth.do`
- Class: `com.geowealth.web.common.SSOAction` (`src/main/java/com/geowealth/web/common/SSOAction.java:25`)
- Method: `authenticate()` (lines 29–106)
- Binding: HTTP-POST
- Flow:
  1. Firm's IdP POSTs a base64-encoded SAML Response
  2. Base64-decode (line 66)
  3. `SamlManager.getSole().validateSaml(firmCd, saml, ipAddress)` (line 69)
  4. Validator checks signature, issuer, conditions
  5. Extract NameID (user email), look up user by email + firmCd, log in

**IdP Mode (FireLight — emits Responses):**
- URL: `/ssoAuth.do` → `SSOFirelightAction` (`struts-ux.xml:1387`)
- `initiateSSO()` (`src/main/java/com/geowealth/ux/insurance/SSOFirelightAction.java:50-114`)
- `sendIndividualProduct()` (line 181+)
- Lines 206–207: `ssoSAMLHelper.createSamlAssertion()` + `.createSamlResponse()`
- Line 273: `HTTPPostEncoder` emits to FireLight's ACS

**IdP Mode (55IP, iCapital):** Atomatron messages → `FiftyFiveIpSamlManagerTrait` → `createSamlAuthorizationResponse()`. iCapital is similar.

**Admin Configuration:**
- `readFirmSsoConfig`, `saveFirmSsoConfig`, `deleteFirmSsoConfig`, `decodeSsoMetadata` → `FirmSsoConfigAction` (`struts-platformOne.xml:1349-1361`)

### 3.4 Flow direction — code proof

**P1 as SP (consumes Responses):**

```java
// SSOAction.java:29-76
String saml = getParameterAsString("SAMLResponse");
saml = new String(Base64.getMimeDecoder().decode(saml));
ValidateSamlMsg validationResult = SamlManager.getSole().validateSaml(
    firmCd, saml, getIPAddress()
);
String userEmail = validationResult.nameId();
User user = UserManager.getSole().lookupByUserUUID(userID.toString());
putToSession(LoginAction.LOGGED_ADVISER, new LoggedUser(user));
```

**P1 as IdP (emits Responses):**

```java
// SsoSAMLHelper.java:228-269
public Response createSamlResponse(String sourceName, Assertion assertion) {
    Response response = buildSAMLObject(Response.class);
    response.setVersion(SAMLVersion.VERSION_20);
    response.setID(Utils.generateSecureRandomId());

    PrivateKey key = loadPrivateKey("firelight.pfx");
    X509Certificate cert = loadPublicKey("firelight.pfx");
    basicX509Credential = new BasicX509Credential(cert, key);

    Signature signature = buildSAMLObject(Signature.class);
    signature.setSignatureAlgorithm(SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256);
    signature.setSigningCredential(basicX509Credential);
    response.setSignature(signature);
    response.getAssertions().add(assertion);
    return response;
}

// SSOFirelightAction.java:273-280
HTTPPostEncoder httpPostEncoder = new HTTPPostEncoder();
httpPostEncoder.setMessageContext(messageContext);
httpPostEncoder.encode();
```

### 3.5 Certificate / key management

**SP Mode (firm-configured IdPs):**
- Storage: DB table `FIRM_SSO_TBL` (`src/main/java/com/geowealth/model/organization/FirmSSO.java`)
- Fields in `FirmSSOConfig`:
  - `definitionMethod` (enum: URL, XML, MANUAL)
  - `entityId`
  - `ssoUrl`
  - `primaryCertificate` (`SamlCertificateInfo`)
  - `secondaryCertificate` (optional)

**IdP Mode (P1 signing):**
- Keystore: file-based `.pfx` (PKCS#12)
  - `firelight.pfx` for FireLight (`SsoSAMLHelper.java:238`)
  - Path: `Constants.devEtcPath + "/" + fileName` (hardcoded)
  - Password: hardcoded `"1234".toCharArray()` (line 251) — **production blocker**
- 55IP/iCapital: loaded from `AbstractSamlAuthenticationResponseBuilder` constructor (lines 75–99): `keyStorePath`, `keyStorePassword`, `keyStoreEntity`

**Admin UI (React):**
- Path: `WebContent/react/app/src/pages/PlatformOne/pages/Integrations/pages/SingleSignOn/`
- Form fields (from `useSingleSignOnFormFields.js`):
  - `FLD_ENFORCEMENT_TYPE` (OFF, OPTIONAL, REQUIRED)
  - `FLD_DEFINITION_METHOD` (URL, XML, MANUAL)
  - `FLD_ENTITY_ID`
  - `FLD_SSO_URL`
  - `FLD_PRIMARY_CERTIFICATE`
  - `FLD_SECONDARY_CERTIFICATE`

### 3.6 IdP-initiated capability

**Current state:**
- P1 does **not** expose a public IdP-Initiated SSO endpoint for arbitrary SPs.
- P1 constructs SAML Responses only in response to internal, domain-specific requests:
  - FireLight: `SSOFirelightAction.sendIndividualProduct()`
  - 55IP: Atomatron messages
  - iCapital: Atomatron messages

**`/ssoAuth.do` (`struts-tiles.xml:213`)** is the only externally exposed SSO action — but **SP mode only**.

**For IdP-Initiated toward Keycloak:**
- New Struts action (e.g., `initiateKeycloakSSO.do`)
- Returns a signed `Response`
- POST to Keycloak's ACS URL
- Reuse `AbstractSamlAuthenticationResponseBuilder` / `SsoSAMLHelper`

### 3.7 Metadata exposure

| Type | Status |
|---|---|
| SP Metadata (P1 as SP) | **Not exposed** |
| IdP Metadata (P1 as IdP) | **Not exposed** |

For Keycloak integration: either publish `/saml/idp/metadata` or configure Keycloak to accept P1 responses without metadata (trust by certificate only).

### 3.8 Single Logout (SLO)

**Status:** Not implemented.

- No SLO endpoints
- `SSOAction.authenticate()` performs login; no corresponding logout handling
- No `LogoutRequest` parsing or `LogoutResponse` emission

### 3.9 Dependencies

```kotlin
// build.gradle.kts
implementation("org.opensaml:opensaml-storage-impl:5.1.6")
implementation("org.opensaml:opensaml-saml-impl:5.1.6")
implementation("org.opensaml:opensaml-xmlsec-api:5.1.6")
```

- **OpenSAML 5.1.6** (current major, 2024)
- Shibboleth shared utilities (transitive)
- No Shibboleth IdP/SP federation library — P1 uses OpenSAML directly

### 3.10 SAML client realm

**P1 is an SP in production. Firms (customers) act as IdPs.**

Evidence:
1. Admin UI field names: "Entity ID (**Issuer**)", "**SSO URL (Login URL)**", "Primary **Certificate**" — all IdP-side configuration
2. `FirmSSOConfig` model (lines 14–22): stores the firm's IdP details
3. `SamlValidator.validate()` (line 125): `issuerUrl.equals(response.getIssuer().getValue())`

**IdP mode (secondary):**
- FireLight, 55IP, iCapital (the SPs)
- Driven by internal workflow, not by an external SSO flow
- Signing key hardcoded (`firelight.pfx`)

### 3.11 Gaps to becoming a public IdP

| # | Gap | Effort |
|---|---|---|
| 1 | Public IdP-Initiated endpoint | 1–2 days (reuse builder) |
| 2 | Configurable P1 EntityId/Issuer | Small |
| 3 | Production signing cert management (not reused from FireLight) | Medium — DB-backed or env var |
| 4 | IdP metadata endpoint | 1–2 days |
| 5 | Keycloak SP config | 0 (Keycloak-side) |
| 6 | Session state mapping (NameID → Keycloak user) | Medium |
| 7 | SLO support | 3–5 days |
| 8 | Attribute mapping for Keycloak schema | 1 day |

**Total:** ~1 week for MVP, +1 week for production hardening.

---

## 4. Final recommendation: Solution 2 — SAML Identity Brokering

### 4.1 Why this one

Solution 2 is the **only solution** that satisfies all four constraints simultaneously:

| Constraint | Solution 1 | **Solution 2** | Solution 3 | Solution 4 |
|---|---|---|---|---|
| 1:1 user mapping | ✅ | **✅** | ⚠️ | ⚠️ |
| Production-grade | ✅ | **✅** | ⚠️ | ❌ |
| Back-channel reachability | ✅ | **✅** | ✅ | ✅ |
| Reuses P1 SAML | ❌ | **✅** | ❌ | ❌ |
| Effort | 15–20 days | **~15 days** | 5–7 days | 4–6 days |

### 4.2 Architecture

```
                    ┌─────────────────────────────────────────────┐
                    │  Keycloak container (localhost:8898)         │
                    │   demo-realm                                 │
                    │     ├─ mfe-shell-client (existing)           │
                    │     └─ Identity Provider: "p1-saml" ← NEW    │
                    │         alias=p1                              │
                    │         SAML 2.0 SP role                      │
                    │         singleSignOnServiceUrl =              │
                    │           http://host.docker.internal:8080/   │
                    │             saml/idp/sso                      │
                    │         signingCertificate = <P1 X509>        │
                    └────────────────┬─────────────────────────────┘
                                     │  back-channel: validate signature
                                     │  via static cert (no metadata fetch)
                                     │
   ┌────────────────────┐            │
   │  P1 (Tomcat host)  │            │
   │  :8080             │◄───────────┘
   │                    │            │ AuthnRequest POST
   │  NEW endpoints:    │            │ ← `/saml/idp/sso` (SP-init)
   │   /saml/idp/sso    │◄───────────┤ OR
   │   /saml/idp/       │            │ ← IdP-init (P1 button click)
   │     metadata       │            │
   │   /saml/idp/slo    │            │
   │                    │            │ SAML Response (signed)
   └────────────────────┘            │ → POST to Keycloak
                                     │   /realms/demo-realm/broker/p1/endpoint
                                     │
   Browser flow:                     │
   1. Click "Demo MFE-BFF" in P1     │
   2. P1 emits signed SAML Response ─┘
   3. Browser POSTs to Keycloak ACS
   4. Keycloak validates, finds user via NameID,
      auto-link / first-broker-login → mfe-shell-client session
   5. 302 → localhost:5173/?code=... → tokens → demo loaded as logged-in user
```

### 4.3 Concrete deliverables

#### A) P1 BE — new SAML IdP module

| # | File / endpoint | Description | Effort |
|---|---|---|---|
| 1 | `src/main/java/com/geowealth/saml/idp/IdpSsoAction.java` | Struts action `/saml/idp/sso.do`. **SP-init mode:** decode AuthnRequest from Keycloak, validate session, build Response. **IdP-init mode:** GET handler — requires an active P1 session, builds a Response targeting Keycloak's ACS. | 2 days |
| 2 | `src/main/java/com/geowealth/saml/idp/IdpMetadataAction.java` | `/saml/idp/metadata.do` — emits EntityDescriptor XML (entityId, IDPSSODescriptor, X509Certificate, SingleSignOnService endpoints) | 0.5 days |
| 3 | `src/main/java/com/geowealth/saml/idp/IdpSloAction.java` | `/saml/idp/slo.do` — back-channel SLO (accepts LogoutRequest, destroys the P1 session, returns LogoutResponse) | 1.5 days |
| 4 | `src/main/java/com/geowealth/saml/idp/KeycloakSamlResponseBuilder.java` | Extends `AbstractSamlAuthenticationResponseBuilder` for the Keycloak ACS audience | 0.5 days |
| 5 | `src/main/java/com/geowealth/saml/idp/IdpKeyStore.java` | **Production blocker:** replace hardcoded `firelight.pfx`+`"1234"` with a configurable keystore (env var or DB-backed). IdP duty requires a **dedicated** key. | 1 day |
| 6 | Struts xml entries in `struts-tiles.xml` | Mapping for the three new actions | 0.25 days |
| 7 | NameID strategy | `NameIDFormat=urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress`, value = P1 user email. Plus `firmCd` as an AttributeStatement for multi-tenancy. | 0.5 days |
| 8 | AttributeStatement mapper | Reuse `AbstractAttributeStatementMapper`. Attributes: `email`, `firstName`, `lastName`, `firmCd`, `roles` (mapped to Keycloak realm roles). | 1 day |

**BE total: ~7 days**

#### B) P1 FE — sidebar entry

| # | File | Description | Effort |
|---|---|---|---|
| 9 | `WebContent/react/app/src/pages/PlatformOne/sidebar/_hooks/useIntegrationLinks.js` | Add an entry to the existing `integrationLinks` array — `{ label: "Demo MFE-BFF", linkUrl: "/saml/idp/sso.do?RelayState=keycloak-demo", absoluteUrl: true, id: "demo-mfe-bff", "data-testid": "sidebar-link-demo-mfe-bff" }` | 0.5 days |
| 10 | `useIntegrationLinks.js` permission gating | `hidden: !permissionsHelper.hasPermissionDemoMfeBff(loggedUser)` | 0.25 days |

**FE total: ~0.75 days.** TypeScript conversion of `.js` → `.ts` if required: +0.5 days.

#### C) Keycloak realm config

| # | Action | Description | Effort |
|---|---|---|---|
| 11 | Add Identity Provider | In `demo-realm` → Identity Providers → Add provider → SAML 2.0. Alias=`p1`. SSO URL=`http://host.docker.internal:8080/saml/idp/sso.do`. NameID Policy Format=Email. Validating Public Key=P1 X509 cert. | 0.5 days |
| 12 | Update `realm-export.json` | Persist the above config in the repo's seed file. | 0.25 days |
| 13 | Auto-link / First Broker Login | Configure the "First Broker Login" flow to auto-link by email (no manual review screen). | 0.5 days |
| 14 | Attribute mappers | Map `email` → user property; `firstName`/`lastName` → names; `roles` → Realm Role Importer. | 0.5 days |
| 15 | `mfe-shell-client` adjustment | `kc_idp_hint=p1` as default or a "Login with P1" button. | 0.25 days |

**Keycloak total: ~2 days**

#### D) Production hardening

| # | Item | Effort |
|---|---|---|
| 17 | HTTPS everywhere (TLS edge, SAML over HTTPS) | 1 day |
| 18 | Dedicated IdP signing key (not reused from FireLight) + cert rotation strategy | 1 day |
| 19 | Replay attack prevention (track `InResponseTo` IDs + timestamps) | 0.5 days |
| 20 | Front-channel SLO (Keycloak logout → P1 session kill) | 1 day |
| 21 | Audit logging: every `SAMLResponse` issuance logged with P1 user UUID + target SP | 0.5 days |
| 22 | E2E test: full flow from P1 login → click → shell loaded as same user | 1 day |

**Hardening total: ~5 days**

### 4.4 Total effort estimate

| Bucket | Days |
|---|---|
| BE — SAML IdP module | 7 days |
| FE — sidebar entry | 0.75 days |
| Keycloak config | 2 days |
| Production hardening | 5 days |
| Merge coordinator | 0.5 days |
| **TOTAL** | **~15 days (3 sprints)** |

### 4.5 Jira sub-task breakdown (per Development Sub-Task pattern)

- `[BE] Implement P1 SAML IdP endpoints (sso + metadata + slo)`
- `[BE] Refactor signing key management (dedicated IdP keystore + rotation)`
- `[BE] Add KeycloakSamlResponseBuilder + AttributeStatement mapper`
- `[BE] Add replay protection + audit logging`
- `[BE] Configure Keycloak P1 broker + realm-export.json update`
- `[FE] Add Demo MFE-BFF integration link with data-testid + permission gating`
- `[BE] E2E test pipeline + TLS hardening`
- `[MERGE] Sequence merges P1 first → demo last`

(No assignee/watchers per workflow pattern.)

---

## 5. Pros / Cons of the chosen solution

### 5.1 Pros

1. **Reuses existing P1 SAML infrastructure** — `AbstractSamlAuthenticationResponseBuilder` is domain-agnostic; adding Keycloak as another "SP target" follows the same pattern as FireLight/55IP/iCapital.
2. **OpenSAML 5.1.6 is the current major** — no need for dep upgrades.
3. **Identity Brokering is Keycloak's signature feature** — production support, documentation, community knowledge.
4. **1:1 user mapping is native** — Keycloak's First Broker Login flow is designed exactly for this; auto-link by email.
5. **Back-channel friendly** — SAML resolves Keycloak↔P1 reachability via `host.docker.internal`; SLO works without cookie/CORS gymnastics.
6. **Future-proof** — once P1 is an IdP for one SP, adding a fifth SP is config-only (no code).

### 5.2 Cons / Risks

1. **Production signing key management** — `firelight.pfx` with password `"1234"` is demo-grade. A real rotation pipeline = +1 day. **Blocker, not nice-to-have.**
2. **NameID stability** — if a P1 user's email changes, Keycloak will third-party-broker-link them as a new user. Mitigation: use the P1 user UUID as NameID, email as an attribute.
3. **`firmCd` in multi-tenant context** — if keycloak-demo must respect firm boundaries, audit BFF role enforcement for this. Escalate if coupling appears.
4. **SLO complexity** — front-channel SLO between Keycloak and P1 requires thinking about kill-session ordering. May be skipped in MVP.
5. **Audit log for production** — every SAML emission must generate a security event log entry. P1 has precedent (`SECURITY_EVENT: User Logout %s, firm: %d, SessionID: %s`); replicate it for SAML emission.

---

## 6. Remaining decision points

1. **NameID format:** email (simple, but if email changes → new broker link) or P1 user UUID (stable, but the email-as-attribute must match the Keycloak user)?
2. **`mfe-shell-client` browser flow override:** `kc_idp_hint=p1` always (every login via P1) or only for the "Demo MFE-BFF" entry (the normal Keycloak login screen stays available for `demoadmin/123`)?
3. **Role mapping:** P1 user roles vs Keycloak realm roles (`client/user/admin`). Is there a mapping already defined?
4. **Production deployment:** will Keycloak in production stay in a container with `host.docker.internal` reach to P1, or will it have a real hostname/network?
5. **SLO MVP scope:** include front-channel SLO in the first iteration, or ship without it?

---

## 7. Reference files

### P1 — existing

| Component | Path | Key lines |
|---|---|---|
| Sidebar menu | `WebContent/react/app/src/pages/PlatformOne/sidebar/_hooks/useSideBarLinks.js` | 27-1111 |
| Integration links | `WebContent/react/app/src/pages/PlatformOne/sidebar/_hooks/useIntegrationLinks.js` | 17-27 |
| Current user check | `WebContent/react/app/src/app/_services/appService.js` | 13, 630-642 |
| Login | `src/main/java/com/geowealth/web/common/action/LoginAction.java` | 94-180 |
| Logout | `src/main/java/com/geowealth/web/common/action/LogOutAction.java` | 21-80 |
| Session | `WebContent/WEB-INF/web.xml` | 149-152 |
| SP SAML action | `src/main/java/com/geowealth/web/common/SSOAction.java` | 29-106 |
| SP SAML validator | `src/main/java/com/geowealth/agent/saml/validator/SamlValidator.java` | 68-207 |
| IdP builder base | `src/main/java/com/geowealth/util/opensaml/AbstractSamlAuthenticationResponseBuilder.java` | 103-220 |
| FireLight IdP helper | `src/main/java/com/geowealth/service/insurance/SsoSAMLHelper.java` | 139-269 |
| FireLight action | `src/main/java/com/geowealth/ux/insurance/SSOFirelightAction.java` | 50-280 |
| OpenSAML utils | `src/main/java/com/geowealth/util/opensaml/OpenSamlUtils.java` | 9 |
| Metadata parser | `src/main/java/com/geowealth/agent/saml/metadata/SamlMetadata.java` | 126-214 |
| Firm SSO model | `src/main/java/com/geowealth/model/organization/FirmSSO.java` | full |
| Firm SSO config | `src/main/java/com/geowealth/model/organization/FirmSSOConfig.java` | 14-22 |
| Admin SSO UI | `WebContent/react/app/src/pages/PlatformOne/pages/Integrations/pages/SingleSignOn/` | full |

### P1 — new (Solution 2)

| Component | Path |
|---|---|
| IdP SSO action | `src/main/java/com/geowealth/saml/idp/IdpSsoAction.java` |
| IdP metadata | `src/main/java/com/geowealth/saml/idp/IdpMetadataAction.java` |
| IdP SLO | `src/main/java/com/geowealth/saml/idp/IdpSloAction.java` |
| Keycloak Response builder | `src/main/java/com/geowealth/saml/idp/KeycloakSamlResponseBuilder.java` |
| IdP keystore manager | `src/main/java/com/geowealth/saml/idp/IdpKeyStore.java` |

### keycloak-demo — existing

| Component | Path |
|---|---|
| Shell auth | `frontend/apps/shell/src/auth/AuthProvider.tsx` |
| Shell host | `frontend/apps/shell/src/auth/useShellHost.ts` |
| Shell API contract | `frontend/packages/shell-api/src/index.ts` |
| BFF user controller | `bff/src/main/java/demo/UserController.java` |
| Realm export | `keycloak/realm-export.json` |
| Email OTP authenticator | `keycloak-provider/src/main/java/com/example/keycloak/EmailOtpAuthenticator.java` |
| User storage SPI | `keycloak-provider/src/main/java/com/example/keycloak/DemoUserStorageProvider.java` |
| Docker compose | `docker-compose.yml` |

### keycloak-demo — new / modified

| Component | Path | Change |
|---|---|---|
| Realm export | `keycloak/realm-export.json` | Adds `p1` SAML identity provider |
| Docker compose | `docker-compose.yml` | Add `extra_hosts: ["host.docker.internal:host-gateway"]` if podman |

---

## 8. Summary

| Parameter | Value |
|---|---|
| **Chosen solution** | Solution 2 — SAML Identity Brokering (P1 as SAML IdP) |
| **Effort** | ~15 days (3 sprints, production-grade) |
| **MVP effort (without full hardening)** | ~10 days |
| **Why** | The only one that reuses existing P1 OpenSAML 5 code + Keycloak native Identity Brokering + 1:1 production user mapping |
| **Main production blocker** | Dedicated IdP signing key (not reused from FireLight `firelight.pfx` with password `"1234"`) |
| **Awaiting decision** | 5 open points in section 6 |
