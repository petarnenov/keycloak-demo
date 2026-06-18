# Bringing the full-stack K8s cluster up on Ubuntu

A step-by-step runbook for standing up the entire demo (data + identity + legacy
P1) on a fresh Ubuntu host with `k8s/up.sh`. Tested target: Ubuntu, 64 GB RAM.

`up.sh` is the single entrypoint; this doc covers the host prerequisites and the
two non-repo files it needs, plus how to reach the stack afterwards.

> **Layout requirement:** `keycloak-demo` and `geowealth` must be **sibling
> directories under `$HOME`** (`$HOME/keycloak-demo`, `$HOME/geowealth`). The
> overlay reads `../../../../geowealth/k8s/env/urls.dev.env` and `up.sh` defaults
> `GEOWEALTH_DIR=$HOME/geowealth`.

## 0. What you get

One minikube cluster (~12 GB) running: Oracle (+ seed Job), Elasticsearch,
memcached, kc-postgres, redis, Keycloak (+ `kc-ext` TLS-terminating proxy for
the in-cluster `https://auth.geowealth.int:5180` issuer URL), token-handler,
two data BFFs, two web front-ends, and the legacy P1 tier: Tomcat
(`web-petar.conf` profile) + **9 Akka agent Deployments** —
`p1-samlmanager` (SAML SSO), `p1-devcommonagents` (AuthorizationManager,
AuthenticationManager, PortalManager, BillingManager,
DistributedCacheControllerManager, etc.), `p1-useragents` (UserManager,
AccountManager, EBrokerManager), `p1-mostagents` (InstrumentManager),
`p1-searchagents` (SearchManager, ClientSearchManager), `p1-cspagents`
(InstrumentPerformanceManager), `p1-reportengine`, `p1-proposalagents`,
`p1-emailagent`. First bring-up takes **~20–30 min** (Oracle init ~15 min +
the heavy P1 image build). On a freshly-cloned host (no Docker layer cache)
budget another ~5 min for the first `docker build` of the P1 image.

## 1. Install system packages

```bash
sudo apt-get update
# Docker (on Linux it uses host RAM directly — no Docker Desktop memory cap)
sudo apt-get install -y docker.io
sudo usermod -aG docker "$USER"        # then log out/in, or run: newgrp docker
# Helpers
sudo apt-get install -y git curl python3 libnss3-tools
# kubectl
curl -LO "https://dl.k8s.io/release/$(curl -sL https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
sudo install -m 0755 kubectl /usr/local/bin/kubectl && rm kubectl
# minikube
curl -LO https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64
sudo install minikube-linux-amd64 /usr/local/bin/minikube && rm minikube-linux-amd64
# mkcert (only to trust the committed dev TLS certs in the browser)
sudo apt-get install -y mkcert || {
  curl -L "https://github.com/FiloSottile/mkcert/releases/latest/download/mkcert-v1.4.4-linux-amd64" -o mkcert
  sudo install mkcert /usr/local/bin/ && rm mkcert; }
```

No host Java/Gradle needed — every image is built inside Docker. The preflight in
`up.sh` wants Docker to report > 14 GB; on Linux that is the host RAM, so 64 GB is
fine for the 12 GB minikube.

## 2. Clone both repos as siblings under `$HOME`

```bash
cd "$HOME"
git clone https://github.com/petarnenov/keycloak-demo.git
git -C keycloak-demo checkout petarnenov/full-stack-k8s

git clone https://gitlab.com/gwm1/geowealth.git
git -C geowealth checkout team/petarnenov/k8s-full-stack
```

## 3. Copy the two non-repo files from the source machine

These are gitignored / transient and the login + SAML flows do not work without
them:

| File | Why |
|---|---|
| `keycloak-demo/db/local/R__local_login_hash.sql` | the seed users' password hash. Without it **no one can log in** (`up.sh` only warns). |
| `/tmp/p1-idp-dev.p12` | P1's SAML signing keystore. Its public half is embedded in `keycloak/realm-export.json`, so it **must match**. |

From the source machine, e.g.:

