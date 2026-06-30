# 09 — Glossary and References

## Glossary

| Term | Definition |
|---|---|
| **ACS** | Assertion Consumer Service — the SAML SP endpoint that receives Responses. **Not used in v2** (no SAML). |
| **Akka cluster** | The actor-system clustering used by P1's agent fan-out. `seed-nodes` and `roles` configured in `akka.conf.tpl`. |
| **`auth-spa`** | The Vite+React project under `auth-spa/` that builds the `geowealth` Keycloak login theme. Output gets baked into the custom KC image. |
| **Back-channel logout** | OIDC feature: KC POSTs a signed `logout_token` to each subscribed client's registered URL when an SSO session ends. |
| **BFF (Backend for Frontend)** | A server-side façade between an SPA and its data backend. Two flavours here: the *Token Handler* (auth-only BFF) and the per-domain *data* BFF. |
| **`demo-realm`** | The Keycloak realm. In v2: has no `identityProviders`, federates users from `user-service` via the User Storage SPI. |
| **`demo-shared-client`** | The single OIDC client used by every demo SPA in this multi-tenant Token Handler design. |
| **`email-otp-authenticator`** | The KC `Authenticator` SPI provider (Phase 4) that issues + validates 6-digit email codes via user-service. Replaces P1's `EmailManager.sendMfaTokenMail`. |
| **`firmCd`** | A GeoWealth firm code. Carried as a KC user attribute (sourced from `ENTITY_TBL.FIRM_CD` via the SPI) and re-emitted as a top-level OIDC claim. |
| **Forward-auth** | The `nginx auth_request` / Envoy ext-authz pattern: a subrequest to an auth service decides whether the upstream is reached. Used to keep data BFFs auth-unaware. |
| **`geowealth` theme** | The KC login theme baked from `auth-spa/`. `parent=keycloak`, overrides `login.ftl` / `login-otp.ftl` / `error.ftl`. |
| **`GWSESSION`** | The Token Handler's HttpOnly session cookie, scoped to each domain host. |
| **`HeaderIdentity`** | Helper in the data BFF that reads `X-Auth-*` headers into an in-process identity object. |
| **`JwtVerifier`** | P1 OIDC RP class (`com.geowealth.neo.oidc.rp.JwtVerifier`) that validates `id_token` and `logout_token` against KC JWKS using Nimbus JOSE+JWT. |
| **`kc-ext`** | nginx pod giving Keycloak a TLS-terminating presence at `https://auth.geowealth.int:5180` reachable from inside the cluster. |
| **`kc_sub`** | The `sub` claim in a KC-issued JWT. P1 indexes Tomcat sessions by `kc_sub` (in Redis) to support cross-pod back-channel logout. |
| **mkcert** | Local CA tool used to issue dev certificates. |
| **NameID** | (v1 only — SAML term) The persistent SAML subject identifier. Replaced by `sub` in OIDC. |
| **`OidcStateStore`** | Redis-backed (TTL 10 min) single-use store for `state → { PKCE verifier, originBase, return_to }`. Survives multi-replica `p1-tomcat`. |
| **`OidcBridgeStore`** | Redis-backed (TTL 30 s) single-use ticket store for cross-host session bootstrap (`OidcEstablishAction`). |
| **OIDC RP** | OpenID Connect Relying Party. Both the Token Handler and P1 are RPs toward Keycloak in v2. |
| **P1 (Platform 1)** | The existing GeoWealth Java/Tomcat product. In v2: an OIDC RP, no longer a SAML IdP. |
| **`p1-client`** | The OIDC client `p1-tomcat` uses. Confidential, PKCE-enabled, back-channel-logout subscribed. |
| **`P1RedisKcSubIndex`** | P1 class (`com.geowealth.web.listeners.P1RedisKcSubIndex`) that maintains a `kc_sub → Set<sessionId>` map in Redis (`p1-tomcat:kc_sub:<sub>`) so back-channel logouts can invalidate Tomcat sessions across replicas. |
| **PKCE** | Proof Key for Code Exchange — the OAuth 2.0 extension that binds an authorization code to a verifier the legitimate client generated. Used by P1's OIDC RP even though it is a confidential client. |
| **Redisson** | The Java Redis client P1 uses to back its Tomcat `HttpSession` via `org.redisson.tomcat.RedissonSessionManager` (key prefix `p1-tomcat`). |
| **`SIDREGISTRY`** | The Redis-backed map `sid → set-of-session-ids` (see `SidSessionRegistry.java`). Drives back-channel logout on the Token Handler side. |
| **`SHAPassword`** | The SHA1+optional-salt+Base64 password algorithm ported 1:1 from P1's `com.netfolio.util.SHAPassword` into `user-service`'s `com.gw.userservice.security.SHAPassword`. Used to verify legacy hashes stored in `ENTITY_TBL.LDAP_PSWD_HASH`. |
| **SLO** | Single Logout. |
| **SPI** | Keycloak Service Provider Interface — the extension surface that lets custom code register user federations, authenticators, mappers, etc. Two SPI providers in v2: `user-storage-spi` and `email-otp-authenticator`. |
| **Tier 1 / Tier 2 / Tier 3** | Coarse role / per-host route permission / row-level filter. See `07-security-and-trust-model.md` §3. |
| **Token Handler** | OAuth 2.0 BFF pattern: a server-side service that holds tokens on the SPA's behalf and exposes only an HttpOnly session cookie. **Unchanged** in v2. |
| **User Storage SPI** | Keycloak's federation mechanism for read-through user stores. The `user-service-spi` provider implements `UserLookupProvider`, `UserQueryProvider`, and `CredentialInputValidator` against `user-service`. |
| **`user-service`** | The Micronaut + JDBC service (Phase 1) that owns Oracle reads for KC's User Storage SPI. Stateless; one image per env. |

