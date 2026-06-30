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
    p1user["P1 user (browser)"]
    sso[["GeoWealth SSO Demo Platform<br/>(this system)"]]
    p1["P1<br/>(OIDC RP + product UI)"]
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
| P1 user (browser) | Same browser; lands directly on the P1 UI and authenticates through OIDC (no separate SSO entry needed — the sidebar deep-link from v1 still exists but is now just an OIDC redirect, no `kc_idp_hint`) |
| Realm administrator | Operates Keycloak through its admin UI or the admin API; touched by `scripts/reconcile-realm.sh` during deploy |

External systems:

| System | Boundary it crosses |
|---|---|
| Platform 1 (OIDC RP + product) | Same organisation but a separate runtime; consumes KC issued tokens |
| Org TLS / DNS | Owns the wildcard cert for `*.geowealth.int` in production and the DNS records the demo hosts hang off |
| Org Oracle / Elasticsearch | The real customer-data store. Both `user-service` and `p1-tomcat` read from Oracle; the demo points its in-cluster names at it via `data-tier.<env>.env` |

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
        kc["keycloak-0<br/>(custom image)"]
        us["user-service<br/>(2 replicas)"]
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

    subgraph P1Tier["Platform 1 (OIDC RP + Tomcat product)"]
        p1tc["p1-tomcat<br/>(HPA 1-5)"]
        p1co["p1-coordinator-0"]
        p1ag["p1-* agent pods (8)"]
        ora[("oracle-0")]
        es[("elasticsearch-0")]
    end

    subgraph MonTier["monitoring namespace"]
        prom["kps-prometheus"]
        graf["kps-grafana"]
        am["kps-alertmanager"]
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

    th --"OIDC + JWKS + end-session"--> kc
    p1tc --"OIDC + JWKS + end-session"--> kc
    th --"Tier-2 authz"--> p1tc
    bffB --"Tier-3 list refine"--> p1tc
    bffT --"Tier-3 list refine"--> p1tc
    th --"GWSESSION + SID index"--> redis
    p1tc --"HttpSession (Redisson) + kc_sub index"--> redis

    kc --"User Storage SPI<br/>+ Email-OTP authenticator"--> us
    kc --"back-channel logout"--> th
    kc --"back-channel logout"--> p1tc
    us --"JDBC"--> ora
    kc --"realm + sessions"--> kcpg

    p1tc --Akka--> p1co
    p1co -.- p1ag
    p1tc --> ora
    p1tc --> es

    prom -.scrape.- th
    prom -.scrape.- us
    prom -.scrape.- redis
    am -.alerts.- prom
    graf -.queries.- prom
```

---

## 3. Cold authentication — sequence (canonical)

```mermaid
sequenceDiagram
    autonumber
    actor B as Browser
    participant W as Web (nginx)
    participant T as Token Handler
    participant K as Keycloak
    participant SPI as User Storage SPI
    participant US as user-service
    participant O as Oracle
    participant R as Redis

    B->>W: GET /auth/me (no GWSESSION)
    W->>T: GET /auth/me
    T-->>B: 401
    B->>W: GET /oauth/login/silent
    W->>T: /oauth/login/silent
    T-->>B: 302 KC authorize (no kc_idp_hint)
    B->>K: GET /protocol/openid-connect/auth
    K-->>B: login page (geowealth theme)
    B->>K: POST username+password
    K->>SPI: getUserByUsername
    SPI->>US: GET /users/search?username=...
    US->>O: SELECT * FROM ENTITY_TBL
    O-->>US: row
    US-->>SPI: User JSON
    SPI-->>K: UserModel
    K->>SPI: isValid(input)
    SPI->>US: POST /users/{id}/verify-credentials
    US->>O: SELECT LDAP_PSWD_HASH
    O-->>US: hash
    US-->>SPI: { valid: true }
    SPI-->>K: true
    K-->>B: 302 redirect_uri ?code
    B->>W: GET /?code
    W->>T: /?code
    T->>K: POST /token
    K-->>T: tokens
    T->>R: persist session + SID
    T-->>B: 302 /, Set-Cookie: GWSESSION
```

---

## 4. Forward-auth — request-level interaction (unchanged from v1)

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
    D->>D: gate.refine(authentication, invoices, ...) (Tier-3)
    D-->>W: 200 + filtered invoices JSON
    W-->>B: 200 + filtered invoices JSON
```

Note the difference between Tier-2 and Tier-3 is unchanged from v1:

