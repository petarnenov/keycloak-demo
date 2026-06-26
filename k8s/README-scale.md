# Horizontal scale — what it takes

What stands between the current `keycloak-demo` deployment and a horizontally
scaled production stack. Tier-by-tier audit of every workload, what blocks it
from running more than one replica, and a sequenced plan that delivers throughput
without breaking session continuity or the Akka cluster.

The demo intentionally runs one replica of nearly everything because the goal is
to demonstrate the SSO flow end-to-end, not capacity. This document is the bridge
to "production" — read alongside [README-pods.md](README-pods.md) (what each
pod does) and [README-ubuntu-bringup.md](README-ubuntu-bringup.md) (how to
bring the stack up).

## TL;DR

- **Edge tier (web + bff)** is stateless — already scalable, only HPAs missing.
- **token-handler** is already scaled (replicas=2, HPA 1-3, Redis sessions, PDB).
  Just needs `resources.requests` so the HPA can compute % targets.
- **p1-tomcat** is stateless: `HttpSession` lives in Redis (Redisson Tomcat
  session manager) and the `kc_sub → sessionId` index used by OIDC
  back-channel logout is also in Redis. Scale to N replicas behind a
  non-sticky LB — `kubectl scale deploy p1-tomcat --replicas=N` is enough.
- **P1 Akka agents** are role-singleton by trait design — every one is
  `replicas: 1`. Scaling needs either Akka Cluster Singleton + standby
  (HA only) or Cluster Sharding refactor of selected traits (real scale).
- **Keycloak** can scale with Infinispan distributed cache (`KC_CACHE_STACK=kubernetes`).
- **Data tier** clustering is engine-level work, mostly out of scope for the
  cluster manifest.

Sticky-session affinity is already wired at the ingress
(`nginx.ingress.kubernetes.io/affinity: cookie`, cookie name `P1AFFINITY` —
`k8s/base/ingress-app.yaml:66-67`). It's a no-op while p1-tomcat is one pod,
but it's ready the moment the tomcat goes multi-replica.

## Current state by workload

| Tier | Workload | Kind | replicas | HPA | State held | Scalable today? |
|---|---|---|---:|---|---|---|
| Edge | `web-billing` | Deployment | 1 | — | static React + nginx forward | **yes**, trivially |
| Edge | `web-trading` | Deployment | 1 | — | same | **yes** |
| Auth | `token-handler` | Deployment | 2 | 1-3 (cpu 70%) | Redis-backed sessions | **already** |
| Auth | `kc-ext` | Deployment | 1 | — | stateless TLS proxy | **yes** |
| Identity | `keycloak` | Deployment | 1 | — | Infinispan caches in JVM + Postgres | conditional |
| Data BFF | `bff-billing` | Deployment | 2 | — | stateless forward-auth (`X-Auth-*`) | **already** (HPA missing) |
| Data BFF | `bff-trading` | Deployment | 2 | — | same | **already** (HPA missing) |
| App | `p1-tomcat` | Deployment | 2 | HPA 2-5 | Redis-backed `HttpSession` + `kc_sub` index | **yes** |
| App | `p1-coordinator` | StatefulSet | 1 | — | Akka seed (singleton actor system) | **no** |
| App | `p1-{cspagents,devcommonagents,emailagent,mostagents,proposalagents,reportengine,samlmanager,searchagents,useragents,crm}` | Deployment | 1 each | — | Akka role-singleton, in-process trait | **no** |
| Data | `oracle` | StatefulSet | 1 | — | RWO PVC, JDBC | **no** (engine-level — RAC) |
| Data | `elasticsearch` | StatefulSet | 1 | — | RWO PVC | conditional (native ES cluster) |
| Data | `redis` | StatefulSet | 1 | — | RWO PVC | conditional (Sentinel/Cluster) |
| Data | `kc-postgres` | StatefulSet | 1 | — | RWO PVC | conditional (Patroni / read-replica) |

PDBs: only `token-handler` has one (`k8s/base/token-handler.yaml:141`). Every
multi-replica workload should pick one up before going live, so a node drain
doesn't take all replicas down at once.

## Tier 1 — easy wins (manifest tweaks, no code, hours of work)

Zero refactor risk, ~3× edge throughput uplift.

