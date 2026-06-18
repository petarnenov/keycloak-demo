# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Survival notes for working in this repo. The **README.md** is the user-facing doc; this file only captures things that matter when editing the code and that you can't reliably infer from a first read.

## What this is

A Keycloak SSO demo with the smallest possible identity tier in front of a per-domain app stack. Each demo "product" lives under `domains/<name>/` with its own React+Vite frontend and its own Micronaut BFF; the two share only a Keycloak realm (`demo-realm`) and the SAML federation to P1.

There are no native users in this realm. Every login is brokered through `kc_idp_hint=p1` → SAML to P1 → first-broker-login auto-link by email → OIDC code → SPA token. Direct login on Keycloak's login screen and direct-grant against `demo-realm` are both effectively broken — there's nothing in the realm's local user store to authenticate against. That's intentional: the demo is about SAML federation, not local auth.

Current domains:

| Domain | FE host | FE port | BFF service | BFF port | OIDC client |
|---|---|---|---|---|---|
| billing | `billing.geowealth.int` | 5184 | `bff-billing` | 8084 | `demo-billing-client`¹ |
| trading | `trading.geowealth.int` | 5185 | `bff-trading` | 8085 | `demo-trading-client`¹ |

¹ The per-domain clients still exist in the realm but are **vestigial** — the actual login/OIDC code flow now runs through the **one shared `demo-shared-client`** used by the multi-tenant `token-handler` (see the `token-handler/` entry under Layout). The per-domain client ids only linger in the (inert, forward-auth) data BFFs' bundled config.

Each FE is a small standalone React + Vite + keycloak-js app, packaged into its own image via its own `Dockerfile` (no bind-mount). Each BFF is a standalone Micronaut module with its own `Dockerfile` and its own image.

## SSO role / tenancy model

Design rationale and trade-offs live in **`sso-role-mapping.md`** at the repo root. The shape that ships here:

- **Realm role vocabulary** (`keycloak/realm-export.json#/roles/realm`):
  - Global coarse capabilities: `client`, `advisor`, `admin`.
  - Per-domain capabilities: `billing-admin`, `billing-viewer`, `trading-trader`, `trading-viewer`.
- **`saml-role-idp-mapper` × 8** under `identityProviderMappers` (all `syncMode=FORCE`): one per role name above, value-mapped from the `roles` SAML attribute, plus one legacy transition mapper `user` → `advisor` so the current P1 `derivePocRoles` POC keeps working until P1 ships the data-driven replacement.
- **`firmCd` is a separate claim, not a role**: `saml-user-attribute-idp-mapper` (existing) writes it as a user attribute; an `oidc-usermodel-attribute-mapper` on each OIDC client (`firm-cd-claim`) emits it as a top-level JWT claim. The BFFs read `authentication.getAttributes().get("firmCd")` for tenant scoping.
- **BFF gating uses `@Secured` with the per-domain capability roles** plus the global escape hatches (`advisor`/`admin` for trading, just `admin` for billing) — see `BillingController.java` / `TradingController.java`. `application.yml` keeps `/api/** -> isAuthenticated()` as a defence-in-depth floor.
- **Adding a new capability role** = add to `realm-export.json#/roles/realm`, add a matching `saml-role-idp-mapper` (value → role, FORCE), and on a running stack POST the role + mapper via admin API (realm import is `IGNORE_EXISTING`).

## Layout