| Tier | Where it runs | Subject | Cost |
|---|---|---|---|
| Tier-2 | Token Handler `/auth/verify` | "Can this user reach `/api/billing` on this host?" | One P1 call per cache-miss (60 s TTL) |
| Tier-3 | Data BFF row-list endpoint | "Which IDs in this list may this user see?" | One P1 call per list response |

---

## 5. P1 OIDC RP — login sequence

```mermaid
sequenceDiagram
    autonumber
    actor B as Browser
    participant P1 as P1 Tomcat
    participant SS as OidcStateStore (Redis)
    participant K as Keycloak
    participant SPI as User Storage SPI
    participant US as user-service
    participant R as Redis (Tomcat session + kc_sub)

    B->>P1: GET /oidc/login.do
    P1->>P1: generate state + PKCE verifier
    P1->>SS: put(state, verifier, originBase, return_to)
    P1-->>B: 302 KC authorize<br/>client_id=p1-client, code_challenge=S256
    B->>K: GET .../auth
    K-->>B: login page (geowealth theme)
    B->>K: POST username+password
    K->>SPI: getUserByUsername / isValid
    SPI->>US: HTTP
    US-->>SPI: ok
    SPI-->>K: ok
    K-->>B: 302 /oidc/callback.do?code&state
    B->>P1: GET /oidc/callback.do
    P1->>SS: consume(state)
    P1->>K: POST /token (code + verifier + client secret)
    K-->>P1: id_token + access_token + refresh_token
    P1->>P1: JwtVerifier.validate(id_token)
    P1->>R: populate HttpSession (Redisson)<br/>p1-tomcat:redisson:tomcat_session:<id>
    P1->>R: P1RedisKcSubIndex.add(sub, sessionId)<br/>p1-tomcat:kc_sub:<sub>
    P1-->>B: 302 /
```

---

## 6. RP-initiated logout — sequence

```mermaid
sequenceDiagram
    autonumber
    actor B as Browser
    participant W as Web
    participant T as Token Handler
    participant K as Keycloak
    participant P1 as P1 OidcLogoutAction
    participant TR as Token Handler back-channel
    participant R as Redis

    B->>W: POST /auth/logout (form)
    W->>T: POST /auth/logout
    par fire-and-forget
        T->>K: POST /protocol/openid-connect/logout
        K->>K: end SSO session
        par fan-out
            K->>TR: POST /backchannel-logout (aud=demo-shared-client)
            TR->>R: invalidate by SID
            K->>P1: POST /oidc/back-channel-logout.do (aud=p1-client)
            P1->>R: invalidate by kc_sub
        end
    end
    T->>R: del session, drop SID
    T-->>B: 303 /oidc/logout.do
    B->>P1: GET /oidc/logout.do
    P1->>P1: invalidate HttpSession (Redisson DEL)
    P1-->>B: 302 KC end-session (id_token_hint)
    B->>K: GET .../logout
    K-->>B: 302 post_logout_redirect_uri
```

---

## 7. Back-channel logout — sequence (KC → both clients in parallel)

```mermaid
sequenceDiagram
    autonumber
    participant K as Keycloak
    participant T as Token Handler<br/>(BackchannelLogoutController)
    participant P1 as P1 Tomcat<br/>(OidcBackChannelLogoutAction)
    participant V1 as LogoutTokenValidator
    participant V2 as JwtVerifier
    participant R as Redis

    par to demo-shared-client
        K->>T: POST /backchannel-logout<br/>logout_token (aud=demo-shared-client)
        T->>V1: validate(logout_token)
        V1-->>T: { sid }
        T->>R: invalidateBySid(sid)
        T-->>K: 200
    and to p1-client
        K->>P1: POST /oidc/back-channel-logout.do<br/>logout_token (aud=p1-client)
        P1->>V2: validate(logout_token)
        V2-->>P1: { sub }
        P1->>R: P1RedisKcSubIndex.invalidate(sub)<br/>DEL each p1-tomcat:redisson:tomcat_session:<id>
        P1-->>K: 204
    end
```

---

## 8. Token refresh — sequence (unchanged from v1)

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
should remember — it is the upper bound on how long an out-of-band
admin-side KC logout takes to surface as a 401 to the SPA.

---

## 9. Sidebar deep-link (warm browser)

