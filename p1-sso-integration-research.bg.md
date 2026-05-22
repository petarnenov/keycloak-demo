# SSO Интеграция: P1 → keycloak-demo (MFE-BFF)

Ресърч и архитектурно решение за добавяне на "Demo MFE-BFF" опция в P1 sidebar-а, която отваря `http://localhost:5173/` (keycloak-demo) с автоматичен SSO от P1 Tomcat сесията към Keycloak.

**Дата:** 2026-05-21
**Източници:** `/home/petar/keycloak-demo/` + `/home/petar/nodejs/geowealth/`

---

## 1. Контекст

### 1.1 P1 (GeoWealth)

| Аспект | Стойност |
|---|---|
| BE framework | Struts 2 + Tomcat |
| Auth механизъм | Session-based (JSESSIONID cookie) |
| Session timeout | 420 минути (`WebContent/WEB-INF/web.xml:149-152`) |
| Login endpoint | `/react/login.do` (`LoginAction.java:94-180`) |
| Logout endpoint | `/react/logout.do` (`LogOutAction.java:21-80`) |
| Current user endpoint | `/react/isUserLoggedIn.do` (`appService.js:13`) |
| Sidebar config | `WebContent/react/app/src/pages/PlatformOne/sidebar/_hooks/useSideBarLinks.js:27-1111` |
| Integration links hook | `WebContent/react/app/src/pages/PlatformOne/sidebar/_hooks/useIntegrationLinks.js:17-27` |
| OIDC/OAuth адаптер | **Няма** |
| SAML — admin UI (SP конфигурация) | `WebContent/react/app/src/pages/PlatformOne/pages/Integrations/pages/SingleSignOn/` |
| SAML — runtime IdP код | **Да, реален OpenSAML 5.1.6** (виж раздел 3) |

### 1.2 keycloak-demo

| Аспект | Стойност |
|---|---|
| FE | React shell на `localhost:5173` + 3 MFE federation remotes (`5181/5182/5183`) |
| Auth механизъм | Authorization Code + PKCE към Keycloak |
| Keycloak URL | `localhost:8898`, realm = `demo-realm` |
| OIDC client | `mfe-shell-client` |
| 2FA | Email OTP authenticator + HMAC trust cookie (60 мин) |
| User store | Standalone Micronaut REST service (`user-service/`) + Keycloak User Storage SPI |
| Демо потребители | `democlient` (`client` role), `demouser` (`user`), `demoadmin` (`admin`, `user`) — парола `123` |
| BFFs | `bff-client:8081`, `bff-ops:8082`, `bff-admin:8083` — JWKS-валидиращи Micronaut services |
| Identity Brokering | **Не е конфигуриран** (но Keycloak native поддържа) |

### 1.3 Constraints от потребителя

| # | Constraint | Решение |
|---|---|---|
| 1 | **User mapping = 1:1** | Real P1 user ↔ Keycloak user (не aliasing към `demoadmin`) |
| 2 | **Target = Production** | Не demo; нужен е audit, key rotation, SLO, hardening |
| 3 | **Back-channel reachability** | Keycloak в контейнер ↔ P1 на host чрез `host.docker.internal` |
| 4 | **Leverage съществуващ P1 SAML код** | Reuse `AbstractSamlAuthenticationResponseBuilder` |

---

## 2. Първоначални 4 решения (анализирани)

### Solution 1 — OIDC Identity Brokering (P1 като OIDC IdP)

**Идея:** P1 имплементира OIDC IdP endpoints (`/.well-known/openid-configuration`, `/authorize`, `/token`, `/userinfo`, `/jwks`). Keycloak добавя P1 като External OIDC Identity Provider.

