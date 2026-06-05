# Linux setup (Ubuntu)

How to bring the `keycloak-demo` stack up on a fresh Ubuntu machine. The stack
is entirely loopback — every host resolves to `127.0.0.1`, the mkcert CA is
local to the machine, and Keycloak / the BFFs / Postgres run as local
containers. Nothing here needs to reach our LAN or the 223 box.

The one external dependency is the **P1 (geowealth) Tomcat** that brokers the
SAML login. The container stack comes up without it, but the end-to-end
`P1 → Keycloak → BFF` login only completes when P1 is reachable from the host
(see [P1 dependency](#6-p1-dependency-saml-login)).

Tested on Ubuntu 22.04 / 24.04, x86_64.

---

## 0. Prerequisites

| Tool | Why | Install |
|---|---|---|
| `git` | clone the repo | `sudo apt install -y git` |
| Docker Engine + Compose v2 | run the stack | see step 1 |
| `mkcert` + `libnss3-tools` | local TLS certs for the domain hosts | see step 3 |
| `ca-certificates` | system trust store for the mkcert CA | `sudo apt install -y ca-certificates` |

Podman works too (`start.sh` auto-detects it), but this guide uses Docker —
on Linux it needs no host-gateway workarounds.

---

## 1. Install Docker Engine + Compose

```bash
# Docker's official convenience repo
sudo apt update
sudo apt install -y ca-certificates curl gnupg
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | \
  sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
  https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# run docker without sudo (log out / back in afterwards for the group to apply)
sudo usermod -aG docker "$USER"
newgrp docker

docker compose version   # sanity check
```

`extra_hosts: host-gateway` in `docker-compose.yml` resolves `host.docker.internal`
to the host automatically on Docker 20.10+ — no manual `/etc/hosts` mapping
inside containers is needed.

---

## 2. Clone the repo

```bash
git clone <repo-url> ~/keycloak-demo     # or wherever you keep projects
cd ~/keycloak-demo
export REPO="$PWD"                        # used by later commands
```

> This guide assumes `$REPO` points at the repo root. On Valentin's box that
> is `/home/valentin/IdeaProjects/loginContainers/keycloak-demo` — set
> `REPO` to whatever the real clone path is.

---

## 3. Install mkcert and generate the TLS certs

The domain SPAs serve HTTPS with mkcert-issued certs. The CA is **per-machine**
— you cannot copy certs from another host; generate them locally.

```bash
sudo apt install -y mkcert libnss3-tools ca-certificates

# install the local CA into the system + browser (NSS) trust store
mkcert -install

# generate one cert pair per domain host
CERTS="$REPO/proxy/certs"
mkdir -p "$CERTS"
for h in auth billing trading; do
  rm -rf "$CERTS/$h.geowealth.int."{crt,key}
  mkcert -cert-file "$CERTS/$h.geowealth.int.crt" -key-file "$CERTS/$h.geowealth.int.key" \
         "$h.geowealth.int" localhost 127.0.0.1
done

ls -la "$CERTS"/*.geowealth.int.{crt,key}   # expect 6 files
```

If `mkcert -install` warns that the CA is not in the Firefox/Chrome store,
`libnss3-tools` provides the `certutil` it needs — make sure it is installed,
then re-run `mkcert -install`.

The whitelabel hosts (`c1wealth.localhost`, `green.localhost`, `john.localhost`)
need **no certs** — browsers treat `*.localhost` as a secure context by the
hostname literal, so `crypto.subtle` works there over plain HTTP.

---

## 4. /etc/hosts

Every domain host points at loopback. Idempotent — adds only what is missing:

```bash
sudo sh -c 'for h in auth.geowealth.int billing.geowealth.int trading.geowealth.int \
                     c1wealth.localhost green.localhost john.localhost; do
  grep -qE "^[^#]*[[:space:]]$h([[:space:]]|\$)" /etc/hosts || \
    printf "127.0.0.1 %s\n" "$h" >> /etc/hosts
done'

grep -E "geowealth\.int|\.localhost" /etc/hosts   # verify
```

---

## 5. Create `.envrc`

`.envrc` is **gitignored** — it holds secrets, so it is not in the clone. The
BFFs are confidential OIDC clients and need their client secrets to match what
the live Keycloak realm has for `demo-billing-client` / `demo-trading-client`.

Easiest path: copy `.envrc` verbatim from a machine where the stack already
works (it contains the matching secrets). Otherwise create it from this
template and reconcile the secrets in step 7:

```bash
cat > "$REPO/.envrc" <<'EOF'
# Email OTP delivery — blank disables it (OTP still printed to Keycloak logs).
export RESEND_API_TOKEN=""
export POC_BRANDING_API_TOKEN=""

# BFF Tier 2/3 fine-grained authz. Needs P1 reachable on the host (:8888).
# Set to "false" to run coarse @Secured only (no P1 dependency for authz).
export AUTHZ_FINE_ENABLED="true"
export P1_AUTHZ_URL="http://host.docker.internal:8888"

# Confidential OIDC client secrets — MUST match the live realm's client
# secrets for demo-billing-client / demo-trading-client.
export BILLING_OAUTH_CLIENT_SECRET="<billing-client-secret>"
export TRADING_OAUTH_CLIENT_SECRET="<trading-client-secret>"
EOF
```

> If P1 is not running on this machine, set `AUTHZ_FINE_ENABLED="false"` so the
> BFFs do not try to reach `host.docker.internal:8888` for the per-entity
> permission map; they fall back to coarse `@Secured` role checks.

---

## 6. Bring the stack up

```bash
cd "$REPO"
./start.sh                 # preserves data volumes (safe to re-run)
# ./start.sh --reset       # DESTRUCTIVE: wipes volumes, re-seeds realm from JSON
```

`start.sh` sources `.envrc`, auto-detects docker/podman, brings Postgres up
first (then polls its health), then Keycloak, then the domain images.

Endpoints once it is up:

| URL | What |
|---|---|
| `https://auth.geowealth.int:5180/realms/demo-realm/...` | Keycloak (TLS via the `auth` nginx) |
| `https://billing.geowealth.int:5184/` | billing SPA → `/api/billing` → bff-billing |
| `https://trading.geowealth.int:5185/` | trading SPA → `/api/trading` → bff-trading |

Admin token (for reconciling client secrets etc.):

```bash
curl -sk -d 'client_id=admin-cli&grant_type=password&username=admin&password=admin' \
  https://auth.geowealth.int:5180/realms/master/protocol/openid-connect/token
```

---

## 7. Reconcile the confidential client secrets

On a fresh `--reset`, the realm is seeded from `keycloak/realm-export.json`,
which does **not** carry the BFF client secrets. So the secret Keycloak
generates for `demo-billing-client` / `demo-trading-client` will not match
`.envrc`. Make the two agree — either read the secret out of Keycloak into
`.envrc`, or push your `.envrc` value into Keycloak via the admin API:

```bash
TOKEN=$(curl -sk -d 'client_id=admin-cli&grant_type=password&username=admin&password=admin' \
  https://auth.geowealth.int:5180/realms/master/protocol/openid-connect/token \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')

# read the current secret Keycloak holds (find the client UUID first):
curl -sk -H "Authorization: Bearer $TOKEN" \
  'https://auth.geowealth.int:5180/admin/realms/demo-realm/clients?clientId=demo-billing-client'
# then GET .../clients/<uuid>/client-secret  → put that value into .envrc
```

After editing `.envrc`, recreate the BFFs so they pick the new env up:

```bash
docker compose up -d --force-recreate bff-billing bff-trading
```

---

## 8. P1 dependency (SAML login)

There are no native users in `demo-realm`. Every login is brokered through
`kc_idp_hint=p1` → SAML to the P1 (geowealth) Tomcat → first-broker-login by
email. So a real login needs:

1. **P1 running and reachable from the host on `:8888`** (the BFF authz URL and
   the SAML IdP both live there). If P1 runs on this same machine, that is
   `localhost:8888`; the containers reach it via `host.docker.internal:8888`.
2. **The P1 SAML signing keystore at `/tmp/p1-idp-dev.p12`.** It is wiped on
   reboot (Linux clears `/tmp` too). If login fails with `SAML emission failed`
   / `IdpKeyStore` errors in the P1 logs, regenerate it and rotate the public
   cert in the live realm with `./scripts/sso-dev-keystore.sh`, then restart P1
   (the failed static init poisons the class until a restart).
3. **The `p1` IdP `signingCertificate` in the realm must match the cert P1
   presents.** `sso-dev-keystore.sh` keeps the two in sync.

Without P1, the container stack still starts and the SPAs load — only the
SAML login round-trip cannot complete.

---

## 9. Stop / reset

```bash
./stop.sh            # down, volumes preserved
./stop.sh --wipe     # DESTRUCTIVE: down -v, wipes Postgres + Keycloak data
```

Both `start.sh` and `stop.sh` are non-destructive by default; only `--reset` /
`--wipe` touch the data volumes.

---

## Quick troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `mkcert: is a directory` | a stray dir with the cert's name | `rm -rf` the path first (the loop in step 3 does this) |
| Browser distrusts the cert | mkcert CA not in the browser store | `sudo apt install -y libnss3-tools && mkcert -install` |
| `Cannot connect to the Docker daemon` | user not in `docker` group / daemon down | `sudo usermod -aG docker $USER` + re-login; `sudo systemctl start docker` |
| Keycloak starts before Postgres is ready | compose healthcheck gating not honored | `start.sh` already sequences this; if running compose by hand, bring `postgres` up first |
| `host.docker.internal` unreachable from a container | old Docker / missing host-gateway | Docker ≥ 20.10; the compose `extra_hosts` already maps it |
| Login bounces to `/saml/idp/sso.do` and stalls | P1 keystore missing/poisoned | `./scripts/sso-dev-keystore.sh` + restart P1 (see step 8) |
| BFF 500s reaching authz | `AUTHZ_FINE_ENABLED=true` but P1 down | set it `false` in `.envrc`, recreate the BFFs |
