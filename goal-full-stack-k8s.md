# Goal: one-command, production-ready Kubernetes stack for the ENTIRE system

> **How to run:** `/goal goal-full-stack-k8s.md` (use the top model). This file is
> the directive. Implement **and** verify; don't stop until the success criteria
> below hold.

## 0. Goal & success criteria

Stand up the **whole** system on Kubernetes — legacy P1 (geowealth Tomcat + Akka
agents), Keycloak, the demo domains (billing/trading web + BFFs + the multi-tenant
Token Handler), and **the database in-container** (Oracle, seeded from `db/` +
Flyway migrations) — plus Elasticsearch, memcached and Redis — and bring it all up
with **one command**.

Done when:
1. **One command** (`k8s/up.sh`, or `make k8s-up`) from a clean machine brings up
   the entire stack — DB seeded, P1 + agents running, KC realm imported, domains
   live — with deterministic ordering/waits, no manual steps.
2. **End-to-end login works in-cluster**: the P1→KC(SAML broker)→BFF flow on
   `billing.geowealth.int` / `trading.geowealth.int`, driven by the existing
   Playwright e2e suite pointed at the in-cluster ingress, is green (same pass bar
   as compose: all auth/api/claims/logout specs pass; the firm-5 `johnastim5` flake
   may remain).
3. **DB is in-cluster and seeded** from `db/migration` (V1 baseline → V2..V18 seeds)
   + the gitignored login hash, verified by the `db/stack` `make verify` checks
   (login lookup, policy-gated visibility, household→accounts, positions→instruments).
4. **Production-shaped**: probes, resources, HPA/PDB where applicable, Secrets (not
   plaintext), versioned images, NetworkPolicies, and a documented teardown.

This extends the Goal-5 work (`k8s/README-multitenant-k8s-plan.md`, branch
`petarnenov/token-handler-multitenant-k8s`): the auth tier + data BFFs + Redis +
Ingress already exist; this goal adds **Keycloak+Postgres, the web SPAs, the Oracle
DB + seed pipeline, Elasticsearch, memcached, and the entire P1 legacy tier**, then
the one-command orchestration over all of it.

## 1. Target architecture (every workload)

Namespace `geowealth`. **Nothing external** — the DB is in-cluster (the prior
"Oracle stays external" decision is reversed by this goal).

| Tier | Workload | Kind | Image | Stateful |
|---|---|---|---|---|
| data | `oracle` | StatefulSet | `gvenzl/oracle-free:23-slim` | PVC |
| data | `oracle-seed` | Job | `db-seed` (new, built here) | — |
| data | `elasticsearch` | StatefulSet | `elasticsearch:7.17.28` | PVC |
| data | `memcached` | Deployment | `memcached:1.6-alpine` | — |
| data | `redis` | StatefulSet (have) | `redis:7-alpine` | PVC |
| data | `kc-postgres` | StatefulSet | `postgres:16` | PVC |
| identity | `keycloak` | StatefulSet | `quay.io/keycloak/keycloak:26.0.7` | realm import |
| identity | `token-handler` (have) | Deployment + HPA | `keycloak-demo-token-handler` | — |
| identity | `bff-billing`/`bff-trading` (have) | Deployment | domain BFF images | — |
| web | `web-billing`/`web-trading` | Deployment | `keycloak-demo-*-web` | — |
| legacy | `p1-tomcat` | StatefulSet | `geowealth` (new, built here) | session |
| legacy | `p1-coordinator` | StatefulSet | `geowealth` (MAIN=agentsystem) | Akka seed |
| legacy | `p1-samlmanager` | Deployment | `geowealth` | — |
| legacy | `p1-reportengine`/`p1-proposalagents`/`p1-emailagent` | Deployment | `geowealth` | — |