```bash
scp ~/keycloak-demo/db/local/R__local_login_hash.sql  user@NEW_HOST:~/keycloak-demo/db/local/
scp /tmp/p1-idp-dev.p12                                user@NEW_HOST:/tmp/
```

The TLS certs in `proxy/certs/*` **are committed** and self-consistent (signed by
the committed `mkcert-rootCA.pem`) — leave them as-is.

**No `.p12` to copy?** Run `./scripts/sso-dev-keystore.sh` *after* Keycloak is up
(it regenerates the keystore **and** rotates the live realm's `p1` IdP cert to
match via the admin API), then recreate the `p1-saml-keystore` Secret from the new
`/tmp/p1-idp-dev.p12` and restart `p1-tomcat`. Copying is simpler.

## 4. Trust the dev CA (clean browser HTTPS — optional)

```bash
cd "$HOME/keycloak-demo"
sudo cp proxy/certs/mkcert-rootCA.pem /usr/local/share/ca-certificates/keycloak-demo-mkcert.crt
sudo update-ca-certificates
# Chrome/Chromium (NSS store):
certutil -d sql:"$HOME/.pki/nssdb" -A -t "C,," -n keycloak-demo-mkcert -i proxy/certs/mkcert-rootCA.pem
```

Firefox: Settings → Certificates → import `proxy/certs/mkcert-rootCA.pem` (trust
for websites). Or just click through the browser warning each time. (The
token-handler trusts this CA server-side regardless, via the `mkcert-ca`
ConfigMap — this step only removes the user-facing HTTPS warning.)

## 5. Bring the cluster up

```bash
cd "$HOME/keycloak-demo"
# On a big box, give minikube more RAM/CPU so more P1 agents / replicas schedule.
# 64 GB host → ~48 GB is a good value (leave headroom for the host OS); push higher
# only if nothing else runs on the box. Defaults (12 GB / 4 CPU) are used if unset.
MINIKUBE_MEM_MIB=49152 MINIKUBE_CPUS=12 ./k8s/up.sh
```

> **Sizing is fixed at cluster creation.** `minikube` ignores a new `--memory` on a
> cluster that already exists — `up.sh` will warn if the running size differs. To
> resize: `minikube -p geowealth delete` then re-run with the new values. The
> docker driver on Linux draws from host RAM, so don't allocate all 64 GB — leave
> ~12–16 GB for the host.

`up.sh` starts minikube (`geowealth`, docker driver, sized by `MINIKUBE_MEM_MIB` /
`MINIKUBE_CPUS` — default 12 GB / 4 CPU) → enables the
ingress addon → builds every image into minikube's docker daemon (including the
heavy P1 image) → `kubectl apply`s the `full-stack` overlay → loads the real
Secrets from local files → waits in ordered waves (Oracle 900s → seed → KC → P1 →
auth/web) → patches the `auth.geowealth.int → kc-ext` hostAlias so the
token-handler can resolve the public issuer in-cluster for OIDC discovery → runs
`scripts/reconcile-realm.sh` to drive the realm's env-specific URLs from
`k8s/env/urls.dev.env`. It prints the pods at the end.

If the P1 image build fails, confirm `$HOME/geowealth` is on the right branch (or
point `GEOWEALTH_DIR=/path ./k8s/up.sh` elsewhere). **The P1 image build copies
`devBuild/classes` (pre-compiled by `gradle devClasses`).** `up.sh` runs
`./gradlew devClasses` in `$GEOWEALTH_DIR` first if `devBuild/` is missing, but if
you edit P1 source you must re-run `./gradlew devClasses` manually before
`./k8s/up.sh` — otherwise the Docker `COPY devBuild/classes` layer hits cache and
ships stale classes (subtle: the image SHA stays identical and the bug looks like
"my edit had no effect"). When in doubt, `docker build --no-cache` the P1 image.

`MINIKUBE_PROFILE` overrides the profile name (default `geowealth`) — use it to run
a second cluster, or to avoid a stale profile of the same name created with a
different driver (a leftover rootful-podman `geowealth` blocks a docker `geowealth`
with `GUEST_DRIVER_MISMATCH`), e.g. `MINIKUBE_PROFILE=gwk8s ./k8s/up.sh`.

