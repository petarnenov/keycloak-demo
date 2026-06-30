# 09 — Glossary and References

## Glossary

| Term | Definition |
|---|---|
| **ACS** | Assertion Consumer Service — the SAML SP endpoint that receives Responses. In this design, KC's `/realms/demo-realm/broker/p1/endpoint`. |
| **Akka cluster** | The actor-system clustering used by P1's agent fan-out. `seed-nodes` and `roles` configured in `akka.conf.tpl`. |
| **B2 / B5 / B6** | Internal labels for security/perf hardening items in the project's risk audit. B2 = Redis-backed SID registry. B5 = mtime-aware signing-credential cache. B6 = signed LogoutRequest enforcement. |
| **Back-channel logout** | OIDC RP-discovery feature: KC POSTs a signed `logout_token` to each subscribed client's registered URL when an SSO session ends. |
| **BFF (Backend for Frontend)** | A server-side façade between an SPA and its data backend. Two flavours here: the *Token Handler* (auth-only BFF) and the per-domain *data* BFF. |
| **`demo-realm`** | The Keycloak realm that owns the demo. Has no native users — every login is brokered through `p1`. |
| **`demo-shared-client`** | The single OIDC client used by every domain in this multi-tenant Token Handler design. |
| **`firmCd`** | A GeoWealth firm code. Carried as a SAML user attribute and re-emitted as a top-level OIDC claim. |
| **First-broker-login** | KC realm auth flow that fires the first time a federated identity is seen — auto-links by email here. |
| **Forward-auth** | The `nginx auth_request` / Envoy ext-authz pattern: a subrequest to an auth service decides whether the upstream is reached. Used to keep data BFFs auth-unaware. |
| **`GWSESSION`** | The Token Handler's HttpOnly session cookie, scoped to each domain host. |
| **`HeaderIdentity`** | Helper in the data BFF that reads `X-Auth-*` headers into an in-process identity object. |
| **`InResponseTo`** | SAML Response correlation field — must match the AuthnRequest ID for an SP-init flow to succeed. |
| **`kc-ext`** | nginx pod giving Keycloak a TLS-terminating presence at `https://auth.geowealth.int:5180` reachable from inside the cluster. |
| **`kc_idp_hint=p1`** | Authorize-URL hint that tells Keycloak to skip its own login screen and broker straight to the named IdP. |
| **mkcert** | Local CA tool used to issue dev certificates. |
| **NameID** | The persistent SAML subject identifier. P1 emits the user UUID, not the email. |
| **OIDC RP** | OpenID Connect Relying Party. The Token Handler is the RP towards Keycloak. |
| **P1 (Platform 1)** | The existing GeoWealth Java/Tomcat product. Acts as the SAML IdP here. |
| **`SIDREGISTRY`** | The Redis-backed map `sid → set-of-session-ids` (see `SidSessionRegistry.java`). Drives back-channel logout. |
| **SLO** | Single Logout — the SAML/OIDC umbrella term for coordinated logout across all parties. |
| **SP** | Service Provider — the SAML party that initiates a SAML AuthnRequest. Here, Keycloak. |
| **SP-init / IdP-init** | SAML flows. SP-init = SP sends AuthnRequest, IdP signs Response. IdP-init = IdP sends unsolicited Response. This design supports SP-init only. |
| **SSO session** | Keycloak's notion of a user's overall session, distinct from the per-client `client-session`. Lives until `ssoSessionMaxLifespan`. |
| **Tier 1 / Tier 2 / Tier 3** | Coarse role / per-host route permission / row-level filter. See `07-security-and-trust-model.md` §3. |
| **Token Handler** | OAuth 2.0 BFF pattern: a server-side service that holds tokens on the SPA's behalf and exposes only an HttpOnly session cookie. |

---

## References

### Source files referenced

Repositories and paths are written relative to the repo root. `KC =
keycloak-demo`, `GW = nodejs/geowealth`.