```mermaid
sequenceDiagram
    autonumber
    actor B as P1 user (browser)
    participant P1 as P1 SPA
    participant KC as Keycloak
    participant Pcb as P1 OidcCallbackAction
    participant W as web-billing

    Note right of P1: useIntegrationLinks → render sidebar link<br/>= https://billing.geowealth.int:5184/<br/>(plain deep-link, the SPA's<br/>own AuthProvider drives the login)
    B->>W: GET https://billing.geowealth.int:5184/
    W-->>B: SPA bundle
    B->>W: GET /auth/me
    W-->>B: 401
    Note over B: SPA: startLogin() → /oauth/login/silent
    B->>KC: GET .../auth?prompt=none<br/>(KC SSO from previous P1 login is alive)
    KC-->>B: 302 redirect_uri ?code
    B->>W: GET /?code → standard code exchange
```

Compared to v1, the sidebar link no longer carries `kc_idp_hint=p1` —
there is no IdP to hint at. The link is now a plain deep-link to the
billing host; the SPA itself drives the login. If KC's SSO session is
already alive on `auth.geowealth.int` (because the user just logged into
P1), the silent-SSO probe succeeds and no UI flashes.

---

## 10. K8s ingress + auth-request (unchanged from v1)

```mermaid
sequenceDiagram
    autonumber
    actor B as Browser
    participant I as ingress-nginx-controller
    participant T as token-handler Service
    participant W as web-billing Service
    participant D as bff-billing Service

    B->>I: HTTPS https://billing.geowealth.int/api/billing/invoices
    I->>T: subrequest http://token-handler:8080/auth/verify<br/>(forwards X-Forwarded-Host, GWSESSION)
    T-->>I: 200 + X-Auth-*
    I->>D: http://bff-billing:8080/api/invoices<br/>(strips Cookie, copies X-Auth-*)
    D-->>I: 200 JSON
    I-->>B: 200 JSON
```

---

## 11. P1 Akka cluster topology

The Akka cluster diagram is conceptual; runtime membership depends on
which agents are scheduled. **Memcached is retired** — the agent count
dropped from v1's nine to eight.

```mermaid
flowchart LR
    p1tc["p1-tomcat<br/>(HPA 1-5 cluster client)"]
    seed["p1-coordinator-0<br/>(seed)"]
    a2["p1-useragents"]
    a3["p1-devcommonagents"]
    a4["p1-searchagents"]
    a5["p1-mostagents"]
    a6["p1-cspagents"]
    a7["p1-emailagent"]
    a8["p1-proposalagents"]
    a9["p1-reportengine"]

    p1tc -.- seed
    seed -.- a2
    seed -.- a3
    seed -.- a4
    seed -.- a5
    seed -.- a6
    seed -.- a7
    seed -.- a8
    seed -.- a9
    p1tc -- ask Manager --> a2
    p1tc -- ask Manager --> a3
```

The load-bearing agent for SSO is now only `p1-devcommonagents` (still
hosts `AuthorizationManager` for the Tier-2 / Tier-3 P1 authz calls). The
v1 `p1-samlmanager` is no longer in the auth path — it may be retained
for unrelated SAML helpers used elsewhere, or removed in a follow-up.

---

## 12. Failure scenarios summarised

```mermaid
flowchart TB
    A[KC pod down] -.-> A1[New logins fail<br/>existing GWSESSION still valid<br/>until next refresh / validate]
    B[Token Handler all replicas down] -.-> B1[Every /api/* call 502<br/>existing sessions in Redis intact;<br/>resume on TH back]
    C[Redis down] -.-> C1[New logins cannot persist<br/>P1 sessions also broken<br/>full re-auth across the platform]
    D[P1 Tomcat any replica down] -.-> D1[Logins continue (other replicas)<br/>sessions in Redis survive<br/>HPA replaces the lost replica]
    E[user-service down] -.-> E1[Cold logins fail at SPI lookup<br/>existing sessions keep working<br/>token refresh keeps working]
    F[Oracle down] -.-> F1[user-service health 503<br/>P1 unable to load firm context<br/>Tier-2 / Tier-3 P1 authz fails]
    G[ingress-nginx down] -.-> G1[Browser can't reach anything in<br/>geowealth-demo namespace]
    H[p1-devcommonagents OOM] -.-> H1[Tier-2 / Tier-3 P1 authz fails<br/>see CLAUDE.md gotcha]
```

Recovery for every one of these is automatic once the failing pod is
back, because the persistent state is in Postgres, Redis, and Oracle —
not in any of the pods that failed. The new failure mode versus v1 is
**`user-service` down**, which is a fail-closed for **new** logins (and
admin lookups in the KC UI) but **not** for active sessions — KC's SPI
cache (`EVICT_DAILY`) keeps user metadata for the lifetime of the SSO
session, and credential validation is only re-run on a fresh login.