1. **`web-billing` / `web-trading` → replicas: 3 + HPA**.
   - Stateless nginx serving the React build. No session, no shared memory.
   - Replicas can grow freely behind the ingress.

2. **`bff-billing` / `bff-trading` → HPA min=2 max=10**.
   - Already at replicas=2. Forward-auth: identity travels in headers from
     `token-handler`'s `/auth/verify` decision, so any replica can serve any
     request.

3. **`kc-ext` → replicas: 2 + PDB**.
   - Stateless nginx forwarding to Keycloak inside the cluster.

5. **PDBs everywhere**.
   - `minAvailable: 1` (or `maxUnavailable: 1`) on each scaled workload.

6. **`token-handler` resources.requests**.
   - The HPA targets cpu 70% but no `requests.cpu` is set, so the controller
     can't compute a utilisation percentage. Add `requests: { cpu: 250m, memory: 512Mi }`
     to `k8s/base/token-handler.yaml` and the HPA starts behaving.

## Tier 2 — medium effort (days)

### 7. p1-tomcat horizontal scaling — done

**State today:** `HttpSession` is externalised to Redis via the Redisson
Tomcat 9 session manager (`org.redisson.tomcat.RedissonSessionManager`,
see `WebContent/META-INF/context.xml`, `keyPrefix=p1-tomcat`,
`broadcastSessionEvents=true`). The OIDC `kc_sub → sessionId` index used
by back-channel logout is also Redis-backed (`P1RedisKcSubIndex`,
key `p1-tomcat:kc_sub:<sub>` → `RSet<sessionId>`), so KC fanning the
logout POST to any p1-tomcat replica still invalidates the user's
session across all of them. Net effect: p1-tomcat is stateless behind a
non-sticky LB.

**Verify:** `HttpSession`-stored objects (`LoggedUserJTO`, `User`,
`PermissionsMap`, etc.) must remain `Serializable` — Redisson serialises
them via the configured codec, and a missing-serialisation regression
shows up as "login, then immediately logged out".

Scale with `kubectl -n geowealth-demo scale deploy/p1-tomcat
--replicas=N` (HPA already 2-5 on cpu 70%). Migrating away from
StatefulSet preserves zero of the previous sticky-cookie ceremony.

### 8. Keycloak Infinispan distributed cache

KC keeps realm/user/session caches in JVM via Infinispan. The default cache
stack is local. Multiple replicas without distributed caching = a logout on
pod A doesn't invalidate the cached session on pod B → "logged out / still
logged in" race.

- Set `KC_CACHE_STACK=kubernetes` (uses JGroups KUBE_PING for peer discovery).
- A headless `Service` already exists (`keycloak` ClusterIP); add a sibling
  headless Service for JGroups, or use the existing one with `clusterIP: None`.
- Set `KC_CACHE_CONFIG_FILE` to a Infinispan XML using `DISTRIBUTED-SYNC` for
  `sessions`, `clientSessions`, `authenticationSessions`, `actionTokens`.
- Postgres remains the source of truth, so the realm config / federated
  identity records survive replica scaling unchanged.
- `KC_HOSTNAME=https://auth.geowealth.int:5180` stays fixed at the
  ingress-fronted FQDN — never tied to a single pod.

### 9. PDBs for everything scaled

Add `minAvailable: 1` per workload (web, bff, token-handler, keycloak,
kc-ext, ingress-controller if you run one in-cluster).

## Tier 3 — deep refactors (weeks)

### 10. P1 Akka agents — singleton-per-role problem

**Why every agent is `replicas: 1` today:** each pod runs `AgentSupervisor`
which `Put(actorRef)`-s `/user/<alias>` (e.g. `/user/CRM`,
`/user/UserManager`, `/user/CRM`, `/user/AuthorizationManager`) on the
DistributedPubSubMediator. With two replicas of the same agent the cluster
sees two registrations for the same path; `Send(/user/X, msg)` randomly picks
one, and any in-process state (caches like `CrntCostBasisLoader`,
`AuthorizationManager`'s firm registry) diverges between the two.
This is the exact split observed when `p1-coordinator` accidentally dual-boots
as Tomcat (`ROLE` env defaults to tomcat in the entrypoint) — two `web-petar`
members register on the same topic, message routing becomes nondeterministic.

**Options:**

- **A) Akka Cluster Singleton + Singleton Proxy** — recommended first step.
  Wraps each existing trait as a `ClusterSingletonManager` so exactly **one**
  instance runs cluster-wide regardless of how many pods host the role.
  `replicas: 2` then buys HA (warm standby takes over on pod death) but not
  throughput. Per-trait changes are mechanical — typically a constructor
  wrap + a proxy lookup at the call sites that today do
  `Send(/user/Alias, msg)`.