- `domains/<name>/web/` — the FE. Standalone npm project (no monorepo), Vite + React + keycloak-js. Builds into its own image. Talks only to its own BFF via Vite preview's `/api/<name>` proxy.
- `domains/<name>/bff/` — the **data** BFF. Per-domain Micronaut module (own Gradle build, own fat-jar, separate image). Holds only domain-specific bits: the `<Domain>Controller` + `DemoAuthz` ObjectType codes + `application.yml`. **Auth-unaware (forward-auth):** the controller reads identity from the `X-Auth-*` headers (`HeaderIdentity`) that nginx injects after the token-handler's `/auth/verify` — it has no `@Secured`, resolves no session, runs no token refresh. Its `/api/**` is `isAnonymous` (nginx gates it). The only authz it runs is the Tier-3 list `refine` (filters its own data rows, via the access token forwarded in a header).
- `bff-core/` — shared BFF library (`io.micronaut.library`, package `demo.bff.core`). The auth/session/logout plumbing: `TokenRefreshFilter`, `AuthController`, `BackchannelLogoutController`, `LogoutTokenValidator`, `KeycloakAuthenticationMapper`, `RotatingSessionLoginHandler`, `SidSessionRegistry`, `P1AuthzClient`, `IdpHintFilter`, the shared `Bff` main, plus `Tier23Gate` / `AuthClaims`. Pulled via a **Gradle composite build** (`includeBuild('../../../bff-core')` for domain BFFs, `../bff-core` for the token-handler) and bundled into each shadow jar. A `bff-core` change is compiled into **every** consumer, so each needs a rebuild — **but** the active auth flow runs in the **token-handler** (see below), so a shared-auth fix is deployed by rebuilding the token-handler image alone; the domain BFFs hold a now-dormant copy. When a domain moves to its own repo, `includeBuild` becomes a registry coordinate (`demo.bff:bff-core:…`).
- `token-handler/` — the **extracted shared auth service** (industry-standard BFF/Token Handler as a separate runtime). It IS `bff-core` with no domain controller (`mainClass = demo.bff.core.Bff`), built into one **generic, env-driven image**. **Multi-tenant: ONE instance fronts EVERY domain** (compose service `token-handler`). It uses **one shared OIDC client `demo-shared-client`** (redirect_uri derived per-host from the proxied request) and **one session cookie `GWSESSION`** — cookies are host-scoped, so `billing.geowealth.int` and `trading.geowealth.int` get distinct cookies under the same name. Per-domain authorization is resolved by the request `Host` from the `app.tenants.*` map in `token-handler/application.yml` (`SubdomainRequirements` / `SubdomainAuthorizer`). **It owns ALL auth: login, callback, `/auth/me`, `/auth/logout`, `/backchannel-logout`, token refresh, Redis sessions, AND the per-request authorization decision (`GET /auth/verify`).** Forward-auth: nginx (in each web container) routes `/(oauth|auth)/` + `/logout` here, and for `/api/<name>` it runs an `auth_request` against `/auth/verify` (forwarding the original host as `X-Forwarded-Host` so the handler picks the right tenant; it validates + refreshes the session, runs coarse + the per-host Tier-2 gate) → on 200 it copies the returned `X-Auth-*` identity onto the upstream request and **drops the session cookie**, then proxies to `bff-<name>`. So **the domain BFFs are fully auth-unaware**: no session, no token refresh, no security filters — they read identity from the `X-Auth-*` headers (`HeaderIdentity`) and only serve data + the Tier-3 list `refine` (which filters the domain's own rows). Net effect: **every shared-auth fix → redeploy the one token-handler image only**; the domain data apps are untouched and users are not logged out (sessions live in Redis). `demo-shared-client`'s `backchannel.logout.url` points at the single `token-handler`. **Adding a domain = one `app.tenants.<slug>` entry + its host on `demo-shared-client`, not a new instance.** See `token-handler-plan.md` and `k8s/README-multitenant-k8s-plan.md`.
- `keycloak/realm-export.json` — `demo-realm` seed: the OIDC clients (`demo-shared-client` — the multi-tenant login client — plus the now-vestigial per-domain `demo-billing-client`/`demo-trading-client` and `p1-self-client`), the `p1` SAML IdP (with embedded signing certificate), the SAML attribute mappers (email/firstName/lastName/firmCd/roles), the `p1-first-broker-login` flow.
- `docker-compose.yml` — services: `postgres`, `redis` (shared BFF/token-handler session + sid store, B2), `keycloak` (stock `quay.io/keycloak/keycloak:26.0.7`), **one** `token-handler` (multi-tenant auth, fronts all domains), and per domain: `demo-<name>` (web) + `bff-<name>` (data).
- `start.sh` / `stop.sh` — canonical entrypoints. Auto-detect docker vs podman, source `.envrc`, tear down (volumes preserved by default), rebuild, bring everything back up. `./start.sh --reset` and `./stop.sh --wipe` are the only opt-in destructive paths.

The P1 sidebar that links into these domains lives **outside** this repo, in `~/geowealth/WebContent/react/app/src/pages/PlatformOne/sidebar/_hooks/useIntegrationLinks.js`. Each domain gets a `links.push(...)` with the Keycloak authorize URL + `kc_idp_hint=p1` + `redirect_uri` pointing at the domain SPA.

## Adding a new domain

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
  via the admin API: the `p1` SAML IdP `singleSignOn/singleLogoutServiceUrl` and
  every active client's (`demo-shared-client`, `p1-self-client`) redirectUris /
  webOrigins / post-logout / back-channel URLs. This exists because realm config
  is data in Postgres and `--import-realm` is `IGNORE_EXISTING` — env vars alone
  never re-drive a running realm. Idempotent; `up.sh` runs it after bring-up
  (via a short-lived in-cluster admin port-forward), so it fixes BOTH a fresh
  install and any drift. **Single-valued realm fields like the IdP SAML URL can't
  list both compose `:8888` and K8s `:8080`, which is exactly why hand-patching
  them used to drift — always change `urls.<env>.env` + reconcile, never the live
  realm by hand.**

`up.sh` also injects a hostAlias mapping the browser-facing `auth.geowealth.int`
to the in-cluster `kc-ext` Service so the token-handler can do OIDC discovery
against the public issuer from inside the pod (the issuer host is otherwise
unresolvable in-cluster). The `kc-ext` ClusterIP is only known at deploy time, so
this is a runtime patch, not a static manifest value.

## Data-tier endpoint config (K8s)

Where each consumer in the cluster reaches **Oracle / Elasticsearch / Memcached**
is also one file per environment: `k8s/env/data-tier.{dev,qa,prod}.env`
(`DATA_TIER_ENV=` overrides the path). Each entry is either the in-cluster
Service name (default) or an external DNS host / IP. Consumers always use the
bare name (`oracle:1521`, `elasticsearch:9200`, `memcached:11211`) — only what
that name resolves to in cluster DNS changes.

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
Same model for `ELASTICSEARCH_HOST` (no auto-reindex) and `MEMCACHED_HOST`
(stateless). If you need to provision the external first, the simplest path
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

## Persistence model — what survives a restart

| Lives in | Persists across | Wiped only by |
|---|---|---|
| Keycloak realms, federated identities, sessions, live admin-API edits | `docker compose restart`, `docker compose down` + `up`, `./start.sh`, `./stop.sh` then `./start.sh`, image rebuild + `--force-recreate` | `docker compose down -v`, **`./start.sh --reset`**, or **`./stop.sh --wipe`** |
| Postgres data backing all of the above | same | same |
| `keycloak/data` (import sources, KeyStore, exported state) | same | same |

Practical consequences:

- **A SAML-brokered login through P1 writes a federated identity to Keycloak's Postgres on first sign-in.** That identity survives every routine `restart` / `down+up` / image rebuild. The next time the same P1 user lands on the broker flow, Keycloak finds the existing record and skips first-broker-login.
- **`./start.sh` and `./stop.sh` are non-destructive.** Both run `down` (without `-v`) so the Postgres volume stays in place.

## Non-obvious runtime gotchas

- **Realm imports are `IGNORE_EXISTING`.** `--import-realm` only seeds an empty Postgres. To apply a `realm-export.json` edit on a running stack, either `./start.sh --reset` (destructive) or change the live realm via admin API (fast, preserves federated identities).
- **The `p1` IdP `signingCertificate` is baked into `realm-export.json`.** It must match the certificate P1's Tomcat presents when signing SAML Responses. If the dev keystore is regenerated on the P1 side, paste the new cert (DER, base64) into the realm export and either reset or update via admin API.
- **P1's SAML signing keystore lives at `/tmp/p1-idp-dev.p12`** (Tomcat env `P1_IDP_KEYSTORE_PATH`). macOS wipes `/tmp` on reboot. Recovery is one script: `./scripts/sso-dev-keystore.sh` regenerates the PKCS#12 at the same path **and** rotates the public cert in the live realm's `p1` IdP via admin API in one go. The previous IdP config is backed up to `/tmp/kc-p1-idp.before-rotation.json` for rollback. **B5 fix:** `AbstractSamlAuthenticationResponseBuilder` now caches the signing `Credential` and reloads only when the keystore file's **mtime changes** (no longer a per-request disk read). So hot rotation still needs no Tomcat restart (rewriting the file → mtime change → reload), and once the credential is loaded a `/tmp` wipe **no longer** breaks signing mid-run — it keeps using the cached key until the next restart. (A wipe before the first signature still 500s; run the recovery script to repopulate the file.)
- **All BFFs run as baked fat jars.** Each domain BFF is built once into its own image (`<domain>/bff/Dockerfile`) using the `com.gradleup.shadow` plugin → `eclipse-temurin:17-jre` base. PID 1 is `java -jar /app/<svc>.jar`; no source tree, no Gradle cache, no bind mount. A source edit needs a `--build --force-recreate`.
- **Shadow plugin is declared explicitly in every BFF's `build.gradle.kts`.** Micronaut 4.4's application plugin doesn't register `shadowJar` on its own — each BFF adds `id("com.gradleup.shadow") version "8.3.5"` so `gradle shadowJar` produces `build/libs/*-all.jar` for the Dockerfile to copy.
- **Keycloak runs the stock image — no custom SPIs.** Compose uses `quay.io/keycloak/keycloak:26.0.7` directly. Any change that wants a SPI would need a fresh `Dockerfile.keycloak` and a `build.context` in compose.
- **Keycloak's admin API is at `https://auth.geowealth.int:5180`.** Get a token at `/realms/master/protocol/openid-connect/token` with `client_id=admin-cli&grant_type=password&username=admin&password=admin`. Admin endpoints live under `/admin/realms/demo-realm/...`. The `auth` nginx terminates TLS and proxies to `keycloak:8080` inside the docker network — Keycloak itself is no longer exposed on the host. `KC_HOSTNAME=https://auth.geowealth.int:5180` makes Keycloak emit consistent issuer / broker URIs.
- **`keycloak-js` must be ≥ 26.x.** Older versions validate a `nonce` claim that Keycloak 26 no longer emits.
- **Domain hosts need HTTPS.** keycloak-js v26 uses `crypto.subtle`, which the browser only exposes in secure contexts: HTTPS, or the loopback hostnames `localhost`/`127.0.0.1`. A custom hostname like `billing.geowealth.int` resolves to 127.0.0.1 via `/etc/hosts`, but the browser classifies secure-context by hostname literal, not resolved IP — so plain `http://billing.geowealth.int:5184` is NOT secure and `crypto.subtle` is `undefined` there. Each domain serves HTTPS via Vite preview with mkcert-issued certs at `proxy/certs/<name>.geowealth.int.{crt,key}`. Generate with `mkcert -cert-file proxy/certs/<name>.geowealth.int.crt -key-file proxy/certs/<name>.geowealth.int.key <name>.geowealth.int localhost 127.0.0.1`.
- **P1 credential login mints a KC session via a post-login "establish round-trip"** (Gap 6). On `loginPassword` success in `appService.js`, React unconditionally redirects to `/saml/idp/silent-sso.do?establish=true&return_to=%2F`; `SilentSsoAction` then drives KC's authorize endpoint with `kc_idp_hint=p1` (and **no** `prompt=none`), so KC SAML-brokers back to the just-authenticated P1 session and creates a KC session for `p1-self-client`. **Loop prevention is server-side only** (`SilentSsoAction.SESSION_KEY_ESTABLISH_DONE` on the P1 `HttpSession`); a previous attempt at a client-side `sessionStorage.kc_sso_established` guard was removed after a stale flag on a long-lived tab silently suppressed the round-trip across re-logins. The server flag is cleared automatically when the HttpSession is invalidated (logout, idle), so a fresh login always gets a fresh attempt. Debug surface: `grep "establish(kc_idp_hint=p1)" catalina.out` should show one line per P1 credential login; if absent, no KC session is being minted.
- **The `silent_failed=1` loop guard in `appService.checkUserLoggedIn` treats a user reload as a retry signal.** `silentFailed = hash.includes('silent_failed=1') && !isUserReload`, where `isUserReload = performance.getEntriesByType('navigation')[0]?.type === 'reload'`. App-initiated `window.location.replace(...)` produces `navigate`, not `reload`, so the loop guard still fires for app bounces — only a genuine F5 / Cmd-R counts as a retry. End-to-end coverage lives in `e2e/tests/p1-relogin-silent-recovery.spec.ts`.
- **BFF `/auth/logout` is idempotent** (`bff-core/AuthController.java`): `@Secured(IS_ANONYMOUS)` with `@Nullable Authentication`. A double-click, an already-expired session, or a back-channel race no longer surfaces as `401 Unauthorized` — the controller does best-effort KC end-session + session delete + SID invalidate + `303 See Other` to the P1 SLO redirect regardless of caller auth state. Both BFFs need a rebuild for the change to land (composite build bundles `bff-core` per image).

## P1 SAML federation flow

1. Entry point (either): user clicks a P1/Integrations sidebar link, OR the SPA's `keycloak.init({ onLoad: 'check-sso' })` finds no session and fires `keycloak.login({ idpHint: 'p1' })`. Both build the same URL: `https://auth.geowealth.int:5180/realms/demo-realm/protocol/openid-connect/auth?client_id=demo-<name>-client&response_type=code&scope=openid&redirect_uri=https://<name>.geowealth.int:518X/&kc_idp_hint=p1&state=…`.
2. Keycloak sees `kc_idp_hint=p1`, skips its own login screen, emits a SAML AuthnRequest to P1's `/saml/idp/sso.do`.
3. P1's `IdpSsoAction` parses the AuthnRequest ID, reads the active P1 session, builds a signed Response with `InResponseTo=ID`, posts back to Keycloak's broker endpoint.
4. Keycloak correlates by `InResponseTo`, runs first-broker-login (auto-link by email, silent), issues an OIDC code, redirects to `redirect_uri` (the domain SPA).
5. The SPA's `AuthProvider` picks up the code, exchanges it for a token, and renders the dashboard.

Pure IdP-init (P1 → Keycloak with unsolicited Response) does **not** work cleanly against an OIDC client target in Keycloak. The `/endpoint` path needs SP-init correlation, and the `/endpoint/clients/{id}` variant requires SAML protocol clients. SP-init via `kc_idp_hint` is the supported flow.

## Applying changes — what needs what

| Change | Minimum action |
|---|---|
| `domains/<name>/web/` source | `podman compose up -d --build --force-recreate demo-<name>` — its own image (`keycloak-demo-<name>-web`) built by `domains/<name>/web/Dockerfile`. No bind-mount, so a source edit needs the full build. |
| `domains/<name>/bff/` source | `podman compose up -d --build --force-recreate bff-<name>` — its own image (`keycloak-demo-<name>-bff`). |
| `bff-core/` **auth** source (login/callback/`/auth/*`/`/auth/verify`/logout/backchannel/token-refresh/session/`SubdomainAuthorizer`) | **Token-handler ONLY** — `docker compose build --no-cache token-handler && docker compose up -d --force-recreate token-handler` (one multi-tenant instance for all domains). Forward-auth: the data BFFs run NO auth, so the fix is live the moment the token-handler restarts; **`bff-billing`/`bff-trading` are NOT rebuilt and users are not logged out** (sessions in Redis). |
| `bff-core/` source the **data BFF still runs** (`Tier23Gate`/`P1AuthzClient` = Tier-3 `refine`, `AuthClaims`, `HeaderIdentity`) | rebuild the data BFFs too — `docker compose build --no-cache bff-billing bff-trading && docker compose up -d --force-recreate bff-billing bff-trading`. (Only the data-row `refine` mechanism lives here now.) |
| (either of the above) | **Gotcha: the Docker `COPY bff-core/` layer can cache-hit even after you edit a `bff-core` file, so plain `--build` silently ships a stale jar.** Symptom: the running container behaves like your edit isn't there. Always use `--no-cache` for bff-core changes, or verify with `docker cp <svc>:/app/*.jar … && javap -c …`. |
| Add a new domain | follow the recipe in "Adding a new domain" above. |
| Env var on a service | `podman compose up -d --force-recreate <service>` |
| `docker-compose.yml` structural change | `podman compose up -d` (compose picks up the diff) |
| `keycloak/realm-export.json` | takes effect on fresh DB only; otherwise patch the live realm via admin API. For env-specific URLs (IdP SAML endpoints, client redirect/web-origin/post-logout/back-channel), edit `k8s/env/urls.<env>.env` and run `./scripts/reconcile-realm.sh k8s/env/urls.<env>.env` (idempotent; `up.sh` runs it automatically). |
| A URL or port that differs per environment (K8s) | edit `k8s/env/urls.<env>.env` (the single source) — see "Environment URL configuration (K8s)" below. No code / image / realm-export edit. |
| Pointing K8s at an external Oracle / Elasticsearch / Memcached (different machine) | edit `k8s/env/data-tier.<env>.env` and re-run `./k8s/up.sh` — see "Data-tier endpoint config (K8s)" below. |
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
