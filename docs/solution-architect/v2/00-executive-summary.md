# 00 — Executive Summary

## Purpose

GeoWealth needs a single sign-on substrate that lets the existing **Platform 1
(P1)** product launch new browser-based products without each new product
re-implementing identity, session management, and logout, **and** without
keeping authentication code tangled in the same JVM as portfolio math and
Akka clustering. The current implementation is the reference architecture
that comes out of the **auth-extraction refactor** (Phases 0–7 of
`docs/plans/2026-06-26-auth-extraction.md`): Keycloak now owns
authentication directly against an Oracle-backed user-service; P1 is just
another OIDC RP; the SAML federation has been retired.

## The architecture in one paragraph

A user lands on a domain SPA (for example `https://billing.geowealth.int`),
on the P1 web UI (`https://p1.geowealth.int`), or clicks a domain link from
the P1 sidebar. The relevant front-end has no session, so the browser is
redirected to Keycloak — to the Token Handler's `/oauth/login/keycloak` for
the demo SPAs, or to `/oidc/login.do` for P1. In both cases Keycloak
**renders its own login page** (theme `geowealth`, visually 1:1 with P1's
`LoginTemplate1`), the user submits username + password, and Keycloak
delegates `isValid` to the User Storage SPI provider, which calls
`user-service`. `user-service` runs the legacy SHA1 password check against
`ENTITY_TBL.LDAP_PSWD_HASH`. If the user has `mfaRequiredFlag = true`, KC's
custom **email-OTP authenticator** issues a 6-digit code through
`user-service` (which writes the SHA1 of the code to `ENTITY_TBL.MFA_TOKEN`),
KC sends the email, and the user submits the code on a follow-up page. On
success KC mints an OIDC code, redirects to the appropriate callback (the
Token Handler for the demo SPAs, P1's `OidcCallbackAction` for P1 itself),
the callback exchanges the code for tokens and stores them server-side
(Redis for the Token Handler, the Redisson-backed Tomcat `HttpSession` for
P1), and an HTTP-only session cookie is issued. Every subsequent API call
to a domain BFF is forward-auth'd by nginx against the Token Handler's
`/auth/verify` endpoint, exactly as in v1.

## What is in scope for the Solution Architect review

| Area | Where it lives |
|---|---|
| Identity broker (now: direct IdP) | Keycloak `demo-realm`, with **no `identityProviders`** — local users only, federated from user-service |
| User store | `user-service/` (Micronaut + JDBC) → Oracle `ENTITY_TBL` / `ENTITY_ROLE_TBL` / `ROLE_TBL` |
| KC custom code | `keycloak-providers/user-storage-spi/`, `keycloak-providers/email-otp-authenticator/`, `auth-spa/` theme — all baked into a custom KC image via `Dockerfile.keycloak` |
| Application-side session and authorization | Multi-tenant Token Handler (`token-handler/`, library code in `bff-core/`) — **unchanged from v1** |
| Application code | Two SPAs (`domains/billing/web`, `domains/trading/web`) and two data BFFs (`domains/billing/bff`, `domains/trading/bff`) — **unchanged from v1** |
| P1 as OIDC RP | `com.geowealth.neo.oidc.rp.*` (`OidcLoginAction`, `OidcCallbackAction`, `OidcLogoutAction`, `OidcBackChannelLogoutAction`, `OidcEstablishAction`, `OidcStateStore`, `OidcBridgeStore`), Struts package `oidcRp` in `struts-oidc-rp.xml` |
| P1 session externalisation | `WebContent/META-INF/context.xml` (Redisson `RedissonSessionManager`, key prefix `p1-tomcat`), `P1RedisKcSubIndex` for `kc_sub`→`sessionId` |
| Deployment | Kubernetes manifests in `k8s/`, environment-specific config in `k8s/env/`, deploy orchestration in `k8s/up.sh`; monitoring stack via `k8s/monitoring.sh` |

## Headline design decisions

The set as a whole defends these choices in detail; the following table is
the short version for an architect skimming this page.

| Decision | Rationale |
|---|---|
| **KC owns authentication directly via the User Storage SPI; the SAML federation is retired.** | P1 owning auth tangled identity with portfolio math, Akka clustering and `CrntCostBasisLoader` heap. Extracting auth into `user-service` (and KC's SPI providers) lets each scale independently and lets KC's standard primitives (login pages, MFA flows, themes, account console) do the work that P1's hand-rolled SAML IdP used to do. See [`08-questions-and-answers.md`](08-questions-and-answers.md) §A1. |
| **One Micronaut `user-service` instead of an in-process KC user federation.** | The SPI provider is a thin HTTP client. The actual JDBC pool, the SHA1 algorithm, and the OTP storage live in a separately-scaled service that any other party (a future write-path admin tool, a service account verifier) can call. Keycloak does not embed an Oracle JDBC pool. |
| **Email-OTP via a custom KC Authenticator SPI, not KC's TOTP.** | The existing P1 user base authenticates via 6-digit email codes stored as SHA1 in `ENTITY_TBL.MFA_TOKEN`. The custom authenticator preserves that exact contract so no user is forced into a TOTP re-enrolment to upgrade. See [`03-login-flows.md`](03-login-flows.md) §3.2. |
| **`auth-spa` is a Keycloak theme, not a separate domain.** | The login UI must live on `auth.geowealth.int` to be a secure context, to ride KC's CSRF tokens, and to use KC's standard form action URLs. The Vite+React build outputs into `theme/login/resources/`, gets packaged into the custom KC image at build time, and KC renders FTL templates that mount the React bundle. See [`02-component-inventory.md`](02-component-inventory.md) §2. |
| **P1 is an OIDC RP using `p1-client`, not a SAML IdP.** | OIDC removes the per-replica SAML signing keystore (no more `/tmp/p1-idp-dev.p12`), the `InResponseTo` correlation dance, the dual-binding signature validation, and the `silent-sso.do?establish=true` round-trip. RP-initiated logout via `id_token_hint` replaces SAML LogoutRequest. See [`01-architecture-overview.md`](01-architecture-overview.md) §3. |
| **P1 Tomcat HttpSession in Redis (Redisson), not in JVM.** | Single-replica P1 was a scaling and availability bottleneck. With sessions in Redis (`p1-tomcat:redisson:tomcat_session:<id>`) plus the `kc_sub → sessionId` index (`p1-tomcat:kc_sub:<sub>`, used by the OIDC back-channel logout handler), the `p1-tomcat` Deployment scales horizontally and survives a pod kill. See [`05-kubernetes-deployment.md`](05-kubernetes-deployment.md) §9. |
| **Memcached retired — Redis serves both sessions and L2 cache.** | One stateful backing store instead of two. The L2 cache hits Redis at a separate logical key namespace; Redis is sized big enough for both and the cluster gets one operational primitive to monitor. |
| **PKCE + state + cross-host bridge for the P1 OIDC RP.** | `OidcStateStore` keeps the PKCE verifier and `originBase` in Redis (TTL 10 min) so a multi-replica P1 can complete the OIDC code exchange regardless of which replica handles the callback. The bridge store (`OidcBridgeStore`, TTL 30 s) carries a single-use ticket across hosts when the post-login target is on a different P1 host than the callback. See [`07-security-and-trust-model.md`](07-security-and-trust-model.md) §6. |
| **`p1-client` is a confidential client with back-channel logout enabled.** | `backchannel.logout.url = http://p1-tomcat:8080/oidc/back-channel-logout.do` and `backchannel.logout.session.required=true`. KC POSTs a signed `logout_token` to that URL on every SSO end-session; P1's `OidcBackChannelLogoutAction` validates the JWT and calls `GeowealthSessionListener.invalidateByKcSub`. Multi-pod fan-out works because the `kc_sub → sessionId` index is in Redis. |
| **Monitoring stack via `kube-prometheus-stack` Helm chart.** | One opinionated install gets Prometheus + Grafana + Alertmanager + node-exporter + kube-state-metrics. Project-specific alerts (Redis health, eviction, AOF rewrite, slow commands) live in `k8s/base/redis-alerts.yaml` and ship with the demo overlay. |

## What this is not

- **It is not a production system today.** The realm seed uses dev secrets,
  the keystores are self-signed, several gaps (rate limiting, audit
  pipeline, third-party IdP fail-over, bcrypt rehashing of legacy SHA1
  passwords) are documented as known follow-ups in the existing repo docs.
- **It is not a greenfield rewrite of P1's full authentication code path.**
  P1's session population logic (`LOGGED_USER`, `LOGGED_ADVISER`, `FIRM_*`
  keys) is still built by `OidcCallbackAction` running essentially the same
  steps `LoginAction#loginUser` used to run after a successful SAML
  assertion. Only the *source of credentials* has moved.
- **It is not a multi-IdP design.** There is exactly one realm (`demo-realm`)
  and no `identityProviders` block in the realm export. Multi-IdP is a
  possible future extension but not part of the current scope.

## What to read next

The recommended reading order is:

1. [`01-architecture-overview.md`](01-architecture-overview.md) — the picture
   in more detail, with the high-level diagram and trust boundaries.
2. [`02-component-inventory.md`](02-component-inventory.md) — every pod,
   what it does, and why it is in the topology.
3. [`03-login-flows.md`](03-login-flows.md) and
   [`04-logout-flows.md`](04-logout-flows.md) — every login and logout
   scenario in sequence diagrams plus the code references that back them.
4. [`05-kubernetes-deployment.md`](05-kubernetes-deployment.md) — the
   deployment topology and how environment-specific URLs flow through the
   stack.
5. [`08-questions-and-answers.md`](08-questions-and-answers.md) — pre-empted
   architect questions and their answers.
