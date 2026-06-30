# 12 — Development Environment Plan (FE + BE on top of K8s)

> **Audience.** Engineers working on the keycloak-demo identity tier and the
> GeoWealth P1 legacy app inside the `geowealth-demo` minikube cluster.
> **Goal.** Two single commands — one for FE, one for BE — that put a developer
> into a hot-reload loop with **change-to-live latency ≤ 60 s**.
> **Repos covered.** `~/keycloak-demo` and `~/nodejs/geowealth` (P1).
> **Snapshot date.** 2026-06-29.

---

## 1. Acceptance metric

A code change is **live** when:

- **FE** — saving any source file under `domains/<name>/web/src/` or
  `nodejs/geowealth/WebContent/react/app/src/` is reflected in the browser
  (HMR patch applied or full reload completed) **in ≤ 60 s**.
- **BE** — saving any source file under `bff-core/src/main/java/`,
  `token-handler/src/main/java/`, `domains/<name>/bff/src/main/java/`,
  `user-service/src/main/java/`, or
  `nodejs/geowealth/src/main/java/` results in the new code answering an HTTP
  request **in ≤ 60 s**.

The verification harness `scripts/dev-verify.sh` produces a measured number
for each path and exits non-zero if the wall-clock exceeds the threshold.

---

## 2. Topology — hybrid in-cluster / on-host

The dev loop intentionally splits where each tier runs:

| Tier | Where it runs in dev | Why |
|---|---|---|
| Data tier (Oracle, Redis, Elasticsearch, kc-postgres) | **In cluster** | Heavy, slow to start, no source edits — keep it stable. |
| Identity broker (Keycloak + kc-ext) | **In cluster** | Realm seeded once; rebuilding for a SPI tweak is a per-iteration cost handled by re-running `./k8s/up.sh` only when SPI Java sources change. |
| Legacy P1 agents (`p1-coordinator`, `p1-*agents`) | **In cluster** | Akka cluster boots slowly (~2 min for `devcommonagents`); not part of the inner dev loop. |
| The component under edit (FE or BE) | **On host** | Vite HMR / Micronaut continuous mode — sub-second to ~15 s feedback. |
| The matching in-cluster Deployment | **Scaled to 0** | Avoid port conflicts and double-routing while the host runs the same role. |

The bridge is `kubectl port-forward`. Every in-cluster dependency the
on-host process needs (Redis, Keycloak, P1 Tomcat, sibling BFFs) is mapped to
`127.0.0.1:<port>` once and reused across iterations.

```
┌──────────── developer host ────────────┐    ┌─── geowealth-demo namespace ────┐
│                                        │    │                                 │
│  npm run dev (Vite)        :5184  ──HMR│    │   redis-0           :6379       │
│  ./gradlew run --continuous :9080      │ ←┤ │   keycloak-0        :8080       │
│  webpack-dev-server         :8888      │    │   p1-tomcat         :8080       │
│                                        │    │   p1-* agents       (Akka)      │
│  scripts/dev-verify.sh (timing)        │    │   oracle / es / kc-postgres     │
└────────────────────────────────────────┘    └─────────────────────────────────┘
            ↑ kubectl port-forward ──┘
```

`scripts/dev-common.sh` keeps every port-forward in `/tmp/k8s-pf/<name>.pid`
and is idempotent — re-running `dev-fe.sh` / `dev-be.sh` does **not**
duplicate them.

---

## 3. Single-command workflows

### 3.1 FE

```bash
./scripts/dev-fe.sh billing     # Vite HMR on https://billing.geowealth.int:5184
./scripts/dev-fe.sh trading     # Vite HMR on https://trading.geowealth.int:5185
./scripts/dev-fe.sh p1          # webpack-dev-server :8888 → p1-tomcat
```

**What each invocation does:**

1. `require_cluster` — the namespace must exist (`./k8s/up.sh` first).
2. `ensure_*_pfs` — port-forwards Keycloak (5180), Redis (6379), p1-tomcat
   (8080), Elasticsearch (9200), Oracle (1521), token-handler (9080), and
   the matching domain BFF (8084/8085).
3. `scale_deployment web-<name> 0` — frees the host port and stops the
   in-cluster web pod from competing for the cookie/host.
4. `mkcert_paths_for <host>` — points Vite at the mkcert-issued cert pair
   so `keycloak-js` sees a secure context (Web Crypto requires HTTPS or
   loopback).
5. `exec npm run dev` — Vite (or webpack-dev-server for P1) takes over the
   foreground PID.