### 5a. Pointing the cluster at an external Oracle (optional)

The defaults bring up an in-cluster Oracle baked from `db/Dockerfile` with a
tiny seeded schema — fine for the SSO flow, but most real demos need the
populated company Oracle (e.g. `192.168.1.42` / SERVICE_NAME `ORCL12VM`). The
swap is **one file edit + one shell var**, no code/manifest/realm change.

1. Edit `k8s/env/data-tier.dev.env`:

   ```
   ORACLE_HOST=192.168.1.42      # was: oracle
   ORACLE_PDB=ORCL12VM           # was: FREEPDB1 (matches the external SERVICE_NAME)
   ORACLE_USER=gp                # rarely changes
   ```

   `ORACLE_PASSWORD` stays OUT of this file — it's a credential, supplied on
   the shell at run time.

2. Re-run `up.sh` with the password in the shell env:

   ```bash
   ORACLE_PASSWORD='real-pw' ./k8s/up.sh
   ```

   For the standard local-dev Oracle the creds are `gp/gp123` (see
   `~/AppServer/setup.sh` or `~/AppServer/geowealth/etc/dev-petar-akka.conf`).

What `up.sh` does, in order:

- **`svc/oracle` → ExternalName.** The Service is re-created as either
  `type: ExternalName` (for a hostname) or a selector-less Service + manual
  `Endpoints` with `addresses[].ip` (for a literal IP — CoreDNS rejects
  ExternalName CNAMEs to IP literals). The in-cluster `statefulset/oracle`
  is scaled to 0 so its seeded image's `restore-oradata.sh` doesn't run.
- **`oracle-config` ConfigMap** picks up `ORACLE_PDB` / `ORACLE_USER`.
- **`oracle-creds` Secret** is created from `ORACLE_PASSWORD` (skipped if
  absent → P1 falls back to its baked default `gp123`, which won't auth most
  externals).
- **Rollout-restart of P1 consumers** iff anything changed AND those
  workloads already existed — `p1-tomcat`, `p1-coordinator`, and the nine
  agents. envFrom ConfigMaps/Secrets don't trigger pod restarts on their
  own, so without this the new env would land but the running pods would
  keep the old values. On a fresh install the consumers don't exist yet, so
  this is skipped. On a no-op re-run the snapshot hashes match and nothing
  rolls.
- **Wave-wait** sees the new pods rolling.

**Heads-up — port-forwards die with the rolled pod.** If you already had
`kubectl port-forward svc/p1-tomcat 8080:8080 --address 127.0.0.1` running
from section 6, it'll silently drop when `p1-tomcat` rolls. Re-establish it:

```bash
pkill -f 'kubectl.*port-forward.*p1-tomcat'
kubectl -n geowealth-demo port-forward svc/p1-tomcat 8080:8080 --address 127.0.0.1 &
```

**Heads-up — agent heap is tuned for the baked PDB.** `p1-devcommonagents`
boots by pre-loading the full `CostBasisAccount` table into an in-memory
cache (`CrntCostBasisLoader`). 1.5G heap fits the baked PDB but OOMs against
real data; `k8s/base/p1.yaml` therefore sets `-Xmx4G` with `limits.memory:
5Gi`. Symptom of an under-sized agent is a blank `localhost:8080/` page
because the agent CrashLoopBackOffs → `AuthorizationManager` never joins
the Akka cluster → `IdentifyFirmByUrlMsg` from `p1-tomcat` goes to dead
letters → the request hangs. Diagnose with

```bash
kubectl -n geowealth-demo get pods | grep p1-devcommonagents          # RESTARTS > 0?
kubectl -n geowealth-demo logs --previous deploy/p1-devcommonagents \
  | grep -A20 OutOfMemoryError                                        # CrntCostBasisLoader?
```

If a different agent OOMs after a future DB grows, bump its `JAVA_OPTS_EXTRA`
+ container `limits.memory` in `k8s/base/p1.yaml` the same way (the pattern
is `<X>Loader.<init>` → heap exhaustion in the boot path).