- **B) Akka Cluster Sharding** — real horizontal scale.
  Rewrite the chosen trait as a sharded entity keyed by some natural identifier
  (`firmCd`, `userId`, `clientUUID`). The sharding coordinator distributes
  entities across cluster members; `replicas: N` then partitions the work.
  Hard cases: `UserManager` holds a cluster-wide cache (every user × firm)
  that doesn't shard cleanly — those traits stay singleton even after
  sharding the rest. Sharding also forces the messages to carry their
  routing key (most do — `GetUserMsg(userId)` etc., but a few don't).

- **C) Per-trait capacity isolation** (not really scaling).
  Move "hot" traits to dedicated pods sized larger; leave "cold" ones on the
  shared `devcommonagents` host. Useful as an interim — e.g.
  `searchagents` already gets `-Xmx4G` for ES query handling.

**Recommended path:** A first on every trait (HA win + zero behavioural risk
once the singleton proxy lookup is in place), then B on the few traits where
demand actually requires it (typical candidates: `SearchManager` /
`ReportManager`, both query-heavy).

**Test focus for any of A/B:** message ordering, cache invalidation across
pod restarts, the `Mailer.sendAndWait` blocking-ask timeout windows
(currently 30s / 60s — under sharding the rebalance pause can exceed that).

### 11. p1-coordinator HA — multi-seed

Today `seed-nodes` = `["akka://DevPetar@p1-coordinator-0.p1-coordinator:4007"]`.
Single point of failure for any node that joins AFTER cluster formation
(formed nodes survive, but a coordinator restart blocks late joiners).

- `replicas: 2` on the `p1-coordinator` StatefulSet; the StatefulSet
  already gives both pods stable DNS (`p1-coordinator-0`, `p1-coordinator-1`).
- Update the `AKKA_SEED` env (set everywhere via `k8s/base/p1.yaml`) to
  carry both: a comma-separated list, parsed by Akka into `seed-nodes` —
  `akka://DevPetar@p1-coordinator-0.p1-coordinator:4007,akka://DevPetar@p1-coordinator-1.p1-coordinator:4007`.
- Stop the coordinator from accidentally running Tomcat: explicitly set
  `ROLE: agent` + a dedicated `AGENT: coordinator` (with its own
  `etc/coordinator.xml`, currently unused) on the StatefulSet. Today the
  entrypoint defaults to `ROLE=tomcat` when `ROLE` is unset, so the
  coordinator pod silently runs the full webapp alongside `p1-tomcat-0` and
  registers conflicting cluster roles.

### 12. Data-tier clustering

- **Oracle**: prod = RAC, outside this manifest. The env-driven
  `data-tier.<env>.env` already supports retargeting at an external host —
  no code change needed when prod moves off the in-cluster StatefulSet.
- **Elasticsearch**: bump replicas to 3 (`statefulset/elasticsearch`),
  configure `discovery.seed_hosts: elasticsearch-0.elasticsearch,…` +
  `cluster.initial_master_nodes`. ES handles the rest. Each pod keeps its
  own RWO PVC.
- **Redis**: HA via Sentinel (3 sentinel pods + 1 primary + N replicas) or
  Redis Cluster (sharded). Token-handler's Lettuce client must be
  reconfigured to know the sentinel/cluster endpoints. For session-store
  duty Sentinel is enough.
- **kc-postgres**: read-replica + automatic failover via Patroni or a
  managed Postgres operator. KC only writes to the primary; replica
  helps with reads but the demo doesn't lean on those.

## Tier 4 — observability prerequisites (do before scaling)

### 13. Resource requests/limits review

- `token-handler` HPA target = `cpu: 70%` of `requests.cpu`. Without
  `requests`, the HPA reports `<unknown>` and never scales (visible today —
  `kubectl get hpa` shows `cpu: <unknown>/70%`).
