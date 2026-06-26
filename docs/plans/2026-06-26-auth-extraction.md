# Auth Extraction Plan — P1 → user-service + auth-spa (KC theme)

**Date:** 2026-06-26
**Status:** Approved design, ready for implementation planning
**Branches at time of writing:**
- `keycloak-demo`: `petarnenov/full-stack-k8s` @ `4543e9d`
- `nodejs/geowealth`: current dev branch

## 1. Context — what we have today

Today P1 (the Tomcat/Akka monolith under `nodejs/geowealth/`) acts as the **SAML
Identity Provider**: Keycloak holds the realm and an `identityProvider` block
`p1` that brokers SAML AuthnRequest/Response with P1's `/saml/idp/sso.do`. The
real authentication — the SHA1 verify against `ENTITY_TBL.LDAP_PASSWORD_HASH` —
happens inside P1 (`NEntityDAO#getUserByAuthentication`, called via
`AuthenticationTrait` → Akka). Roles are emitted from P1 to KC as the SAML
`roles` attribute and value-mapped into realm roles by eight
`saml-role-idp-mapper` entries in `realm-export.json`.

This shape has three pains:

1. **P1 owns auth, but it's not auth-shaped.** Authentication lives in the same
   JVM as portfolio math, the Akka cluster, in-memory caches the size of the
   `CostBasisAccount` table, and a Struts action graph. The single concern is
   tangled with everything else.
2. **P1 can't scale horizontally.** Tomcat HttpSession is in-memory (not
   externalized to Redis). Login flow assumes sticky session affinity. The SAML
   signing keystore lives on local `/tmp` per replica. Akka has a single seed
   node. Adding a second P1 replica today breaks login mid-flow.
3. **The SAML federation hop is overhead for a demo.** The demo's other
   domains (`bff-billing`, `bff-trading`) are already OIDC RPs behind a shared
   `token-handler`. P1 is the odd one out.

## 2. Target architecture

```
Browser
  │
  ├── p1.geowealth.int           ─┐
  ├── billing.geowealth.int      ─┤
  ├── trading.geowealth.int      ─┼─→ token-handler (multi-tenant, unchanged)
  │                               │     │
  │                               │     ▼
  │                               │   Keycloak (custom image)
  │                               │     ├── User Storage SPI (HTTP) ─→ user-service ─→ Oracle
  │                               │     ├── Email-OTP Authenticator SPI ─→ user-service
  │                               │     └── Login theme = auth-spa (React)
  │                               │
  └── auth.geowealth.int ─────────┘   (serves KC login pages, same hostname as today)
```

**Three new artifacts:**

| Artifact | Tech | Role |
|---|---|---|
| `user-service/` | Micronaut + JDBC | Owns Oracle reads against `ENTITY_TBL` / `ENTITY_ROLE_TBL` / `PERSON_TBL`. Stateless. Phase 1 = read-only + verify-only SHA1. |
| `auth-spa/` | Vite + React | Keycloak custom login theme. Replaces default `login.ftl`, `login-otp.ftl`, `login-reset-password.ftl`. Branded GeoWealth login UX. |
| `keycloak-providers/` | Java JARs | (a) User Storage SPI provider that proxies KC user lookups + credential validation to user-service over HTTP. (b) Email-OTP Authenticator SPI that mirrors P1's current email-token MFA, reusing `ENTITY_TBL.MFA_TOKEN` columns via user-service. |

**Four things change in existing artifacts:**

- `Dockerfile.keycloak` — first custom KC image in the demo. Bakes theme JAR + provider JARs.
- `keycloak/realm-export.json` — remove `p1` IdP + 8 SAML mappers + `p1-self-client` + `user→advisor` legacy mapper. Add `p1-client` OIDC RP. Add one `oidc-usermodel-attribute-mapper` per claim (`firmCd`, `memberships`, `roles`, `personId`). Set `loginTheme=geowealth`. Configure realm User Federation = SPI provider.
- `nodejs/geowealth` — remove P1's SAML IdP code (`IdpSsoAction`, `KeycloakSamlResponseBuilder`, `IdpKeyStore`, `BackChannelLogoutAction`, `SilentSsoAction`, `AbstractSamlAuthenticationResponseBuilder`). Add a small OIDC RP package (`com.geowealth.neo.oidc.rp`) with `OidcLoginServlet` + `OidcCallbackServlet`. Externalize Tomcat session to Redis (Redisson Tomcat SessionManager).
- `docker-compose.yml` + `k8s/` — add `user-service` Deployment; switch Keycloak service to custom image; bump `p1-tomcat` to horizontally scalable.

## 3. What disappears (P1)

- `nodejs/geowealth/src/main/java/com/geowealth/neo/saml/idp/IdpSsoAction.java`
- `nodejs/geowealth/src/main/java/com/geowealth/neo/saml/idp/KeycloakSamlResponseBuilder.java`
- `nodejs/geowealth/src/main/java/com/geowealth/neo/saml/idp/AbstractSamlAuthenticationResponseBuilder.java`
- `nodejs/geowealth/src/main/java/com/geowealth/neo/saml/idp/IdpKeyStore.java`
- `nodejs/geowealth/src/main/java/com/geowealth/neo/saml/idp/BackChannelLogoutAction.java`
- `nodejs/geowealth/src/main/java/com/geowealth/neo/saml/idp/SilentSsoAction.java`
- `keycloak-demo/scripts/sso-dev-keystore.sh` + `/tmp/p1-idp-dev.p12`
- All `derivePocRoles` POC code in `SsoRoleTranslator` / `IdpSsoAction`
- `appService.js#loginPassword` SHA1+session bootstrap + the `silent-sso.do?establish=true` round-trip
- 8 `identityProviderMappers` for `p1`, 1 `identityProvider` block `p1`, and `p1-self-client` in `realm-export.json`

