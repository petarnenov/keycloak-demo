# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Survival notes for working in this repo. The **README.md** is the user-facing doc; this file only captures things that matter when editing the code and that you can't reliably infer from a first read.

## What this is

A Keycloak SSO demo with the smallest possible identity tier in front of a per-domain app stack. Each demo "product" lives under `domains/<name>/` with its own React+Vite frontend and its own Micronaut BFF; the two share only a Keycloak realm (`demo-realm`).

Login is **direct authentication against Keycloak** — no SAML federation. Keycloak renders its own login form (the `geowealth` theme) and delegates the credential check to a **User Storage SPI** provider bundled into the custom KC image, which calls the `user-service` (a read-only Oracle DAO). On success KC issues an OIDC code → the `token-handler` exchanges it → SPA session. There are no native users in the realm's local store; users are loaded from `user-service` per login. **P1 is no longer a SAML IdP** (`identityProviders: []`); the whole `kc_idp_hint=p1` / first-broker-login flow was retired in the auth-extraction ("Phase 5"). P1 itself is now just another OIDC Relying Party of this realm. (Detailed current flows: `docs/solution-architect/v2/03-login-flows.md`.)

Current domains:

| Domain | FE host | FE port | BFF service | BFF port | OIDC client |
|---|---|---|---|---|---|
| billing | `billing.geowealth.int` | 5184 | `bff-billing` | 8084 | `demo-billing-client`¹ |
| trading | `trading.geowealth.int` | 5185 | `bff-trading` | 8085 | `demo-trading-client`¹ |
| portfolio | `portfolio.geowealth.int` | 5186 | `bff-portfolio` | 8086 | (shared)¹ |

(Add/remove domains with `./scripts/add-domain.sh <slug>` / `./scripts/remove-domain.sh <slug>` — see "Adding a new domain".)

¹ The per-domain clients still exist in the realm but are **vestigial** — the actual login/OIDC code flow now runs through the **one shared `demo-shared-client`** used by the multi-tenant `token-handler` (see the `token-handler/` entry under Layout). The per-domain client ids only linger in the (inert, forward-auth) data BFFs' bundled config; scaffolded domains (e.g. portfolio) use only the shared client.

Each FE is a small standalone React + Vite + keycloak-js app, packaged into its own image via its own `Dockerfile` (no bind-mount). Each BFF is a standalone Micronaut module with its own `Dockerfile` and its own image.

## SSO role / tenancy model

Design rationale and trade-offs live in **`sso-role-mapping.md`** at the repo root. The shape that ships here:

- **Realm role vocabulary** (`keycloak/realm-export.json#/roles/realm`):
  - Global coarse capabilities: `client`, `advisor`, `admin`.
  - Per-domain capabilities: `billing-admin`, `billing-viewer`, `trading-trader`, `trading-viewer`.
