# 01 — Architecture Overview

## High-level container picture

```mermaid
flowchart LR
    subgraph Browser["End-user browser"]
        spaB["Billing SPA<br/>https://billing.geowealth.int"]
        spaT["Trading SPA<br/>https://trading.geowealth.int"]
        p1ui["P1 web UI<br/>p1.geowealth.int"]
    end

    subgraph Edge["Ingress / TLS termination"]
        ingress["ingress-nginx<br/>per-host TLS"]
    end

    subgraph Identity["Identity tier"]
        kc["Keycloak<br/>demo-realm"]
        kcext["kc-ext<br/>(in-cluster TLS sidecar)"]
        kcdb[(KC Postgres)]
        th["Token Handler<br/>(multi-tenant)"]
        redis[(Redis<br/>sessions + SID index)]
    end

    subgraph Domain["Domain tier"]
        wb["web-billing<br/>(nginx + SPA)"]
        bb["bff-billing<br/>(data BFF)"]
        wt["web-trading<br/>(nginx + SPA)"]
        bt["bff-trading<br/>(data BFF)"]
    end

    subgraph P1Tier["Legacy P1 (SAML IdP)"]
        p1tc["p1-tomcat<br/>(Struts + IdP actions)"]
        p1co["p1-coordinator<br/>(Akka seed)"]
        agents["9 × p1-agent pods<br/>(Akka cluster)"]
        oracle[(Oracle PDB)]
        es[(Elasticsearch)]
        mc[(Memcached)]
    end

    spaB --> ingress
    spaT --> ingress
    p1ui --> ingress
    ingress --> wb
    ingress --> wt
    ingress --> kcext --> kc
    ingress --> p1tc

    wb -- "/oauth/*, /auth/*, /logout" --> th
    wb -- "/api/billing forward-auth /auth/verify" --> th
    wb -- "/api/billing (after 200)" --> bb
    wt -- "/oauth/*, /auth/*, /logout" --> th
    wt -- "/api/trading forward-auth /auth/verify" --> th
    wt -- "/api/trading (after 200)" --> bt

    th -- "OIDC code, token, JWKS, logout" --> kc
    th -- "Tier-2 authz" --> p1tc
    bb -- "Tier-3 list refine" --> p1tc
    bt -- "Tier-3 list refine" --> p1tc
    th -- "GWSESSION + SID index" --> redis

    kc -- "SAML AuthnRequest / Response" --> p1tc
    kc -- "users, realms, federated ids" --> kcdb

    p1tc -- "Akka" --> p1co
    p1co --- agents
    p1tc --- oracle
    p1tc --- es
    p1tc --- mc
```

The diagram above is intentionally lossy. The full pod-by-pod inventory is in
[`02-component-inventory.md`](02-component-inventory.md); the
flow-by-flow sequence diagrams are in
[`03-login-flows.md`](03-login-flows.md),
[`04-logout-flows.md`](04-logout-flows.md), and
[`06-communication-diagrams.md`](06-communication-diagrams.md).

## Trust boundaries

```mermaid
flowchart TB
    subgraph Public["Public boundary — browser-facing"]
        S1[Domain SPAs]
        S2[Keycloak hostname<br/>auth.geowealth.int:5180]
        S3[P1 web UI]
    end
    subgraph Cluster["Cluster boundary — geowealth-demo namespace"]
        T1[Token Handler]
        T2[Domain BFFs]
        T3[Keycloak pod]
        T4[Redis]
        T5[KC Postgres]
        T6[P1 tomcat + agents]
        T7[Oracle / ES / Memcached]
    end

    S1 -- "TLS via ingress" --> T1
    S2 -- "TLS via ingress → kc-ext" --> T3
    S3 -- "TLS via ingress" --> T6

    T1 -. "JWKS + token exchange" .- T3
    T1 -. "Tier-2 authz" .- T6
    T2 -. "Tier-3 refine (via P1)" .- T6
    T3 -. "SAML AuthnRequest / Response" .- T6
    T1 -. "GWSESSION + SID" .- T4
    T3 -. "DB writes" .- T5
    T6 -. "JDBC + ES + cache" .- T7
```

Two boundaries matter for review:

1. **Browser to ingress.** Everything outside the cluster terminates TLS at
   `ingress-nginx`. Domain hosts use mkcert-signed certificates in
   development; production is expected to source certificates from the
   organisation's standard CA pipeline.
2. **Pod to pod inside the namespace.** Pod-to-pod traffic is plain HTTP
   today, scoped by Kubernetes Services. The pattern is compatible with a
   future service-mesh / mTLS overlay (Linkerd, Istio, Cilium) without source
   changes — the application code is connection-agnostic.

## Actors and where state lives

