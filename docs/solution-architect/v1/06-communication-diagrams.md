# 06 — Communication Diagrams

This file collects the diagrams referenced from other documents in one
place, plus a few that did not fit cleanly inside the flow descriptions.
Confluence renders Mermaid inline; if a target space does not, the
text-source versions below can be rendered with any Mermaid CLI.

The diagrams follow a loose C4-style hierarchy:

- **Level 1 — System context.** Who interacts with the platform.
- **Level 2 — Containers.** Which processes are involved.
- **Level 3 — Component interactions.** Specific runtime flows.

---

## 1. Level 1 — System context

```mermaid
flowchart LR
    user["End user<br/>(browser)"]
    admin["Realm administrator<br/>(KC admin UI / API)"]
    p1user["P1 user with sidebar links"]
    sso[["GeoWealth SSO Demo Platform<br/>(this system)"]]
    p1["P1<br/>(SAML IdP + product UI)"]
    org["Org TLS / DNS"]
    pdb[("Org Oracle / ES")]

    user --> sso
    p1user --> sso
    admin --> sso
    sso --> p1
    sso --> org
    sso --> pdb
```

External actors:

| Actor | Why it matters here |
|---|---|
| End user (browser) | Carries `GWSESSION` per host; the only place untrusted input enters the system |
| P1 user with sidebar links | Same browser; pre-authenticated at P1, drives SP-init SSO via sidebar deep links |
| Realm administrator | Operates Keycloak through its admin UI or the admin API; touched by `scripts/reconcile-realm.sh` during deploy |

External systems:

| System | Boundary it crosses |
|---|---|
| P1 (SAML IdP) | The federation. Owned by the same organisation but is a separate runtime |
| Org TLS / DNS | Owns the wildcard cert for `*.geowealth.int` in production and the DNS records the demo hosts hang off |
| Org Oracle / Elasticsearch | The real customer-data store. The demo points its in-cluster names at it via `data-tier.<env>.env` |

---

## 2. Level 2 — Containers (runtime)

```mermaid
flowchart TB
    user[Browser]

    subgraph Edge["Cluster edge"]
        ing["ingress-nginx"]
    end

    subgraph IdTier["Identity tier"]
        kcext["kc-ext (nginx TLS sidecar)"]
        kc["keycloak-0"]
        th["token-handler<br/>(2-12 replicas)"]
        kcpg[("kc-postgres-0")]
        redis[("redis-0")]
    end

    subgraph DomTier["Domain tier"]
        webB["web-billing"]
        webT["web-trading"]
        bffB["bff-billing"]
        bffT["bff-trading"]
    end

    subgraph P1Tier["P1 (SAML IdP + Tomcat product)"]
        p1tc["p1-tomcat-0"]
        p1co["p1-coordinator-0"]
        p1ag["p1-* agent pods (9)"]
        ora[("oracle-0")]
        es[("elasticsearch-0")]
    end

    user --HTTPS--> ing
    ing --> webB
    ing --> webT
    ing --> kcext --> kc
    ing --> p1tc

    webB --"/oauth/* /auth/* /logout"--> th
    webB --"auth_request /auth/verify"--> th
    webB --"/api/billing (after 200)"--> bffB
    webT --> th
    webT --> bffT

    th --"OIDC, JWKS, end-session"--> kc
    th --"Tier-2 authz (REST)"--> p1tc
    bffB --"Tier-3 list refine (REST)"--> p1tc
    bffT --"Tier-3 list refine (REST)"--> p1tc
    th --"GWSESSION + SID index"--> redis

    kc --"SAML AuthnRequest / Response"--> p1tc
    kc --"realm + users + sessions"--> kcpg

    p1tc --Akka--> p1co
    p1co -.- p1ag
    p1tc --> ora
    p1tc --> es
    p1tc --> mc
```

---

## 3. SP-initiated SSO — sequence (canonical)

```mermaid
sequenceDiagram
    autonumber
    actor B as Browser
    participant W as Web (nginx)
    participant T as Token Handler
    participant K as Keycloak
    participant P as P1 IdpSsoAction
    participant R as Redis

    B->>W: GET /auth/me (no GWSESSION)
    W->>T: GET /auth/me
    T-->>B: 401
    B->>W: GET /oauth/login/silent
    W->>T: /oauth/login/silent
    T-->>B: 302 KC authorize ?kc_idp_hint=p1
    B->>K: GET /protocol/openid-connect/auth
    K-->>B: 302 /saml/idp/sso.do (SAMLRequest)
    B->>P: GET /saml/idp/sso.do
    P-->>B: 200 auto-submit form (signed SAML Response)
    B->>K: POST /broker/p1/endpoint
    K-->>B: 302 redirect_uri ?code
    B->>W: GET /?code
    W->>T: /?code
    T->>K: POST /token
    K-->>T: tokens
    T->>R: persist session + SID
    T-->>B: 302 /, Set-Cookie: GWSESSION
```

