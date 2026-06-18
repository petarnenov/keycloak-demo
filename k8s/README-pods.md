# K8s pod inventory — what runs and why

The full-stack overlay (`k8s/overlays/full-stack/kustomization.yaml`) brings up
**four logical tiers** in one namespace `geowealth-demo`. Below: each pod, what
it does, and why it can't be removed.

---

## 1. Data tier — stateful backing stores

These exist because P1, Keycloak, the token-handler, and the BFFs each need
persistence/cache. All four can be redirected to external hosts via
`k8s/env/data-tier.<env>.env` (see CLAUDE.md "Data-tier endpoint config");
in-cluster is just the dev default.

| Pod | Kind | Role |
|---|---|---|
| **`oracle-0`** | StatefulSet (1 replica) | Oracle XE PDB baked with the GeoWealth schema + seed (`db/Dockerfile`, image `keycloak-demo-db:seeded`). Backs **all** P1 reads/writes — entitlements, accounts, billing nomenclature (V22 seed), instruments. Without it P1 won't boot. |
| **`elasticsearch-0`** | StatefulSet (1 replica) | Search index P1 hits via `SearchManager` / `ClientSearchManager`. Needed for client search UI and watchdog status; P1 boot won't fail without it, but client-facing search returns empty. |
| **`memcached`** | Deployment (1 replica) | Distributed cache for P1's `DistributedCacheController` (instrument prices, policy rules). In prod also fronts Tomcat HttpSession via MSM; here single-Tomcat keeps sessions in-memory so memcached is cache-only. |
| **`kc-postgres-0`** | StatefulSet (1 replica) | Postgres backing **Keycloak** (realm config, federated identities, sessions). Required for KC to start. |
| **`redis-0`** | StatefulSet (1 replica) | Session store for the **token-handler** (`GWSESSION` → access/refresh tokens) AND the SID registry for back-channel logout. Without it the token-handler can't persist a login. |

---

## 2. Identity tier — auth & SSO

Sits in front of the domain apps. This is the brokered-SSO core: Keycloak
federates SAML from P1; the token-handler is the multi-tenant BFF/Token Handler
pattern.

| Pod | Kind | Role |
|---|---|---|
| **`keycloak-0`** | StatefulSet (1 replica) | The IdP-broker. Owns the `demo-realm`, the `p1` SAML IdP, the OIDC `demo-shared-client`, first-broker-login auto-link, OIDC token issuance. Serves JWKS in-cluster at `http://keycloak:8080`. |
| **`kc-ext`** | Deployment (nginx, 1 replica) | TLS-terminating sidecar that gives Keycloak a **browser-facing** HTTPS endpoint at `https://auth.geowealth.int:5180` from inside the cluster. Reason: the issuer KC emits in the `iss` claim is the browser-facing URL — the token-handler does OIDC discovery against that same URL and needs an HTTPS endpoint inside the cluster to do it. `up.sh` patches a hostAlias so `auth.geowealth.int → kc-ext.ClusterIP`. |
| **`token-handler`** | Deployment (1–3 replicas, HPA) | The **multi-tenant Token Handler** (extracted `bff-core`). One instance fronts every domain. Owns: `/oauth/*`, `/auth/me`, `/auth/logout`, `/auth/verify` (nginx forward-auth), back-channel logout, token refresh, Redis sessions, per-host Tier-2 authorization. Per-domain identity is resolved by `Host` against `app.tenants.*` (billing/trading). |

---

## 3. Domain tier — per-product apps

Two products today (`billing`, `trading`); each has a web SPA pod and a data BFF
pod. Cookie-cutter shape — adding a domain is "copy these two pods + one tenants
entry."

| Pod | Kind | Role |
|---|---|---|
| **`web-billing`** | Deployment | nginx serving the built React SPA (`keycloak-demo-billing-web`). Its nginx runs `auth_request` against the token-handler's `/auth/verify`, then proxies `/api/billing` to `bff-billing`. Listens on `5184` over TLS (mkcert). |
| **`web-trading`** | Deployment | Same shape on `5185`, image `keycloak-demo-trading-web`, proxies `/api/trading` to `bff-trading`. |
| **`bff-billing`** | Deployment | Micronaut **data** BFF — `BillingController` + Tier-3 list `refine`. **Auth-unaware**: reads identity from `X-Auth-*` headers injected by web's forward-auth filter. No session, no token refresh, no `@Secured`. |
| **`bff-trading`** | Deployment | Same shape — `TradingController` only. |

Why split web from BFF? Web is a dumb cert-terminator + static-asset server
(nginx, tiny). BFF is a JVM with domain logic. Lifecycle, image size, and
resource shape are completely different.

---

### A note on what may NOT be running

