# 00 — Executive Summary

## Purpose

GeoWealth needs a single sign-on substrate that lets the existing **Platform 1
(P1)** product launch new browser-based products without each new product
re-implementing identity, session management, and logout. The brokered-SSO
demo in this repository is the reference implementation: a Keycloak realm sits
in front of two demo applications (`billing`, `trading`) and federates every
login back to P1 via SAML.

The same shape is intended to host any number of future products: adding a
product is one container pair, one ingress host, and one tenants-map entry.

## The architecture in one paragraph

A user lands on a domain SPA (for example `https://billing.geowealth.int`) or
clicks a domain link from the P1 sidebar. The SPA has no session, so the
browser is redirected to Keycloak (the OIDC IdP). Keycloak does not present a
login screen of its own — it brokers the request out to P1 via SAML, lands
back at Keycloak with an assertion, mints an OIDC code, and bounces the
browser back to the domain. The domain's nginx routes the resulting OIDC
callback to one shared multi-tenant **Token Handler** service, which
exchanges the code for tokens, stores them server-side in Redis, and issues an
HTTP-only session cookie scoped to the domain host. Every subsequent API call
to the domain BFF is forward-auth'd by nginx against the Token Handler's
`/auth/verify` endpoint, which validates the session, refreshes the OIDC
access token if needed, runs per-host authorization, and injects identity
headers onto the upstream request. The domain BFF itself runs zero
authentication code.

## What is in scope for the Solution Architect review

| Area | Where it lives |
|---|---|
| Identity broker | Keycloak `demo-realm`, one SAML IdP (`p1`), one shared OIDC client (`demo-shared-client`) |
| Application-side session and authorization | Multi-tenant Token Handler (`token-handler/`, library code in `bff-core/`) |
| Application code | Two SPAs (`domains/billing/web`, `domains/trading/web`) and two data BFFs (`domains/billing/bff`, `domains/trading/bff`) |
| SAML IdP | P1 Tomcat application (`/home/petar/nodejs/geowealth`), Struts actions `IdpSsoAction`, `IdpSloAction`, `SilentSsoAction` |
| Deployment | Kubernetes manifests in `k8s/`, environment-specific config in `k8s/env/`, deploy orchestration in `k8s/up.sh` |

## Headline design decisions

The set as a whole defends these choices in detail; the following table is the
short version for an architect skimming this page.

| Decision | Rationale |
|---|---|
| **One shared OIDC client (`demo-shared-client`), not one per domain.** | A single Token Handler can therefore front every domain; users land on one logical session realm at the IdP layer; adding a domain does not require a new client registration. |
| **Multi-tenant Token Handler, fronts every domain.** | Sharing the auth runtime across domains means a security fix to login/logout is one image rebuild and one restart instead of N. Sessions live in Redis, so a rolling restart does not log users out. |
| **Forward-auth (`nginx auth_request`), not in-process security filters in the data BFF.** | The data BFFs (`bff-billing`, `bff-trading`) contain zero auth code, which means an auth-layer change cannot accidentally break a domain BFF. Identity reaches the BFF as a set of `X-Auth-*` HTTP headers injected by nginx after the Token Handler authorises the request. |
| **Sessions in Redis (B2), not in-process.** | Lets the Token Handler scale horizontally and survive restart without invalidating any user. Same store holds the OIDC SID → session-id index used by back-channel logout. |
| **Back-channel logout via OIDC `sid` instead of front-channel iframes.** | Survives third-party cookie blocking, has no UI flash, and works the same in development and production. |
| **SAML IdP is P1 itself (not a third-party IdP).** | Real authentication continues to live in the same place as the existing book of users. Keycloak is a stateless broker over it; no native users exist in `demo-realm`. |
| **Environment-specific URLs externalised into `k8s/env/urls.<env>.env`.** | The same images run unchanged in dev, QA, and prod. The realm is reconciled from the env file via an idempotent admin-API patcher (`scripts/reconcile-realm.sh`). |
| **Forward-auth host resolution by `X-Forwarded-Host`.** | The Token Handler resolves which tenant a request belongs to from the original browser host, so it stays a singleton even as new domains are added (`app.tenants.<slug>` in `token-handler/application.yml`). |

## What this is not

- **It is not a production system today.** The realm seed uses dev secrets,
  the keystores are self-signed, and several gaps (rate limiting, audit
  pipeline, third-party IdP fail-over) are documented as known follow-ups in
  the existing repo docs.
- **It is not a greenfield rewrite of P1's authentication.** P1 continues to
  own the password store and the SAML IdP. Keycloak is in the path purely as
  a federation hub.
- **It is not a multi-IdP design.** There is exactly one SAML IdP (`p1`) and
  one OIDC client per realm today. Multi-IdP is a possible future extension
  but not part of the current scope.

## What to read next

The recommended reading order is:

1. [`01-architecture-overview.md`](01-architecture-overview.md) — the picture
   in more detail, with the high-level diagram and trust boundaries.
2. [`02-component-inventory.md`](02-component-inventory.md) — every pod, what
   it does, and why it is in the topology.
3. [`03-login-flows.md`](03-login-flows.md) and
   [`04-logout-flows.md`](04-logout-flows.md) — every login and logout
   scenario in sequence diagrams plus the code references that back them.
4. [`05-kubernetes-deployment.md`](05-kubernetes-deployment.md) — the
   deployment topology and how environment-specific URLs flow through the
   stack.
5. [`08-questions-and-answers.md`](08-questions-and-answers.md) — pre-empted
   architect questions and their answers.