The same diagram, with the lookups and storage emphasised:

```mermaid
sequenceDiagram
    participant T as Token Handler
    participant K as Keycloak
    participant R as Redis

    T->>K: GET /.well-known/openid-configuration
    K-->>T: discovery doc
    T->>K: GET /protocol/openid-connect/certs
    K-->>T: JWKS
    Note over T: cached for life of pod

    Note over T,R: at login:
    T->>R: SET bff:sess:<sid> = session-id (TTL 12h)
    T->>R: SADD bff:sid:<sid> session-id (TTL 12h)
```

---

## 4. Forward-auth — request-level interaction

```mermaid
sequenceDiagram
    autonumber
    actor B as Browser
    participant W as Web (nginx)
    participant T as Token Handler
    participant D as Data BFF

    B->>W: GET /api/billing/invoices
    W->>T: GET /auth/verify (subrequest, with GWSESSION + X-Forwarded-Host)
    Note over T: TokenRefreshFilter:<br/>refresh access token if expiring
    Note over T: SubdomainAuthorizer.authorize(auth, host)<br/>→ Tier-2 P1 authz
    T-->>W: 200 + X-Auth-* headers
    W->>D: GET /api/invoices (no cookie, X-Auth-* set)
    D->>D: HeaderIdentity.from(request)
    D->>D: gate.refine(authentication, invoices, ...)<br/>(Tier-3)
    D-->>W: 200 + filtered invoices JSON
    W-->>B: 200 + filtered invoices JSON
```

Note the difference between Tier-2 and Tier-3:

| Tier | Where it runs | Subject | Cost |
|---|---|---|---|
| Tier-2 | Token Handler `/auth/verify` | "Can this user reach `/api/billing` on this host?" | One P1 call per cache-miss (60 s TTL) |
| Tier-3 | Data BFF row-list endpoint | "Which IDs in this list may this user see?" | One P1 call per list response |

---

## 5. RP-initiated logout — sequence

```mermaid
sequenceDiagram
    autonumber
    actor B as Browser
    participant W as Web
    participant T as Token Handler
    participant K as Keycloak
    participant P as P1 IdpSloAction
    participant R as Redis

    B->>W: POST /auth/logout (form)
    W->>T: POST /auth/logout
    par fire-and-forget
        T->>K: POST /protocol/openid-connect/logout
        K->>T: POST /backchannel-logout (per client-session)
        K->>P: SAML LogoutRequest (via browser POST)
    end
    T->>R: del session, drop SID
    T-->>B: 303 /saml/idp/initiate-slo.do
    B->>P: GET /saml/idp/initiate-slo.do
    P->>P: invalidate HttpSession(s) by NameID
    P-->>B: 200 auto-submit SAML LogoutResponse
    B->>K: POST /broker/p1/endpoint
    K-->>B: 302 logout-complete
```

---

## 6. Back-channel logout — sequence (KC → TH)

```mermaid
sequenceDiagram
    autonumber
    participant K as Keycloak
    participant T as Token Handler<br/>(BackchannelLogoutController)
    participant V as LogoutTokenValidator
    participant Reg as SidSessionRegistry
    participant R as Redis

    K->>T: POST /backchannel-logout (form, logout_token=JWT)
    T->>V: validate(logout_token)
    V->>V: verify RS256 against JWKS,<br/>iss, aud, events, no nonce, jti
    V-->>T: { sid }
    T->>Reg: invalidateBySid(sid)
    Reg->>R: SMEMBERS bff:sid:<sid>
    Reg->>R: for each session-id: DEL session<br/>and DEL bff:sess:<session-id>
    Reg->>R: DEL bff:sid:<sid>
    T-->>K: 200 Cache-Control: no-store
```

---

## 7. Token refresh — sequence

```mermaid
sequenceDiagram
    autonumber
    actor B as Browser
    participant T as Token Handler<br/>(TokenRefreshFilter)
    participant K as Keycloak
    participant R as Redis

    B->>T: any /auth/* or /api/* request
    T->>R: load session
    R-->>T: { access_token, refresh_token, last_validated_at }
    alt access token within 60s of expiry<br/>OR last_validated_at older than 30s
        T->>K: POST /token grant_type=refresh_token
        alt 200
            K-->>T: new tokens
            T->>R: save updated session
        else 4xx invalid_grant
            K-->>T: 400
            T->>R: DEL session
            T-->>B: 401
        else 5xx / transport
            K-->>T: error
            Note over T: keep current token, serve request
        end
    end
    T->>T: continue handler
```

The 30 s `VALIDATE_INTERVAL_SECONDS` is the headline number an architect
should remember — it is the upper bound on how long a P1-side SLO that
fails to back-channel through Keycloak takes to surface as a 401 to the
SPA.

---