**Provisioning hint — if the external is empty.** This cluster does NOT
migrate into an external host. The simplest path to a populated external
Oracle is `docker run` of the baked image on the target machine:

```bash
docker run -d --name oracle -p 1521:1521 -v oradata:/opt/oracle/oradata \
  keycloak-demo-db:seeded
```

(Built from `db/Dockerfile`; schema + Flyway seeds + the local-login hash
are already inside.) Same model applies to Elasticsearch (run
`RefreshClientSearcherTool` once) and Memcached (stateless, no provisioning).

**Switching back to in-cluster** is the same edit in reverse — set
`ORACLE_HOST=oracle` (and `ORACLE_PDB=FREEPDB1`) and re-run `up.sh`. The
script detects the leftover `ExternalName` Service from the previous run and
deletes it before the apply (`Service.spec.type` is immutable).

## 6. Access: `/etc/hosts` + port-forwards

The realm/app config uses the ports `:5184 / :5185 / :5180 / :8080`, which come
from port-forwards (the ingress itself listens on 443). Add the hosts and start
the forwards (keep them running, e.g. in tmux):

```bash
echo "127.0.0.1 billing.geowealth.int trading.geowealth.int auth.geowealth.int p1.geowealth.int" | sudo tee -a /etc/hosts

kubectl -n ingress-nginx port-forward svc/ingress-nginx-controller 5180:443 5184:443 5185:443 --address 127.0.0.1 &
kubectl -n geowealth-demo  port-forward svc/p1-tomcat 8080:8080 --address 127.0.0.1 &
```

The ingress routes by Host header (hostname, not port), so `:5184/:5185/:5180` all
forward to the controller's 443 and land on the right backend. The whitelabel
`*.localhost` hosts (e.g. `c1wealth.localhost`) resolve to 127.0.0.1 automatically
— no `/etc/hosts` line needed.

## 7. Verify

```bash
curl -s -o /dev/null -w "billing %{http_code}\n" -k https://billing.geowealth.int:5184/
curl -s -o /dev/null -w "auth %{http_code}\n"    -k https://auth.geowealth.int:5180/realms/demo-realm/.well-known/openid-configuration
curl -s -o /dev/null -w "p1 %{http_code}\n"      --max-time 4 http://localhost:8080/saml/idp/initiate-slo.do
kubectl -n geowealth-demo get pods
```

Expected: billing 200, auth 200, p1 302, all pods `Running`.

## 8. Log in (browser)

Open `https://billing.geowealth.int:5184` → it bounces to the P1 login on
`localhost:8080` → sign in as `tim1` → the billing dashboard renders. Sign out
should land back on the P1 login (not a "connection refused" page).

The P1 React bundle (`app.min.js`) is ~13 MB; the **first** browser navigation
after a fresh `p1-tomcat` start may show the AppLoader spinner for 10–30 s while
Tomcat compiles JSPs, opens DB pools, joins the Akka cluster and the browser
downloads the bundle. Subsequent loads are sub-second. If the spinner sticks
past ~60 s, check the Akka cluster health (Section 10).

## 9. Useful commands

