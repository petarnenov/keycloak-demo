# 13 — Development Environment Setup (FE + BE)

> **Audience.** Any engineer bringing the demo up locally for the first time.
> **Scope.** Step-by-step from a clean machine to two single commands —
> `./scripts/dev-fe.sh` and `./scripts/dev-be.sh` — that put you in a
> hot-reload loop with **change-to-live latency ≤ 60 s**.
> **Companion reads.** [`05-kubernetes-deployment.md`](05-kubernetes-deployment.md)
> for topology, [`12-dev-environment-plan.md`](12-dev-environment-plan.md) for
> the design rationale and the ≤60 s acceptance metric.

---

## 1. Prerequisites

Install once per host. Versions are the lowest tested.

| Tool | Version | Why |
|---|---|---|
| `git` | 2.x | Clone both repos. |
| `node` | ≥ 20 | Vite (keycloak-demo SPAs) + webpack-dev-server (P1). |
| `npm` | ≥ 10 | Both FE projects use npm + `package-lock.json`. |
| `java` | 17 (Temurin/Corretto) | Micronaut BFFs + token-handler + user-service. |
| Gradle wrapper | 8.5 (bundled) | Don't install Gradle globally; use `./gradlew` per project. |
| `minikube` | ≥ 1.32 | Local Kubernetes; the only supported flavour for dev. |
| `kubectl` | ≥ 1.30 | Cluster CLI. |
| `docker` *or* `podman` | recent | Image builds. `./start.sh` and `./k8s/up.sh` auto-detect. |
| `mkcert` | 1.4.x | Locally trusted TLS certs for the browser-secure-context requirement. |
| `kustomize` | ≥ 5 | `kubectl kustomize` works; standalone optional. |
| `inotify-tools` | recent (Linux) | `inotifywait` for the P1-Tomcat hot-reload loop in `dev-be.sh`. |
| Local Tomcat 9 (optional) | 9.0.x | Only needed for `./scripts/dev-be.sh p1-tomcat`; set `TOMCAT_HOME` env. |

On Ubuntu the bootstrap script `scripts/linux-bootstrap.sh` installs the
distribution-packaged tools and points at the manual-install instructions
for the rest.

---

## 2. Repository layout

Clone both repos side by side; the `dev-be.sh p1-tomcat` path expects the
GeoWealth repo at `~/geowealth` by default.

```
~/keycloak-demo/                # this repo
~/nodejs/geowealth/             # P1 monolith (Tomcat + Akka agents + React app)
~/geowealth -> nodejs/geowealth # convenience symlink (or set GEOWEALTH_DIR env)
```

Override either default with the `GEOWEALTH_DIR` environment variable.

---

## 3. /etc/hosts entries

Every host below resolves to `127.0.0.1`. They are required because
the browser classifies "secure context" by hostname literal, not by
resolved IP, and `keycloak-js` v26 uses `crypto.subtle` which only
exists in secure contexts (HTTPS or the literal loopback hostnames
`localhost` / `127.0.0.1`).

Append to `/etc/hosts`:

```
127.0.0.1 auth.geowealth.int
127.0.0.1 billing.geowealth.int
127.0.0.1 trading.geowealth.int
127.0.0.1 users.geowealth.int
127.0.0.1 geowealth.int
127.0.0.1 p1.geowealth.int
127.0.0.1 geowealth-be
```

Confirm with:

```bash
getent hosts billing.geowealth.int
# 127.0.0.1       billing.geowealth.int
```

Adding a new domain `<slug>.geowealth.int` requires a matching entry plus
the mkcert pair in §4.

---

## 4. TLS certificates (mkcert)

The browser refuses to expose `crypto.subtle` over plain HTTP for
non-loopback hosts. Each domain SPA + Keycloak + user-service therefore
serves HTTPS in dev. Certs live under `proxy/certs/`:

```
proxy/certs/
├── mkcert-rootCA.pem                  # the local CA's public cert
├── auth.geowealth.int.{crt,key}       # Keycloak (kc-ext)
├── billing.geowealth.int.{crt,key}    # billing SPA
├── trading.geowealth.int.{crt,key}    # trading SPA
└── users.geowealth.int.{crt,key}      # user-service (admin UI)
```

Bootstrap mkcert on a clean host:

