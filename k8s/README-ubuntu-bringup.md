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
memcached, kc-postgres, redis, Keycloak, token-handler, two data BFFs, two web
front-ends, and the legacy P1 tier (Tomcat + Akka agents). First bring-up takes
**~20–30 min** (Oracle init ~15 min + the heavy P1 image build).

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
./k8s/up.sh
```

`up.sh` starts minikube (`geowealth`, 12 GB / 4 CPU, docker driver) → enables the
ingress addon → builds every image into minikube's docker daemon (including the
heavy P1 image) → `kubectl apply`s the `full-stack` overlay → loads the real
Secrets from local files → waits in ordered waves (Oracle 900s → seed → KC → P1 →
auth/web) → patches the `auth.geowealth.int → kc-ext` hostAlias so the
token-handler can resolve the public issuer in-cluster for OIDC discovery → runs
`scripts/reconcile-realm.sh` to drive the realm's env-specific URLs from
`k8s/env/urls.dev.env`. It prints the pods at the end.

If the P1 image build fails, confirm `$HOME/geowealth` is on the right branch (or
point `GEOWEALTH_DIR=/path ./k8s/up.sh` elsewhere).

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
```

## 10. Troubleshooting

- **Nobody can log in** → `db/local/R__local_login_hash.sql` is missing (step 3).
- **"SAML emission failed" / login hangs** → keystore mismatch; copy
  `/tmp/p1-idp-dev.p12`, or run `scripts/sso-dev-keystore.sh` (after KC is up).
- **token-handler login 500 / "Failed to retrieve OpenID configuration"** → the
  `auth.geowealth.int → kc-ext` hostAlias is missing. `up.sh` adds it; check with
  `kubectl -n geowealth-demo get pod -l app.kubernetes.io/name=token-handler -o jsonpath='{.items[0].spec.hostAliases}'`
  and re-run `up.sh` (or re-apply the patch from its step 4.5).
- **Oracle / Elasticsearch unschedulable** → Docker has too little memory (on
  Linux that is host RAM; 64 GB is plenty — check nothing else is hogging it).
- **e2e suite** → needs MFA disabled for `tim1`: `./e2e/scripts/disable-mfa-for-tim1.sh`
  (one-time DB tweak), then `cd e2e && npm ci && npx playwright test` (P1 reached
  on `localhost:8080`).

## How the URL/port config is wired (context)

Every environment-specific URL/port lives in **one file per env**:
`k8s/env/urls.{dev,qa,prod}.env`. It feeds (1) the token-handler env via the
generated `app-urls` ConfigMap and (2) the realm via `scripts/reconcile-realm.sh`
(realm import is `IGNORE_EXISTING`, so a reconcile is the only thing that drives a
running realm). See CLAUDE.md → "Environment URL configuration (K8s)".