---

## References

### Source files referenced

Repositories and paths are written relative to the repo root. `KC =
keycloak-demo`, `GW = nodejs/geowealth`.

#### Identity-tier sources (KC + SPI + theme + user-service)

| File | Purpose |
|---|---|
| `KC/keycloak/realm-export.json` | Realm seed: clients (`demo-shared-client`, `p1-client`, vestigial per-domain clients), user-federation component (`user-service-spi`), no IdP block, `loginTheme=geowealth` |
| `KC/Dockerfile.keycloak` | 4-stage custom KC image: spa-builder → java-builder → kc-builder → runtime; bakes theme + provider JARs |
| `KC/auth-spa/` | Vite+React theme source; `theme/login/*.ftl`, `src/main.tsx`, build output in `theme/login/resources/` |
| `KC/keycloak-providers/user-storage-spi/` | `UserStorageProviderFactoryImpl`, `UserStorageProviderImpl`, `UserServiceUser`, `UserServiceClient`, `MiniJson` |
| `KC/keycloak-providers/email-otp-authenticator/` | `EmailOtpAuthenticator`, `EmailOtpAuthenticatorFactory`, `UserServiceClient` |
| `KC/user-service/src/main/java/com/gw/userservice/Application.java` | Micronaut entry point |
| `KC/user-service/.../api/UserController.java` | `/users/search`, `/users/{id}`, `/users/{id}/attributes`, `/users/{id}/roles`, `/users/{id}/verify-credentials`, `/users/{id}/mfa-token`, `/users/{id}/mfa-token/verify` |
| `KC/user-service/.../dao/EntityDao.java` | `findByUsernameAndFirm`, `findByEntityId`, `findPasswordHash`, `searchByUsername` |
| `KC/user-service/.../dao/RoleDao.java` | JOIN `ENTITY_ROLE_TBL` + `ROLE_TBL` |
| `KC/user-service/.../dao/MembershipDao.java` | CTE walk of `LINKED_GW_USER` graph |
| `KC/user-service/.../dao/MfaTokenDao.java` | OTP issue + verify against `ENTITY_TBL.MFA_TOKEN` |
| `KC/user-service/.../security/SHAPassword.java` | SHA1 + salt + `{SHA}` / `{SSHA}` labels |
| `KC/user-service/Dockerfile` | gradle:8.5-jdk17 builder → eclipse-temurin:17-jre runtime |
| `KC/user-service/src/main/resources/application.yml` | Datasource (Hikari), Micronaut server, MFA TTL |