```bash
mkcert -install                # install the mkcert root CA into the system + Firefox/Chrome trust stores
mkdir -p proxy/certs
cd proxy/certs
mkcert -cert-file auth.geowealth.int.crt    -key-file auth.geowealth.int.key    auth.geowealth.int
mkcert -cert-file billing.geowealth.int.crt -key-file billing.geowealth.int.key billing.geowealth.int localhost 127.0.0.1
mkcert -cert-file trading.geowealth.int.crt -key-file trading.geowealth.int.key trading.geowealth.int localhost 127.0.0.1
mkcert -cert-file users.geowealth.int.crt   -key-file users.geowealth.int.key   users.geowealth.int   localhost 127.0.0.1
cp "$(mkcert -CAROOT)/rootCA.pem" mkcert-rootCA.pem
```

The token-handler image trusts `mkcert-rootCA.pem` at runtime (see
`docker-compose.yml` mount + the overlay's `mkcert-ca` ConfigMap). When
you regenerate the CA (`mkcert -uninstall && mkcert -install`), re-run
the `cp` above and rebuild the token-handler image.

---

## 5. Port map

| Endpoint | Browser-facing port | In-cluster Service | Container port | Bridge in dev |
|---|---:|---|---:|---|
| Keycloak (HTTPS via kc-ext) | `5180` | `kc-ext` (nginx TLS sidecar) | `5180` | `kubectl port-forward svc/kc-ext 5180:5180` |
| Keycloak (HTTP, in-cluster only) | — | `keycloak` | `8080` | not exposed to host |
| Redis | `6379` | `redis` | `6379` | `kubectl port-forward svc/redis 6379:6379` |
| Oracle XE (in-cluster default) | `1521` | `oracle` | `1521` | `kubectl port-forward svc/oracle 1521:1521` |
| Elasticsearch (in-cluster default) | `9200` | `elasticsearch` | `9200` | `kubectl port-forward svc/elasticsearch 9200:9200` |
| `kc-postgres` (Keycloak DB) | — | `kc-postgres` | `5432` | not normally needed |
| `user-service` | `8090` (image), exposed via Ingress on `users.geowealth.int` | `user-service` | `8080` | optional `port-forward svc/user-service 8090:8080` |
| `token-handler` | `9080` (host) → `8080` (pod) | `token-handler` | `8080` | `port-forward svc/token-handler 9080:8080` |
| `bff-billing` | `8084` (host) → `8080` (pod) | `bff-billing` | `8080` | `port-forward svc/bff-billing 8084:8080` |
| `bff-trading` | `8085` (host) → `8080` (pod) | `bff-trading` | `8080` | `port-forward svc/bff-trading 8085:8080` |
| `web-billing` (in-cluster nginx) | `5184` | `web-billing` | `5184` | scaled to 0 when Vite runs locally |
| `web-trading` (in-cluster nginx) | `5185` | `web-trading` | `5185` | scaled to 0 when Vite runs locally |
| `p1-tomcat` | `8080` | `p1-tomcat` | `8080` | `port-forward svc/p1-tomcat 8080:8080` |
| `p1-coordinator` Akka | `4007` | `p1-coordinator` | `4007` | in-cluster only |
| Vite dev (billing SPA, HTTPS) | `5184` | — | — | started by `./scripts/dev-fe.sh billing` |
| Vite dev (trading SPA, HTTPS) | `5185` | — | — | started by `./scripts/dev-fe.sh trading` |
| webpack-dev-server (P1 React) | `8888` | — | — | started by `./scripts/dev-fe.sh p1` |

`scripts/dev-common.sh` owns the port-forward lifecycle: PIDs land in
`/tmp/k8s-pf/<name>.pid`, the helper re-uses an already-listening port
instead of duplicating it, and `dev-be.sh` scales the matching in-cluster
Deployment to **0** before launching its host process so the same logical
port isn't served by two implementations at once.

---

## 6. Cluster bring-up

The cluster manifests live under `k8s/`. One command:

```bash
./k8s/up.sh                    # full-stack overlay (data + identity + legacy P1)
```

What `up.sh` does, in order:

1. Picks `k8s/env/urls.<env>.env` and `k8s/env/data-tier.<env>.env`
   (env defaults to `dev`, override with `URLS_ENV=qa ./k8s/up.sh`).
2. `kustomize build` → `kubectl apply -k k8s/overlays/full-stack` with
   `--load-restrictor LoadRestrictionsNone` so it can read
   `keycloak/realm-export.json` from the repo root.
3. Patches a `hostAlias` on each pod that needs to resolve
   `auth.geowealth.int` from inside the cluster (the issuer host) to the
   `kc-ext` Service ClusterIP.
4. If `data-tier.<env>.env` points `ORACLE_HOST` or `ELASTICSEARCH_HOST`
   at an external machine, swaps the matching Service to
   `type: ExternalName` and scales the in-cluster StatefulSet to 0.
5. `kubectl rollout restart` on the eleven Oracle-consuming P1 workloads
   **iff** their env materially changed AND the pods already existed.
6. Waits for every workload's `rollout status`, then runs
   `scripts/reconcile-realm.sh` against the live Keycloak admin API to
   PATCH the IdP / client URLs from `urls.<env>.env`.

Tear down without losing volumes:

```bash
./k8s/down.sh                   # keeps PVCs (Oracle, Postgres, Redis, ES)
./k8s/down.sh --wipe            # also deletes the namespace + PVCs
```

Confirm:

```bash
kubectl -n geowealth-demo get pods                # all Running
./scripts/dev-verify.sh preflight                 # listening ports
```

---

## 7. Data tier — Oracle

Demo default: `oracle-0` StatefulSet built from the **baked seed image**
(`db/Dockerfile` → `keycloak-demo-db:seeded`). Connection details are
fixed in the image and re-emitted into the cluster via the
`oracle-config` ConfigMap + `oracle-creds` Secret.

| Setting | Default (in-cluster) | Where it lives |
|---|---|---|
| Host | `oracle` (Service DNS) | `k8s/env/data-tier.dev.env:ORACLE_HOST` |
| Port | `1521` | `ORACLE_PORT` |
| PDB / SERVICE_NAME | `FREEPDB1` | `ORACLE_PDB` |
| Schema user | `gp` | `ORACLE_USER` |
| Password | `gp123` | `ORACLE_PASSWORD` (shell env on `up.sh`; defaults to image default) |

JDBC URL the BFF / P1 sees:

```
jdbc:oracle:thin:@//oracle:1521/FREEPDB1
```

### 7.1 Pointing at an external Oracle

Edit `k8s/env/data-tier.dev.env`:

```env
ORACLE_HOST=192.168.1.42
ORACLE_PORT=1521
ORACLE_PDB=ORCL12VM
ORACLE_USER=gp
```

Provide the credential out-of-band:

```bash
ORACLE_PASSWORD='real-prod-pw' ./k8s/up.sh
```

`up.sh` rewrites the Service to `type: ExternalName, externalName: 192.168.1.42`,
scales the in-cluster StatefulSet to 0, and restarts the eleven P1
Oracle-consuming workloads if their env changed. The Oracle service name
inside the cluster (`oracle:1521`) does NOT change — only what cluster
DNS resolves it to.

### 7.2 Connecting from the host

```bash
kubectl -n geowealth-demo port-forward svc/oracle 1521:1521 &
sqlplus gp/gp123@//127.0.0.1:1521/FREEPDB1
```

(SQL*Plus client comes from any Oracle client distribution; the dev team
often uses `sqlcl`.)

### 7.3 Heap warning when swapping to a real PDB

`p1-devcommonagents` pre-loads `CostBasisAccount` into an in-memory
cache (`CrntCostBasisLoader`) at boot. The default `1.5Gi` heap fits the
baked PDB only; against real data it OOMs and the SPA blanks (because
`AuthorizationManager` runs in the same agent). `k8s/base/p1.yaml`
therefore already sets `-Xmx4G` with `limits.memory: 5Gi`. If a
different agent OOMs the same way, bump its `JAVA_OPTS_EXTRA` +
container limit proportionally.

---

## 8. Data tier — Elasticsearch

Demo default: single-node `elasticsearch-0` StatefulSet, listening on
`elasticsearch:9200` (HTTP).

| Setting | In-cluster | External (`dev-elastic.geowealth.com`) |
|---|---|---|
| `ELASTICSEARCH_HOST` | `elasticsearch` | `dev-elastic.geowealth.com` |
| `ELASTICSEARCH_PORT` | `9200` | `9200` |
| `ELASTICSEARCH_SCHEME` | `http` | `https` |

The external host must be reachable from inside the cluster pods.
Pointing at the company dev-elastic populates client search; without it
the search UI returns empty results (boot does not fail).

### 8.1 Connecting from the host

```bash
kubectl -n geowealth-demo port-forward svc/elasticsearch 9200:9200 &
curl -s http://127.0.0.1:9200/_cluster/health | jq
```

### 8.2 When switching to external ES

```env
# k8s/env/data-tier.dev.env
ELASTICSEARCH_HOST=dev-elastic.geowealth.com
ELASTICSEARCH_PORT=9200
ELASTICSEARCH_SCHEME=https
```

Re-run `./k8s/up.sh`. The in-cluster ES StatefulSet is scaled to 0, the
`elasticsearch` Service becomes `ExternalName`, and `p1-searchagents`
restarts pointing at the company dev ES.

---

## 9. Redis

Used by token-handler (sessions, SID registry) **and** by P1 Tomcat
(Redisson session manager, `kc_sub → sessionId` index).

| Setting | Value |
|---|---|
| Service / port | `redis:6379` |
| Auth | password Secret `redis-auth` (key `password`) |
| Default dev password | `dev-redis-pw-change-me` (when `redis-auth` is absent) |
| `REDIS_URI` env (token-handler / BFFs) | `redis://:<password>@redis:6379` (in-cluster) or `redis://:<password>@127.0.0.1:6379` (host) |

`scripts/dev-common.sh:redis_uri_from_cluster` reads the password from
the live `redis-auth` Secret and falls back to the dev default if the
Secret is missing — so the on-host Micronaut process always gets the
right URI without any manual env wiring.

### 9.1 Connecting from the host

```bash
kubectl -n geowealth-demo port-forward svc/redis 6379:6379 &
PW=$(kubectl -n geowealth-demo get secret redis-auth -o jsonpath='{.data.password}' | base64 -d)
redis-cli -h 127.0.0.1 -a "$PW" PING
# PONG
```

---

## 10. Keycloak realm reconciliation

Realm imports are `IGNORE_EXISTING` — the JSON only seeds an empty
Postgres. For env-specific URL drift (IdP endpoints, client redirect /
post-logout / back-channel) edit `k8s/env/urls.<env>.env` and run:

```bash
./scripts/reconcile-realm.sh k8s/env/urls.dev.env
```

`up.sh` runs this automatically after every bring-up. The reconciler
PATCHes the live realm via the admin API; it never edits `realm-export.json`.

Admin console (browser): `https://auth.geowealth.int:5180`
Credentials: `admin` / `admin` (dev only).

---

## 11. Start the FE — one command

```bash
./scripts/dev-fe.sh billing     # https://billing.geowealth.int:5184 — Vite HMR
./scripts/dev-fe.sh trading     # https://trading.geowealth.int:5185 — Vite HMR
./scripts/dev-fe.sh p1          # http://localhost:8888 — webpack-dev-server
```

What the script does for `billing`:

1. Confirms the cluster is up (`require_cluster`).
2. Brings up port-forwards: `kc-ext:5180`, `redis:6379`, `p1-tomcat:8080`,
   `oracle:1521`, `elasticsearch:9200`, `token-handler:9080`,
   `bff-billing:8084`.
3. Scales `deploy/web-billing` to **0** to free host port `5184`.
4. Exports `HTTPS_CERT_PATH` / `HTTPS_KEY_PATH` to the mkcert pair.
5. Exports `DEV_FORWARD_AUTH=1`, `TOKEN_HANDLER_URL=http://127.0.0.1:9080`,
   `BFF_BILLING_URL=http://127.0.0.1:8084`.
6. `exec npm run dev` in `domains/billing/web/` — Vite takes over PID.

**Cold start measured:** ~163 ms (Vite). **HMR patch:** sub-second.
Open `https://billing.geowealth.int:5184` — the mkcert cert is trusted
by the system store, so no browser warning.

### 11.1 P1 React app

```bash
./scripts/dev-fe.sh p1
```

Brings up `p1-tomcat:8080` port-forward, then runs
`npm run dev:local` in `~/nodejs/geowealth/WebContent/react/app/` —
webpack-dev-server on `:8888` proxying to `127.0.0.1:8080` (`p1-tomcat`).
**`changeOrigin: false`** in the proxy is load-bearing: P1's
`IdentifyFirmByUrlMsg` reads the original `Host` header to resolve the
whitelabel firm.

---

## 12. Start the BE — one command

```bash
./scripts/dev-be.sh token-handler   # Micronaut continuous on :9080
./scripts/dev-be.sh bff-billing     # Micronaut continuous on :8084
./scripts/dev-be.sh bff-trading     # Micronaut continuous on :8085
./scripts/dev-be.sh p1-tomcat       # gradle devClasses watch + local Tomcat
```

### 12.1 Micronaut variants (`token-handler`, `bff-billing`, `bff-trading`)

What happens:

1. `micronaut_dev_env` exports:
   - `REDIS_URI` (built from the cluster Secret),
   - `OAUTH_CLIENT_ID` (`demo-shared-client` for `token-handler`,
     `demo-billing-client` / `demo-trading-client` for the BFFs),
   - `OAUTH_CLIENT_SECRET` (dev secret),
   - `KEYCLOAK_ISSUER=https://auth.geowealth.int:5180/realms/demo-realm`,
   - `KEYCLOAK_AUTH_SERVER_URL=http://keycloak:8080/realms/demo-realm`
     (in-cluster JWKS; resolves to the port-forward via `/etc/hosts` →
     127.0.0.1, **but** the in-cluster JWKS path requires the in-cluster
     hostAlias which is patched by `up.sh`),
   - `P1_AUTHZ_URL=http://127.0.0.1:8080` (port-forwarded P1).
2. Scales the matching in-cluster Deployment to **0**.
3. `ensure_auth_pfs` brings up: `kc-ext:5180`, `redis:6379`,
   `p1-tomcat:8080`, `oracle:1521`, `elasticsearch:9200`,
   `token-handler:9080`.
4. `exec ./gradlew run --continuous --no-daemon -Dmicronaut.server.port=<port>`.

`--continuous` keeps the JVM and Gradle watcher alive; on every saved
file Gradle recompiles incrementally and restarts the Micronaut
application. Composite build means a `bff-core` change recompiles into
every consuming module automatically.

**Measured (2026-06-29):**

- Gradle incremental classes after `bff-core` touch: **1.4 s**.
- Netty restart + DI graph ready: **3–7 s**.
- **Total edit-to-live: ~5–10 s**, well inside the 60 s budget.

### 12.2 P1 Tomcat variant

```bash
./scripts/dev-be.sh p1-tomcat
```

Requirements: a local Tomcat 9 install with `TOMCAT_HOME` env exported
(default `~/tools/tomcat9`), and `inotify-tools` on the host.

The script:

1. Brings up `ensure_infra_pfs` (Redis, Oracle, ES, p1-tomcat sibling).
2. Scales `deploy/p1-tomcat` to **0**.
3. Builds `gradle devClasses -Ptarget=$DEV_NAME` (first run ~1–2 min).
4. `rsync -a --delete devBuild/classes/ $TOMCAT_WEBAPP/WEB-INF/classes/`.
5. Starts local Tomcat (`$TOMCAT_HOME/bin/startup.sh`).
6. Enters an `inotifywait` loop: on any change in
   `src/main/java`, `src/main/resources`, `WebContent/WEB-INF`, rebuilds
   `devClasses`, rsyncs, and lets Tomcat's `reloadable="true"` context
   pick up the new classes.

**Measured:** first build 1–2 min cold; subsequent incremental cycles
**10–30 s** — fits the 60 s budget for routine `*.java` edits. JSP /
`*.tpl` changes are rsync-only (~1 s).

`DEV_NAME` defaults to `petar`; override with `DEV_NAME=yo ./scripts/dev-be.sh p1-tomcat`
to point at a different per-dev `etc/<name>-akka.conf` profile.

---

## 13. Verify

```bash
./scripts/dev-verify.sh preflight     # cluster + port-forwards alive
./scripts/dev-verify.sh fe-billing    # measure FE HMR end-to-end
./scripts/dev-verify.sh be-th         # measure token-handler reload end-to-end
```

Override the threshold:

```bash
DEV_VERIFY_MAX_SECONDS=30 ./scripts/dev-verify.sh fe-billing
```

What `fe-billing` proves:

1. Saves `domains/billing/web/src/pages/OverviewPage.tsx` with a unique
   marker (`__DEV_VERIFY_<random>__`) replacing `<h1>Overview</h1>`.
2. Polls Vite's transformed module endpoint
   `https://127.0.0.1:5184/src/pages/OverviewPage.tsx` for the marker.
3. Restores the file on EXIT trap.
4. Passes iff elapsed wall-clock `≤ DEV_VERIFY_MAX_SECONDS`.

What `be-th` proves:

1. Inserts a comment above `private static final Logger LOG` in
   `bff-core/src/main/java/demo/bff/core/AuthController.java`.
2. Polls `http://127.0.0.1:9080/health` for `UP`.
3. Restores the file on EXIT trap.
4. Passes iff elapsed wall-clock `≤ DEV_VERIFY_MAX_SECONDS`.

---

## 14. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `PORT :5184 not listening` in preflight | Vite never started OR another process holds it | `lsof -i :5184` to identify, `kill <pid>`, re-run `./scripts/dev-fe.sh billing` |
| Browser shows "Connection refused" on `https://auth.geowealth.int:5180` | `kc-ext` port-forward not running | `./scripts/dev-fe.sh billing` (re-runs `ensure_*_pfs`) or manually `kubectl -n geowealth-demo port-forward svc/kc-ext 5180:5180` |
| Browser error `crypto.subtle is undefined` | SPA opened over plain HTTP for a non-loopback host | Use `https://billing.geowealth.int:5184` not `http://`. Confirm mkcert installed (`mkcert -install`). |
| Login loops back to the IdP every time | Cookie domain mismatch — the SPA is on a different host than the cookie | Confirm the cookie path/domain via DevTools; the token-handler cookie is `GWSESSION` and is **host-scoped**, so `billing.geowealth.int` and `trading.geowealth.int` get distinct cookies. |
| P1 logout 401 / 502 | Stale `kc-ext` port-forward (pod restarted) | `pkill -f 'port-forward.*kc-ext'` then re-run `./scripts/dev-fe.sh ...` |
| `localhost:8080` renders blank after switching to external Oracle | `p1-devcommonagents` OOM (see §7.3) | `kubectl -n geowealth-demo logs deploy/p1-devcommonagents --previous` for `OutOfMemoryError`; bump heap |
| "Currently Offline" indicator stuck | `p1-mostagents` not running or watchdog list mismatch | Check `p1-mostagents` logs; the K8s-specific fix is `appname=web-petar` in `geowealth/k8s/entrypoint.sh` |
| `dev-be.sh token-handler` fails with `JAVA_HOME not set` | Java not on PATH | Install Temurin/Corretto 17 and export `JAVA_HOME` |
| Gradle `run --continuous` exits immediately | Composite build not picked up (sibling `bff-core` directory missing) | Confirm `~/keycloak-demo/bff-core/` exists; the BFFs `includeBuild('../../../bff-core')` |
| HMR works but the BFF call returns 401 | `bff-billing` port-forward died (pod restart) | `./scripts/dev-fe.sh billing` is idempotent; re-running brings the port-forward back |
| `dev-be.sh p1-tomcat` cold build slow / OOM | `gradle devClasses` heap | Set `GRADLE_OPTS='-Xmx4g'` |
| `dev-verify.sh fe-billing` returns PASS in 0 s | Vite served stale module OR marker grep matched without an HMR cycle | Confirm the file was actually modified (`grep DEV_VERIFY` in the source); if PASS happens before the change reaches Vite, the script's wait_http polls every second so a 0-second pass means the marker landed on the first poll — Vite normally takes ~100–300 ms |

---

## 15. Cleanup

End-of-day teardown:

```bash
pkill -f 'kubectl.*port-forward'      # close all port-forwards
./k8s/down.sh                         # tear cluster down (PVCs preserved)
```

Hard wipe (use sparingly — loses every database row):

```bash
./k8s/down.sh --wipe                  # also deletes the namespace + PVCs
```

---

## 16. References

- [`12-dev-environment-plan.md`](12-dev-environment-plan.md) — design
  rationale, complexity score, ≤ 60 s acceptance metric.
- [`05-kubernetes-deployment.md`](05-kubernetes-deployment.md) — full
  cluster topology and env-file model.
- [`scripts/dev-fe.sh`](../../scripts/dev-fe.sh), [`scripts/dev-be.sh`](../../scripts/dev-be.sh),
  [`scripts/dev-common.sh`](../../scripts/dev-common.sh),
  [`scripts/dev-verify.sh`](../../scripts/dev-verify.sh).
- `k8s/env/urls.dev.env`, `k8s/env/data-tier.dev.env` — single sources
  of URL + data-tier endpoint config.
- `proxy/certs/` — mkcert-issued TLS pairs and the CA root.