| File | Purpose |
|---|---|
| `KC/keycloak/realm-export.json` | Realm seed (clients, IdP, mappers, roles, first-broker-login flow) |
| `KC/token-handler/src/main/resources/application.yml` | Token Handler config (tenants, session, OIDC, Redis) |
| `KC/token-handler/Dockerfile` | Token Handler image build |
| `KC/bff-core/src/main/java/demo/bff/core/AuthController.java` | `/auth/me`, `/auth/logout`, `/auth/login-failed`, `/auth/th-version` |
| `KC/bff-core/.../ForwardAuthController.java` | `/auth/verify` forward-auth endpoint |
| `KC/bff-core/.../TokenRefreshFilter.java` | Token refresh window + concurrent-dedup |
| `KC/bff-core/.../BackchannelLogoutController.java` | `/backchannel-logout` |
| `KC/bff-core/.../LogoutTokenValidator.java` | JWT validation for back-channel logout |
| `KC/bff-core/.../SidSessionRegistry.java` | Redis SID → session map |
| `KC/bff-core/.../RotatingSessionLoginHandler.java` | Session-fixation defence |
| `KC/bff-core/.../KeycloakAuthenticationMapper.java` | Code-exchange → `Authentication` |
| `KC/bff-core/.../IdpHintFilter.java` | Appends `kc_idp_hint=p1` (+ optional `prompt=none`) |
| `KC/bff-core/.../SubdomainAuthorizer.java` | Per-host authz dispatcher |
| `KC/bff-core/.../SubdomainRequirements.java` | Multi-tenant tenant map |
| `KC/bff-core/.../P1AuthzClient.java` | REST client for P1 Tier-2/3 authz |
| `KC/bff-core/.../Tier23Gate.java` | Tier-2 `require` and Tier-3 `refine` |
| `KC/bff-core/.../HeaderIdentity.java` | Reads `X-Auth-*` headers in data BFFs |
| `KC/domains/billing/bff/.../BillingController.java` | Billing data endpoints (auth-unaware) |
| `KC/domains/trading/bff/.../TradingController.java` | Trading data endpoints (auth-unaware) |
| `KC/domains/billing/web/nginx.conf` | Per-pod nginx forward-auth contract |
| `KC/k8s/base/*.yaml` | Kustomize base manifests |
| `KC/k8s/env/urls.*.env` | Per-environment URLs |
| `KC/k8s/env/data-tier.*.env` | Per-environment data-tier hosts |
| `KC/k8s/up.sh` | Deploy orchestration |
| `KC/scripts/reconcile-realm.sh` | Idempotent realm PATCHer |
| `KC/k8s/README-pods.md` | Verbatim pod inventory |
| `GW/src/main/java/com/geowealth/neo/saml/idp/IdpSsoAction.java` | SAML AuthnRequest receiver, Response builder |
| `GW/src/main/java/com/geowealth/neo/saml/idp/IdpSloAction.java` | SAML LogoutRequest receiver, replay + signature validation |
| `GW/src/main/java/com/geowealth/neo/saml/idp/IdpInitiateSloAction.java` | P1-initiated SLO entry |
| `GW/src/main/java/com/geowealth/neo/saml/idp/SilentSsoAction.java` | OIDC probe + establish round-trip |
| `GW/src/main/java/com/geowealth/util/opensaml/AbstractSamlAuthenticationResponseBuilder.java` | mtime-aware credential cache |
| `GW/src/main/java/com/geowealth/neo/saml/idp/IdpKeyStore.java` | Keystore env-var resolution |
| `GW/src/main/java/com/geowealth/neo/saml/idp/KeycloakSpCert.java` | KC SP cert for inbound signature validation |
| `GW/WebContent/react/app/src/app/_services/appService.js` | Post-login establish redirect + silent-SSO loop guard |
| `GW/k8s/entrypoint.sh` | Pod entrypoint, template rendering, watchdog name |
| `GW/k8s/config/akka.conf.tpl` | Akka cluster template |
| `GW/k8s/config/hibernate.properties.tpl` | JDBC connection template |

### Existing repo documents drawn on

| File | What it provided |
|---|---|
| `KC/CLAUDE.md` | Project-wide architecture facts (layout, multi-tenant Token Handler, data-tier env model, gotchas) |
| `KC/README.md` | User-facing entry points and topology |
| `KC/login-logout-algorithm.md` | Detailed flow narrative |
| `KC/auth-flow-scenarios.md` | Scenario walkthroughs |
| `KC/token-handler-plan.md` | Token Handler extraction rationale |
| `KC/sso-role-mapping.md` | Realm role + IdP mapper design |
| `KC/p1-auth-flow.md` | Tier-2/3 model |
| `KC/production-topology.md` | Production K8s expectations |
| `KC/production-risk-report.md` | Gap items reflected in §10 |
| `KC/sso-production-readiness-gap-analysis.md` | Production readiness gap inventory |
| `KC/cross-subdomain-sso-implementation.md` | Cross-domain SSO scenario context |
| `KC/restart-impact-matrix.md` | Which restarts affect which sessions |
| `KC/k8s/README-multitenant-k8s-plan.md` | Multi-tenant K8s rollout plan |
| `KC/k8s/README-pods.md` | Pod inventory (Section 2) |
| `KC/k8s/README-scale.md` | Production sizing notes |
| `KC/k8s/README-ubuntu-bringup.md` | Bring-up sequence |
| `GW/keycloak-poc-findings.md` | Phase 2 branding-API analysis (out of scope here but referenced for context) |

### External standards and reference material

- **IETF OAuth 2.0 for Browser-Based Apps draft** — the *Token Handler*
  / *BFF* pattern this design implements.
- **OpenID Connect Back-Channel Logout 1.0** — the `logout_token` schema
  validated by `LogoutTokenValidator.java`.
- **OASIS SAML 2.0 Core / Bindings / Profiles** — the SAML
  AuthnRequest/Response and SLO mechanics implemented by P1.
- **NIST SP 800-63C** — federation guidance (Levels of Assurance, IdP/SP
  separation).
- **OWASP ASVS V3** — session management baseline (HttpOnly, Secure,
  SameSite, fixation, refresh).
- **Curity *Token Handler* reference architecture** — the
  industry-standard reference implementation of the same pattern.