The full table is in
[`login-logout-algorithm.md`](../../login-logout-algorithm.md) in the
repo root; the version below highlights what an architect cares about.

| Actor | State held server-side | Browser cookie / storage |
|---|---|---|
| **Browser** | None (no front-end-side token storage) | `GWSESSION` (HttpOnly, Secure, SameSite=Lax) per host; SPA session-state for in-memory UI flags only |
| **Keycloak** | SSO session, federated identity link, per-client client-session, realm data in Postgres | `KEYCLOAK_IDENTITY`, `KEYCLOAK_SESSION` on the `auth.geowealth.int` host |
| **Token Handler** | Server-side session in Redis, holds `access_token`, `refresh_token`, `id_token`, `sid`, last-refresh timestamp; `SidSessionRegistry` map (sid → session-ids) | n/a (the cookie above is its only browser surface) |
| **Domain data BFFs** | None (forward-auth) | n/a |
| **P1 Tomcat** | `LoggedUser`, `LoggedAdviser`, `firmInfo`, `ConversationManager` in `HttpSession`; persistent identity in Oracle | `JSESSIONID` |

The four lifetime knobs that drive how these expire (token lifespan, KC SSO
idle, KC SSO max, Tomcat session timeout) live in
`keycloak/realm-export.json` and the P1 `web.xml`; the mismatch between them is
why the **post-login establish round-trip** (see
[`03-login-flows.md`](03-login-flows.md) §3.3) and the **silent SSO probe**
(§3.5) exist.

## Tenancy model

The platform is multi-tenant in two senses:

1. **Per-domain tenancy.** Each domain (`billing`, `trading`, …) is an
   independent tenant from the Token Handler's perspective. The Token Handler
   resolves which tenant a request belongs to from the original browser host
   (`X-Forwarded-Host`) and looks up the tenant's authorization requirement
   in `app.tenants.<slug>` (see
   `token-handler/src/main/resources/application.yml`). The full per-domain
   tenant model lives in
   [`02-component-inventory.md`](02-component-inventory.md) §2 and
   [`07-security-and-trust-model.md`](07-security-and-trust-model.md) §3.

2. **Per-firm tenancy.** Inside a domain, users belong to a `firmCd` (a
   GeoWealth concept). The `firmCd` arrives at Keycloak from P1 as a SAML
   user attribute, is re-emitted as a top-level OIDC claim by the realm's
   `oidc-usermodel-attribute-mapper` (`firm-cd-claim`), and is then included
   in the `X-Auth-Firm-Cd` forward-auth header. Data BFFs use it to scope
   queries.

## Authorization model — three tiers

| Tier | Where it fires | What it gates | Source |
|---|---|---|---|
| **Tier 1 — coarse roles** | Token Handler (Micronaut Security) | Login / no-login, plus role-name checks (for example `@Secured("admin")`) where present | Realm role mappers in `keycloak/realm-export.json` |
| **Tier 2 — route / object-type permission** | Token Handler `/auth/verify` (called by nginx forward-auth) | Whether the user may *reach* `/api/<domain>` at all on this host | `app.tenants.<slug>` in `token-handler/application.yml`, evaluated by `SubdomainAuthorizer.authorize()` against `P1AuthzClient` |
| **Tier 3 — row-level refine** | Inside each domain BFF, on list endpoints | Which rows of a result set the user may see | `Tier23Gate.refine()` in the domain BFF, calling `P1AuthzClient.refine()` against P1 |

Tier 3 is intentionally co-located with the data (the domain BFF, not the
Token Handler), because it operates on row IDs that only the data layer
knows. The access token reaches the BFF as the `X-Auth-Access-Token` forward
header, so the BFF can authenticate to P1 for the refine call without
holding a session itself.

## Why this shape

The whole-system shape is the answer to one production concern: **a
shared-auth fix must not log users out and must not require rebuilding every
domain's data BFF**. Folding all auth into a separate Token Handler service
that runs in front of every data BFF gets that property cleanly, and is the
industry-standard *Token Handler* pattern (OAuth 2.0 BFF for SPAs, as
specified by the *OAuth 2.0 for Browser-Based Apps* IETF draft).

The same shape also gives:

- A single OIDC client to manage in the realm (`demo-shared-client`) instead
  of one per domain.
- A single place where session, token refresh, and back-channel logout live.
- A clean path for adding a new domain: copy the SPA-and-BFF pair, add one
  tenants entry, add one ingress host, and it is integrated.

This is not unique to this codebase — it mirrors the BFF/Token Handler
guidance in Curity's *Token Handler pattern*, Auth0's *Single Page App BFF*
guide, and the OWASP *OAuth 2.0 BFF* recommendations.
