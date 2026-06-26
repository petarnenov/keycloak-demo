# 02 — Component Inventory

Every pod that runs in the `geowealth-demo` Kubernetes namespace, what it
does, and what it talks to. The set is split into five tiers; the last one
(platform/system) is the Kubernetes control plane and ingress controller, not
the demo proper, but is listed for completeness so `kubectl get pods -A` is
readable to someone seeing the cluster for the first time.

The verbatim engineering note that backs this section is in
[`k8s/README-pods.md`](../../k8s/README-pods.md) at the repo root.

---

## 1. Data tier — stateful backing stores

These exist because P1, Keycloak, the Token Handler, and the data BFFs each
need durable state or a cache. All five can be redirected to external hosts
via `k8s/env/data-tier.<env>.env` (see
[`05-kubernetes-deployment.md`](05-kubernetes-deployment.md) §3); in-cluster
is the dev default.

| Pod | Kind | Role |
|---|---|---|
| **`oracle-0`** | StatefulSet (1) | Oracle XE PDB baked with the GeoWealth schema + seed (`db/Dockerfile`). Backs all P1 reads and writes: entitlements, accounts, billing nomenclature, instruments. P1 will not boot without it. |
| **`elasticsearch-0`** | StatefulSet (1) | Search index for `SearchManager` / `ClientSearchManager`. P1 boots without it, but client-search UI returns empty results. |
| **`memcached`** | Deployment (1) | Distributed cache for `DistributedCacheController` (prices, policy rules). In prod also fronts Tomcat sessions via MSM; in this single-Tomcat demo it is cache-only. |
| **`kc-postgres-0`** | StatefulSet (1) | Postgres backing Keycloak (realm config, federated identities, sessions). Required for Keycloak to start. |
| **`redis-0`** | StatefulSet (1) | Session store for the Token Handler (`GWSESSION` → access/refresh tokens) **and** the SID registry for back-channel logout. Without it the Token Handler cannot persist a login. |

---

## 2. Identity tier — auth and SSO

This is the brokered-SSO core. Keycloak federates SAML from P1; the Token
Handler is the multi-tenant BFF/Token Handler pattern.

| Pod | Kind | Role |
|---|---|---|
| **`keycloak-0`** | StatefulSet (1) | The IdP-broker. Owns the `demo-realm`, the `p1` SAML IdP, the OIDC `demo-shared-client`, the `p1-first-broker-login` flow (auto-link by email), and OIDC token issuance. Serves JWKS in-cluster at `http://keycloak:8080`. |
| **`kc-ext`** | Deployment (nginx, 1) | TLS-terminating sidecar that gives Keycloak a **browser-facing HTTPS endpoint** at `https://auth.geowealth.int:5180` reachable from inside the cluster. Reason: the issuer Keycloak emits in the `iss` claim is the browser-facing URL — the Token Handler does OIDC discovery against that same URL and needs an in-cluster HTTPS endpoint to do it. `up.sh` patches a hostAlias mapping `auth.geowealth.int` to this pod's ClusterIP. |
| **`token-handler`** | Deployment (HPA 2–12) | The **multi-tenant Token Handler** (extracted from `bff-core`). One instance fronts every domain. Owns `/oauth/*`, `/auth/me`, `/auth/logout`, `/auth/verify` (forward-auth), `/backchannel-logout`, token refresh, Redis sessions, and per-host Tier-2 authorization. Per-domain identity is resolved by `Host` against `app.tenants.*`. |

The Token Handler's `SecurityContext` is tightened: `runAsNonRoot=true`,
`runAsUser=1000`, `readOnlyRootFilesystem=true` with a writable `/tmp`
emptyDir. The image bakes the JVM truststore at build time and trusts an
optional mkcert dev CA mounted at `/certs/mkcert-rootCA.pem`.

---

## 3. Domain tier — per-product applications