- **Roles come from the database, not SAML.** With no SAML federation, the realm has `identityProviderMappers: []`. Realm roles are served **straight from Oracle** by the User Storage SPI: `UserModel.getRealmRoleMappings()` joins `ENTITY_ROLE_TBL` × `ROLE_TBL` (via `user-service`). The role *names* must exist in `realm-export.json#/roles/realm` so KC recognises them.
- **`firmCd` is a separate claim, not a role**: the User Storage SPI exposes it as a user attribute (from `user-service`'s `/users/{id}/attributes`), and an `oidc-usermodel-attribute-mapper` on the OIDC client (`firm-cd-claim`) emits it as a top-level JWT claim. The BFFs read `authentication.getAttributes().get("firmCd")` for tenant scoping.
- **BFF gating uses `@Secured` with the per-domain capability roles** plus the global escape hatches (`advisor`/`admin` for trading, just `admin` for billing) — see `BillingController.java` / `TradingController.java`. `application.yml` keeps `/api/** -> isAuthenticated()` as a defence-in-depth floor.
- **Adding a new capability role** = add to `realm-export.json#/roles/realm` (so KC knows the name) and grant it in the DB (`ROLE_TBL` / `ENTITY_ROLE_TBL`); on a running stack POST the role via admin API (realm import is `IGNORE_EXISTING`). No IdP mapper needed anymore.

## Layout

- `domains/<name>/web/` — the FE. Standalone npm project (no monorepo), Vite + React + keycloak-js. Builds into its own image. Talks only to its own BFF via Vite preview's `/api/<name>` proxy.
- `domains/<name>/bff/` — the **data** BFF. Per-domain Micronaut module (own Gradle build, own fat-jar, separate image). Holds only domain-specific bits: the `<Domain>Controller` + `DemoAuthz` ObjectType codes + `application.yml`. **Auth-unaware (forward-auth):** the controller reads identity from the `X-Auth-*` headers (`HeaderIdentity`) that nginx injects after the token-handler's `/auth/verify` — it has no `@Secured`, resolves no session, runs no token refresh. Its `/api/**` is `isAnonymous` (nginx gates it). The only authz it runs is the Tier-3 list `refine` (filters its own data rows, via the access token forwarded in a header).
- `bff-core/` — shared BFF library (`io.micronaut.library`, package `demo.bff.core`). The auth/session/logout plumbing: `TokenRefreshFilter`, `AuthController`, `BackchannelLogoutController`, `LogoutTokenValidator`, `KeycloakAuthenticationMapper`, `RotatingSessionLoginHandler`, `SidSessionRegistry`, `P1AuthzClient`, `IdpHintFilter`, the shared `Bff` main, plus `Tier23Gate` / `AuthClaims`. Pulled via a **Gradle composite build** (`includeBuild('../../../bff-core')` for domain BFFs, `../bff-core` for the token-handler) and bundled into each shadow jar. A `bff-core` change is compiled into **every** consumer, so each needs a rebuild — **but** the active auth flow runs in the **token-handler** (see below), so a shared-auth fix is deployed by rebuilding the token-handler image alone; the domain BFFs hold a now-dormant copy. When a domain moves to its own repo, `includeBuild` becomes a registry coordinate (`demo.bff:bff-core:…`).
- `token-handler/` — the **extracted shared auth service** (industry-standard BFF/Token Handler as a separate runtime). It IS `bff-core` with no domain controller (`mainClass = demo.bff.core.Bff`), built into one **generic, env-driven image**. **Multi-tenant: ONE instance fronts EVERY domain** (compose service `token-handler`). It uses **one shared OIDC client `demo-shared-client`** (redirect_uri derived per-host from the proxied request) and **one session cookie `GWSESSION`** — cookies are host-scoped, so `billing.geowealth.int` and `trading.geowealth.int` get distinct cookies under the same name. Per-domain authorization is resolved by the request `Host` from the `app.tenants.*` map in `token-handler/application.yml` (`SubdomainRequirements` / `SubdomainAuthorizer`). **It owns ALL auth: login, callback, `/auth/me`, `/auth/logout`, `/backchannel-logout`, token refresh, Redis sessions, AND the per-request authorization decision (`GET /auth/verify`).** Forward-auth: nginx (in each web container) routes `/(oauth|auth)/` + `/logout` here, and for `/api/<name>` it runs an `auth_request` against `/auth/verify` (forwarding the original host as `X-Forwarded-Host` so the handler picks the right tenant; it validates + refreshes the session, runs coarse + the per-host Tier-2 gate) → on 200 it copies the returned `X-Auth-*` identity onto the upstream request and **drops the session cookie**, then proxies to `bff-<name>`. So **the domain BFFs are fully auth-unaware**: no session, no token refresh, no security filters — they read identity from the `X-Auth-*` headers (`HeaderIdentity`) and only serve data + the Tier-3 list `refine` (which filters the domain's own rows). Net effect: **every shared-auth fix → redeploy the one token-handler image only**; the domain data apps are untouched and users are not logged out (sessions live in Redis). `demo-shared-client`'s `backchannel.logout.url` points at the single `token-handler`. **Adding a domain = one `app.tenants.<slug>` entry + its host on `demo-shared-client`, not a new instance.** See `token-handler-plan.md` and `k8s/README-multitenant-k8s-plan.md`.
- `user-service/` — a small standalone Micronaut service: a **read-only Oracle DAO** behind Keycloak's User Storage SPI (`GET /users/...`, `/users/{id}/attributes`, `/roles`, credential verify). Stateless; horizontally scaled via replicas. This is the identity source that replaced the SAML federation. Its own `Dockerfile` / image.
- `keycloak-providers/` — the custom KC extensions baked into the Keycloak image: `user-storage-spi` (the provider that calls `user-service` for lookup + credential check + roles + `firmCd`) and `email-otp-authenticator`.
- `keycloak/realm-export.json` — `demo-realm` seed: the OIDC clients (`demo-shared-client` — the multi-tenant login client — plus the now-vestigial per-domain `demo-billing-client`/`demo-trading-client` and `p1-client`), the OIDC protocol mappers (`firm-cd-claim`, person-id, memberships, roles), and the `org.keycloak.storage.UserStorageProvider` component pointing at `user-service`. **No `identityProviders` and no SAML mappers** — both empty since auth-extraction.
- `docker-compose.yml` — services: `postgres`, `redis` (shared BFF/token-handler session + sid store, B2), `keycloak` (**custom image `keycloak-demo-keycloak:latest` built from `Dockerfile.keycloak`** = stock KC 26.0.7 + the `keycloak-providers/*` SPIs + `geowealth` theme), `user-service` (User Storage SPI backend), **one** `token-handler` (multi-tenant auth, fronts all domains), and per domain: `demo-<name>` (web) + `bff-<name>` (data).
- `start.sh` / `stop.sh` — canonical entrypoints. Auto-detect docker vs podman, source `.envrc`, tear down (volumes preserved by default), rebuild, bring everything back up. `./start.sh --reset` and `./stop.sh --wipe` are the only opt-in destructive paths.

The P1 sidebar that links into these domains lives **outside** this repo, in `~/geowealth/WebContent/react/app/src/pages/PlatformOne/sidebar/_hooks/useIntegrationLinks.js`. Each domain gets a `links.push(...)` pointing at the domain SPA (which bounces through the `token-handler`'s `/oauth/login/keycloak`). **No `kc_idp_hint`** — there is no IdP to hint at; the flow lands on Keycloak's local login form directly.

## Adding a new domain

**Use the script — it does everything below automatically and idempotently:**

```bash
./scripts/add-domain.sh <slug> [--web-port N --bff-port N --type resource|firm \
       --object-type N --permission N --firm-cd N --cookie X --force --no-hosts --no-deploy]
```

`add-domain.sh <slug>` clones `domains/billing/` into a thin web + thin BFF,
renames every slug-derived token (host `<slug>.geowealth.int`, Java package
`demo.<slug>`, `<Slug>Controller`, client `demo-<slug>-client`, service
`bff-<slug>`, cookie, next-free `518X`/`808X` ports), and wires **every** place a
domain must be registered: `urls.dev.env` `SPA_HOSTS`, the realm
`demo-shared-client` (redirect/webOrigin/post-logout, via `jq`), the
token-handler tenants in **both** `token-handler/application.yml` (compose) **and**
`k8s/base/token-handler-config.yaml` (the K8s ConfigMap), `k8s/base/web-<slug>.yaml`
+ `bff-<slug>.yaml`, the full-stack overlay, `ingress-app.yaml`, `docker-compose.yml`
services, `k8s/portforward.sh`, an mkcert cert, and `/etc/hosts`. Then, unless
`--no-deploy`: if the minikube cluster is **up** it builds the two images into
minikube and applies them live (+ TLS Secret, tenant ConfigMap, token-handler
restart, realm reconcile); if the cluster is **down** the files are wired and the
next `./k8s/up.sh` (now domain-generic) builds + deploys it. `--type firm` swaps
the tenant gate to a `firm-cd` membership check instead of the P1
object-type/permission gate.

**Reverse it with `./scripts/remove-domain.sh <slug>`** — the exact inverse:
strips all of the above (files, config edits, realm, compose, port-forwards,
cert, `/etc/hosts`) and, if the cluster is up, deletes the live Deployments /
Service / Ingress / TLS Secret / minikube images, re-applies the shrunk tenant
ConfigMap, restarts the token-handler and reconciles the realm. `--dry-run`
previews, `--no-deploy` edits files only, `--yes` skips the typed confirmation.

After adding a domain, in `~/geowealth/...useIntegrationLinks.js` push a P1
sidebar link (`kcAuthorize('demo-shared-client', 'https://<slug>.geowealth.int:<port>/', 'demo-<slug>')`)
— that lives outside this repo and the script can't touch it.

---

### What the script does under the hood (manual recipe, for reference)

The shape is fixed; copy `domains/billing/` (or `trading/`) and adapt. Roughly:

1. Pick a slug (`reporting`), a host (`reporting.geowealth.int`), a FE port (next free `518X`), a BFF port (next free `808X`).
2. `cp -r domains/billing domains/reporting` and rename: `package.json#name`, `vite.config.ts` (proxy path, ports, allowedHosts), `src/auth/keycloak.ts` (KC_CLIENT default), Gradle group/rootProject, Java package, controller class, all stub data. **The BFF side is thin now:** keep only `<Domain>Controller` + `DemoAuthz` + `application.yml` — do **not** re-copy the shared auth/session/logout infra (it lives in `bff-core`). Keep the new `settings.gradle`'s `includeBuild('../../../bff-core')` and `build.gradle.kts`'s `implementation("demo.bff:bff-core:…")` + `mainClass = demo.bff.core.Bff`. The Dockerfile builds from the repo-root context so it can `COPY bff-core/` alongside the domain (see `docker-compose.yml` `build.context: .`).
3. **No new OIDC client.** Multi-tenant: add the new host to `demo-shared-client`'s `redirectUris` / `webOrigins` / `post.logout.redirect.uris` in `keycloak/realm-export.json` (and on the live realm via admin API — imports are `IGNORE_EXISTING`), then add one `app.tenants.<slug>` entry (host + the per-host gate) to `token-handler/application.yml`. In the new web's `nginx.conf`, point `/(oauth|auth)/` + `/logout` + the `/th-verify` subrequest at `token-handler:8080` and forward `X-Forwarded-Host` on `/th-verify` (copy from billing's `nginx.conf`).
4. Add `demo-<name>` and `bff-<name>` services to `docker-compose.yml` (no per-domain token-handler — the one `token-handler` already fronts it). Rebuild + restart `token-handler` to pick up the new tenant.
5. Add `/etc/hosts` entry `127.0.0.1 <name>.geowealth.int` and generate an mkcert cert pair at `proxy/certs/<name>.geowealth.int.{crt,key}`.
6. In `~/geowealth/...useIntegrationLinks.js`, push a link with `kcAuthorize('demo-shared-client', 'https://<name>.geowealth.int:<port>/', 'demo-<name>')`.

## Environment URL configuration (K8s)

Every environment-specific URL/port the identity tier uses lives in **one file per
environment**: `k8s/env/urls.{dev,qa,prod}.env`. Swap the file to retarget — no
code, no image rebuild, no `realm-export.json` edit. Two consumers, both driven by
that file:

- **token-handler env** — the full-stack overlay generates the `app-urls`
  ConfigMap from `urls.dev.env` and the token-handler pulls `KEYCLOAK_ISSUER` /
  `KEYCLOAK_AUTH_SERVER_URL` / `P1_AUTHZ_URL` / `APP_P1_INITIATE_SLO_URL` from it
  via `valueFrom: configMapKeyRef` (with `value: null` to drop the base literals).
  (The data BFFs are forward-auth/auth-unaware, so their KC env is inert and left
  hardcoded.)
- **the realm** — `scripts/reconcile-realm.sh <env-file>` PATCHes the live realm
  via the admin API: every active client's (`demo-shared-client`, `p1-client`)
  redirectUris / webOrigins / post-logout / back-channel URLs. **It no longer
  touches any `identityProviders` block — there are none** (SAML retired). This
  exists because realm config is data in Postgres and `--import-realm` is
  `IGNORE_EXISTING` — env vars alone never re-drive a running realm. Idempotent;
  `up.sh` runs it after bring-up (via a short-lived in-cluster admin port-forward),
  so it fixes BOTH a fresh install and any drift. **Single-valued per-env fields
  can't list both compose `:8888` and K8s `:8080` — always change `urls.<env>.env`
  + reconcile, never the live realm by hand.**

`up.sh` also injects a hostAlias mapping the browser-facing `auth.geowealth.int`
to the in-cluster `kc-ext` Service so the token-handler can do OIDC discovery
against the public issuer from inside the pod (the issuer host is otherwise
unresolvable in-cluster). The `kc-ext` ClusterIP is only known at deploy time, so
this is a runtime patch, not a static manifest value.

## Data-tier endpoint config (K8s)

Where each consumer in the cluster reaches **Oracle / Elasticsearch** is also
one file per environment: `k8s/env/data-tier.{dev,qa,prod}.env`
(`DATA_TIER_ENV=` overrides the path). Each entry is either the in-cluster
Service name (default) or an external DNS host / IP. Consumers always use the
bare name (`oracle:1521`, `elasticsearch:9200`) — only what that name
resolves to in cluster DNS changes.

`up.sh` reads the file and, per service:

- **In-cluster mode** (`<SVC>_HOST` equals the Service name): no-op. The
  kustomize-applied `ClusterIP` Service + StatefulSet / Deployment is the
  canonical default. (If a previous run had redirected the Service to
  `ExternalName`, `data_tier_pre_align` deletes that stale Service BEFORE the
  apply so the apply can re-create the default — `Service.spec.type` is
  immutable, so an in-place apply over an `ExternalName` Service would fail.)
- **External mode** (`<SVC>_HOST` differs): AFTER the apply, `data_tier_redirect`
  scales the in-cluster workload to 0 replicas, deletes the freshly-applied
  `ClusterIP` Service, and re-applies it as `type: ExternalName,
  externalName: <HOST>`.

`ExternalName` doesn't remap ports — the external host MUST listen on the same
port the in-cluster Service exposed (1521 / 9200 / 11211). If it can't, NAT or
port-forward on the external side, or run a small in-cluster TCP proxy.

**External = assumed already populated.** This cluster will NOT seed anything
into an external host. When `ORACLE_HOST` is off-cluster the in-cluster
StatefulSet is scaled to 0 (so the seeded image's `restore-oradata.sh` doesn't
run) and the old `oracle-seed` Flyway Job is gone, so neither path touches the
external Oracle. The BFFs and P1 are read/write consumers, not migrators.
Same model for `ELASTICSEARCH_HOST` (no auto-reindex). If you need to
provision the external first, the simplest path
for Oracle is `docker run keycloak-demo-db:seeded` (built from
`db/Dockerfile`) on the target host; for Elasticsearch, run the GeoWealth
`RefreshClientSearcherTool` against it once.

**Oracle JDBC coordinates beyond `_HOST`.** A real external Oracle almost
always uses a different SERVICE_NAME (PDB) and password than the baked image:

- `ORACLE_PDB` in the env file overrides the JDBC SERVICE_NAME (default
  `FREEPDB1`, common external value `ORCL12VM`). P1's `hibernate.properties.tpl`
  and `akka.conf.tpl` substitute it into `jdbc:oracle:thin:@//${ORACLE_HOST}:${ORACLE_PORT}/${ORACLE_PDB}`.
- `ORACLE_USER` (default `gp`) — same template substitution.
- `ORACLE_PASSWORD` is a **credential** and is NEVER stored in the env file.
  Provide it on the shell: `ORACLE_PASSWORD='real-pw' ./k8s/up.sh`. Absent →
  no `oracle-creds` Secret is applied and P1's entrypoint falls back to its
  baked-image default (`gp123`), which won't authenticate against most
  external instances. The standard local-dev creds are `gp/gp123` and live in
  `~/AppServer/setup.sh` / `~/AppServer/geowealth/etc/dev-petar-akka.conf`.

**Consumers auto-restart on data-tier env change.** envFrom ConfigMaps and
Secrets DON'T auto-restart pods when their values change. Without help, a
`data-tier.<env>.env` swap would land in `oracle-config` / `oracle-creds` but
the running P1 pods would keep the old env until they happened to restart for
another reason. `up.sh` guards this: it snapshots `cm/oracle-config.data` +
`secret/oracle-creds.data` + `svc/oracle.{externalName,clusterIP}` before and
after the apply, and if anything changed AND the consumer workloads
pre-existed, it `kubectl rollout restart`s the eleven Oracle-consuming P1
workloads (`p1-tomcat`, `p1-coordinator`, all nine agents) BEFORE the
wave-wait so wait sees the new pods. On a fresh install (consumers don't
exist yet) the restart is skipped. On an idempotent re-run with no change
the hashes match and nothing rolls. **Beware:** any `kubectl port-forward
svc/p1-tomcat 8080:8080` you had running will die with the old pod —
re-establish it after the rollout (`pkill -f 'port-forward.*p1-tomcat'` then
re-run the command in section 6 of `k8s/README-ubuntu-bringup.md`).

**Agents are heap-sized for the baked PDB, not real data.** The biggest
landmine when switching to a populated external Oracle is `p1-devcommonagents`:
its `JAVA_OPTS_EXTRA` defaults to `-Xmx4G` (with container limit `5Gi`) in
`k8s/base/p1.yaml`, which is the bumped value AFTER hitting `OutOfMemoryError`
during `CrntCostBasisLoader.<init>` against a real `CostBasisAccount` table
(it pre-loads the whole table into an in-memory cache at boot). The earlier
`1536m` value was tuned for the near-empty baked PDB only. **User-facing
symptom of an agent OOM:** the SPA at `localhost:8080/` (or any P1 page)
renders blank because `AuthorizationManager` lives on `devcommonagents` →
the agent CrashLoopBackOffs → `IdentifyFirmByUrlMsg` from `p1-tomcat` goes
to dead letters → the action hangs and Tomcat times the request out.
Diagnose with `kubectl -n geowealth-demo get pods | grep p1-devcommonagents`
(non-zero RESTARTS) and the `--previous` log (look for `OutOfMemoryError`
under `DistributedCacheController.<init>` → `CrntCostBasisLoader`). If any
other agent OOMs on the same DB swap, bump its `JAVA_OPTS_EXTRA` + container
`limits.memory` proportionally; the OOM signature is always `<TraitName>Loader.<init>`
followed by heap exhaustion in the boot path. Boot against a real DB also
takes longer (~2 min for `devcommonagents` cache pre-load) — wait for
`Looks like we are UP to the cluster` in its logs before probing the page.

**Switching in-cluster ↔ external — the recipe + the gotchas.** Edit
`k8s/env/data-tier.dev.env` and re-run `./k8s/up.sh` — that's the whole switch;
everything else is derived. **The dev default is now in-cluster** (`ORACLE_HOST=oracle`,
`ORACLE_PDB=FREEPDB1` — the baked seeded image), so a fresh clone is self-contained.

- **In-cluster:** `ORACLE_HOST=oracle`, `ORACLE_PDB=FREEPDB1`. `up.sh` runs the
  in-cluster seeded StatefulSet; creds fall back to the baked `gp/gp123`.
- **External:** `ORACLE_HOST=<host-or-ip>`, `ORACLE_PDB=<its PDB, e.g. ORCL12VM>`,
  and `ORACLE_PASSWORD='…' ./k8s/up.sh`. **The PDB must match the mode** — an
  in-cluster `ORCL12VM` or an external `FREEPDB1` won't connect.
- **PDB/host mismatch symptom:** `user-service` (and P1) `CrashLoopBackOff` with
  `ORA-12170` / `T4CConnection.logon`. After a mode switch, if a consumer keeps
  crashlooping on the *old* endpoint, `kubectl -n geowealth-demo rollout restart
  deploy/user-service` (up.sh restarts the P1 consumers automatically but a
  stuck pod may need a manual nudge). Verify with the pod's `/health` → `{"status":"UP"}`.
- **External by IP** (not DNS): the `oracle` Service becomes a **ClusterIP with a
  manual EndpointSlice** pointing at the IP, NOT `ExternalName` — CoreDNS rejects
  an IP literal in `externalName`. (`up.sh` picks the right form.)
- **minikube can't reach a LAN host the *host* can't.** An external
  `192.168.1.42` must be reachable from inside the minikube node, not just from
  your machine — test `minikube -p geowealth ssh -- nc -z <ip> <port>`. If the
  box is off/unroutable you'll see `ORA-12170` timeouts even though the wiring is
  correct; the fix is the DB host, not the cluster.
- **Run-profiles are tied to the data-tier mode they were saved under.** A
  profile saved in external mode carries `statefulset/oracle 0`; applying it (or
  `up.sh --profile <it>`) after switching to in-cluster Oracle keeps oracle at 0
  → login breaks because `user-service` has no DB. Re-save the profile (or edit
  the `oracle` line to `1`) after switching modes.

## Dev: selective bringup — `toggle.sh` + run-profiles

On a laptop you rarely need the whole stack. `k8s/toggle.sh` scales groups of
workloads up/down without a redeploy; **run-profiles** freeze a chosen shape so
`up.sh` can reproduce it. Full detail in
[`k8s/README-dev-selective-bringup.md`](k8s/README-dev-selective-bringup.md).

```bash
./k8s/toggle.sh status              # replicas + HPA per workload
./k8s/toggle.sh <group> up|down     # scale a group
./k8s/toggle.sh save <name>         # snapshot current cluster → k8s/profiles/<name>.profile
./k8s/toggle.sh apply <name>        # scale back to a saved profile
./k8s/up.sh --profile <name>        # bring up ONLY that profile's workloads (0 for the rest)
```

- **Groups:** `p1` (coordinator + tomcat + agents), `agents`, `authz-min`
  (tomcat + coordinator + devcommonagents = the domain `/api` gate), `identity`,
  `data`, and **live-discovered domains** — bare `<slug>`, `web:<slug>`,
  `bff:<slug>`, `agent:<name>`. Domains are read from the cluster (`web-*`/`bff-*`
  Deployments), so a new domain needs no script edit.
- **Login needs zero P1** (P1 is not in the auth path since Phase 5 — see
  `README-dev-selective-bringup.md`): scale the whole `legacy` tier to 0 and you
  can still log into the domains; P1 only re-enters for the domain `/api` Tier-2
  gate and the legacy P1 UI. Biggest laptop-RAM win.
- **HPA caveat:** `token-handler` / `user-service` / `p1-tomcat` have HPAs and
  stock K8s rejects `minReplicas: 0`, so "off" **deletes the HPA** and scales to
  0; `up.sh` re-creates it. toggle/profiles handle this for you.
- **Port-forwards auto-start.** `k8s/portforward.sh` (idempotent, persistent
  nohup forwards for Keycloak `:5180`, the domain SPAs/BFFs, P1, redis/oracle/ES,
  and the monitoring UIs) is now run automatically wherever the stack is brought
  up: at the end of `./k8s/up.sh` (incl. `--profile`), after `toggle.sh <group> up`
  and `toggle.sh apply`, and after `add-domain.sh` deploys a domain live;
  `remove-domain.sh` stops the removed domain's forwards. It runs **last** (after
  every rollout/restart) so nothing kills the forwards. Opt out with
  `SKIP_PORTFORWARD=1`. Re-run `./k8s/portforward.sh` any time a forward flakes
  (e.g. a pod restarted out from under it).
- **Profiles are local state** (`k8s/profiles/*.profile`, gitignored) and are
  tied to the data-tier mode they were saved under (e.g. a profile saved with
  external Oracle carries `oracle 0`; don't `apply` it after switching to
  in-cluster Oracle). `up.sh --profile` applies the full overlay first (every
  object exists) then scales to the profile, and the wave-wait skips anything at
  0.

## Persistence model — what survives a restart

| Lives in | Persists across | Wiped only by |
|---|---|---|
| Keycloak realm config, sessions/tokens, live admin-API edits | `docker compose restart`, `docker compose down` + `up`, `./start.sh`, `./stop.sh` then `./start.sh`, image rebuild + `--force-recreate` | `docker compose down -v`, **`./start.sh --reset`**, or **`./stop.sh --wipe`** |
| Postgres data backing all of the above | same | same |
| `keycloak/data` (import sources, KeyStore, exported state) | same | same |

Practical consequences:

- **Users are NOT stored in Keycloak's Postgres.** With the User Storage SPI, KC holds no native/federated user records — every login re-reads the user from `user-service` (Oracle), caching per the SPI's eviction policy. So "who exists" survives because it lives in Oracle, not KC. What KC's Postgres persists is realm config + live sessions/tokens (which is why `down` without `-v` keeps you logged in).
- **`./start.sh` and `./stop.sh` are non-destructive.** Both run `down` (without `-v`) so the Postgres volume stays in place.

## Non-obvious runtime gotchas

- **Realm imports are `IGNORE_EXISTING`.** `--import-realm` only seeds an empty Postgres. To apply a `realm-export.json` edit on a running stack, either `./start.sh --reset` (destructive) or change the live realm via admin API (fast, preserves live sessions).
- **The `p1` IdP `signingCertificate` is baked into `realm-export.json`.** It must match the certificate P1's Tomcat presents when signing SAML Responses. If the dev keystore is regenerated on the P1 side, paste the new cert (DER, base64) into the realm export and either reset or update via admin API.
- **P1's SAML signing keystore lives at `/tmp/p1-idp-dev.p12`** (Tomcat env `P1_IDP_KEYSTORE_PATH`). macOS wipes `/tmp` on reboot. Recovery is one script: `./scripts/sso-dev-keystore.sh` regenerates the PKCS#12 at the same path **and** rotates the public cert in the live realm's `p1` IdP via admin API in one go. The previous IdP config is backed up to `/tmp/kc-p1-idp.before-rotation.json` for rollback. **B5 fix:** `AbstractSamlAuthenticationResponseBuilder` now caches the signing `Credential` and reloads only when the keystore file's **mtime changes** (no longer a per-request disk read). So hot rotation still needs no Tomcat restart (rewriting the file → mtime change → reload), and once the credential is loaded a `/tmp` wipe **no longer** breaks signing mid-run — it keeps using the cached key until the next restart. (A wipe before the first signature still 500s; run the recovery script to repopulate the file.)
- **All BFFs run as baked fat jars.** Each domain BFF is built once into its own image (`<domain>/bff/Dockerfile`) using the `com.gradleup.shadow` plugin → `eclipse-temurin:17-jre` base. PID 1 is `java -jar /app/<svc>.jar`; no source tree, no Gradle cache, no bind mount. A source edit needs a `--build --force-recreate`.
- **Shadow plugin is declared explicitly in every BFF's `build.gradle.kts`.** Micronaut 4.4's application plugin doesn't register `shadowJar` on its own — each BFF adds `id("com.gradleup.shadow") version "8.3.5"` so `gradle shadowJar` produces `build/libs/*-all.jar` for the Dockerfile to copy.
- **Keycloak runs a CUSTOM image** (`keycloak-demo-keycloak:latest`, built from `Dockerfile.keycloak` = stock KC 26.0.7 + the `keycloak-providers/*` extensions + `geowealth` theme). A change to `user-storage-spi`, `email-otp-authenticator`, or the theme needs a KC image rebuild (`docker compose build keycloak` / `./k8s/up.sh` rebuilds it), not just a realm edit.
- **Keycloak's admin API is at `https://auth.geowealth.int:5180`.** Get a token at `/realms/master/protocol/openid-connect/token` with `client_id=admin-cli&grant_type=password&username=admin&password=admin`. Admin endpoints live under `/admin/realms/demo-realm/...`. The `auth` nginx terminates TLS and proxies to `keycloak:8080` inside the docker network — Keycloak itself is no longer exposed on the host. `KC_HOSTNAME=https://auth.geowealth.int:5180` makes Keycloak emit consistent issuer / broker URIs.
- **`keycloak-js` must be ≥ 26.x.** Older versions validate a `nonce` claim that Keycloak 26 no longer emits.
- **Domain hosts need HTTPS.** keycloak-js v26 uses `crypto.subtle`, which the browser only exposes in secure contexts: HTTPS, or the loopback hostnames `localhost`/`127.0.0.1`. A custom hostname like `billing.geowealth.int` resolves to 127.0.0.1 via `/etc/hosts`, but the browser classifies secure-context by hostname literal, not resolved IP — so plain `http://billing.geowealth.int:5184` is NOT secure and `crypto.subtle` is `undefined` there. Each domain serves HTTPS via Vite preview with mkcert-issued certs at `proxy/certs/<name>.geowealth.int.{crt,key}`. Generate with `mkcert -cert-file proxy/certs/<name>.geowealth.int.crt -key-file proxy/certs/<name>.geowealth.int.key <name>.geowealth.int localhost 127.0.0.1`.
- **The SAML "establish round-trip" (Gap 6) is RETIRED.** It used to mint a KC session after P1 credential login by driving `kc_idp_hint=p1`; with no SAML IdP that flow is gone. P1 is now an OIDC RP and gets its KC session by the normal OIDC code flow. Ignore any lingering `/saml/idp/silent-sso.do` / `SilentSsoAction` references — they map only Tier-2/3 authz endpoints now.
- **BFF `/auth/logout` is idempotent** (`bff-core/AuthController.java`): `@Secured(IS_ANONYMOUS)` with `@Nullable Authentication`. A double-click, an already-expired session, or a back-channel race no longer surfaces as `401 Unauthorized` — the controller does best-effort KC end-session + session delete + SID invalidate + `303 See Other` to the P1 SLO redirect regardless of caller auth state. Both BFFs need a rebuild for the change to land (composite build bundles `bff-core` per image).

## Login flow (current — no SAML)

The SAML federation to P1 was retired in the auth-extraction. Current flow:

1. The SPA hits a protected route; nginx forward-auth / `token-handler` finds no `GWSESSION` and redirects to `token-handler` `/oauth/login/keycloak` (a silent `?silent=1` attempt first, then interactive).
2. `token-handler` starts an OIDC authorization-code + PKCE flow against `demo-shared-client`. **No `kc_idp_hint`** — Keycloak renders its own login form (the `geowealth` theme).
3. User submits username + password. Keycloak's auth flow calls the **User Storage SPI**, which calls `user-service` for the lookup + credential check (+ roles + `firmCd`). No native realm users, no SAML, no first-broker-login.
4. On success KC issues an OIDC code; `token-handler` exchanges it (at the callback), mints the `GWSESSION` (Redis-backed), and the SPA renders.
5. `/api/**` calls go through nginx `auth_request` → `token-handler` `/auth/verify` (coarse + per-host Tier-2 gate) → `X-Auth-*` headers onto the auth-unaware data BFF.

Full detail + logout/back-channel + P1's own OIDC RP flow: `docs/solution-architect/v2/03-login-flows.md` and `04-logout-flows.md`.

## Applying changes — what needs what

| Change | Minimum action |
|---|---|
| `domains/<name>/web/` source | `podman compose up -d --build --force-recreate demo-<name>` — its own image (`keycloak-demo-<name>-web`) built by `domains/<name>/web/Dockerfile`. No bind-mount, so a source edit needs the full build. |
| `domains/<name>/bff/` source | `podman compose up -d --build --force-recreate bff-<name>` — its own image (`keycloak-demo-<name>-bff`). |
| `bff-core/` **auth** source (login/callback/`/auth/*`/`/auth/verify`/logout/backchannel/token-refresh/session/`SubdomainAuthorizer`) | **Token-handler ONLY** — `docker compose build --no-cache token-handler && docker compose up -d --force-recreate token-handler` (one multi-tenant instance for all domains). Forward-auth: the data BFFs run NO auth, so the fix is live the moment the token-handler restarts; **`bff-billing`/`bff-trading` are NOT rebuilt and users are not logged out** (sessions in Redis). |
| `bff-core/` source the **data BFF still runs** (`Tier23Gate`/`P1AuthzClient` = Tier-3 `refine`, `AuthClaims`, `HeaderIdentity`) | rebuild the data BFFs too — `docker compose build --no-cache bff-billing bff-trading && docker compose up -d --force-recreate bff-billing bff-trading`. (Only the data-row `refine` mechanism lives here now.) |
| (either of the above) | **Gotcha: the Docker `COPY bff-core/` layer can cache-hit even after you edit a `bff-core` file, so plain `--build` silently ships a stale jar.** Symptom: the running container behaves like your edit isn't there. Always use `--no-cache` for bff-core changes, or verify with `docker cp <svc>:/app/*.jar … && javap -c …`. |
| Add a new domain | `./scripts/add-domain.sh <slug>` (scaffolds + wires + deploys). Remove with `./scripts/remove-domain.sh <slug>`. See "Adding a new domain". |
| Bring up the whole K8s stack | `./k8s/up.sh` (data + identity + P1, in ordered waves). |
| Bring up only part of the K8s stack | `./k8s/up.sh --profile <name>` — applies the full overlay then scales to the saved profile (`k8s/profiles/<name>.profile`, from `toggle.sh save`); everything the profile excludes is scaled to 0 and the wave-wait skips it. Fails fast on an unknown profile. See "Dev: selective bringup". |
| Env var on a service | `podman compose up -d --force-recreate <service>` |
| `docker-compose.yml` structural change | `podman compose up -d` (compose picks up the diff) |
| `keycloak/realm-export.json` | takes effect on fresh DB only; otherwise patch the live realm via admin API. For env-specific URLs (client redirect/web-origin/post-logout/back-channel), edit `k8s/env/urls.<env>.env` and run `./scripts/reconcile-realm.sh k8s/env/urls.<env>.env` (idempotent; `up.sh` runs it automatically). |
| A URL or port that differs per environment (K8s) | edit `k8s/env/urls.<env>.env` (the single source) — see "Environment URL configuration (K8s)" below. No code / image / realm-export edit. |
| Pointing K8s at an external Oracle / Elasticsearch (different machine) | edit `k8s/env/data-tier.<env>.env` and re-run `./k8s/up.sh` — see "Data-tier endpoint config (K8s)" below. |
| New realm role needed for a running demo | POST `/admin/realms/demo-realm/roles` with admin token; also add to `realm-export.json` for future fresh installs |
| New OIDC client needed for a running demo | POST `/admin/realms/demo-realm/clients` with admin token; also add to `realm-export.json` |
| P1/Integrations link source (`~/geowealth/.../useIntegrationLinks.js`) | rebuild the GeoWealth Tomcat app — outside this repo. |

## Running this on macOS + Podman

`./start.sh` is the normal way in — it auto-detects docker or podman and handles the macOS podman quirks below. Force one or the other with `CONTAINER_ENGINE=docker|podman ./start.sh`.

If you're using `podman compose` directly, two things to know:

1. **`DOCKER_HOST` socket path.** The compose CLI talks through a Docker-style socket; Podman uses a different socket path than the machine-default claims:

   ```bash
   export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
   ```

   The `.envrc` has a version of this, but if `podman compose` errors with "Cannot connect to the Docker daemon," export the path directly.

2. **`depends_on: condition: service_healthy` is ignored.** `podman compose` doesn't honor compose healthcheck gating, so a naive `podman compose up -d` starts `keycloak` before postgres is reachable. `start.sh` works around this by `up -d`-ing postgres first, polling its health, then bringing keycloak up, then everything else. If you skip `start.sh`, replicate the sequence by hand or wait long enough.

## Commit style

Recent commits use plain prose bodies, no trailers other than `Co-Authored-By: Claude ...`. Prefer one commit per logical change; match the existing style.

## Language: English for everything that lands in the repo

All persistent artifacts must be in English: markdown docs, code comments, identifiers, file names, commit messages. Conversational replies in chat can mirror whatever language the user is writing in (often Bulgarian), but the moment something is written to disk and committed it must be English. The repo is a public-shareable demo, and contributors / future readers won't necessarily read Bulgarian.

When the user dictates content in Bulgarian and asks for it to be saved, translate as you write rather than transcribing. Filenames stay in kebab-case English.