#### Token Handler + data BFFs + theme (unchanged from v1)

| File | Purpose |
|---|---|
| `KC/token-handler/src/main/resources/application.yml` | Tenants, session, OIDC, Redis, fine-authz toggle |
| `KC/token-handler/Dockerfile` | Composite build with bff-core |
| `KC/bff-core/src/main/java/demo/bff/core/AuthController.java` | `/auth/me`, `/auth/logout`, `/auth/login-failed`, `/auth/th-version` |
| `KC/bff-core/.../ForwardAuthController.java` | `/auth/verify` |
| `KC/bff-core/.../TokenRefreshFilter.java` | Refresh window + concurrent-dedup |
| `KC/bff-core/.../BackchannelLogoutController.java` | `/backchannel-logout` |
| `KC/bff-core/.../LogoutTokenValidator.java` | JWT validation for back-channel logout |
| `KC/bff-core/.../SidSessionRegistry.java` | Redis SID → session map |
| `KC/bff-core/.../RotatingSessionLoginHandler.java` | Session-fixation defence |
| `KC/bff-core/.../KeycloakAuthenticationMapper.java` | Code-exchange → `Authentication` |
| `KC/bff-core/.../SubdomainAuthorizer.java` | Per-host authz dispatcher |
| `KC/bff-core/.../SubdomainRequirements.java` | Multi-tenant tenant map |
| `KC/bff-core/.../P1AuthzClient.java` | REST client for P1 Tier-2/3 authz |
| `KC/bff-core/.../Tier23Gate.java` | Tier-2 `require` and Tier-3 `refine` |
| `KC/bff-core/.../HeaderIdentity.java` | Reads `X-Auth-*` headers in data BFFs |
| `KC/domains/billing/bff/.../BillingController.java` | Billing data endpoints (auth-unaware) |
| `KC/domains/trading/bff/.../TradingController.java` | Trading data endpoints (auth-unaware) |
| `KC/domains/billing/web/nginx.conf` | Per-pod nginx forward-auth contract |

#### P1 OIDC RP (new in v2)

| File | Purpose |
|---|---|
| `GW/src/main/java/com/geowealth/neo/oidc/rp/OidcLoginAction.java` | `/oidc/login.do` — start OIDC code flow |
| `GW/.../oidc/rp/OidcCallbackAction.java` | `/oidc/callback.do` — exchange code, validate id_token, populate session |
| `GW/.../oidc/rp/OidcLogoutAction.java` | `/oidc/logout.do` — invalidate session, redirect to KC end-session |
| `GW/.../oidc/rp/OidcEstablishAction.java` | `/oidc/establish.do` — cross-host bridge |
| `GW/.../oidc/rp/OidcBackChannelLogoutAction.java` | `/oidc/back-channel-logout.do` — validate logout_token, invalidate by kc_sub |
| `GW/.../oidc/rp/OidcConfig.java` | env-driven config (issuer, client id/secret, redirect URI) |
| `GW/.../oidc/rp/OidcStateStore.java` | Redis-backed PKCE/state |
| `GW/.../oidc/rp/OidcBridgeStore.java` | Redis-backed single-use bridge ticket |
| `GW/.../oidc/rp/JwtVerifier.java` | Nimbus JOSE+JWT validator |
| `GW/src/main/resources/struts-oidc-rp.xml` | Struts registration of `/oidc/*` actions |
| `GW/.../web/listeners/P1RedisKcSubIndex.java` | Redis-backed `kc_sub → sessionId` set |
| `GW/.../web/listeners/GeowealthSessionListener.java` | Wires session-create / destroy into the kc_sub index |
| `GW/WebContent/META-INF/context.xml` | Redisson Tomcat session manager registration |
| `GW/WebContent/react/app/src/app/_services/appService.js` | Replaces `loginPassword` with a redirect to `/oidc/login.do` |
| `GW/k8s/entrypoint.sh` | Renders `oidc.properties` and `redisson.yaml` from env |