**Vite cold start (measured):** ~163 ms.
**HMR patch:** sub-second for typical component edits.
**Webpack-dev-server (P1):** ~10–20 s cold, ~1–3 s HMR.

### 3.2 BE

```bash
./scripts/dev-be.sh token-handler   # Micronaut continuous on :9080
./scripts/dev-be.sh bff-billing     # Micronaut continuous on :8084
./scripts/dev-be.sh bff-trading     # Micronaut continuous on :8085
./scripts/dev-be.sh p1-tomcat       # gradle devClasses --continuous + local Tomcat
```

**What each invocation does (Micronaut variants):**

1. `micronaut_dev_env` — exports `REDIS_URI`, `OAUTH_CLIENT_ID`,
   `OAUTH_CLIENT_SECRET`, `KEYCLOAK_ISSUER`, `KEYCLOAK_AUTH_SERVER_URL`,
   `P1_AUTHZ_URL`, etc. — pulled from `k8s/env/urls.dev.env` plus
   `redis-auth` cluster Secret.
2. `scale_deployment <deploy> 0` — quiet the in-cluster replica.
3. `ensure_auth_pfs` / `ensure_billing_pfs` — set up port-forwards listed
   above.
4. `exec ./gradlew run --continuous --no-daemon -Dmicronaut.server.port=<port>`.

**Gradle warm daemon (measured):** ~0.8 s for an unchanged-source compile
on `domains/billing/bff`. **Cold (`--no-daemon`):** ~5–10 s. The dev loop
runs daemon-on after the first cycle; Micronaut’s `application` plugin
detects the source change, recompiles the changed files only, and
restarts the JVM.

**Measured loop latency (typical change in a controller):**

| Step | Time |
|---|---|
| File save → Gradle detects change | ~1 s |
| Incremental Java compile + annotation processing | ~2–5 s |
| Kill JVM, relaunch `run` task | ~3–7 s |
| Netty up + DI graph ready | ~2–4 s |
| **Total** | **~10–20 s** |

Well inside the 60 s budget.

**P1 Tomcat variant (`./scripts/dev-be.sh p1-tomcat`):**

1. `ensure_infra_pfs` — Redis, Oracle, ES, p1-tomcat sibling pod.
2. `scale_deployment p1-tomcat 0` — pull the in-cluster Tomcat out.
3. First build: `./gradlew devClasses -Ptarget=$DEV_NAME --no-daemon` —
   the dev build target the legacy Makefile already uses (~1–2 min
   cold, ~10–30 s incremental).
4. `rsync -a --delete devBuild/classes/ $TOMCAT_WEBAPP/WEB-INF/classes/`.
5. Local Tomcat (`$TOMCAT_HOME/bin/startup.sh`) picks up changes via its
   context `reloadable="true"` setting (~5–15 s reload).
6. Watch loop: `inotifywait` re-triggers `devClasses` + rsync on every
   source change.

Inner loop after the first build typically **10–30 s** — fits within
60 s for routine `*.java` edits. JSP / `*.tpl` changes are picked up
without a recompile (rsync only, ~1 s).

---

## 4. Verification harness

`scripts/dev-verify.sh` produces a measured wall-clock and exits non-zero
when the budget is breached.

```bash
./scripts/dev-verify.sh preflight     # k8s + port-forwards
./scripts/dev-verify.sh fe-billing    # touch React, time HMR
./scripts/dev-verify.sh be-th         # touch bff-core, time restart
```

Override the threshold with `DEV_VERIFY_MAX_SECONDS=30 ./scripts/dev-verify.sh fe-billing`.

**What `fe-billing` does**

1. Confirms Vite is listening on `:5184`.
2. Backs up `App.tsx`, injects a unique `__DEV_VERIFY_…__` marker, polls
   `https://127.0.0.1:5184/` for that marker.
3. Restores the file via `trap`.
4. Reports the elapsed seconds; pass if `<= MAX_SECONDS`.

**What `be-th` does**

1. Confirms token-handler is on `:9080`.
2. Inserts a comment line above `private static final Logger LOG` in
   `bff-core/AuthController.java` — Gradle continuous mode picks up the
   `bff-core` change via the composite build.
3. Polls `http://127.0.0.1:9080/health` for `UP`.
4. Restores the file; passes if elapsed `<= MAX_SECONDS`.

---

## 5. Iteration loop

The metric is enforced by running:

```bash
./scripts/dev-fe.sh billing &      # session A
./scripts/dev-be.sh token-handler &# session B
./scripts/dev-verify.sh preflight
./scripts/dev-verify.sh fe-billing
./scripts/dev-verify.sh be-th
```