The DAO that did the password check (`NEntityDAO#getUserByAuthentication`,
`AuthenticationTrait`, `SHAPassword`, `EntityMfaDevice`) **stays** in the P1
codebase — `user-service` does NOT depend on it via shared JAR (decoupling
goal), but the bit-exact SHA1 algorithm is copied into `user-service` (~10 LOC).

## 4. What appears (new code)

### 4.1 `user-service/`

Standalone Micronaut module. Own `build.gradle.kts`, own Dockerfile, own image
`keycloak-demo-user-service:dev`. JDK 17, Micronaut 4.x. Bundled fat jar via
`com.gradleup.shadow` (same pattern as the BFFs).

**Public HTTP contract** (consumed by KC SPI provider):

| Method | Path | Purpose | Returns |
|---|---|---|---|
| `GET` | `/users/search?username={u}&firmCd={f}` | Lookup by composite key (firm-scoped username). | `User` or 404 |
| `GET` | `/users/{id}` | Lookup by `entityId` (UUID). | `User` or 404 |
| `POST` | `/users/{id}/verify-credentials` | Body: `{"password":"plain"}`. SHA1 check vs `ldapPasswordHash`. Honors `loginInactivatedFlag`. | `{"valid":true\|false,"reason":"OK\|LOCKED\|BAD_PASSWORD"}` |
| `GET` | `/users/{id}/attributes` | Returns `ldapUid`, `firmCd`, `memberships[]`, `gwAdmin`, `personId`, `email`, `firstName`, `lastName`, `mfaRequiredFlag`. | `Map<String,Object>` |
| `GET` | `/users/{id}/roles` | Returns role names from `ENTITY_ROLE_TBL` JOIN `ROLE_TBL`. | `String[]` |
| `POST` | `/users/{id}/mfa-token` | Generate 6-digit token, SHA1, store in `MFA_TOKEN`, set 5-min expiry. (Email send is KC's job via existing KC mail config.) | `{"tokenSentTo":"...@..."}` |
| `POST` | `/users/{id}/mfa-token/verify` | Body: `{"token":"123456"}`. Compares SHA1, checks expiry. | `{"valid":true\|false}` |

**Internal package layout:**

- `com.gw.userservice.api` — controllers
- `com.gw.userservice.domain` — `User`, `Membership`, `MfaToken` POJOs
- `com.gw.userservice.dao` — JDBC mappers; one class per table; raw SQL, no JPA (keep dependencies thin)
- `com.gw.userservice.security` — `SHAPassword` (copied 1:1 from P1's `com.netfolio.util.SHAPassword`)
- `com.gw.userservice.config` — Oracle DataSource bean (driven by `ORACLE_HOST` / `ORACLE_PDB` / `ORACLE_USER` / `ORACLE_PASSWORD` env, same envs the K8s data-tier ConfigMap already populates)

**Resilience:** read-only against Oracle in Phase 1. Connection pool: HikariCP
`maximum-pool-size=10`, `connection-timeout=2s`. Health endpoint
`/health/oracle` selects `1 FROM DUAL` so K8s readiness gate exists.

### 4.2 `auth-spa/`

Vite + React + TypeScript SPA. Packaged not as a separate domain but as a
Keycloak login theme:

```
auth-spa/
├── src/                          # React app
│   ├── pages/
│   │   ├── LoginPage.tsx         # username + password
│   │   ├── OtpPage.tsx           # email OTP entry
│   │   ├── ResetPasswordPage.tsx # forgot password
│   │   └── ErrorPage.tsx
│   ├── api/keycloak.ts           # reads KC theme context (kcContext)
│   └── main.tsx
├── theme/                        # FreeMarker templates that mount the React app
│   ├── login.ftl                 # <div id="root"></div> + bundle <script>
│   ├── login-otp.ftl
│   ├── login-reset-password.ftl
│   ├── login-update-password.ftl
│   ├── error.ftl
│   ├── theme.properties          # parent=keycloak (inherit defaults)
│   └── resources/
│       └── dist/                 # Vite build output copied here
├── package.json
├── vite.config.ts
└── Dockerfile                    # build-only image, output mounted into KC image
```

**KC integration:** Theme uses the [`keycloakify`](https://www.keycloakify.dev)
pattern OR a plain React-in-FTL bundle. Pick keycloakify if the existing FE
team already knows it; otherwise plain bundle (one HTML page per FTL,
all-React after mount). Decision deferred to Phase 4 spike.

**Form-action contract:** `login.ftl` POSTs `${url.loginAction}` with
`username` + `password` — the form action is provided by Keycloak. The
React app builds the form data and submits to that URL via `fetch`.
Keycloak's authentication flow runs the SPI verify-credentials, the email-OTP
authenticator (if configured), then mints session + redirects with code.

### 4.3 `keycloak-providers/user-storage-spi/`

Java 17, Gradle, single fat-jar artifact. Implements:

- `org.keycloak.storage.UserStorageProvider`
- `org.keycloak.storage.user.UserLookupProvider`
- `org.keycloak.storage.user.UserQueryProvider` (search by username)
- `org.keycloak.credential.CredentialInputValidator` (delegates to `/users/{id}/verify-credentials`)

`UserStorageProviderFactory` registers a config field `userServiceUrl` (default
`http://user-service:8080`). Realm admin sees "User Federation → user-service"
provider in KC UI.

**Cache policy:** `EVICT_DAILY` for user lookups (KC's `UserStorageProviderModel.CachePolicy`).
Credential validation is NEVER cached (KC handles that — `getCachePolicy`
applies only to user metadata).

### 4.4 `keycloak-providers/email-otp-authenticator/`

KC `Authenticator` SPI. Replaces KC's built-in OTP flow with email-based 6-digit
codes, mirroring P1's existing `GenerateAndSendMfaTokenMsg`/`ValidateMfaTokenMsg`
flow (so existing users don't lose MFA when P1's MFA path goes away). Token
generation + verification happen in user-service (reusing
`ENTITY_TBL.MFA_TOKEN` and `MFA_TOKEN_EXPIRATION_DATE`); the Authenticator
calls user-service and renders the `login-otp.ftl` template from the theme.

KC realm authentication flow `browser-with-email-otp` configured as:
1. Cookie (existing)
2. Identity Provider Redirector (existing, but now no `p1` IdP registered)
3. Forms:
   - Username password form (uses SPI for validation)
   - **Email OTP form** (new, conditional: `user.mfaRequiredFlag == true`)

### 4.5 P1 OIDC RP package

`nodejs/geowealth/src/main/java/com/geowealth/neo/oidc/rp/`:

- `OidcLoginServlet.java` — `GET /oidc/login`. Builds KC authorize URL
  (`response_type=code`, `client_id=p1-client`, `scope=openid`, PKCE,
  `redirect_uri=https://p1.geowealth.int/oidc/callback`,
  `state=<random>`), 302s.
- `OidcCallbackServlet.java` — `GET /oidc/callback?code=…&state=…`. Validates
  state, exchanges code for tokens at KC `/token` endpoint, validates `id_token`
  signature against KC JWKS, extracts claims (`personId`, `firmCd`, `ldapUid`,
  `memberships`, `roles`, `email`). Calls `NEntityDAO#findByLoginAndFirm`
  to load the `User` row, then reproduces what `LoginAction#loginUser` does
  today — populates `LOGGED_USER`, `LOGGED_USER_LOGIN_KEY`, `LOGGED_ADVISER`,
  `FIRM_*` keys in HttpSession. Logs `SECURITY_EVENT: User Login (OIDC)…`.
- `OidcLogoutServlet.java` — `GET /oidc/logout`. Invalidates HttpSession,
  redirects to KC `/end-session?id_token_hint=…&post_logout_redirect_uri=…`.
- `OidcConfig.java` — reads KC issuer URL, client ID, client secret from env
  (`KC_ISSUER`, `OIDC_CLIENT_ID=p1-client`, `OIDC_CLIENT_SECRET`).
- `JwtVerifier.java` — caches KC JWKS, validates `id_token`. Library: Nimbus
  JOSE+JWT (already widely used; no Keycloak adapter dep).

**`appService.js` change:** `loginPassword(username, password)` body removed.
The React app's "Login" button instead navigates to `/oidc/login` (full page
redirect). Post-callback, P1's existing session checking kicks in and the
React app reloads with `LOGGED_USER` populated. The `silent-sso.do` round-trip
is removed in the same commit.

### 4.6 P1 Tomcat session externalization

Use [Redisson Tomcat session manager](https://github.com/redisson/redisson/tree/master/redisson-tomcat).
Wire in `META-INF/context.xml`:

```xml
<Manager className="org.redisson.tomcat.JndiRedissonSessionManager"
         jndiName="bean/RedissonClient" />
```

Plus a Redisson client bean (singleton) configured against the same Redis the
token-handler / B2 already use (`REDIS_URI` env). All `HttpSession` attributes
must be `Serializable`. Audit set (already mostly clean since `User`,
`FirmDTO`, `LoggedUser` are POJOs with String/Integer fields):

| Attribute | Type | Serializable? |
|---|---|---|
| `LOGGED_USER` | `com.geowealth.model.user.User` | Verify in Phase 6 |
| `LOGGED_USER_LOGIN_KEY` | `java.util.UUID` | Yes |
| `LOGGED_ADVISER` | `LoggedUser` / `LoggedUserImpostor` | Verify in Phase 6 |
| `FIRM_*` | `FirmDTO` | Verify in Phase 6 |

Any non-serializable attribute that surfaces in the audit gets a small wrapper
that holds only the primitive fields actually used downstream.

## 5. Realm config diff (`keycloak/realm-export.json`)

**Remove:**
- `identityProviders[0]` (alias `p1`, the entire block)
- `identityProviderMappers` — all 9 entries with `identityProviderAlias=p1`
- `clients[*]` where clientId == `p1-self-client`
- The `p1-first-broker-login` authentication flow (no longer referenced)

**Add:**
- `clients[*]` new entry `p1-client`:
  ```json
  {
    "clientId": "p1-client",
    "enabled": true,
    "protocol": "openid-connect",
    "publicClient": false,
    "secret": "p1-dev-secret",
    "redirectUris": ["https://p1.geowealth.int/oidc/callback"],
    "webOrigins": ["https://p1.geowealth.int"],
    "attributes": {
      "backchannel.logout.url": "http://p1-tomcat:8080/oidc/back-channel-logout",
      "backchannel.logout.session.required": "true",
      "post.logout.redirect.uris": "https://p1.geowealth.int"
    },
    "protocolMappers": [
      { "name": "firmCd-claim",    "protocolMapper": "oidc-usermodel-attribute-mapper", "config": { "user.attribute": "firmCd",      "claim.name": "firmCd",      "jsonType.label": "int",    "id.token.claim": "true", "access.token.claim": "true" } },
      { "name": "memberships",     "protocolMapper": "oidc-usermodel-attribute-mapper", "config": { "user.attribute": "memberships", "claim.name": "memberships", "jsonType.label": "JSON",   "id.token.claim": "true", "access.token.claim": "true", "multivalued": "true" } },
      { "name": "personId-claim",  "protocolMapper": "oidc-usermodel-attribute-mapper", "config": { "user.attribute": "personId",   "claim.name": "personId",    "jsonType.label": "String", "id.token.claim": "true", "access.token.claim": "true" } },
      { "name": "ldapUid-claim",   "protocolMapper": "oidc-usermodel-attribute-mapper", "config": { "user.attribute": "ldapUid",    "claim.name": "ldapUid",     "jsonType.label": "String", "id.token.claim": "true", "access.token.claim": "true" } }
    ]
  }
  ```
- Update `clients[demo-shared-client]`:
  - `redirectUris` += `https://p1.geowealth.int/*`
  - `webOrigins` += `https://p1.geowealth.int`
  - `post.logout.redirect.uris` += `https://p1.geowealth.int`
- New `components` entry under realm User Federation:
  ```json
  {
    "name": "user-service",
    "providerId": "user-service-spi",
    "providerType": "org.keycloak.storage.UserStorageProvider",
    "config": {
      "userServiceUrl": ["http://user-service:8080"],
      "cachePolicy": ["EVICT_DAILY"]
    }
  }
  ```
- Realm-level setting: `"loginTheme": "geowealth"`
- New auth flow `browser-with-email-otp` (cookie → identity provider redirector → username password form → conditional email OTP). Realm `browserFlow` → this new flow.

**Add to env-file flow (`scripts/reconcile-realm.sh`)** so a live realm gets
the same patch idempotently — extend the script to upsert `p1-client`'s
redirect/post-logout URLs from the `urls.<env>.env` file.

## 6. Phase-by-phase implementation plan

Each phase is **independently merge-able** and **independently revertable**.
Phases are ordered by dependency; running them in order avoids feature flags.

### Phase 0 — Schema freeze + spike (≈ 1 day)

**Goal:** Lock the contract user-service depends on before writing code.

**Tasks:**
1. Audit `ENTITY_TBL`, `ENTITY_ROLE_TBL`, `ROLE_TBL`, `PERSON_TBL` for the
   exact columns user-service needs. Produce a one-page spec:
   `docs/solution-architect/10-user-service-schema-contract.md`.
2. Identify which columns are NOT NULL and which can be NULL (drives default
   handling in user-service).
3. Confirm `SHAPassword`'s algorithm (SHA1 + salt? no salt? hex vs base64?) by
   running `SHAPassword.check("known", "<hash from tim1 row>")` in a one-off
   Kotlin scratch.
4. Confirm Tomcat HttpSession attributes are `Serializable` by inserting
   `assert attr instanceof Serializable` in `LoginAction#loginUser` and
   running the existing flow once. List violations for Phase 6.
5. **Spike**: stand up Keycloak with a hand-rolled `UserStorageProvider` JAR
   that returns a single hardcoded user. Verify KC admin UI shows the user
   under "Users → user-service". Verify a manual login against KC default
   theme with that hardcoded credential succeeds. This proves the SPI
   plumbing before we invest in user-service.

**Deliverable:** Schema contract MD + working SPI spike branch
(`spike/kc-user-storage-spi-hello-world`).

### Phase 1 — `user-service` standalone (≈ 3 days)

**Goal:** Service serves the full HTTP contract from §4.1 against real Oracle.

**Tasks:**
1. Scaffold `user-service/` with Micronaut, JDBC, shadow-jar (mirror
   `bff-billing` skeleton).
2. Implement `SHAPassword` (copy 1:1 from P1's `com.netfolio.util.SHAPassword`).
3. Implement DAOs:
   - `EntityDao.findByEntityId(UUID)`, `findByUsernameAndFirm(String, int)`
   - `RoleDao.findByEntityId(UUID) → String[]`
   - `MembershipDao.findByPersonId(UUID) → Membership[]` (queries `PERSON_TBL`
     + `ENTITY_TBL` JOIN to build `firmCd:ldapUid` list, matching
     `PersonRegistry#loadAccountsByFirm`)
4. Implement controllers (one per route in §4.1).
5. Implement `OracleDataSource` config bean. Read `ORACLE_HOST` etc. from env.
6. Write integration tests against H2 with fixture rows (no Oracle in CI).
7. Write a separate smoke test that runs against a real Oracle in K8s (manual
   trigger, not in CI).
8. Dockerfile + `docker-compose.yml` entry.

**Verification:** See §7 Phase 1.

### Phase 2 — KC User Storage SPI provider (≈ 2 days)

**Goal:** Keycloak federates users from user-service; admin UI and credential
test confirm the bridge works.

**Tasks:**
1. Scaffold `keycloak-providers/user-storage-spi/` (Gradle, KC SPI deps via
   `keycloak-server-spi`, `keycloak-server-spi-private`, `keycloak-services`).
2. Implement `UserStorageProviderFactory`, `UserStorageProvider`,
   `UserLookupProvider`, `UserQueryProvider`, `CredentialInputValidator`.
3. HTTP client wrapper around user-service routes (use Java 17
   `HttpClient`; no Feign / RestClient deps to keep the JAR small).
4. Map user-service `User` → Keycloak `UserModel` (delegate via
   `AbstractUserAdapter` — KC pattern for read-only federated users).
5. `Dockerfile.keycloak`:
   ```dockerfile
   FROM quay.io/keycloak/keycloak:26.0.7 AS builder
   COPY keycloak-providers/user-storage-spi/build/libs/*.jar /opt/keycloak/providers/
   RUN /opt/keycloak/bin/kc.sh build

   FROM quay.io/keycloak/keycloak:26.0.7
   COPY --from=builder /opt/keycloak/lib/quarkus /opt/keycloak/lib/quarkus
   COPY --from=builder /opt/keycloak/providers /opt/keycloak/providers
   ```
6. Switch `docker-compose.yml` Keycloak service to `build:` instead of `image:`.
7. Update realm-export with the User Federation component block.
8. Update `start.sh` and `k8s/up.sh` to build the custom KC image.

**Verification:** See §7 Phase 2.

### Phase 3 — Repoint realm to SPI; remove `p1` SAML IdP (≈ 1 day)

**Goal:** Login through KC default UI works; `p1` SAML path retired but P1
still serves data the old way (we haven't migrated it yet — billing/trading
still log in via the same path, just now hitting SPI instead of SAML).

**Tasks:**
1. Edit `realm-export.json`: remove `p1` IdP block + 8 SAML idp-mappers +
   `p1-self-client` + `p1-first-broker-login` flow.
2. Update `realm-export.json` realm authentication flow `browser` to use the
   plain `browser` flow (no `Identity Provider Redirector` step pre-selecting
   `p1`).
3. Run `./start.sh --reset` to apply the realm changes on a fresh DB.
4. Verify `bff-billing` login: navigate to `https://billing.geowealth.int:5184`,
   click Login. Should land on KC default login form (no `kc_idp_hint=p1`
   redirect), authenticate against a SPI user, dashboard renders.
5. Same for `bff-trading`.
6. **P1 is broken at this checkpoint** — that's expected, Phase 5 fixes it.

**Verification:** See §7 Phase 3.

### Phase 4 — `auth-spa` theme + email-OTP Authenticator (≈ 3 days)

**Goal:** Login UI is the GeoWealth-branded React SPA; MFA flow preserved.

**Tasks:**
1. Scaffold `auth-spa/` with Vite + React + TypeScript.
2. Build `LoginPage`, `OtpPage`, `ResetPasswordPage`, `ErrorPage` React components.
3. Build FreeMarker shells (`login.ftl` etc.) that mount the React app and
   pass `kcContext` into a `window.__KC__` global.
4. Build pipeline: `npm run build` outputs to `theme/resources/dist/`.
5. Theme JAR: gzip the `theme/` dir into `geowealth-theme.jar` with
   `META-INF/keycloak-themes.json` declaring the theme. Bake into
   `Dockerfile.keycloak`.
6. Realm-export: `"loginTheme": "geowealth"`.
7. Implement `keycloak-providers/email-otp-authenticator/`:
   - `EmailOtpAuthenticator implements Authenticator`
   - `EmailOtpAuthenticatorFactory implements AuthenticatorFactory`
   - On `authenticate(context)`: call user-service `POST /users/{id}/mfa-token`
     to generate + persist the token. Render the OTP form (`login-otp.ftl`).
   - On `action(context)`: read submitted token, call user-service
     `POST /users/{id}/mfa-token/verify`, success → `context.success()`,
     failure → re-render with error.
   - Conditional: only fires if the user's `mfaRequiredFlag` attribute is
     `true` (read from SPI user attributes).
8. New realm authentication flow `browser-with-email-otp` (declarative JSON in
   realm-export). Realm `browserFlow` → this flow.
9. Email delivery: configure KC SMTP to the same fake SMTP used by P1 today
   (`mailhog` or whatever — out of scope for this plan to set up SMTP infra,
   but the env var is `KC_EMAIL_*`).

**Verification:** See §7 Phase 4.

### Phase 5 — P1 as OIDC RP; cutover (≈ 4 days)

**Goal:** P1 is just another OIDC client. SAML IdP code deleted. P1 SPA login
button → OIDC redirect → KC → SPI → Oracle → KC mints JWT → P1 callback
populates HttpSession.

**Tasks:**
1. Add `nodejs/geowealth/src/main/java/com/geowealth/neo/oidc/rp/`:
   - `OidcLoginServlet`, `OidcCallbackServlet`, `OidcLogoutServlet`
   - `OidcConfig`, `JwtVerifier` (Nimbus JOSE+JWT)
   - `BackChannelLogoutServlet` (POST `/oidc/back-channel-logout` — KC posts
     `logout_token`; we reuse `GeowealthSessionListener.invalidateByKcSub`
     that exists today — same pattern as P1's current
     `BackChannelLogoutAction.java`, just at a new path)
2. Add dep: `com.nimbusds:nimbus-jose-jwt` to P1's build (for `JwtVerifier`).
3. `web.xml`: map `/oidc/login`, `/oidc/callback`, `/oidc/logout`,
   `/oidc/back-channel-logout`.
4. `appService.js`: replace `loginPassword(u, p)` with
   `window.location.assign('/oidc/login')`. Remove the
   `silent-sso.do?establish=true` round-trip and its session guard
   `SilentSsoAction.SESSION_KEY_ESTABLISH_DONE`.
5. Delete:
   - `IdpSsoAction.java` (+ Struts mapping in `struts-config.xml`)
   - `KeycloakSamlResponseBuilder.java`
   - `AbstractSamlAuthenticationResponseBuilder.java`
   - `IdpKeyStore.java`
   - `BackChannelLogoutAction.java`
   - `SilentSsoAction.java`
   - `derivePocRoles` POC code in `SsoRoleTranslator.java`
6. Drop env vars `P1_IDP_KEYSTORE_PATH`, `P1_IDP_KEYSTORE_PASSWORD`,
   `P1_IDP_KEYSTORE_ENTITY` from all manifests, scripts, `.envrc` examples.
7. Delete `scripts/sso-dev-keystore.sh` from keycloak-demo.
8. Update `keycloak-demo/k8s/env/urls.<env>.env`: remove the P1 SAML SSO/SLO
   URL lines; add `P1_OIDC_REDIRECT_URI` + `P1_BACK_CHANNEL_LOGOUT_URL`. Add
   handling to `scripts/reconcile-realm.sh` to PATCH `p1-client`'s
   redirect/back-channel URLs from these.

**Verification:** See §7 Phase 5.

### Phase 6 — P1 Tomcat session → Redis (≈ 2 days)

**Goal:** Killing a P1 Tomcat replica mid-session does NOT log the user out.

**Tasks:**
1. Add Redisson Tomcat session manager dep to P1's build (`redisson-tomcat-10`
   matching P1's Tomcat major version).
2. `META-INF/context.xml`:
   ```xml
   <Manager className="org.redisson.tomcat.JndiRedissonSessionManager"
            jndiName="bean/RedissonClient" />
   ```
3. Provide a JNDI Redisson client in `server.xml` or via `RedissonInitializer`
   ContextListener that wires `REDIS_URI` from env into a singleton.
4. Walk every `session.setAttribute(...)` call and verify the value is
   `Serializable`. The Phase 0 audit produces this list; this task addresses
   each violation (most likely: lambda-captured non-serializable inner classes
   in cached `ViewState` objects, fixed by extracting to top-level records).
5. K8s ConfigMap: `REDIS_URI=redis://redis:6379` for `p1-tomcat`.
6. Remove `sessionStickyAffinity` annotations from the `p1-tomcat` Ingress.

**Verification:** See §7 Phase 6.

### Phase 7 — P1 horizontal scale enablement (≈ 1 day)

**Goal:** `p1-tomcat` Deployment scales to N replicas; HPA in place; load test
green.

**Tasks:**
1. K8s: convert `p1-tomcat` to plain Deployment (already is — just bump
   `replicas: 1` to `replicas: 2`).
2. Add `HorizontalPodAutoscaler`:
   ```yaml
   apiVersion: autoscaling/v2
   kind: HorizontalPodAutoscaler
   metadata: { name: p1-tomcat }
   spec:
     scaleTargetRef: { apiVersion: apps/v1, kind: Deployment, name: p1-tomcat }
     minReplicas: 1
     maxReplicas: 5
     metrics:
       - type: Resource
         resource:
           name: cpu
           target: { type: Utilization, averageUtilization: 70 }
   ```
3. Verify no other `p1-tomcat` assumption breaks at N=2: nothing writes to
   local FS (export, log paths go to stdout already), no in-JVM singleton state
   matters because none of the request-path code held per-Tomcat state (caches
   live in agents, not Tomcat).
4. Note: Akka multi-replica fixes are OUT OF SCOPE (see §10). Tomcat scales
   independently because it's an Akka client, not a cluster member.

**Verification:** See §7 Phase 7.

## 7. Verification plan

Every phase has three layers: **unit/contract**, **integration**, **end-to-end**.
"E2E" means Playwright against the running stack (compose or K8s).

### Phase 0

| Layer | Check |
|---|---|
| Spike | Custom KC image with hardcoded SPI provider boots; KC admin UI lists 1 user from SPI; KC login form accepts hardcoded credentials. |
| Schema MD | Reviewed by user; merged. |

### Phase 1 — user-service

| Layer | Check |
|---|---|
| Contract test | `GET /users/search?username=tim1&firmCd=1` returns 200 with expected JSON shape. 404 for unknown user. |
| Contract test | `POST /users/{id}/verify-credentials` with the known `tim1` password returns `{valid:true}`. With wrong password returns `{valid:false,reason:"BAD_PASSWORD"}`. With locked user returns `{reason:"LOCKED"}`. |
| Contract test | `GET /users/{id}/roles` returns the role names in `ENTITY_ROLE_TBL` for that user. |
| Contract test | `GET /users/{id}/attributes` returns `firmCd`, `memberships` (multi-firm linked-user case included), `gwAdmin`. |
| Integration | `./start.sh` brings up `user-service`. `curl` smoke against compose endpoint passes. |
| Integration (K8s) | `kubectl run -it --rm curl --image=curlimages/curl --restart=Never -- http://user-service:8080/users/search?...` passes against external Oracle. |
| E2E | N/A this phase (no UI yet). |

### Phase 2 — KC User Storage SPI provider

| Layer | Check |
|---|---|
| Unit | `UserStorageProvider#getUserByUsername` calls user-service and returns `UserModel`. Mocked HTTP. |
| Integration | Custom KC image boots without errors; logs show `Loaded provider: user-service-spi`. |
| Integration | KC admin REST: `GET /admin/realms/demo-realm/components?type=org.keycloak.storage.UserStorageProvider` returns the user-service provider entry. |
| Integration | `kcadm.sh get users -r demo-realm -q username=tim1` returns the user. |
| Integration | `kcadm.sh test-credentials --userid=<id> -r demo-realm --password=<known>` returns success. |
| E2E | KC default login form on `https://auth.geowealth.int:5180/realms/demo-realm/account` accepts `tim1` + password, lands on account page. |

### Phase 3 — repoint realm to SPI

| Layer | Check |
|---|---|
| Smoke | `./start.sh --reset` succeeds. Realm has no `p1` IdP, no SAML mappers, no `p1-self-client`. (`curl` realm export confirms.) |
| E2E (billing) | `https://billing.geowealth.int:5184` → click Login → KC default form (NOT P1 SAML form, NOT P1 login.do redirect) → submit → dashboard renders. |
| E2E (trading) | Same for trading. |
| Negative | `https://p1.geowealth.int` login button → expected to break with "no OIDC client configured" or 404 — this is the expected state until Phase 5. Documented in the merge PR. |

### Phase 4 — auth-spa theme + email-OTP

| Layer | Check |
|---|---|
| Visual | KC login page rendered with GeoWealth header/footer/CSS. Matches a Figma reference (TBD with design — for the demo, "looks branded" is enough). |
| Integration | New theme JAR loaded: `curl https://auth.geowealth.int:5180/resources/<hash>/login/geowealth/css/styles.css` returns 200. |
| Integration | New auth flow `browser-with-email-otp` is the realm's `browserFlow`. |
| E2E | Login a user that has `mfaRequiredFlag=false` → straight to dashboard. |
| E2E | Login a user with `mfaRequiredFlag=true` → OTP page → check mailhog inbox → type 6-digit code → success. |
| E2E | Login fail (wrong password) → error message rendered in the React theme. |
| E2E | Forgot-password → email contains reset link → reset password page accepts new password (Phase 4 minimum: KC's built-in reset flow rendered through our theme; user-service `POST /users/{id}/password` belongs to the "user-service write paths" follow-up listed in §10). |

### Phase 5 — P1 as OIDC RP

| Layer | Check |
|---|---|
| Unit | `JwtVerifier` validates a known KC-signed token. Rejects tampered token. |
| Integration | `OidcCallbackServlet` populates `LOGGED_USER` etc. (mocked KC token). |
| E2E | `https://p1.geowealth.int` → click Login → KC theme → submit credentials → P1 dashboard renders, `getLoggedUser() != null`. |
| E2E | Logout from P1 → KC end-session → other tabs (billing, trading) detect SLO via back-channel logout → all sessions invalidated. |
| E2E | Logout from billing → KC end-session → P1 tab on next request gets `LOGGED_USER == null`. |
| Smoke | `grep -r SAML nodejs/geowealth/src` returns no references in the IdP code paths. |
| Smoke | `/tmp/p1-idp-dev.p12` not created, `P1_IDP_KEYSTORE_PATH` env not set in any manifest. |
| Regression | Existing P1 API endpoints continue to authenticate via session (now populated by OIDC callback instead of LoginAction). Pick 5 representative endpoints (`/api/v1/firms`, `/api/permissioncheck`, etc.), confirm 200 from logged-in browser. |

### Phase 6 — P1 Tomcat session → Redis

| Layer | Check |
|---|---|
| Integration | Tomcat boots with `RedissonSessionManager` (catalina.out logs `RedissonSessionManager started`). |
| Integration | Login → `redis-cli KEYS 'redisson:tomcat_session:*'` shows a key. |
| Chaos | With 2 P1 replicas behind LB: login on replica-A, `kubectl delete pod` replica-A, refresh page → user still logged in (request lands on replica-B which reads session from Redis). |
| Chaos | Restart Redis: existing in-flight sessions survive (Redis has `appendonly yes`), browser refresh works. |
| Regression | No `NotSerializableException` in catalina.out under a 5-min smoke walkthrough of the app (open dashboard, run a report, click a few CRM pages). |

### Phase 7 — horizontal scale

| Layer | Check |
|---|---|
| K8s | `kubectl get hpa p1-tomcat` shows HPA exists. |
| K8s | `kubectl scale deployment/p1-tomcat --replicas=3` succeeds; 3 pods Ready. |
| Smoke | Login through Ingress; verify requests fan across replicas (correlate by `kubectl logs` and a per-pod log marker). |
| Load | k6 script: 50 VU ramping over 30s, each VU loops `GET /api/permissioncheck` 100 times after login. HPA scales `p1-tomcat` from 1 to ≥2 replicas. p95 latency stays ≤ 1.5× baseline. |
| Negative | Kill 1 of 3 replicas during the load test; HPA replaces it within 30s; no 5xx surges above baseline noise. |
| Cleanup | `kubectl scale deployment/p1-tomcat --replicas=1`; HPA brings it back when load drops. |

### Cross-phase regression checklist (run after every phase)

- `./start.sh` succeeds end-to-end.
- Existing Playwright suite under `e2e/` runs green (especially
  `e2e/tests/p1-relogin-silent-recovery.spec.ts` which was added for Gap 6;
  after Phase 5 it should be DELETED or rewritten since the establish
  round-trip is gone).
- `kubectl -n geowealth-demo get pods` shows all expected pods Running 1/1.

## 8. Risks + mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| `SHAPassword` algorithm has a salt or encoding subtlety we miss → all users fail to log in. | Medium | Critical | Phase 0 spike validates against a known `tim1` password. Phase 1 contract test pins behavior. |
| KC theme JAR doesn't hot-reload during development → slow theme iteration. | High | Low | Mount `theme/resources/dist/` as a bind-mount in dev `docker-compose.override.yml`. |
| HttpSession attributes contain non-`Serializable` lambdas (common in Java codebases that ran single-node forever) → Phase 6 throws at runtime. | High | Medium | Phase 0 audit catches them; Phase 6 fixes; cross-phase regression catches anything missed. |
| Removing `p1-self-client` breaks an existing P1 feature we forgot about. | Low | Medium | `grep -r p1-self-client nodejs/keycloak-demo` and `nodejs/geowealth` before Phase 3; document every match. |
| OIDC back-channel logout from KC → P1 race condition vs token-handler. | Low | Low | Already handled in token-handler `BackchannelLogoutController`; P1's new `BackChannelLogoutServlet` is the same shape. |
| Akka cluster instability surfaces under N>1 P1 replicas (multiple cluster clients). | Low | Medium | Akka is fine with multiple clients (this is its normal mode). Only the cluster *members* (agents) need single-seed. Tomcat is a client. |
| Existing `e2e/` tests assume P1 SAML flow → all fail post-Phase 3. | High | Low | Update tests as part of each phase. Add a "tests touched" subsection to each phase's PR. |
| Custom KC image bloats CI build time + image size. | Medium | Low | Multi-stage build; cache `kc.sh build` between layers; expected image growth ~20MB. |

## 9. Rollback plan

Each phase is one PR. Rollback = revert the PR. Specifically:

- Phases 1–2: revertable cleanly; user-service / SPI JAR not on the auth path
  until Phase 3.
- Phase 3: revert restores the `p1` IdP + SAML mappers; P1 login works again.
  Must run `./start.sh --reset` OR run the `scripts/reconcile-realm.sh` flow
  with the pre-Phase-3 `urls.<env>.env`.
- Phase 4: revert restores KC default theme.
- Phase 5: revert restores P1's SAML IdP code. Must also revert Phase 3 since
  the realm no longer has `p1` IdP. So Phase 3 + Phase 5 share a rollback
  unit in practice; consider merging Phases 3+5 as one PR if a wall-rollback
  matters more than incremental review.
- Phase 6: revert restores in-memory Tomcat session. Users currently logged
  in are kicked once (their session was in Redis).
- Phase 7: revert sets `replicas: 1`. Trivial.

## 10. Out of scope for this plan (follow-up MDs)

These were identified during analysis but explicitly deferred to keep this
plan focused:

1. **Bcrypt password migration** — Phase 1 uses verify-only SHA1. A follow-up
   MD `2026-XX-XX-bcrypt-migration.md` will cover the transparent rehash
   approach: `ALTER TABLE ENTITY_TBL ADD PASSWORD_HASH_ALGO`, dual-verifier in
   user-service, rehash on successful SHA1 login, eventual SHA1 drop.
2. **Akka cluster multi-replica** — agents stay single-seed in this plan. A
   follow-up MD `2026-XX-XX-akka-multi-replica.md` will cover K8s-aware seed
   discovery, split-brain resolver, cluster-singleton patterns for the
   watchdog actor, in-JVM cache invalidation protocol for
   `DistributedCacheController` / `CrntCostBasisLoader`.
3. **user-service write paths** — Phase 1 is read-only. Self-service password
   change, account lockout admin operations, etc. are a follow-up.
4. **Self-service account console beyond login/MFA/forgot-password** — profile
   editing, MFA device management, audit log view: separate SPA.
5. **KC admin UI behind GeoWealth SSO** — currently `admin/admin` direct
   login. A follow-up could promote KC admin auth to use the same SPI.
6. **External SAML federation back-INTO Keycloak** — if some external IdP
   (Okta, Azure AD) wants to federate INTO KC, that's a separate `identityProvider`
   block, not affected by this plan.

## 11. Open questions parked

- **`auth-spa` packaging — keycloakify vs plain React-in-FTL.** Deferred to
  Phase 4 spike. Either works; pick the one with less friction once we see the
  theme dev loop.
- **Where do the SPI provider JARs get versioned?** Suggest publishing to a
  local Maven repo in the demo (gradle `mavenLocal()`), letting the KC image
  build pull from there. Open to "just check in the .jar binaries to
  `keycloak-providers/dist/`" if that's simpler for the demo.
- **MFA email subject/body content** — copy verbatim from P1's
  `EmailManager.sendMfaTokenMail()` template. No new design needed.
- **`p1-client` secret rotation** — Phase 5 hardcodes `p1-dev-secret` in
  `realm-export.json`. Production rotation would move this to a K8s Secret +
  env-driven `scripts/reconcile-realm.sh` PATCH. Mentioned in
  `urls.<env>.env` section but not implemented in Phase 5.

## 12. Estimate summary

| Phase | Days (working) |
|---|---|
| 0 — Schema + spike | 1 |
| 1 — user-service | 3 |
| 2 — KC SPI provider | 2 |
| 3 — Repoint realm | 1 |
| 4 — auth-spa theme + email-OTP | 3 |
| 5 — P1 as OIDC RP | 4 |
| 6 — Tomcat session → Redis | 2 |
| 7 — Horizontal scale | 1 |
| **Total** | **17 working days (~3.5 calendar weeks for one person)** |

These are rough estimates for a developer familiar with the codebase. Add 30%
buffer for issues that surface during integration. The plan is sized for one
person serial; Phases 1 and 4 can be partially parallelized if two people are
available.