Two products today (`billing`, `trading`); each is one SPA pod plus one data
BFF pod. The shape is cookie-cutter — adding a domain is "copy these two
pods, add one `app.tenants.*` entry, register one extra ingress host".

| Pod | Kind | Role |
|---|---|---|
| **`web-billing`** | Deployment | nginx serving the built React SPA (`keycloak-demo-billing-web`). The same nginx runs `auth_request` against the Token Handler's `/auth/verify`, then proxies `/api/billing` to `bff-billing`. Listens on `5184` over TLS. |
| **`web-trading`** | Deployment | Same shape on `5185`, image `keycloak-demo-trading-web`, proxies `/api/trading` to `bff-trading`. |
| **`bff-billing`** | Deployment | Micronaut data BFF (`BillingController` + Tier-3 list `refine`). **Auth-unaware:** reads identity from `X-Auth-*` headers injected by the web pod's forward-auth filter. No session, no token refresh, no `@Secured`. |
| **`bff-trading`** | Deployment | Same shape; `TradingController` only. |

The web vs BFF split is intentional. The web pod is a static-asset server
(nginx) plus a TLS terminator; the BFF is a JVM with domain logic. Lifecycle,
image size, and resource shape differ enough that they make sense as two
deployments.

### Web pod nginx routes (`domains/billing/web/nginx.conf`)

| Path | Handling |
|---|---|
| `/api/billing/` | `auth_request = /th-verify` → on 200, copy `X-Auth-*` headers, strip `Cookie`, proxy to `bff-billing:8080` with rewritten path `/api/` |
| `= /th-verify` | Internal-only; proxies to `token-handler:8080/auth/verify`; forwards original host as `X-Forwarded-Host`; forwards the user's session cookie |
| `/(oauth|auth)/` and `= /logout` | Proxies to `token-handler:8080` directly (lets the Token Handler do code-exchange, `/auth/me`, `/auth/logout`) |
| Anything else | `try_files $uri $uri/ /index.html` — React Router takeover |

### Forward-auth contract (`bff-core/.../ForwardAuthController.java:50`)

```java
@Get("/verify")
@Secured(SecurityRule.IS_AUTHENTICATED)
public HttpResponse<?> verify(Authentication auth, HttpRequest<?> request) {
    String host = request.getHeaders().get("X-Forwarded-Host");
    if (host == null || host.isBlank()) {
        host = request.getHeaders().get(HttpHeaders.HOST);
    }
    try {
        authorizer.authorize(auth, host);
    } catch (HttpStatusException e) {
        return HttpResponse.status(e.getStatus());
    }
    ...
    header(r, "X-Auth-Sub", str(a.get("sub")));
    header(r, "X-Auth-Person-Id", str(a.get("personId")));
    header(r, "X-Auth-Firm-Cd", AuthClaims.firmCd(auth));
    header(r, "X-Auth-Memberships", String.join(",", AuthClaims.memberships(auth)));
    header(r, "X-Auth-Roles", String.join(",", auth.getRoles()));
    header(r, "X-Auth-Access-Token", str(a.get("accessToken")));
    return r;
}
```

The HTTP status codes nginx receives are:

| Status | Cause | What nginx does next |
|---|---|---|
| `200 OK` | Session valid, authz passes | Forward request to data BFF with `X-Auth-*` set, no session cookie |
| `401 Unauthorized` | No session / expired / invalid | Return 401 to the SPA, which triggers `startLogin()` |
| `403 Forbidden` | Session valid but Tier-2 authz denies | Return 403 to the SPA, which surfaces a permission-denied UI |

---

## 4. Legacy P1 tier — GeoWealth Tomcat + Akka agents

P1 is brought into the cluster as a single image (`keycloak-demo-geowealth`)
with role-driven entrypoints (`ROLE`, `AGENT`). The agents are an Akka
cluster; the coordinator is the seed node and every other agent joins it.
Each agent owns a fixed set of "Managers"; **if its agent is down, every
Tomcat action that uses one of those Managers hangs on
`ServiceTimeoutException` and the SPA blanks out.** The split mirrors the
legacy `nfstart` + `commandspecs/*.spec` ops layout.

