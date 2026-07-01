# Dev: controlling which K8s parts run

How to pick **which agents / domains / BFFs / tiers** run in a local
`geowealth-demo` (minikube) cluster, instead of always bringing up the whole
`full-stack` overlay.

Grounded in `docs/solution-architect/v2`:
[`02-component-inventory`](../docs/solution-architect/v2/02-component-inventory.md),
[`05-kubernetes-deployment`](../docs/solution-architect/v2/05-kubernetes-deployment.md),
[`12-dev-environment-plan`](../docs/solution-architect/v2/12-dev-environment-plan.md),
[`13-dev-environment-setup`](../docs/solution-architect/v2/13-dev-environment-setup.md).

> **Scope note.** This is about the **run-set** (what schedules at all). It is
> *complementary* to the hot-reload loop in doc 12/13, where `dev-fe.sh` /
> `dev-be.sh` scale **the one component you're editing** to 0 and run it on the
> host. That's per-component dev; this is "how little can I schedule and still do X".

---

## 1. Where things run (per doc 12 §2)

| Tier | Dev default | Source |
|---|---|---|
| Data (Oracle, Redis, ES, kc-postgres) | in-cluster, kept stable | doc 12 §2 |
| Identity broker (Keycloak + kc-ext) | in-cluster | doc 12 §2 |
| Legacy P1 (`p1-coordinator`, `p1-*agents`, `p1-tomcat`) | in-cluster | doc 12 §2 |
| The component under edit (FE/BE) | on host, its Deployment scaled to 0 | doc 12 §2, `dev-common.sh:scale_deployment` |

`up.sh` always applies `overlays/full-stack` — the whole system (doc 13 §6).
There is **no** built-in "run only X" profile today; the levers below add that.

### Workload inventory (`app.kubernetes.io/component`)

| Tier label | Workloads | Notes |
|---|---|---|
| `data` | `oracle`, `elasticsearch`, `kc-postgres`, `redis` (`session-store`) | Everything sits on these |
| `identity` / `auth` | `keycloak`, `user-service`, `token-handler` | The Identity Service |
| `web` | `web-billing`, `web-trading` | Domain SPAs (nginx) |
| domain BFF | `bff-billing`, `bff-trading` | Domain data APIs (forward-auth) |
| `legacy` (P1) | `p1-coordinator` (StatefulSet) + `p1-tomcat` + 8 agents: `devcommonagents`, `useragents`, `samlmanager`, `crm`, `searchagents`, `mostagents`, `cspagents`, `proposalagents`, `emailagent`, `reportengine` | P1 monolith (doc 05 §1) |

---

## 2. Where P1 sits now — authn vs authz (the correction)

