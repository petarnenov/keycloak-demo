# Developing `user-service` in IntelliJ IDEA — step by step

`user-service` is a standalone Micronaut 4 module (Java 17) — a read-only Oracle
DAO behind Keycloak's User Storage SPI. It does **not** depend on `bff-core`, so
you open the module directory on its own and it builds from just this source tree.

## 0. Prerequisites (one-time)

- **JDK 17** installed (the module targets Java 17). On this Mac it's already
  present: `Temurin 17` at `/Library/Java/JavaVirtualMachines/temurin-17.jdk`.
  Do **not** build with JDK 21 — the Micronaut annotation processor + shadow
  config here are pinned to 17.
- **IntelliJ IDEA** with the bundled **Gradle** and **Lombok is not used**, but
  the **Micronaut / Micronaut Launch** plugin (bundled in Ultimate) is nice to
  have for the run gutter; Community works fine too.
- An **Oracle** the service can reach (see step 4). There is **no gradle
  wrapper** in this repo, so IntelliJ will use its bundled Gradle — that's fine.

## 1. Open the module

1. `File ▸ Open…` → select **`user-service/`** (the folder that has
   `build.gradle.kts`), **not** the repo root.
2. When prompted, choose **Open as Project** and **trust** it.
3. Let IntelliJ import the Gradle model (bottom-right progress bar). First import
   downloads dependencies — give it a minute.

## 2. Point the project at JDK 17

1. `Settings ▸ Build, Execution, Deployment ▸ Build Tools ▸ Gradle`.
2. Set **Gradle JVM** = **Temurin 17** (17.0.6). This is the single most common
   trip-up — if Gradle JVM is 21 the build fails or produces a jar that behaves
   oddly.
3. `File ▸ Project Structure ▸ Project` → **SDK** = 17, **Language level** = 17.
4. `File ▸ Project Structure ▸ Modules` → **Language level** = 17.

## 3. Confirm the Gradle build works

Open the **Gradle** tool window (right edge) or a terminal in the module dir:

```bash
gradle build            # compile + run tests (tests use H2, no Oracle needed)
gradle shadowJar -x test   # produce build/libs/user-service-0.1-all.jar
```

Tests (`EntityDaoTest`, `SHAPasswordTest`) run against **H2 + Mockito**, so they
pass with **no** Oracle connection. If `gradle build` is green you're set up
correctly.

## 4. Give it an Oracle to talk to (run-time config)

`application.yml` defaults to the docker-network host `oracle:1521/FREEPDB1`,
which does **not** resolve when you run from IntelliJ on the host. Override the
JDBC coordinates via env vars on the run configuration. Three env vars matter:

| Env var | Default in `application.yml` | What to set locally |
|---|---|---|
| `JDBC_URL` | `jdbc:oracle:thin:@//oracle:1521/FREEPDB1` | a reachable Oracle URL (below) |
| `ORACLE_USER` | `gp` | usually `gp` |
| `ORACLE_PASSWORD` | `gp123` | usually `gp123` (never commit real creds) |

Pick whichever Oracle you have up:

- **Local containerized Oracle** (the demo's own DB, `FREEPDB1`):
  `JDBC_URL=jdbc:oracle:thin:@//localhost:1521/FREEPDB1`
- **qa4 / external `ORCL12VM`**:
  `JDBC_URL=jdbc:oracle:thin:@//<host>:1521/ORCL12VM`

> Tip: the port must be reachable from the host. If Oracle runs in
> docker/minikube, make sure it's port-forwarded to `localhost:1521` first.

## 5. Create the Run configuration

**Option A — from the main class (simplest):**
1. Open `src/main/java/com/gw/userservice/Application.java`.
2. Click the green ▶ in the gutter next to `class Application` → **Run
   'Application'** (this generates an *Application* run config).

**Option B — a Gradle run config:**
- Run `gradle run` (the Micronaut `application` plugin wires `run` to
  `com.gw.userservice.Application`).

Then edit the run config (**Run ▸ Edit Configurations…**) and add the
**Environment variables** from step 4, e.g.:

```
JDBC_URL=jdbc:oracle:thin:@//localhost:1521/FREEPDB1;ORACLE_USER=gp;ORACLE_PASSWORD=gp123
```

Make sure the run config's **JRE** is also **17**.

The service starts on **`http://localhost:8080`** (`micronaut.server.port`).

## 6. Verify it's running

```bash
curl -s http://localhost:8080/health          # -> {"status":"UP"}
```

Exercise the SPI endpoints (same ones Keycloak's User Storage SPI calls):

```bash
curl -s "http://localhost:8080/users/search?username=tim1&firmCd=1"
curl -s "http://localhost:8080/users/<entityId>/attributes"
curl -s "http://localhost:8080/users/<entityId>/roles"
curl -s -X POST "http://localhost:8080/users/<entityId>/verify-credentials" \
     -H 'Content-Type: application/json' -d '{"password":"..."}'
```

If `/health` is `UP` but `/users/...` returns 500, it's almost always the Oracle
connection (wrong `JDBC_URL`/PDB/creds) — check the run console for
`ORA-12170` / `T4CConnection.logon`.

## 7. Fast dev loop

- **Edit → re-run.** Micronaut has no hot-reload here; just stop and re-run the
  Application config (Ctrl-F5 / the ↻ button). Startup is a couple of seconds.
- **Debug** with the bug icon; breakpoints work in the DAOs
  (`EntityDao`, `RoleDao`, `MembershipDao`, `MfaTokenDao`) and `UserController`.
- **Run a single test:** green gutter arrow next to the test method, or
  `gradle test --tests 'com.gw.userservice.dao.EntityDaoTest'`.

## 8. When you're done — how the change ships

IntelliJ runs it as a plain JVM process; production runs the **fat jar in a
container image**. After you're happy with the code, rebuild the image the same
way the stack does:

```bash
# from the repo root — the Dockerfile build context is the repo root
docker compose build --no-cache user-service
docker compose up -d --force-recreate user-service
```

(K8s: `./k8s/up.sh` rebuilds the `keycloak-demo-user-service:latest` image and
rolls the Deployment; `kubectl -n geowealth-demo rollout restart deploy/user-service`
nudges a stuck pod.)

## Common gotchas

- **No gradle wrapper** (`gradlew` doesn't exist). Use IntelliJ's bundled Gradle
  or the system `gradle`; either is fine as long as **Gradle JVM = 17**.
- **Gradle JVM = 21 by default** on this Mac → set it to 17 (step 2).
- **`oracle:1521` unresolvable from the host** → always override `JDBC_URL` in
  the run config (step 4).
- **PDB must match the DB**: `FREEPDB1` for the local/containerized demo Oracle,
  `ORCL12VM` for the external/qa instances. A mismatch gives `ORA-12170`.
- **Don't commit real credentials.** Keep `ORACLE_PASSWORD` in the run config
  only; the demo default `gp/gp123` is for local dev.