#### K8s

| File | Purpose |
|---|---|
| `KC/k8s/base/*.yaml` | Kustomize base manifests (one per component) |
| `KC/k8s/base/redis-alerts.yaml` | PrometheusRule with 11 Redis alerts (new in v2) |
| `KC/k8s/env/urls.*.env` | Per-environment URLs |
| `KC/k8s/env/data-tier.*.env` | Per-environment data-tier hosts |
| `KC/k8s/up.sh` | Deploy orchestration |
| `KC/k8s/monitoring.sh` | Helm install of kube-prometheus-stack |
| `KC/k8s/monitoring-values.yaml` | Helm chart values |
| `KC/k8s/portforward.sh` | Idempotent persistent port-forwards |
| `KC/scripts/reconcile-realm.sh` | Idempotent realm PATCHer (no longer touches SAML IdP) |
| `KC/k8s/README-pods.md` | Verbatim pod inventory |

### Existing repo documents drawn on

| File | What it provided |
|---|---|
| `KC/docs/plans/2026-06-26-auth-extraction.md` | The plan that drives the v2 architecture |
| `KC/CLAUDE.md` | Project-wide architecture facts (layout, multi-tenant Token Handler, data-tier env model, gotchas) |
| `KC/README.md` | User-facing entry points and topology |
| `KC/login-logout-algorithm.md` | Detailed flow narrative (v1 baseline; v2 supersedes the SSO portions) |
| `KC/token-handler-plan.md` | Token Handler extraction rationale (unchanged in v2) |
| `KC/sso-role-mapping.md` | Realm role design (v1 baseline; v2 uses SPI-sourced roles instead of SAML mappers) |
| `KC/p1-auth-flow.md` | Tier-2/3 model |
| `KC/production-topology.md` | Production K8s expectations |
| `KC/production-risk-report.md` | Gap items |
| `KC/sso-production-readiness-gap-analysis.md` | Production readiness gap inventory |
| `KC/restart-impact-matrix.md` | Which restarts affect which sessions |
| `KC/k8s/README-multitenant-k8s-plan.md` | Multi-tenant K8s rollout plan |
| `KC/k8s/README-scale.md` | Production sizing notes |
| `KC/k8s/README-ubuntu-bringup.md` | Bring-up sequence |

### External standards and reference material

- **OpenID Connect Core 1.0** — the OIDC code flow used by both the
  Token Handler and P1.
- **OpenID Connect Back-Channel Logout 1.0** — the `logout_token`
  schema validated by both `LogoutTokenValidator` and the P1
  `JwtVerifier`.
- **RFC 7636 — Proof Key for Code Exchange** — PKCE.
- **IETF OAuth 2.0 for Browser-Based Apps draft** — the *Token
  Handler* / *BFF* pattern.
- **Keycloak Server Developer Guide — User Storage SPI** — the SPI
  contract `user-storage-spi` implements.
- **Keycloak Server Developer Guide — Authentication SPI** — the
  contract `email-otp-authenticator` implements.
- **NIST SP 800-63B** — authenticator guidance (relevant for the
  future SHA1 → bcrypt migration discussion).
- **OWASP ASVS V3** — session management baseline (HttpOnly, Secure,
  SameSite, fixation, refresh).