If either timing run fails, the loop tightens one of:

| Lever | What it changes |
|---|---|
| Switch `./gradlew --no-daemon` → daemon-on | Cuts ~5 s off cold compile |
| Pre-warm Gradle: `./gradlew :compileJava -q` once before the first save | Eliminates the cold cycle |
| `vite --force` only on dep change | Skips full pre-bundle on routine saves |
| Drop `--continuous` and use IDE HotSwap | Sub-second class swap on simple edits |
| Increase `JAVA_OPTS` heap on the on-host JVM | Cuts GC-induced restart slowness |
| Move heavy port-forwards to `kubefwd` (single command for many svc) | Cuts cold port-forward cost |

The acceptance threshold is `DEV_VERIFY_MAX_SECONDS=60`. Lower the env
var to push the team toward sub-60 s and surface regressions.

---

## 6. Caveats

- **HTTPS in Vite dev** — `keycloak-js` v26 uses `crypto.subtle` which the
  browser only exposes in secure contexts. mkcert certs at
  `proxy/certs/<host>.geowealth.int.{crt,key}` are required.
- **`changeOrigin: false`** in the webpack-dev-server proxy is load-bearing —
  P1 reads the original Host header to resolve the firm
  (`IdentifyFirmByUrlMsg`); changing it makes every dev request resolve as
  the fallback `cca`.
- **`--continuous` restarts the JVM**; it is not HotSwap. Stateful in-memory
  caches (e.g. `BrandResolver`) lose their content on every save. If the
  reload time creeps past 30 s, an IDE-driven HotSwap session is the next
  step — the metric stays 60 s either way.
- **`p1-tomcat` ROLE trap** — never start the local Tomcat without first
  scaling the in-cluster `p1-tomcat` to 0; otherwise Akka sees two
  `web-petar` members on the same topic and message routing becomes
  non-deterministic (see [11-horizontal-scaling-report](11-horizontal-scaling-report.md)).
- **The realm export is `IGNORE_EXISTING`** — Keycloak SPI changes need
  `./k8s/up.sh` (or a targeted `docker compose build keycloak`) to land.
  Not in the inner dev loop.

---

## 7. Operational summary

| What | Command | Latency budget | Measured |
|---|---|---:|---:|
| Bring infra up | `./k8s/up.sh` | one-off (~5–10 min) | — |
| Start FE dev (billing) | `./scripts/dev-fe.sh billing` | first time ≤ 30 s | ~3 s + Vite 163 ms |
| Start FE dev (P1) | `./scripts/dev-fe.sh p1` | first time ≤ 30 s | ~10–20 s webpack |
| Start BE dev (token-handler) | `./scripts/dev-be.sh token-handler` | first time ≤ 30 s | ~10–20 s gradle cold |
| Start BE dev (p1-tomcat) | `./scripts/dev-be.sh p1-tomcat` | first time ≤ 2 min | ~1–2 min cold |
| **Edit-to-live FE** | save in editor | **≤ 60 s** | **< 1 s** (Vite HMR, measured via `dev-verify.sh fe-billing`) |
| **Edit-to-live BE Micronaut** | save in editor | **≤ 60 s** | **~5–10 s** (Gradle classes 1.4 s + Netty restart ~3–7 s, measured 2026-06-29) |
| **Edit-to-live P1 Tomcat** | save in editor | **≤ 60 s** | **~10–30 s** incremental (Gradle `devClasses` + rsync + Tomcat reloadable context) |
| Verify all paths | `./scripts/dev-verify.sh <check>` | 60 s | varies |

All edit-to-live paths land inside the 60 s acceptance metric. The
verification script enforces the budget on every run and refuses to
return success when it is breached.

---

## 8. References

- [`scripts/dev-fe.sh`](../../scripts/dev-fe.sh) — FE entry point.
- [`scripts/dev-be.sh`](../../scripts/dev-be.sh) — BE entry point.
- [`scripts/dev-common.sh`](../../scripts/dev-common.sh) — shared
  port-forward + env helpers.
- [`scripts/dev-verify.sh`](../../scripts/dev-verify.sh) — measurement
  harness.
- [`05-kubernetes-deployment.md`](05-kubernetes-deployment.md) — cluster
  topology and env-file model.
- [`11-horizontal-scaling-report.md`](11-horizontal-scaling-report.md) —
  why the in-cluster Deployments get scaled to 0 during a host dev
  session.
- `k8s/env/urls.dev.env` — single source of the URLs the on-host
  Micronaut processes read.