```bash
./k8s/up.sh                                  # idempotent — re-run after changes (re-apply + re-wait + reconcile)
./k8s/up.sh --down                           # delete the namespace (cluster + data stay)
URLS_ENV=k8s/env/urls.prod.env ./k8s/up.sh   # target a different environment file
minikube -p geowealth delete                 # destroy the whole cluster

# Re-run just the realm URL reconcile (e.g. after editing an env file), via an
# in-cluster admin port-forward:
kubectl -n geowealth-demo port-forward svc/keycloak 18080:8080 & PF=$!; sleep 3
KC_ADMIN_BASE=http://localhost:18080 ./scripts/reconcile-realm.sh k8s/env/urls.dev.env
kill $PF

# Restart P1 cluster in the CORRECT order (coordinator → agents → web). Doing
# it any other way (or all-at-once) leaves the Akka cluster split and Tomcat
# eventually serves ServiceTimeoutException / blank AppLoader spinner for every
# request.
kubectl -n geowealth-demo rollout restart sts/p1-coordinator
kubectl -n geowealth-demo rollout status  sts/p1-coordinator --timeout=180s
for d in p1-samlmanager p1-reportengine p1-proposalagents p1-emailagent \
         p1-devcommonagents p1-mostagents p1-searchagents p1-cspagents \
         p1-useragents; do
  kubectl -n geowealth-demo rollout restart deploy/$d
done
for d in p1-samlmanager p1-reportengine p1-proposalagents p1-emailagent \
         p1-devcommonagents p1-mostagents p1-searchagents p1-cspagents \
         p1-useragents; do
  kubectl -n geowealth-demo rollout status deploy/$d --timeout=180s
done
kubectl -n geowealth-demo rollout restart sts/p1-tomcat

# Confirm the full cluster joined back (look for 11 'Member is Up' lines:
# coordinator + 9 agents + tomcat).
kubectl -n geowealth-demo logs sts/p1-tomcat --tail=400 | grep -c 'Member is Up'

# Restart any port-forward that died with its pod. `kubectl port-forward` does
# NOT auto-reconnect when the target pod is replaced (tomcat StatefulSet
# rollouts, token-handler Deployment rollouts, kc-ext, etc.).
pkill -f 'kubectl.*port-forward.*p1-tomcat'
kubectl -n geowealth-demo port-forward svc/p1-tomcat 8080:8080 --address 127.0.0.1 &
```

## 10. Troubleshooting

- **Nobody can log in** → `db/local/R__local_login_hash.sql` is missing (step 3).
- **"SAML emission failed" / "ServerDown.jsp Platform Currently Offline" /
  login hangs after a Secret-touching re-apply** → on every `kubectl apply -k`
  the overlay re-stamps the **placeholder** `p1-saml-keystore` Secret
  (`stringData: idp.p12: REPLACE_WITH_p1-idp-dev.p12`, 27 bytes); `up.sh`'s
  post-apply step then overlays the real 2.6 KB PKCS#12 from
  `/tmp/p1-idp-dev.p12`. If you ever `kubectl apply -k` manually without that
  step, recreate the Secret yourself and restart Tomcat:
  ```bash
  kubectl -n geowealth-demo exec sts/p1-tomcat -- wc -c /etc/p1/idp.p12     # 27 = placeholder, 2632 = real
  kubectl -n geowealth-demo create secret generic p1-saml-keystore \
    --from-file=idp.p12=/tmp/p1-idp-dev.p12 --dry-run=client -o yaml \
    | kubectl -n geowealth-demo apply -f -
  kubectl -n geowealth-demo rollout restart sts/p1-tomcat
  ```
  If `/tmp/p1-idp-dev.p12` is missing (e.g. Ubuntu `systemd-tmpfiles-clean` wiped
  `/tmp` on reboot — even though `/tmp` is **not** tmpfs by default on Ubuntu,
  the timer still purges old files), regenerate it with
  `./scripts/sso-dev-keystore.sh` (rotates the realm's `p1` IdP `signingCertificate`
  in one shot via the admin API), then recreate the Secret as above.
- **token-handler login 500 / "Failed to retrieve OpenID configuration" /
  "TLS connect error: wrong version number"** → the `auth.geowealth.int → kc-ext`
  hostAlias is missing OR `kc-ext` is selecting plain-HTTP keycloak pods instead
  of the TLS-terminating nginx Deployment (`app.kubernetes.io/name: kc-ext`,
  port 5180→5180). `up.sh` patches the hostAlias; the Service+Deployment are
  in `k8s/base/keycloak.yaml`. Verify:
  ```bash
  kubectl -n geowealth-demo get endpoints kc-ext        # should point at the kc-ext pod IP : 5180
  kubectl -n geowealth-demo exec deploy/token-handler -- \
    curl -sk -o /dev/null -w '%{http_code}\n' \
    https://auth.geowealth.int:5180/realms/demo-realm/.well-known/openid-configuration   # expect 200
  ```