**One image, many entrypoints** for the legacy tier: build the geowealth codebase
once (`gradle devClasses` → `devBuild/classes` + `devBuild/lib` + `etc` + `patches`
baked into a Tomcat image), then Tomcat and every Akka agent run **the same image**
with different command/`MAIN_CLASS`/args — exactly mirroring the `nfstart` +
`commandspecs/*.spec` model (no WAR task exists; the dev model rsyncs exploded
classes, so the image bakes the exploded classes the same way).

## 2. Implementation plan

### 2.1 Data tier

**Oracle** (`k8s/base/oracle.yaml`): StatefulSet `gvenzl/oracle-free:23-slim`, PVC,
`ORACLE_PASSWORD`/`APP_USER=GP`/`APP_USER_PASSWORD=gp123` from a Secret, headless
Service `oracle`, `startupProbe` using `healthcheck.sh` with a generous window
(Oracle init ~1–2 min). Resources: request ~2Gi/1cpu (Oracle Free is heavy).

**`db-seed` image + Job** — the crux. **V1 is 5.9 MB / 121K lines → it CANNOT be a
ConfigMap (1 MB limit).** Build a small `db-seed` image (`db/Dockerfile`, base
`flyway/flyway:10-alpine` + the Oracle instant-client/sqlplus, or a thin image with
both) that **bakes `db/migration/`, `db/local/`, and a `provision.sh`**. The Job
runs the exact `db/stack` `make provision` sequence, translated:
1. `prepare-oracle` — connect as SYSDBA, set `MAX_STRING_SIZE=EXTENDED` (the
   pluggable-DB close/open-UPGRADE + `utl32k.sql` dance), grant `DBMS_CRYPTO` to GP.