| Параметър | Стойност |
|---|---|
| Effort | **15–20 дни** (greenfield в Struts 2 — JWT signing, OAuth endpoints, PKCE storage, consent screen, discovery) |
| Industrial-standard | ⭐⭐⭐⭐⭐ |
| Scales | ✅✅✅ — N apps само добавят broker config |
| Pros | Native в Keycloak; refresh tokens, logout propagation, audience scoping out of the box; пълен audit trail |
| Cons | Най-голям effort; signing key + JWKS rotation management; P1 трябва да е reachable от Keycloak |
| Verdict | **Отхвърлено за тази итерация** — greenfield ефорт твърде голям при наличието на готов P1 SAML код |

### Solution 2 — SAML Identity Brokering (P1 като SAML IdP)

**Идея:** P1 имплементира SAML IdP endpoints. Keycloak добавя P1 като SAML SP federating към P1. Sidebar бутон → P1 emits signed SAML Response → browser POSTs към Keycloak ACS.

| Параметър | Стойност |
|---|---|
| Effort | **15 дни (production-grade)** (виж section 4) |
| Industrial-standard | ⭐⭐⭐⭐⭐ (battle-tested десетилетие; Okta/ADFS/Ping pattern) |
| Scales | ✅✅✅ |
| Pros | Reuse на съществуващ P1 OpenSAML код; Keycloak native SAML SP support; back-channel friendly; future-proof |
| Cons | XML/XMLDSig debug е по-неудобен; signing cert rotation; production hardening нужен |
| Verdict | ✅ **ИЗБРАНО** — виж section 4 |

### Solution 3 — OAuth 2.0 Token Exchange (RFC 8693)

**Идея:** P1 BE минтва subject_token (signed JWT). Browser handoff page вика Keycloak `/token` с `grant_type=urn:ietf:params:oauth:grant-type:token-exchange`.

| Параметър | Стойност |
|---|---|
| Effort | 5–7 дни |
| Industrial-standard | ⭐⭐⭐⭐ (RFC 8693, native Keycloak feature) |
| Scales | ✅✅ |
| Pros | Standards-based middleground; Keycloak event log records exchange; short-lived JWT |
| Cons | `keycloak-js` не expects pre-existing tokens (handoff trickier); external-issuer config документация е по-малко зряла; не e clean federation pattern за 1:1 production user mapping |
| Verdict | **Отхвърлено** — half-way до OIDC IdP без реалните federation ползи |

### Solution 4 — Custom Keycloak Authenticator SPI ("Magic Token" handoff)

**Идея:** Custom SPI в `keycloak-provider/` чете URL param `kc_p1_token=<UUID>`, прави back-channel call към P1 verify endpoint, успях → `context.success()` с user.

| Параметър | Стойност |
|---|---|
| Effort | 4–6 дни |
| Industrial-standard | ⭐⭐⭐ (custom plugin, не federation standard) |
| Scales | ✅ (всеки нов source = нов authenticator) |
| Pros | Reuse-ва съществуваща SPI инфра в `keycloak-provider/`; opaque token = single-use; no JWT/SAML overhead |
| Cons | Custom plugin = ops burden (всеки Keycloak upgrade = retest); не e federation standard; coupling между Keycloak и P1 endpoint |
| Verdict | **Отхвърлено за production** — приемливо за PoC, не за production federation с 1:1 user mapping |

---

## 3. Deep audit на P1 SAML имплементация

### 3.1 Executive summary

P1 имплементира **двете страни** на SAML 2.0:

1. **SP (Service Provider) mode (production-facing):** P1 *приема* SAML Responses от външни IdPs за firm-configured SSO. Customer firms configure their own IdP в P1 admin UI.
2. **IdP (Identity Provider) mode (domain-specific):** P1 *генерира, подписва и emit-ва* SAML Responses към външни SPs (FireLight, 55IP, iCapital).

**Заключение:** Използването на P1 като IdP към Keycloak е **технически възможно**, но изисква нов HTTP endpoint код. Signing infrastructure-та съществува; HTTP binding не.

### 3.2 OpenSAML класове в P1