- **Oracle pod stuck / datafiles corrupted on the PV** → the StatefulSet now
  runs the BAKED image (`keycloak-demo-db:seeded`): on a fresh PVC the
  container's restore-oradata.sh seeds `/opt/oracle/oradata` from the image's
  `/opt/oracle/baked-oradata` snapshot, then gvenzl just opens the DB. Recovery
  is "wipe the PV dir, let restore run again" — no separate seed Job to delete:
  ```bash
  minikube -p geowealth ssh -- 'sudo rm -rf \
    /tmp/hostpath-provisioner/geowealth-demo/data-oracle-0/*'
  kubectl -n geowealth-demo delete pod oracle-0 --grace-period=0 --force
  ./k8s/up.sh    # idempotent — pod restarts, restore-oradata.sh re-seeds
  ```
  If a bad migration was baked into the image itself, rebuild it via up.sh
  (`docker build -t keycloak-demo-db:seeded -f db/Dockerfile db`).
- **Browser AppLoader spinner sticks / `ServiceTimeoutException` on
  `IdentifyFirmByUrlMsg` / `LoadFirmMsg` / etc. in p1-tomcat logs** → an Akka
  cluster split. Re-restart in the CORRECT order **coordinator → agents → web**
  (see Section 9). All-at-once rolling restarts leave members marked
  `UNREACHABLE` and back-channel logout / firm lookup messages go to dead
  letters. Check `kubectl -n geowealth-demo logs sts/p1-tomcat | grep -c
  'Member is Up'` — anything below **11** is a split.
- **billing/trading nginx upstream resolution fails with
  `Connection refused while resolving 127.0.0.11:53`** → the web image's
  `/docker-entrypoint.d/05-resolver.sh` didn't run. The script auto-detects the
  in-pod nameserver (`/etc/resolv.conf`) — Docker DNS at `127.0.0.11` in compose,
  CoreDNS at `10.96.0.10` in K8s — and rewrites `nginx.conf`'s `resolver`
  directive plus bare K8s Service names to FQDNs. If the script is missing,
  rebuild the web image (`domains/{billing,trading}/web/Dockerfile` `COPY`s it
  to `/docker-entrypoint.d/`).
- **`up.sh` aborts with `services "kc-ext" not found`** → you're on an older
  branch that pre-dates the `kc-ext` Deployment in `k8s/base/keycloak.yaml`.
  Pull the latest `petarnenov/full-stack-k8s` and re-run.
- **Oracle / Elasticsearch unschedulable** → Docker has too little memory (on
  Linux that is host RAM; 64 GB is plenty — check nothing else is hogging it).
  Memory budget per pod is tight at the default 12 GB minikube: Oracle 4 GB,
  Elasticsearch 1 GB, KC + P1 Tomcat ~3 GB, the 9 P1 agents ~5 GB, token-handler
  + BFFs + web + redis + postgres + memcached ~2 GB. Push `MINIKUBE_MEM_MIB`
  higher if pods stay `Pending` with `Insufficient memory`.
- **e2e suite** → needs MFA disabled for `tim1`: in K8s
  `kubectl -n geowealth-demo exec oracle-0 -- bash -c "echo \"UPDATE GP.ENTITY_TBL
  SET MFA_REQUIRED_FLAG=0 WHERE LDAP_UID='tim1'; COMMIT;\" | sqlplus -S gp/gp123@FREEPDB1"`
  (the committed `e2e/scripts/disable-mfa-for-tim1.sh` calls `docker exec
  geo-oracle` — compose-only — and needs an `--engine=k8s` flag to be useful
  here). Then `cd e2e && npm ci && npx playwright test --retries=2` (P1 reached
  on `localhost:8080`). **Sequential run** — the suite serializes (`workers=1`)
  because it shares KC/P1 server state. Expected on K8s: 26 passed + 1 flaky
  (KC admin revoke back-channel logout, passes on retry; the in-cluster sid
  round-trip is slower than the compose monolith) + 2 intentionally skipped
  (`test.skip()` in `external-logout.spec.ts`). The first 1–2 tests cold-start
  the Tomcat session cache; warm the cluster up first with a couple of curl
  logins to avoid the 90 s per-test timeout (see Section 9).