2. `load-baseline` — load `V1__baseline_schema.sql` via **sqlplus** (Flyway OSS
   can't parse the baseline PL/SQL).
3. `record-baseline` — `flyway baseline` to record V1.
4. `migrate` — `flyway migrate` runs V2..V18.
5. `local-hash` — apply `R__local_login_hash.sql` from a **Secret** (it is a
   credential; gitignored today). The realm/login email must match (`tim1@…`).
6. `verify` — the `make verify` smoke queries; fail the Job on mismatch.
The Job is **idempotent** (Flyway skips applied versions) and gated `depends`-style
on Oracle health (initContainer that waits for `oracle:1521`). Mark seeded with a
sentinel so re-runs are cheap.

**Elasticsearch** (`k8s/base/elasticsearch.yaml`): StatefulSet `7.17.28` (match
geowealth's client jars), single-node, `xpack.security.enabled=false`, PVC, ulimits
via securityContext/initContainer (`vm.max_map_count`), readiness on
`_cluster/health`. **Plus an `es-reindex` Job** running `RefreshClientSearcherTool`
(list views are ES-backed; login/detail/policy SQL work without it, but directories
need the index). ECK operator is the more "prod" option — note it as an alternative;
the self-contained one-command path uses the plain StatefulSet.

**memcached** (`k8s/base/memcached.yaml`): Deployment + Service `memcached:11211`
(hibernate L2 target). **Redis** (have) — also serves P1's Redisson
(`redis://…:6379`); one Redis can back both (note the dual use). **kc-postgres**:
StatefulSet `postgres:16` + Service + Secret for KC's DB.

### 2.2 Identity tier

**Keycloak** (`k8s/base/keycloak.yaml`): StatefulSet `26.0.7`, env `KC_DB=postgres`
+ `KC_DB_URL/USERNAME/PASSWORD` (Secret), `KC_HEALTH_ENABLED=true`,
`KC_HTTP_ENABLED=true`, **`KC_HOSTNAME=https://auth.geowealth.int`** (issuer
consistency — see Risks), `--import-realm` with `keycloak/realm-export.json` mounted
as a ConfigMap (32 KB, fits) at `/opt/keycloak/data/import/`. Service + Ingress
`auth.geowealth.int`. startupProbe (KC boot is slow).

**Token Handler / BFFs** (have): update env so `KEYCLOAK_ISSUER` =
`https://auth.geowealth.int/realms/demo-realm` (browser-facing) and
`KEYCLOAK_AUTH_SERVER_URL` = `http://keycloak:8080/realms/demo-realm` (in-cluster
JWKS). `P1_AUTHZ_URL` → the in-cluster `p1-tomcat` service. `demo-shared-client`
already seeds via the realm import.

**Web SPAs** (`k8s/base/web-billing.yaml` / `web-trading.yaml`): Deployment of the
existing `keycloak-demo-*-web` images (nginx serves SPA + already proxies to
`token-handler`/`bff-<name>` by service name — works in-cluster as-is). Mount the
mkcert certs (`proxy/certs/*.{crt,key}`) as Secrets; Service; Ingress `/` per host
with `backend-protocol: HTTPS` (the images listen on 5184/5185 TLS). Decide:
**(a)** reuse the SSL images as-is (web pod does its own `auth_request` — fastest),
or **(b)** the external-auth Ingress design already in `k8s/base/ingress.yaml`.
Pick (a) for the one-command path; keep (b) documented.

### 2.3 Legacy P1 tier (the hard part)

**`geowealth` image** (`<geowealth>/Dockerfile` — needs a geowealth sub-branch):
multi-stage — builder runs `gradle devClasses` (heavy: Oracle JDBC, BIRT), final
stage is `tomcat:9-jre17` with `devBuild/classes` + `devBuild/lib` + `etc` +
`patches` baked into the webapp, classpath per `common_vars_petar`. The SAME image
serves Tomcat **and** every agent (`MAIN_CLASS=atomatron.worker.agentsystem.Main`,
args from each `.spec`).

**Config templating** — the dev config is **hardcoded** and must become env/Service
DNS-driven (don't commit the existing `etc/*` dev edits — see §4):
- `etc/hibernate-petar.properties`: Oracle `//localhost:1521/FREEPDB1` → `//oracle:1521/FREEPDB1`.
- `etc/dev-petar-akka.conf`: ES `dev-elastic.geowealth.com:9200` → `elasticsearch:9200`;
  Akka seed `akka://DevPetar@127.0.0.1:4007` → `akka://DevPetar@p1-coordinator-0.p1-coordinator:4007`.
- `etc/redisson.yaml`: `redis://127.0.0.1:6379` → `redis://redis:6379`.
- memcached host → `memcached:11211`.
Deliver these as a ConfigMap/Secret-mounted overlay (templated at deploy, values
from the in-cluster Service names).

**Akka cluster on K8s**: `p1-coordinator` as a StatefulSet (stable DNS
`p1-coordinator-0.p1-coordinator:4007` = the seed node) + headless Service; agents
join via that seed. The **industry-standard** upgrade is Akka Cluster Bootstrap +
`akka-discovery-kubernetes-api` (no fixed seed) — note it; the minimal path uses the
stable-DNS seed node. Akka remoting/management ports stay in-cluster (NetworkPolicy).

**Agents to run**: minimal for the e2e = `p1-coordinator` (seed) +
`p1-samlmanager` (`samlintegrationmanager`, login). For whitelabel Save add
`p1-reportengine` + `p1-proposalagents` + `p1-emailagent`. The full ~22-agent set
(`conf/dev/petar_server.yml`) is staged/optional. `cacheagents` needs real memory
limits (OOMs at 1 GB; spec uses `-Xmx8G`).

**SAML keystore** (`Secret`, not `/tmp`): mount `p1-idp-dev.p12` at a stable path
(`/etc/p1/idp.p12`), set `P1_IDP_KEYSTORE_PATH`. The realm's `p1` IdP
`signingCertificate` **must match** this keystore's public cert (and
`P1_IDP_KC_SP_CERT` must be KC's broker SP cert) — seed both consistently. The
`scripts/sso-dev-keystore.sh` rotation story becomes a Secret update + realm patch.

**HttpSession** (P1 holds `LoggedUser`/`firmInfo` in-memory): single replica +
ingress **sticky sessions** (`JSESSIONID` affinity) for the demo; the prod path is
**memcached-session-manager (MSM)** to externalize sessions (memcached is already in
the stack) — document MSM as the scale step.

**P1 → host SAML**: P1 is now in-cluster, but the **browser** drives the SAML
redirect to P1 — so P1 must be reachable by the browser at its ingress host, and KC
(in-cluster) reaches P1 via the `p1-tomcat` Service. Add a `p1.geowealth.int`
Ingress and keep the realm `p1` IdP endpoints consistent with it.

### 2.4 One-command entrypoint

`k8s/up.sh` (+ `make k8s-up`) — declarative apply with **dependency-ordered waits**
(Oracle 1–2 min, seed Job, KC realm import, Akka cluster form, Tomcat slow start):
1. Ensure cluster: `minikube start` (sized for Oracle+ES+P1 — ~8 GB/4 cpu) +
   `addons enable ingress` (+ optional metrics-server for HPA).
2. Build + load every image into the cluster (token-handler, 2 BFFs, 2 web,
   `db-seed`, `geowealth`).
3. Apply in waves with gates:
   `oracle` → wait healthy → `oracle-seed` Job → wait Complete →
   `elasticsearch`+`memcached`+`redis`+`kc-postgres` → wait Ready →
   `keycloak` (realm import) → wait `/health` →
   `p1-coordinator` → wait Akka seed up → `p1-samlmanager`(+whitelabel agents) →
   `p1-tomcat` → wait Ready → `es-reindex` Job →
   `token-handler`+BFFs+web → wait Ready → final health gate.
4. Print the access URLs + `/etc/hosts` line (`minikube ip` → all hosts).
Implement waves as kustomize apply + `kubectl rollout status`/`kubectl wait`, OR as
an **umbrella Helm chart with hook weights / ArgoCD sync-waves** (the more GitOps
form — note as the production alternative). Teardown: `k8s/down.sh` (`minikube
delete` or `kubectl delete ns geowealth`).

## 3. Config & secrets externalization

Every credential → `Secret` (Oracle GP pw, KC DB pw, KC admin, OIDC client secrets,
ES password — **note: `prod-config/etc/elasticsearch_prod.properties` has a
committed ES password; do NOT reuse it; this stack uses its own**, the login hash,
the SAML keystore). For real prod: External Secrets Operator / Vault — note it; the
one-command demo uses in-repo Secrets (placeholder) with a clear "rotate in prod"
banner. All host-specific dev config (Oracle/ES/Redis/memcached/Akka) becomes
Service-DNS values supplied via ConfigMap overlay — nothing hardcoded in the image.

## 4. Sub-branches & commit hygiene

- **keycloak-demo**: make a sub-branch off the current
  `petarnenov/token-handler-multitenant-k8s` (e.g. `petarnenov/full-stack-k8s`).
- **geowealth**: the P1 image + config templating need files IN the geowealth repo
  (a `Dockerfile`, an entrypoint, a templated config overlay). Make a sub-branch off
  geowealth's current `team/petarnenov/keycloak-persons-registry`.
  **CONSTRAINT: only commit the files you create/change. The pre-existing `etc/*`
  dev edits (`dev-petar-akka.conf`, `hibernate*.properties`, `*.bak`) are unrelated
  prior work and MUST NOT be committed** — deliver the K8s config as NEW overlay
  files, not by committing the live dev edits. GitLab requires a `GEO-####` prefix
  on geowealth commits.
- **Commit/push only when the user asks.** `Co-Authored-By: Claude Opus 4.8`.

## 5. Verification plan

**Per-tier gates** (each a hard check, not just "applied"):
- Oracle: container healthy; `oracle-seed` Job `Complete`.
- DB seed: `make verify` queries pass (login lookup for `tim1`, policy-gated
  visibility, household→accounts, positions→instruments) — run as a Job, assert rows.
- ES: cluster `green/yellow`; `es-reindex` Job complete; a directory query returns rows.
- KC: realm imported (`demo-shared-client` + `p1` IdP present via admin API); `/health` UP.
- P1: `p1-tomcat` Ready; **Akka cluster `Up`** with `samlmanager` joined (check
  akka-management `/cluster/members` or the agent log "establish(kc_idp_hint=p1)"-class lines).
- Auth tier: token-handler 1/1 + `/health` UP + tenant ConfigMap mounted +
  `/auth/verify`→401; web/BFF Ready.

**End-to-end (the real proof)**: run the existing Playwright suite (`e2e/`) against
the **in-cluster ingress hosts** (point `e2e/fixtures/config.ts` at the minikube
ingress; `/etc/hosts` → `minikube ip`). Target: same bar as compose — all
auth/api/claims/logout/silent-first/whitelabel specs green; the firm-5 `johnastim5`
flake may remain. Capture evidence (per the standing "verify with browser tools"
rule): the full P1→KC→BFF login, cross-subdomain `personId`, logout fan-out.

**One-command cold-start smoke**: from `minikube delete`, run `k8s/up.sh` → assert
every tier reaches Ready and the e2e is green — proving the single-command goal.

## 6. Risks & genuinely hard parts (address explicitly)

1. **V1 = 5.9 MB** → seed via a **baked `db-seed` image**, never a ConfigMap. (Decided.)
2. **Oracle Free on K8s**: heavy (~2 GB+), slow init; confirm the `gvenzl/oracle-free:23-slim`
   arch matches the cluster nodes (Apple-silicon minikube needs an arm64 image).
3. **Akka cluster bootstrap on K8s** — stable-DNS seed node (minimal) vs
   `akka-discovery-kubernetes-api` (prod). The real risk; budget time here.
4. **P1 HttpSession clustering** — sticky single-replica (demo) vs MSM/memcached (scale).
5. **SAML keystore + cert match** between P1 and the KC realm `p1` IdP — seed both
   consistently, or login fails at the SAML signature step.
6. **ES reindex** required for list/directory views (`RefreshClientSearcherTool`).
7. **geowealth image build is heavy** (`gradle devClasses`, Oracle JDBC, BIRT) —
   long first build; cache the Gradle layer.
8. **KC issuer/hostname consistency** — `iss` must equal the browser authorize URL
   (`KC_HOSTNAME`), JWKS fetched internally — split the two URLs (compose already does).
9. **Resource budget** — Oracle + ES + P1 (multi-GB heaps) + KC + the rest needs a
   sizeable node (≥8 GB / 4 cpu); `up.sh` must size minikube accordingly or fail clear.

## 7. Out of scope (stage later, document)

- Full ~22-agent Akka fleet (start with coordinator + samlmanager + the 3 whitelabel
  agents; the rest are additive Deployments).
- Real GitOps (ArgoCD/Flux) + Helm packaging — note the umbrella-chart/sync-wave form.
- External Secrets/Vault, cert-manager TLS, NetworkPolicies hardening beyond the
  baseline, multi-node HA for Oracle/ES.
- Containerizing P1's full prod agent topology from `prod-config/bin/commandspecs/`
  (the ~25 prod agents) — this goal targets the demo-functional subset.

## Implementation status (2026-06-13) — branch `petarnenov/full-stack-k8s`

**Built (all under `k8s/`, + geowealth `k8s/` on `team/petarnenov/k8s-full-stack`):**
- Data tier: `oracle.yaml` (StatefulSet + server-side init scripts: EXTENDED dance +
  utl32k + DBMS_CRYPTO grant + V1 load, V1 staged via initContainer from the
  `db-seed` image), `oracle-seed.yaml` (flyway baseline+migrate Job + login-hash
  Secret), `elasticsearch.yaml`, `memcached.yaml`, `kc-postgres.yaml`. `db-seed`
  image (`k8s/images/db-seed/Dockerfile`) — **built**, all 18 migrations baked
  (V1 = 5.9 MB confirmed).
- Identity tier: `keycloak.yaml` (realm import + KC_HOSTNAME issuer split),
  `web.yaml` (reuse the SSL web images), `ingress-app.yaml`.
- Legacy P1 tier: geowealth `k8s/Dockerfile` (one image, many entrypoints) +
  `entrypoint.sh` (role dispatcher) + `config/*.tpl` (service-DNS config) +
  `README.md`; `k8s/base/p1.yaml` (coordinator seed + samlmanager + reportengine/
  proposalagents/emailagent + Tomcat + keystore Secret).
- Orchestration: `k8s/overlays/full-stack/kustomization.yaml` (48 resources) +
  `k8s/up.sh` (ordered waves + secret loading + teardown).

**Verified:**
- Full-stack overlay **renders (48 resources)** and **server-dry-run validates
  (46)** against a live API server; in-cluster KC env patches applied.
- `db-seed` image built + migrations baked.
- **Identity tier RUNS in-cluster**: Keycloak + its Postgres deployed on minikube;
  `demo-realm` imported with `demo-shared-client` + the `p1` SAML IdP (confirmed via
  admin API). `up.sh`/`entrypoint.sh` pass `bash -n`.

### FULL in-cluster run — DONE (2026-06-13, Docker raised to 32 GB)

With Docker Desktop bumped to 32 GB and a 24 GB minikube (`-p geowealth`), the
**entire stack runs in containers controlled by K8s — 19/19 pods Ready**:
- **DB in-container, seeded from `db/`**: Oracle (`gvenzl/oracle-free:23-slim`) with
  the GP schema loaded by the K8s init scripts — **1792 tables + `MAX_STRING_SIZE=
  EXTENDED`** — then the `oracle-seed` Job ran flyway baseline V1 + migrate V2..V18 +
  the login hash → **v18**. Verified: 4 firms (1/3/5/40), 3 login users
  (tim1/tim3/tim40), 9 password hashes. Job made idempotent via `-baselineOnMigrate`.
- **Identity**: Keycloak (realm imported: `demo-shared-client` + `p1` IdP) + its
  Postgres; token-handler + bff-billing×2 + bff-trading×2 + web-billing + web-trading.
- **Legacy P1 — Akka cluster FORMED**: `p1-coordinator` (seed) + `samlintegrationmanager`
  (SamlManager) + `reportengine` + `proposalagents` + `emailagent` + `p1-tomcat`
  (joined as role `web`, HTTP 200) all **Member is Up**, leader elected. The P1
  config templated to in-cluster Service DNS (`oracle:1521`, `elasticsearch:9200`,
  the coordinator seed). One geowealth image, many entrypoints (ROLE/AGENT).
- **Backing**: ES 7.17, memcached, Redis.

Hurdles cleared during the run: local-image `imagePullPolicy: IfNotPresent`;
minikube docker-daemon DNS flakiness after the Docker restart (node restart);
Oracle server-side EXTENDED+V1 init; the full `akka.conf` template (`provider=cluster`
+ `include base_akka_config` + artery `:4007`); the **coordinator's canonical
hostname = its stable DNS** so its self-address matches the seed-nodes literal;
flyway `baselineOnMigrate` for the empty-history retry case.

**Remaining (env, not artifacts):** a browser-driven login through the *ingress*
needs `minikube tunnel` (Mac docker-driver can't reach `192.168.49.2` directly) and
`/etc/hosts` that currently maps those hosts to the compose stack — so the
end-to-end e2e through the ingress is the one piece not yet driven headlessly; the
in-cluster functional state (seeded DB, formed Akka cluster, imported realm, serving
Tomcat, all pods Ready) is verified.

## 8. Execution notes

- Use the **top model**.
- Lean on the existing `db/stack/Makefile` (`make provision`/`verify`) and
  `docker-compose.yml` as the authoritative source for ports/env/seed steps —
  translate, don't reinvent.
- Reuse the Goal-5 `k8s/base` + overlays; add the new tiers alongside, keep one
  `overlays/full-stack` that composes everything for `up.sh`.
- After each tier, verify with real checks before moving on; after the whole stack,
  drive the browser e2e and capture evidence.