`oracle-0` is absent from a fresh `kubectl get pods -n geowealth-demo` when
`k8s/env/data-tier.<env>.env` points `ORACLE_HOST` at an external IP (e.g.
`192.168.1.42`). In that case `up.sh` scales the StatefulSet to 0 replicas and
re-applies the `oracle` Service as `type: ExternalName`. The Service name still
exists in cluster DNS — only the pod is gone. Same idea for `elasticsearch-0` /
`memcached` when their `_HOST` is redirected.

---

## 4. Legacy P1 tier — the GeoWealth Tomcat app + Akka agents

This is the existing GeoWealth product, brought into the cluster from one image
(`keycloak-demo-geowealth`) selected by `ROLE` / `AGENT` env. The agents are an
Akka cluster; the coordinator is the seed node and every other agent joins it.
**Each agent owns a fixed set of "Managers"; if its agent is down, every Tomcat
action that talks to one of those Managers hangs on `ServiceTimeoutException`
and the SPA blanks out.**

| Pod | Kind | Role / Managers it provides |
|---|---|---|
| **`p1-coordinator-0`** | StatefulSet | Akka SEED node (`akka://DevPetar@p1-coordinator-0.p1-coordinator:4007`). Stable DNS so every other agent + Tomcat can join the cluster. **No P1 logic itself** — its only job is cluster membership. |
| **`p1-samlmanager`** | Deployment | `samlintegrationmanager` — handles SAML AuthnRequest/Response signing & validation between Keycloak and P1. **Required for SAML brokering.** Mounts the dev keystore from secret `p1-saml-keystore`. |
| **`p1-reportengine`** | Deployment | Whitelabel report rendering. Needed for the proposal Save flow. |
| **`p1-proposalagents`** | Deployment | Proposal lifecycle (Save flow). |
| **`p1-emailagent`** | Deployment | Email send. |
| **`p1-useragents`** | Deployment | `UserManager`, `AccountManager`, `AccountTransactionManager`, `EBrokerManager`, `EBrokerAccountManager`. **`UserManager.loadFirm` is the second-stage Akka call after `IdentifyFirmByUrlMsg`** — without this the firm context post-SAML never resolves and the page hangs. |
| **`p1-mostagents`** | Deployment | `mostagents` watchdog role + `InstrumentManager`. The watchdog is what the "Currently Offline" indicator queries — if its `appname` doesn't match the watchdog list, the UI shows offline. |
| **`p1-searchagents`** | Deployment | `SearchManager`, `ClientSearchManager`. Powers client search. |
| **`p1-cspagents`** | Deployment | `InstrumentPerformanceManager`. |
| **`p1-devcommonagents`** | Deployment, **5 Gi limit, 4 G heap** | `AuthorizationManager`, `AuthenticationManager`, `GeowealthPolicyRuleManager`, `entitypropertymanager`, `policyrules`, `DistributedCacheControllerManager`, `cacheagents`, `custodianagents`, `billingagents`, `portalagents`, `PortalManager`, `PortletManager`, `BillingManager`, `BillingSpecificationManager`. **Heaviest agent** — `CrntCostBasisLoader` pre-loads the whole `CostBasisAccount` table at boot, so heap is sized for a real PDB. If this OOMs, **`localhost:8080` renders blank** because `AuthorizationManager` lives here and `IdentifyFirmByUrlMsg` from Tomcat dead-letters. |
| **`p1-tomcat-0`** | StatefulSet (1 replica, sticky session) | The webapp itself — `react/indexReact.do`, all `*.do` actions, `/saml/idp/*`. Single replica because HttpSession is in-memory; prod uses MSM/memcached for session replication. Reads all URLs from `p1-urls` ConfigMap (generated from `geowealth/k8s/env/urls.<env>.env`). |

The split into nine agent pods isn't K8s ceremony — it mirrors `nfstart` +
`commandspecs/*.spec` from the legacy ops layout. Each agent is a separate JVM
with its own heap and Manager set; you can scale or restart them independently
without taking down Tomcat or the rest of the cluster.

---

## 5. Platform / system tier — cluster control plane & ingress

These pods are NOT part of the demo — they belong to the minikube control plane
and the `ingress-nginx` add-on. None of the four tiers above can function
without them. Listed for completeness so the full `kubectl get pods -A` output
makes sense.

### `kube-system` — Kubernetes control plane (single-node minikube)