| Файл | Цел |
|---|---|
| `src/main/java/com/geowealth/util/opensaml/AbstractSamlAuthenticationResponseBuilder.java` | **IdP builder abstraction** — конструира и подписва SAML `Response` + `Assertion`. Зарежда signing keys от `.jks` keystores; RSA-SHA256 signing. `createSamlAuthorizationResponse()` (lines 103–220). |
| `src/main/java/com/geowealth/util/opensaml/OpenSamlUtils.java` | Utility factory за OpenSAML обекти. `buildSAMLObject()` (line 9). |
| `src/main/java/com/geowealth/agent/saml/validator/SamlValidator.java` | **SP validator** — parse и validate на incoming SAML Responses. `validate()` (lines 68–207) проверява signature, issuer, time conditions, audience, X509 cert matching. |
| `src/main/java/com/geowealth/agent/saml/metadata/SamlMetadata.java` | **Metadata parser** — fetch и parse на IdP metadata. `extractMetadataXMLDetails()` (lines 126–214). |
| `src/main/java/com/geowealth/service/insurance/SsoSAMLHelper.java` | **Insurance domain IdP** — builds SAML Responses за FireLight. `createSamlAssertion()` (lines 139–197), `createSamlResponse()` (lines 228–269). Hardcoded `firelight.pfx` + password `"1234"`. |
| `src/main/java/com/geowealth/agent/fiftyfiveip/FiftyFiveIpSamlAuthenticationResponseBuilder.java` | **55IP domain IdP** — extends `AbstractSamlAuthenticationResponseBuilder<>` за FiftyFiveIP enrollment. |
| `src/main/java/com/geowealth/agent/fiftyfiveip/FiftyFiveIpSamlManagerTrait.java` | **55IP IdP controller** — Atomatron agent trait, lines 77–104. |
| `src/main/java/com/geowealth/agent/subdocintegration/icapital/saml/ICapitalSamlAuthenticationResponseBuilder.java` | **iCapital domain IdP** — extends `AbstractSamlAuthenticationResponseBuilder<>`. |
| `src/main/java/com/geowealth/agent/fiftyfiveip/FiftyFiveIpAttributeStatementBuilder.java` | Maps FiftyFiveIP account data → SAML attribute statements. |
| `src/main/java/com/geowealth/agent/subdocintegration/icapital/saml/ICapitalAttributeStatementBuilder.java` | Maps iCapital proposal data → SAML attribute statements. |
| `src/main/java/com/geowealth/util/opensaml/AbstractAttributeStatementMapper.java` | Abstract mapper за domain → `AttributeStatement`. |

### 3.3 HTTP endpoints

**SP Mode (firm SSO — приема Responses):**
- URL: `/ssoAuth.do`
- Class: `com.geowealth.web.common.SSOAction` (`src/main/java/com/geowealth/web/common/SSOAction.java:25`)
- Method: `authenticate()` (lines 29–106)
- Binding: HTTP-POST
- Flow:
  1. Firm's IdP POSTs base64-encoded SAML Response
  2. Base64-decode (line 66)
  3. `SamlManager.getSole().validateSaml(firmCd, saml, ipAddress)` (line 69)
  4. Validator проверява signature, issuer, conditions
  5. Extract NameID (user email), lookup user by email + firmCd, login

**IdP Mode (FireLight — emit-ва Responses):**
- URL: `/ssoAuth.do` → `SSOFirelightAction` (`struts-ux.xml:1387`)
- `initiateSSO()` (`src/main/java/com/geowealth/ux/insurance/SSOFirelightAction.java:50-114`)
- `sendIndividualProduct()` (line 181+)
- Lines 206–207: `ssoSAMLHelper.createSamlAssertion()` + `.createSamlResponse()`
- Line 273: `HTTPPostEncoder` emit-ва към FireLight's ACS