| Pod | Kind | Role / Managers it provides |
|---|---|---|
| **`p1-coordinator-0`** | StatefulSet | Akka seed node (`akka://DevPetar@p1-coordinator-0.p1-coordinator:4007`). Stable DNS so every other agent + Tomcat can join the cluster. Holds **no** P1 logic. |
| **`p1-samlmanager`** | Deployment | `samlintegrationmanager` — SAML AuthnRequest/Response signing and validation between Keycloak and P1. Required for SAML brokering. Mounts the dev keystore from secret `p1-saml-keystore`. |
| **`p1-reportengine`** | Deployment | Whitelabel report rendering. Needed for the proposal-save flow. |
| **`p1-proposalagents`** | Deployment | Proposal lifecycle (save flow). |
| **`p1-emailagent`** | Deployment | Outbound email send. |
| **`p1-useragents`** | Deployment | `UserManager`, `AccountManager`, `AccountTransactionManager`, `EBrokerManager`, `EBrokerAccountManager`. **`UserManager.loadFirm` is the second-stage Akka call after `IdentifyFirmByUrlMsg`** — without it the firm context post-SAML never resolves and the page hangs. |
| **`p1-mostagents`** | Deployment | `mostagents` watchdog role plus `InstrumentManager`. The watchdog backs the "Currently Offline" indicator. |
| **`p1-searchagents`** | Deployment | `SearchManager`, `ClientSearchManager`. Powers client search. |
| **`p1-cspagents`** | Deployment | `InstrumentPerformanceManager`. |
| **`p1-devcommonagents`** | Deployment, 5 Gi limit, 4 G heap | `AuthorizationManager`, `AuthenticationManager`, `GeowealthPolicyRuleManager`, `entitypropertymanager`, `policyrules`, `DistributedCacheControllerManager`, `cacheagents`, `custodianagents`, `billingagents`, `portalagents`, `PortalManager`, `PortletManager`, `BillingManager`, `BillingSpecificationManager`. **Heaviest agent** — `CrntCostBasisLoader` pre-loads the entire `COST_BASIS_ACCOUNT_TBL` at boot. If this OOMs, `localhost:8080` renders blank because `AuthorizationManager` lives here and `IdentifyFirmByUrlMsg` from Tomcat dead-letters. |
| **`p1-tomcat-0`** | StatefulSet (1, sticky session) | The webapp itself — `react/indexReact.do`, every `*.do` action, every `/saml/idp/*` endpoint. Single replica because `HttpSession` is in-memory; prod uses MSM/memcached for session replication. Reads URLs from `p1-urls` ConfigMap (generated from `geowealth/k8s/env/urls.<env>.env`). |

### Pod-level config rendering (`/home/petar/nodejs/geowealth/k8s/entrypoint.sh`)

Every P1 pod uses the same `entrypoint.sh` to render config templates from
`k8s-config/*.tpl` using `envsubst` (replaces `${VAR}` with env values). Key
substitutions:

| Env var | Used in template | Default |
|---|---|---|
| `ORACLE_HOST` `ORACLE_PORT` `ORACLE_PDB` `ORACLE_USER` `ORACLE_PASSWORD` | `hibernate.properties.tpl` | `oracle:1521/FREEPDB1`, `gp/gp123` |
| `ES_HOST` `ES_PORT` | `akka.conf.tpl` (Elasticsearch) | `elasticsearch:9200` |
| `MEMCACHED_HOST` | (memcached client config) | `memcached:11211` |
| `AKKA_SEED` | `akka.conf.tpl` (cluster join) | `akka://DevPetar@p1-coordinator-0.p1-coordinator:4007` |
| `AKKA_CANONICAL` | `akka.conf.tpl` (artery.canonical.hostname) | `${POD_IP}` |