## How the URL/port config is wired (context)

Every environment-specific URL/port lives in **one file per env**:
`k8s/env/urls.{dev,qa,prod}.env`. It feeds (1) the token-handler env via the
generated `app-urls` ConfigMap and (2) the realm via `scripts/reconcile-realm.sh`
(realm import is `IGNORE_EXISTING`, so a reconcile is the only thing that drives a
running realm). See CLAUDE.md → "Environment URL configuration (K8s)".

## How P1 splits across pods on K8s (context)

In compose mode P1 runs as **one** Tomcat JVM with `web-petar.conf` —
`akka.cluster.roles = [web, UserManager, AccountManager, CustodianManager]` —
plus every Akka service trait wired in via `AkkaBooter` into that same JVM,
so any `Mailer.sendAnyMessageToLocalActorAndWait(...)` lands on a local actor
instantly. The K8s deploy splits that monolith: each role family is its own
Deployment that joins the cluster as a remote member, and Tomcat reaches the
service traits over Akka Artery TCP rather than via in-JVM dispatch.

What this means in practice:

- **Every watchdog role in `geowealth.watchdog` needs a real agent pod**, not
  just a `akka.cluster.roles` tag on Tomcat. `AkkaClusterListener` ANDs the live
  cluster's roles against `geowealth.watchdog` and gates
  `Login.setLoginEnabled` on that — a missing agent role means the
  `LoginInterceptor` returns `checkLogin` → `.loginDisabled` →
  `ServerDown.jsp` "Platform Currently Offline" for every action, including the
  P1 login form itself. The K8s template `geowealth/k8s/config/akka.conf.tpl`
  sets `watchdog = [web]` to short-circuit this (Tomcat owns the `web` role, so
  the check is trivially satisfied), and the K8s entrypoint loads
  `etc/web-petar.conf` (`-Dcom.netfolio.appname=web-petar`) so `web.conf`'s
  prod watchdog list doesn't override it. **If you regenerate the P1 image
  outside `up.sh`, re-apply both lines** or the login page will serve
  `ServerDown.jsp`.
- **Each `<Manager>` actor lives in exactly one pod.** When `BasicAction`
  resolves `AuthorizationManager` it goes through the cluster's
  `DistributedPubSubMediator` topic `AuthorizationManager` — only the pod that
  registered the topic answers. The agent set bundled by `k8s/base/p1.yaml`
  covers the roles every `actions.HomepageAction` /
  `ReactIndexAction.execute()` path touches (and the prod watchdog list); if a
  spec or page reaches into a role NOT in that list you get
  `MessageExpirationException` after the Mailer's send-and-wait deadline. The
  fix is additive: add a new `p1-<bundle>` Deployment that runs
  `ROLE=agent AGENT=<bundle>` (modeled after the existing ones — same image,
  same env, only `AGENT` differs).
- **Restart order matters.** `coordinator → agents → web` keeps the seed node
  available while agents (re)join, then brings Tomcat in last so it sees a
  complete cluster from boot. Restarting Tomcat first or rolling everything in
  parallel leaves `UNREACHABLE` members until split-brain resolution kicks in
  (`stable-after = 120s`), and any request that crosses a marked-unreachable
  node hangs to the Mailer timeout. Section 9 has the exact command sequence.
- **`kc-ext` is a TLS terminator, not just a Service alias.** The token-handler
  does OIDC discovery + token exchange against the **browser-facing** issuer
  URL (`https://auth.geowealth.int:5180`, baked into `iss` claims by
  `KC_HOSTNAME`). To make that URL reachable in-cluster without a public
  ingress, `kc-ext` is a tiny `nginx:alpine` Deployment with the mkcert
  `auth-tls` cert that listens on `:5180` TLS and forwards to `keycloak:8080`
  plain. The `auth.geowealth.int → kc-ext.ClusterIP` hostAlias `up.sh` patches
  onto token-handler is what closes the loop. (A plain Service with
  `targetPort: 8080` does NOT work — token-handler talks TLS, Keycloak speaks
  HTTP, and the handshake fails with `wrong version number`.)