**IdP Mode (55IP, iCapital):** Atomatron messages → `FiftyFiveIpSamlManagerTrait` → `createSamlAuthorizationResponse()`. iCapital similar.

**Admin Configuration:**
- `readFirmSsoConfig`, `saveFirmSsoConfig`, `deleteFirmSsoConfig`, `decodeSsoMetadata` → `FirmSsoConfigAction` (`struts-platformOne.xml:1349-1361`)

### 3.4 Flow direction — code proof

**P1 като SP (приема Responses):**

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

**P1 като IdP (emit-ва Responses):**

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
- Fields в `FirmSSOConfig`:
  - `definitionMethod` (enum: URL, XML, MANUAL)
  - `entityId`
  - `ssoUrl`
  - `primaryCertificate` (`SamlCertificateInfo`)
  - `secondaryCertificate` (optional)

**IdP Mode (P1 signing):**
- Keystore: file-based `.pfx` (PKCS#12)
  - `firelight.pfx` за FireLight (`SsoSAMLHelper.java:238`)
  - Path: `Constants.devEtcPath + "/" + fileName` (hardcoded)
  - Password: hardcoded `"1234".toCharArray()` (line 251) — **production blocker**
- 55IP/iCapital: load от `AbstractSamlAuthenticationResponseBuilder` constructor (lines 75–99): `keyStorePath`, `keyStorePassword`, `keyStoreEntity`

**Admin UI (React):**
- Path: `WebContent/react/app/src/pages/PlatformOne/pages/Integrations/pages/SingleSignOn/`
- Form fields (от `useSingleSignOnFormFields.js`):
  - `FLD_ENFORCEMENT_TYPE` (OFF, OPTIONAL, REQUIRED)
  - `FLD_DEFINITION_METHOD` (URL, XML, MANUAL)
  - `FLD_ENTITY_ID`
  - `FLD_SSO_URL`
  - `FLD_PRIMARY_CERTIFICATE`
  - `FLD_SECONDARY_CERTIFICATE`

### 3.6 IdP-initiated capability

**Current state:**
- P1 НЕ expose-ва public IdP-Initiated SSO endpoint за arbitrary SPs.
- P1 constructs SAML Responses само в response to internal, domain-specific requests:
  - FireLight: `SSOFirelightAction.sendIndividualProduct()`
  - 55IP: Atomatron messages
  - iCapital: Atomatron messages

**`/ssoAuth.do` (`struts-tiles.xml:213`)** е единственото externally exposed SSO action — но **SP mode only**.

**За IdP-Initiated към Keycloak:**
- Нов Struts action (e.g., `initiateKeycloakSSO.do`)
- Връща signed `Response`
- POST към Keycloak's ACS URL
- Reuse `AbstractSamlAuthenticationResponseBuilder` / `SsoSAMLHelper`

### 3.7 Metadata exposure

| Тип | Статус |
|---|---|
| SP Metadata (P1 като SP) | **Не e exposed** |
| IdP Metadata (P1 като IdP) | **Не e exposed** |

За Keycloak integration: или публикуваме `/saml/idp/metadata` или конфигурираме Keycloak да приема P1 responses без metadata (trust by certificate only).

### 3.8 Single Logout (SLO)

**Status:** Not implemented.

- Няма SLO endpoints
- `SSOAction.authenticate()` прави login; няма съответен logout handling
- Няма `LogoutRequest` parsing или `LogoutResponse` emission

### 3.9 Dependencies

```kotlin
// build.gradle.kts
implementation("org.opensaml:opensaml-storage-impl:5.1.6")
implementation("org.opensaml:opensaml-saml-impl:5.1.6")
implementation("org.opensaml:opensaml-xmlsec-api:5.1.6")
```

- **OpenSAML 5.1.6** (current major, 2024)
- Shibboleth shared utilities (transitive)
- Няма Shibboleth IdP/SP federation library — P1 use-ва OpenSAML directly

### 3.10 Realm на SAML clients

**P1 е SP в production. Firms (customers) act като IdPs.**

Evidence:
1. Admin UI field names: "Entity ID (**Issuer**)", "**SSO URL (Login URL)**", "Primary **Certificate**" — all IdP-side configuration
2. `FirmSSOConfig` model (lines 14–22): stores firm's IdP details
3. `SamlValidator.validate()` (line 125): `issuerUrl.equals(response.getIssuer().getValue())`

**IdP mode (secondary):**
- FireLight, 55IP, iCapital (SP-те)
- Driven by internal workflow, not external SSO flow
- Signing key hardcoded (`firelight.pfx`)

### 3.11 Gaps to becoming a public IdP

| # | Gap | Effort |
|---|---|---|
| 1 | Public IdP-Initiated endpoint | 1–2 дни (reuse builder) |
| 2 | Configurable P1 EntityId/Issuer | Small |
| 3 | Production signing cert management (не reused от FireLight) | Medium — DB-backed или env var |
| 4 | IdP metadata endpoint | 1–2 дни |
| 5 | Keycloak SP config | 0 (Keycloak-side) |
| 6 | Session state mapping (NameID → Keycloak user) | Medium |
| 7 | SLO support | 3–5 дни |
| 8 | Attribute mapping за Keycloak schema | 1 ден |

**Total:** ~1 седмица за MVP, +1 седмица за production hardening.

---

## 4. Финална препоръка: Solution 2 — SAML Identity Brokering

### 4.1 Защо това

Solution 2 е **единственото решение** което удовлетворява всичките четири constraints едновременно:

| Constraint | Solution 1 | **Solution 2** | Solution 3 | Solution 4 |
|---|---|---|---|---|
| 1:1 user mapping | ✅ | **✅** | ⚠️ | ⚠️ |
| Production-grade | ✅ | **✅** | ⚠️ | ❌ |
| Back-channel reachability | ✅ | **✅** | ✅ | ✅ |
| Reuse-ва P1 SAML | ❌ | **✅** | ❌ | ❌ |
| Effort | 15–20 дни | **~15 дни** | 5–7 дни | 4–6 дни |

### 4.2 Архитектура

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
   5. 302 → localhost:5173/?code=... → tokens → demo loaded като logged-in user
```

### 4.3 Конкретни deliverables

#### A) P1 BE — нов SAML IdP module

| # | Файл / endpoint | Описание | Effort |
|---|---|---|---|
| 1 | `src/main/java/com/geowealth/saml/idp/IdpSsoAction.java` | Struts action `/saml/idp/sso.do`. **SP-init mode:** decode AuthnRequest от Keycloak, validate session, build Response. **IdP-init mode:** GET handler — изисква active P1 session, builds Response targeting Keycloak ACS. | 2 дни |
| 2 | `src/main/java/com/geowealth/saml/idp/IdpMetadataAction.java` | `/saml/idp/metadata.do` — emit EntityDescriptor XML (entityId, IDPSSODescriptor, X509Certificate, SingleSignOnService endpoints) | 0.5 дни |
| 3 | `src/main/java/com/geowealth/saml/idp/IdpSloAction.java` | `/saml/idp/slo.do` — back-channel SLO (приема LogoutRequest, унищожава P1 session, връща LogoutResponse) | 1.5 дни |
| 4 | `src/main/java/com/geowealth/saml/idp/KeycloakSamlResponseBuilder.java` | Extends `AbstractSamlAuthenticationResponseBuilder` за Keycloak ACS audience | 0.5 дни |
| 5 | `src/main/java/com/geowealth/saml/idp/IdpKeyStore.java` | **Production blocker:** замени hardcoded `firelight.pfx`+`"1234"` с конфигурируем keystore (env var или DB-backed). За IdP duty трябва **dedicated** key. | 1 ден |
| 6 | Struts xml entries в `struts-tiles.xml` | Mapping за горните 3 actions | 0.25 дни |
| 7 | NameID strategy | `NameIDFormat=urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress`, value = P1 user email. Plus `firmCd` като AttributeStatement за multi-tenancy. | 0.5 дни |
| 8 | AttributeStatement mapper | Reuse-вай `AbstractAttributeStatementMapper`. Атрибути: `email`, `firstName`, `lastName`, `firmCd`, `roles` (mapped към Keycloak realm roles). | 1 ден |

**BE общо: ~7 дни**

#### B) P1 FE — sidebar entry

| # | Файл | Описание | Effort |
|---|---|---|---|
| 9 | `WebContent/react/app/src/pages/PlatformOne/sidebar/_hooks/useIntegrationLinks.js` | Добави запис към existing `integrationLinks` array — `{ label: "Demo MFE-BFF", linkUrl: "/saml/idp/sso.do?RelayState=keycloak-demo", absoluteUrl: true, id: "demo-mfe-bff", "data-testid": "sidebar-link-demo-mfe-bff" }` | 0.5 дни |
| 10 | `useIntegrationLinks.js` permission gating | `hidden: !permissionsHelper.hasPermissionDemoMfeBff(loggedUser)` | 0.25 дни |

**FE общо: ~0.75 дни.** TypeScript conversion на `.js` → `.ts` ако е изисквано: +0.5 дни.

#### C) Keycloak realm config

| # | Действие | Описание | Effort |
|---|---|---|---|
| 11 | Добавяне на Identity Provider | В `demo-realm` → Identity Providers → Add provider → SAML 2.0. Alias=`p1`. SSO URL=`http://host.docker.internal:8080/saml/idp/sso.do`. NameID Policy Format=Email. Validating Public Key=P1 X509 cert. | 0.5 дни |
| 12 | Update `realm-export.json` | Persist горната config в repo's seed file. | 0.25 дни |
| 13 | Auto-link / First Broker Login | Конфигурирай "First Broker Login" flow да auto-link by email (без manual review screen). | 0.5 дни |
| 14 | Attribute mappers | Map `email` → user property; `firstName`/`lastName` → имена; `roles` → Realm Role Importer. | 0.5 дни |
| 15 | `mfe-shell-client` adjustment | `kc_idp_hint=p1` като default или "Login with P1" button. | 0.25 дни |

**Keycloak общо: ~2 дни**

#### D) Production hardening

| # | Item | Effort |
|---|---|---|
| 17 | HTTPS навсякъде (TLS edge, SAML over HTTPS) | 1 ден |
| 18 | Dedicated IdP signing key (не reused от FireLight) + cert rotation strategy | 1 ден |
| 19 | Replay attack prevention (track `InResponseTo` IDs + timestamps) | 0.5 дни |
| 20 | Front-channel SLO (Keycloak logout → P1 session kill) | 1 ден |
| 21 | Audit logging: всеки `SAMLResponse` issuance log-нат със P1 user UUID + target SP | 0.5 дни |
| 22 | E2E test: пълен flow от P1 login → click → shell loaded като same user | 1 ден |

**Hardening общо: ~5 дни**

### 4.4 Общ effort estimate

| Bucket | Дни |
|---|---|
| BE — SAML IdP module | 7 дни |
| FE — sidebar entry | 0.75 дни |
| Keycloak config | 2 дни |
| Production hardening | 5 дни |
| Merge coordinator | 0.5 дни |
| **TOTAL** | **~15 дни (3 sprints)** |

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

## 5. Pros / Cons на избраното решение

### 5.1 Pros

1. **Reuses съществуваща P1 SAML инфраструктура** — `AbstractSamlAuthenticationResponseBuilder` е domain-agnostic; добавянето на Keycloak като поредния "SP target" следва същия pattern като FireLight/55IP/iCapital.
2. **OpenSAML 5.1.6 е current major** — нямa нужда от dep upgrades.
3. **Identity Brokering е Keycloak's signature feature** — production support, документация, community knowledge.
4. **1:1 user mapping native** — Keycloak's First Broker Login flow точно за това; auto-link by email.
5. **Back-channel friendly** — SAML resolve-ва Keycloak↔P1 reachability чрез `host.docker.internal`; SLO работи без cookie/CORS работа.
6. **Future-proof** — щом P1 е IdP за един SP, добавянето на 5-ти SP е config-only.

### 5.2 Cons / Рискове

1. **Production signing key management** — `firelight.pfx` с password `"1234"` е demo-grade. Real rotation pipeline = +1 ден. **Blocker, не nice-to-have.**
2. **NameID stability** — ако P1 user email change-не, Keycloak ще third-party-broker-link като нов user. Mitigation: P1 user UUID като NameID, email като attribute.
3. **`firmCd` в multi-tenant context** — ако keycloak-demo трябва да респектира firm boundary, audit-вай BFF role enforcement за това. Escalate ако виждаш coupling.
4. **SLO complexity** — front-channel SLO между Keycloak и P1 изисква обмисляне на kill-session ordering. May skip в MVP.
5. **Audit log за production** — всеки SAML emission трябва да генерира security event log entry. P1 има precedent (`SECURITY_EVENT: User Logout %s, firm: %d, SessionID: %s`); replicate за SAML emission.

---

## 6. Оставащи decision points

1. **NameID format:** email (просто, но ако email change-не → нов broker link) или P1 user UUID (стабилно, но email-като-attribute трябва да match-ва Keycloak user)?
2. **`mfe-shell-client` browser flow override:** `kc_idp_hint=p1` всякога (всеки login през P1) или само за "Demo MFE-BFF" entry (нормален Keycloak login screen остава наличен за `demoadmin/123`)?
3. **Role mapping:** P1 user roles vs Keycloak realm roles (`client/user/admin`). Имаш ли вече mapping defined?
4. **Production deployment:** Keycloak в production ще остане ли в container с `host.docker.internal` reach към P1, или ще има реален hostname/network?
5. **SLO MVP scope:** включваме front-channel SLO в първата итерация, или ship-ваме без него?

---

## 7. Файлове за reference

### P1 — съществуващи

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

### P1 — нови (Solution 2)

| Component | Path |
|---|---|
| IdP SSO action | `src/main/java/com/geowealth/saml/idp/IdpSsoAction.java` |
| IdP metadata | `src/main/java/com/geowealth/saml/idp/IdpMetadataAction.java` |
| IdP SLO | `src/main/java/com/geowealth/saml/idp/IdpSloAction.java` |
| Keycloak Response builder | `src/main/java/com/geowealth/saml/idp/KeycloakSamlResponseBuilder.java` |
| IdP keystore manager | `src/main/java/com/geowealth/saml/idp/IdpKeyStore.java` |

### keycloak-demo — съществуващи

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

### keycloak-demo — нови / modified

| Component | Path | Промяна |
|---|---|---|
| Realm export | `keycloak/realm-export.json` | Добавя `p1` SAML identity provider |
| Docker compose | `docker-compose.yml` | Add `extra_hosts: ["host.docker.internal:host-gateway"]` ако podman |

---

## 8. Summary

| Параметър | Стойност |
|---|---|
| **Избрано решение** | Solution 2 — SAML Identity Brokering (P1 като SAML IdP) |
| **Effort** | ~15 дни (3 sprints, production-grade) |
| **MVP effort (без full hardening)** | ~10 дни |
| **Защо** | Само то reuses съществуващ P1 OpenSAML 5 код + Keycloak native Identity Brokering + 1:1 production user mapping |
| **Главен blocker за production** | Dedicated IdP signing key (не reuse-нат FireLight `firelight.pfx` с password `"1234"`) |
| **Чакам decision от теб** | 5 open points в section 6 |