**P1 is NOT an identity provider.** Per doc 05: the realm has **no**
`identityProviders` (§2.2 "does NOT touch any identityProviders block now — there
are none"); identity comes from the **`user-service` User-Storage SPI**, which
reads **Oracle directly** (`user-service.yaml`, doc 05 §3). **P1 is now an OIDC
*client* (RP)** of Keycloak (doc 05 §4.2, §5), and its SAML audit path is gone,
replaced by `OIDC_LOGIN_OK` / `OIDC_LOGOUT_OK` (doc 05 §10).
`login-logout-algorithm.md` and `IdpHintFilter` (default `kc_idp_hint=p1`) still
describe the retired SAML broker — **stale**; with empty `identityProviders`,
`kc_idp_hint=p1` is a no-op.

So two independent concerns, different run-sets:

- **Authenticate / log in-out of the domains** → **zero P1.** Needs only
  `keycloak` + `kc-postgres` + `user-service` + `oracle` (user-service's DB) +
  `token-handler` + `redis` + the domain `web`/`bff`. Scale the **entire `legacy`
  tier to 0** and login still works.
- **Authorize domain `/api`** (Tier-2/3 gate) → token-handler's
  `SubdomainAuthorizer` calls P1 at `P1_AUTHZ_URL = http://p1-tomcat:8080`
  (doc 05 §2). billing/trading are `type: resource`, so **loading their data**
  needs `p1-tomcat` + `p1-coordinator` + `p1-devcommonagents`
  (`AuthorizationManager`). Login works without them; the data call is what 403s.
- **The legacy P1 app UI** (`p1.geowealth.int` / :8080) → P1 itself is an OIDC RP
  now; needs its own agents (below).

### P1 agent → function map (only for P1-itself or domain /api)

| Agent | Drop it and you lose |
|---|---|
| `p1-coordinator` | Akka seed — no agent can cluster without it |
| `p1-tomcat` | P1 web app **and** the `P1_AUTHZ_URL` endpoint the /api gate calls; HPA min1/max5 (doc 05 §9) |
| `p1-devcommonagents` | `AuthorizationManager` → /api Tier-2 gate + P1 login blank page; heavy `-Xmx4G` (doc 05 §3) |
| `p1-useragents` | User management (Edit User, lookups) |
| `p1-samlmanager` | **Vestigial for the demo** — inbound firm-SAML; broker retired |
| `p1-crm` | `clientDirectory.do` / CRM pages |
| `p1-searchagents` | Search pages |
| `p1-mostagents` | "Currently Offline" watchdog / most workloads (doc 13 §14) |
| `p1-cspagents` | Client Service Portal |
| `p1-proposalagents` | Proposal generation |
| `p1-emailagent` | P1-side email (P1's own forgot-pw/MFA mail — **not** KC login) |
| `p1-reportengine` | BIRT reports |

**Minimal run-sets**
- **Log into the Identity Service (domains):** `data` + `identity` + one
  `web`/`bff`. **All P1 at 0.**
- **…and load protected /api data:** add `p1-tomcat` + `p1-coordinator` +
  `p1-devcommonagents`.
- **Use the legacy P1 UI:** `p1-coordinator` + `p1-tomcat` + `devcommonagents` +
  `useragents` + the feature agents the page needs.

---

## 3. Levers

### ⚠ HPA gotcha (read first)

`token-handler`, `user-service`, and `p1-tomcat` each have a
**HorizontalPodAutoscaler** (doc 05 §9). A plain
`kubectl scale deploy/<x> --replicas=0` on any of them is **reverted within
~15 s** by the HPA (`minReplicas ≥ 1`). To actually stop an HPA-managed
workload you must first neutralise the HPA:

```bash
NS=geowealth-demo
kubectl -n $NS patch hpa p1-tomcat --type merge -p '{"spec":{"minReplicas":0}}'
kubectl -n $NS scale deploy/p1-tomcat --replicas=0
# (or: kubectl -n $NS delete hpa p1-tomcat  — up.sh re-creates it on next apply)
```

Non-HPA workloads (the 8 P1 agents, `web-*`, `bff-*`, `p1-coordinator`) scale
cleanly with `kubectl scale`. `dev-common.sh:scale_deployment` (used by the dev
scripts) is Deployment-only and does **not** touch HPAs — fine for the agents,
insufficient for `p1-tomcat`.

### Option A — Label scale (now, zero repo changes)

```bash
NS=geowealth-demo
# stop one agent
kubectl -n $NS scale deploy/p1-emailagent --replicas=0
# stop the 8 P1 agents + coordinator (NOT p1-tomcat — HPA; handle per above)
kubectl -n $NS scale deploy,statefulset -l app.kubernetes.io/component=legacy --replicas=0
# stop the trading domain
kubectl -n $NS scale deploy/web-trading deploy/bff-trading --replicas=0
```

- **Pro:** instant, no rebuild, frees laptop RAM (agents are heap-heavy).
- **Con:** a later `./k8s/up.sh` re-applies `full-stack` and **resurrects**
  everything (declarative `replicas`/HPA). Session-local only.

### Option B — `k8s/toggle.sh` helper (recommended small add)

Named groups over Option A + the HPA dance:

```bash
./k8s/toggle.sh p1 down          # 8 agents + coordinator + (hpa-safe) p1-tomcat
./k8s/toggle.sh trading up        # web-trading + bff-trading
./k8s/toggle.sh agent:crm up      # one agent
./k8s/toggle.sh authz-min up      # p1-tomcat + coordinator + devcommonagents (/api gate)
```

Group→selector map + HPA-minReplicas handling live in the script; pure
`kubectl scale`/`patch hpa` underneath. Same resurrect-on-reapply caveat as A.

### Option C — Profiles that survive re-apply

Make the desired set an **input to `up.sh`** so it never resurrects the excluded
parts. Two implementations:

- **C1 — post-apply reconcile (~15 lines in `up.sh`):** add `PROFILE=` /
  `DISABLE=`. After the kustomize apply, `up.sh` patches HPA minReplicas + scales
  the disabled groups to 0 and skips their `rollout status` waves (doc 13 §6 step
  6). Idempotent per profile.
  ```bash
  PROFILE=login  ./k8s/up.sh     # data + identity + one domain, all P1 at 0
  PROFILE=domain ./k8s/up.sh     # login + authz-min P1
  DISABLE=p1-crm,p1-reportengine,trading ./k8s/up.sh
  ```
- **C2 — kustomize components (declarative, bigger refactor):** split
  `base/p1.yaml` (12 workloads in one file, doc 05 §1) into per-agent files and
  turn each tier into a reusable
  [component](https://kubectl.docs.kubernetes.io/references/kustomize/kustomization/component/);
  a dev overlay then *composes* only chosen components. `overlays/minikube-auth-tier`
  already proves the cherry-pick (redis + token-handler-config + token-handler
  only) — it is the real "auth-tier only" subset. (Note: `overlays/dev` includes
  the **whole** `../../base` at `replicas:1`, despite doc 05 §1's "auth-tier only"
  one-liner — the doc comment is imprecise; the file is the source of truth.)

### Suggested profiles to ship

`full` (default) · `login` (data+identity+one domain, **P1 at 0**) ·
`domain` (login + authz-min P1) · `p1-app` (legacy P1 UI set) ·
`no-agents` (all but the optional P1 agents).

---

## 4. Watch-outs

- **Login/logout needs zero P1** (doc 05 §2/§10). Run the whole `legacy` tier at
  0 and still authenticate into the domains. P1 re-enters only for the domain
  `/api` authz gate (`P1_AUTHZ_URL`) and the legacy P1 UI.
- **HPA reverts `scale 0`** on `token-handler` / `user-service` / `p1-tomcat` —
  patch `minReplicas` first (§3).
- **Don't scale `data` down** casually — KC needs `kc-postgres`, sessions need
  `redis`, `user-service`/BFF authz need `oracle`. "Fewer parts" means dropping
  the P1 tier and/or one domain, not data.
- **`p1-coordinator` + `p1-devcommonagents` are load-bearing for authorization**
  and the P1 UI, not for domain authentication. Dropping `devcommonagents` →
  blank `localhost:8080` (doc 13 §7.3, §14).
- **No `k8s/down.sh`** despite doc 12 §6 / 13 §6 referencing it. Tear down with
  `kubectl delete ns geowealth-demo` (or `minikube -p geowealth delete`).
  Selective stop = the scale levers above, not a teardown.
- Biggest laptop-RAM win: drop the whole `legacy` tier when you only need the
  auth/SSO flow (`p1-devcommonagents` alone is `-Xmx4G` / `5Gi` limit).