| Pod | Kind | Role |
|---|---|---|
| **`kube-apiserver-geowealth`** | Static pod | The Kubernetes API server. EVERY `kubectl` call, every controller reconcile loop, every kubelet status update goes through it. If it dies, the cluster keeps running but no changes can be made. |
| **`etcd-geowealth`** | Static pod | The KV store backing the API server (all manifests, Secrets, ConfigMaps, leases, lock state). The single source of truth for cluster state. |
| **`kube-controller-manager-geowealth`** | Static pod | Runs the built-in control loops — Deployment → ReplicaSet → Pod reconciliation, StatefulSet ordering, Job/CronJob, endpoints, ServiceAccount tokens, garbage collection. Restart the workload manifests with this down and nothing actually rolls. |
| **`kube-scheduler-geowealth`** | Static pod | Picks a node for every new Pod. Trivial here (single node) but still required — without it new pods stay `Pending`. |
| **`kube-proxy-vfdp5`** | DaemonSet | Runs on every node and programs the iptables (or IPVS) rules that make `ClusterIP` Services actually route to pod IPs. Every `bff-billing` → `redis:6379` lookup goes through rules this pod installed. |
| **`coredns-…`** | Deployment | Cluster DNS. Resolves all the in-cluster names this demo relies on (`oracle`, `redis`, `keycloak`, `p1-tomcat`, `auth.geowealth.int` via the `up.sh`-patched hostAlias). If CoreDNS is down, JDBC URLs, OIDC discovery, and Akka cluster joining all fail with `UnknownHostException`. |
| **`storage-provisioner`** | Deployment (minikube add-on) | Watches PVCs and provisions hostPath PVs on the minikube VM's local disk. Backs the `oracle`, `elasticsearch`, `kc-postgres`, `redis`, `p1-coordinator`, `p1-tomcat` StatefulSet volumes. Prod uses a real CSI driver (EBS, etc.) and would not run this. |

The four `*-geowealth` pods (apiserver, etcd, controller-manager, scheduler) are
**static pods** managed directly by the kubelet from manifests in
`/etc/kubernetes/manifests/`, not by the scheduler. Their name suffix matches
the node hostname, which is why they read `…-geowealth` here.

### `ingress-nginx` — the Ingress controller

| Pod | Kind | Role |
|---|---|---|
| **`ingress-nginx-controller-…`** | Deployment | The nginx reverse proxy that resolves every `Ingress` resource (`base/ingress.yaml`, `base/ingress-app.yaml`) into real nginx server blocks. Terminates TLS for `billing.geowealth.int`, `trading.geowealth.int`, `auth.geowealth.int`, and the P1 hosts; routes by host header into the in-cluster `web-*` / `kc-ext` / `p1-tomcat` Services. Also implements the **`nginx.ingress.kubernetes.io/auth-url`** forward-auth annotation that points `/api/<name>` at `token-handler:/auth/verify` before letting traffic reach `bff-<name>`. |
| **`ingress-nginx-admission-create-…`** | Job (Completed) | Ran ONCE at install time. Generated a self-signed cert for the controller's validating admission webhook and stored it in a Secret. After completion the pod stays around with `STATUS=Completed`; this is normal, not a stuck pod. |
| **`ingress-nginx-admission-patch-…`** | Job (Completed) | Ran ONCE at install time. Patched the `ValidatingWebhookConfiguration` with the CA bundle from the Secret above so the API server trusts the webhook. Same Completed-Job pattern — leave it alone. |

If `ingress-nginx-controller` is down, the browser can't reach anything in
`geowealth-demo` (every hostname routes through it). Internal pod-to-pod
traffic is unaffected — Services + kube-proxy don't depend on the Ingress.

---

## Networking glue (Ingress resources)

- **`ingress-app.yaml`** — Ingress routing for `billing.geowealth.int`,
  `trading.geowealth.int` (→ web pods), `auth.geowealth.int` (→ `kc-ext` →
  keycloak), and P1 hosts (→ `p1-tomcat`).
- **`ingress.yaml`** — older per-domain Ingress definitions (billing-api,
  trading-api, billing-auth, trading-auth) using the nginx external-auth
  annotation pattern.

These are read by the `ingress-nginx-controller` pod above; the YAML files
themselves don't run anything.

---

## Why this shape (not "could we collapse it?")

- **`token-handler` is one Deployment, not per-domain.** Multi-tenancy by `Host`
  header is the whole point of the refactor — adding a domain is one
  tenants-map entry, not a new pod. Restarting it for a shared-auth fix doesn't
  log users out (sessions in Redis).
- **Domain BFFs are forward-auth / auth-unaware.** They run no security
  filters, so a `bff-core` auth fix → rebuild **token-handler only**, data BFFs
  untouched.
- **`kc-ext` exists purely so `iss` works in-cluster.** It's a 16 Mi nginx pod,
  not a "real" service; remove it and OIDC discovery from the token-handler
  fails because the issuer URL is HTTPS on a hostname that has no in-cluster
  HTTPS listener otherwise.
- **`p1-coordinator` is dedicated** rather than colocated with an agent because
  every Akka member needs a stable seed address; making any non-StatefulSet pod
  the seed risks pod-IP churn breaking cluster formation.
- **The agent fan-out is non-optional for the demo.** Removing `devcommonagents`
  blanks the home page; removing `useragents` hangs the post-SAML firm resolve;
  removing `samlmanager` breaks login signing.