- Agent heap (already bumped on `devcommonagents` and `searchagents` to
  `-Xmx4G`) needs re-validation once load is real. Match container
  `limits.memory` to heap + 1Gi for JVM overhead.

### 14. Metrics

- HPA on raw cpu only catches throughput-bound work. Login flow is mostly
  I/O-bound — CPU-targeted HPA will under-scale.
- Add custom metrics adapter (Prometheus-Adapter) and target HPAs on:
  - `token-handler`: req/s, `/auth/verify` latency p99.
  - `p1-tomcat`: thread-pool utilisation, request queue depth.
  - Akka agents: mailbox size (Akka exposes via JMX → JMX exporter).
- Expose JMX on every JVM in the stack via the Bitnami JMX exporter or
  similar sidecar.

### 15. Distributed tracing

A login spans web → KC → token-handler → BFF → P1 → agents → data tier. Once
every layer is multi-replica, debugging a hang without trace context is
impractical. Wire OpenTelemetry (or B3) headers through:
- `token-handler` (Micronaut + OpenTelemetry instrumentation).
- BFFs (same).
- P1 (manual MDC propagation through Akka messages — the message envelope
  needs to carry the trace context).
- KC supports OTel via env (`KC_TRACING_ENABLED=true`).

### 16. Load-test baseline

Before scaling out, baseline single-replica throughput per workload (k6 /
Gatling, against `localhost:8080` for P1 and `https://billing.geowealth.int`
for the SPA path). Without a baseline, HPA min/max numbers are guesses and
scale events fire on the wrong dimension.

## Recommended sequence

1. **week 1** — Tier 1: web/bff/kc-ext replicas + HPA + PDB. Fix
   `token-handler` `resources.requests`. Zero risk, immediate edge uplift.
2. **week 1-2** — Tier 4 #13 + #16: metrics-server-only baseline; record
   single-pod p95/p99 per route.
3. **week 2-3** — Tier 2 #7: p1-tomcat already on Redisson session manager
   + Redis-backed `kc_sub` index (done) — just bump replicas/HPA as load
   demands.
4. **week 3-4** — Tier 2 #8: Keycloak Infinispan distributed-cache.
5. **week 4** — Tier 3 #11: multi-seed coordinator + explicit
   `ROLE=agent` on the coordinator pod (also closes the dual-boot Tomcat bug).
6. **week 4-6** — Tier 3 #10A: Cluster Singleton wrap of every trait. Frees
   `replicas: 2` for HA on all P1 agents.
7. **week 6+** — Tier 3 #10B: Cluster Sharding refactor of selected hot
   traits (likely `SearchManager`, `ReportManager`). Independent stream:
   Tier 3 #12 ES cluster mode + Redis Sentinel.
8. **parallel stream** — Tier 4 #14, #15: Prometheus custom metrics +
   OpenTelemetry across the stack. Without these any post-scale incident
   is blind.

## Quick wins available today

Pure manifest changes, no code:

- `web-billing` / `web-trading` → replicas 3 + HPA.
- `bff-billing` / `bff-trading` → HPA min=2 max=10.
- `kc-ext` → replicas 2 + PDB.
- `token-handler` → add `resources.requests` so the existing HPA works.
- PDB on every scaled workload.

~3× edge throughput at zero refactor cost. The whole P1 application tier
remains the bottleneck — that's a deliberate Tier 3 problem to schedule, not
a same-week fix.

## Out of scope here

- Multi-region failover.
- Active-active across AZs.
- Cost optimisation (right-sizing `requests`/`limits`).
- Service mesh (Istio / Linkerd) — often a prerequisite for distributed
  tracing in mixed JVM/Node stacks, but bringing it in is a separate
  initiative.

## Related docs

- [README-pods.md](README-pods.md) — what every current pod does.
- [README-ubuntu-bringup.md](README-ubuntu-bringup.md) — bringing the
  stack up from zero.
- [README-multitenant-k8s-plan.md](README-multitenant-k8s-plan.md) — how
  the multi-tenant `token-handler` is shaped (already scaled).
- Root [CLAUDE.md](../CLAUDE.md) — env-driven data-tier configuration that
  underpins Tier 3 #12.