## 8. Sidebar deep-link (warm browser, IdP-side state alive)

```mermaid
sequenceDiagram
    autonumber
    actor B as P1 user (browser)
    participant P1 as P1 SPA
    participant KC as Keycloak
    participant Pid as P1 IdpSsoAction
    participant W as web-billing

    P1->>P1: useIntegrationLinks → render sidebar link
    Note right of P1: link = KC authorize URL ?<br/>client_id=demo-shared-client&<br/>kc_idp_hint=p1&<br/>redirect_uri=https://billing.geowealth.int:5184/
    B->>KC: GET /auth?... (click)
    KC-->>B: 302 /saml/idp/sso.do (SAMLRequest)
    B->>Pid: GET /saml/idp/sso.do (with JSESSIONID)
    Pid-->>B: 200 auto-submit form (signed Response)
    B->>KC: POST /broker/p1/endpoint
    KC-->>B: 302 redirect_uri ?code
    B->>W: GET /?code → flow 3.1 from "code exchange" onward
```

The interesting piece here is what the sidebar link **does not contain**:
no SPA-side state, no token, no session ID — it is a pure deep-link to
Keycloak's authorize endpoint with the `kc_idp_hint` set. The
`useIntegrationLinks` source lives in
`~/geowealth/WebContent/react/app/src/pages/PlatformOne/sidebar/_hooks/useIntegrationLinks.js`
(outside this repo).

---

## 9. K8s ingress + auth-request

```mermaid
sequenceDiagram
    autonumber
    actor B as Browser
    participant I as ingress-nginx-controller
    participant T as token-handler Service
    participant W as web-billing Service
    participant D as bff-billing Service

    B->>I: HTTPS https://billing.geowealth.int/api/billing/invoices
    I->>T: subrequest http://token-handler:8080/auth/verify<br/>(forwards X-Forwarded-Host: billing.geowealth.int, GWSESSION)
    T-->>I: 200 + X-Auth-*
    I->>D: http://bff-billing:8080/api/invoices<br/>(strips Cookie, copies X-Auth-*)
    D-->>I: 200 JSON
    I-->>B: 200 JSON
```

In compose, the same contract is enforced one layer lower (inside each
`web-*` pod's nginx). In Kubernetes, the cluster-edge controller does it
once for every host.

---

## 10. P1 Akka cluster topology

The Akka cluster diagram below is conceptual; runtime membership depends
on which agents are scheduled. Every agent points its `seed-nodes` at
`akka://DevPetar@p1-coordinator-0.p1-coordinator:4007`, joins on boot,
and announces the Managers it provides. Tomcat sends `ask` messages to
named Managers; the cluster routes them to the actor on the agent that
owns the Manager.

```mermaid
flowchart LR
    p1tc["p1-tomcat-0<br/>(cluster member)"]
    seed["p1-coordinator-0<br/>(seed)"]
    a1["p1-samlmanager"]
    a2["p1-useragents"]
    a3["p1-devcommonagents"]
    a4["p1-searchagents"]
    a5["p1-mostagents"]
    a6["p1-cspagents"]
    a7["p1-emailagent"]
    a8["p1-proposalagents"]
    a9["p1-reportengine"]

    p1tc -.- seed
    seed -.- a1
    seed -.- a2
    seed -.- a3
    seed -.- a4
    seed -.- a5
    seed -.- a6
    seed -.- a7
    seed -.- a8
    seed -.- a9
    p1tc -- ask Manager --> a1
    p1tc -- ask Manager --> a2
    p1tc -- ask Manager --> a3
```

The two **load-bearing** agents for SSO are `p1-samlmanager` (signs the
SAML responses and validates inbound LogoutRequests) and
`p1-devcommonagents` (hosts `AuthorizationManager`, used by Tier-2 and
Tier-3 P1 authz calls).

---

## 11. Failure scenarios summarised

```mermaid
flowchart TB
    A[KC pod down] -.-> A1[New logins fail<br/>existing GWSESSION still valid<br/>until next refresh / validate]
    B[Token Handler all replicas down] -.-> B1[Every /api/* call 502<br/>existing sessions in Redis intact;<br/>resume on TH back]
    C[Redis down] -.-> C1[New logins cannot persist<br/>existing sessions evicted on Redis bounce<br/>full re-auth]
    D[P1 Tomcat down] -.-> D1[Logins via SAML round-trip fail<br/>existing GWSESSION still works until<br/>next P1 authz call]
    E[ingress-nginx down] -.-> E1[Browser can't reach anything in<br/>geowealth-demo namespace]
    F[p1-devcommonagents OOM] -.-> F1[P1 page blank<br/>Tomcat dead-letters IdentifyFirmByUrlMsg<br/>see CLAUDE.md gotcha]
```

The recovery for every one of these is automatic once the failing pod is
back, because the persistent state is in Postgres, Redis, and Oracle —
not in any of the pods that failed.