The Tomcat startup sets the watchdog name explicitly:

```bash
export CATALINA_OPTS="${CATALINA_OPTS:-} ${JAVA_OPTS_EXTRA:-} ${COMMON_JVM[*]} -Dcom.netfolio.appname=web-petar"
```

`appname=web-petar` is deliberate — using `web` would load the production
watchdog list and the "Currently Offline" indicator on the SPA would flag
healthy services as down.

---

## 5. Platform / system tier — control plane and ingress

These pods are not part of the demo. They belong to the minikube control
plane and to the `ingress-nginx` add-on. None of the four tiers above can
function without them; they are listed so the full `kubectl get pods -A`
output is readable.

### `kube-system`

| Pod | Kind | Role |
|---|---|---|
| `kube-apiserver-<node>` | static pod | The Kubernetes API. Every reconcile loop and `kubectl` call goes through it. |
| `etcd-<node>` | static pod | Backing KV store for the API server. |
| `kube-controller-manager-<node>` | static pod | Built-in control loops (Deployment → ReplicaSet → Pod, StatefulSet ordering, GC). |
| `kube-scheduler-<node>` | static pod | Picks a node for each Pod. |
| `kube-proxy-<id>` | DaemonSet | Programs iptables / IPVS rules that make `ClusterIP` Services route to pod IPs. |
| `coredns-<…>` | Deployment | Cluster DNS. Resolves `oracle`, `redis`, `keycloak`, `p1-tomcat`, and (after the `up.sh` hostAlias patch) `auth.geowealth.int`. |
| `storage-provisioner` | Deployment (minikube add-on) | hostPath PV provisioner. Replaced by a real CSI driver in production (EBS, etc). |

### `ingress-nginx`

| Pod | Kind | Role |
|---|---|---|
| `ingress-nginx-controller-<…>` | Deployment | nginx reverse-proxy that turns `Ingress` resources into nginx server blocks; terminates TLS for every host; implements the `nginx.ingress.kubernetes.io/auth-url` forward-auth annotation. |
| `ingress-nginx-admission-create-<…>` | Job (Completed) | Ran once at install — generated a self-signed cert for the validating admission webhook. |
| `ingress-nginx-admission-patch-<…>` | Job (Completed) | Ran once at install — patched the `ValidatingWebhookConfiguration` with the CA bundle. |

If `ingress-nginx-controller` is down, no browser request reaches anything in
`geowealth-demo`. In-cluster pod-to-pod traffic is unaffected (Services and
`kube-proxy` do not depend on Ingress).

---

## 6. Why this shape

Decisions an architect is likely to challenge, and the answer:

- **The Token Handler is one Deployment, not one per domain.** The whole
  point of the multi-tenant refactor is that adding a domain is one
  tenants-map entry, not a new pod. A shared-auth fix restarting it does
  not log users out, because sessions are in Redis.
- **Domain BFFs are forward-auth / auth-unaware.** They run zero security
  filters, so a `bff-core` auth fix means rebuilding only the Token Handler;
  domain BFFs stay running unchanged.
- **`kc-ext` exists purely so the OIDC `iss` claim works in-cluster.** It is
  a 16 Mi nginx pod; remove it and OIDC discovery from the Token Handler
  fails because the issuer URL is HTTPS on a hostname with no in-cluster
  HTTPS listener otherwise.
- **`p1-coordinator` is dedicated** rather than co-located with an agent
  because every Akka member needs a stable seed address; making any
  non-StatefulSet pod the seed risks pod-IP churn breaking cluster
  formation.
- **The 9-agent fan-out is not K8s ceremony** — it mirrors the existing
  `nfstart` + `commandspecs/*.spec` layout, so each agent is a separate JVM
  with its own heap and Manager set and can be scaled or restarted
  independently.
