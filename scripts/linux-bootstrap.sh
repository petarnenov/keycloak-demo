#!/usr/bin/env bash
# One-shot bootstrap for the keycloak-demo stack on a fresh Ubuntu/Linux box.
#
# Does, in order:
#   1. install mkcert + libnss3-tools + ca-certificates (apt)        [sudo]
#   2. install the local mkcert CA into the system/browser trust     [sudo]
#   3. generate the per-host TLS certs (auth/billing/trading)
#   4. copy THIS machine's mkcert root CA into proxy/certs/mkcert-rootCA.pem
#      — the BFF entrypoint imports it into the JRE truststore so the
#        server-side OIDC metadata fetch trusts Keycloak's cert. This is the
#        one that bites: the file is gitignored, so a fresh clone has the wrong
#        (or no) CA → "OpenID configuration ... bean is disabled".
#   5. add the /etc/hosts loopback entries                           [sudo]
#   6. ensure a .envrc exists (template if missing)
#   7. bring the stack up (./start.sh) or, if it is already running,
#      recreate the BFFs so they re-import the corrected CA.
#
# Idempotent: safe to re-run. Loopback-only; nothing here reaches our LAN.
#
# Usage:
#   ./scripts/linux-bootstrap.sh                # full bootstrap
#   ./scripts/linux-bootstrap.sh --skip-apt     # skip the apt installs
#   ./scripts/linux-bootstrap.sh --no-up        # prep only, don't touch containers
#   ./scripts/linux-bootstrap.sh --recreate-bff # force the "recreate BFFs" path
#
# Env overrides:
#   HOSTS_GEOWEALTH  domain hosts            (default: auth billing trading)
#   HOSTS_LOCALHOST  whitelabel .localhost   (default: c1wealth green john)
#   CONTAINER_ENGINE docker|podman           (auto-detected, docker preferred)
set -euo pipefail

cd "$(dirname "$0")/.."        # repo root
REPO="$PWD"

SKIP_APT=0; NO_UP=0; RECREATE_BFF=0
for a in "$@"; do
  case "$a" in
    --skip-apt)     SKIP_APT=1 ;;
    --no-up)        NO_UP=1 ;;
    --recreate-bff) RECREATE_BFF=1 ;;
    -h|--help)      sed -n '2,40p' "$0"; exit 0 ;;
    *) echo "Unknown arg '$a' (see --help)" >&2; exit 1 ;;
  esac
done

GEO_HOSTS=(${HOSTS_GEOWEALTH:-auth billing trading})
LH_HOSTS=(${HOSTS_LOCALHOST:-c1wealth green john})
CERTS="$REPO/proxy/certs"

step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
have() { command -v "$1" >/dev/null 2>&1; }

# --- container engine + compose --------------------------------------------
ENGINE="${CONTAINER_ENGINE:-}"
if [ -z "$ENGINE" ]; then
  if have docker; then ENGINE=docker; elif have podman; then ENGINE=podman
  else echo "Error: neither docker nor podman on PATH." >&2; exit 1; fi
fi
case "$ENGINE" in
  docker) COMPOSE=(docker compose) ;;
  podman) COMPOSE=(podman compose) ;;
  *) echo "Error: unknown CONTAINER_ENGINE='$ENGINE'." >&2; exit 1 ;;
esac

# --- 1) apt deps ------------------------------------------------------------
if [ "$SKIP_APT" -eq 0 ]; then
  if have apt-get; then
    step "1) Install mkcert + libnss3-tools + ca-certificates"
    sudo apt-get update -y
    sudo apt-get install -y mkcert libnss3-tools ca-certificates || {
      echo "  ! 'mkcert' package not available (older Ubuntu). Install the binary"
      echo "    from https://github.com/FiloSottile/mkcert/releases and re-run with --skip-apt."
      exit 1; }
  else
    echo "1) apt-get not found — skipping package install (use --skip-apt to silence)."
  fi
else
  step "1) Skipping apt installs (--skip-apt)"
fi
have mkcert || { echo "Error: mkcert not on PATH. Install it, then re-run." >&2; exit 1; }

# --- 2) local CA into trust stores -----------------------------------------
step "2) Install the local mkcert CA (mkcert -install)"
mkcert -install

# --- 3) per-host TLS certs --------------------------------------------------
step "3) Generate per-host TLS certs in $CERTS"
mkdir -p "$CERTS"
for h in "${GEO_HOSTS[@]}"; do
  rm -rf "$CERTS/$h.geowealth.int."{crt,key}
  mkcert -cert-file "$CERTS/$h.geowealth.int.crt" -key-file "$CERTS/$h.geowealth.int.key" \
         "$h.geowealth.int" localhost 127.0.0.1
  echo "  $h.geowealth.int"
done

# --- 4) copy this machine's root CA where the BFF expects it ----------------
# The BFF Dockerfile entrypoint does:
#   keytool -importcert -alias mkcert -file /certs/mkcert-rootCA.pem -cacerts ...
# mounted from ./proxy/certs/mkcert-rootCA.pem. It MUST be THIS machine's CA,
# else the server-side OIDC metadata fetch fails TLS and the keycloak OpenID
# bean is disabled (curl -k still returns 200, which is why it looks reachable).
step "4) Copy this machine's mkcert root CA into proxy/certs/mkcert-rootCA.pem"
# release the bind mount first if the BFFs are holding it
"${COMPOSE[@]}" stop bff-billing bff-trading 2>/dev/null || true
rm -rf "$CERTS/mkcert-rootCA.pem"            # clear any stray dir Docker created
cp "$(mkcert -CAROOT)/rootCA.pem" "$CERTS/mkcert-rootCA.pem"
ls -la "$CERTS/mkcert-rootCA.pem"

# --- 5) /etc/hosts ----------------------------------------------------------
step "5) Add /etc/hosts loopback entries"
HOST_LIST=()
for h in "${GEO_HOSTS[@]}"; do HOST_LIST+=("$h.geowealth.int"); done
for h in "${LH_HOSTS[@]}";  do HOST_LIST+=("$h.localhost"); done
sudo sh -c 'for h in '"${HOST_LIST[*]}"'; do
  grep -qE "^[^#]*[[:space:]]$h([[:space:]]|\$)" /etc/hosts || printf "127.0.0.1 %s\n" "$h" >> /etc/hosts
done'
grep -E "geowealth\.int|\.localhost" /etc/hosts || true

# --- 6) ensure .envrc -------------------------------------------------------
ENVRC="$REPO/.envrc"
if [ ! -f "$ENVRC" ]; then
  step "6) Create a .envrc template (gitignored)"
  cat > "$ENVRC" <<'EOF'
# Local config — gitignored.
export RESEND_API_TOKEN=""
export POC_BRANDING_API_TOKEN=""

# BFF Tier 2/3 fine-grained authz. Needs P1 reachable on the host (:8888).
# Set "false" to run coarse @Secured only (no P1 dependency for authz).
export AUTHZ_FINE_ENABLED="true"
export P1_AUTHZ_URL="http://host.docker.internal:8888"

# The demo OIDC clients are PUBLIC (publicClient=true in realm-export.json),
# so these secrets are unused by default. Left blank to silence compose warnings.
export BILLING_OAUTH_CLIENT_SECRET=""
export TRADING_OAUTH_CLIENT_SECRET=""
EOF
  echo "  wrote $ENVRC"
else
  step "6) .envrc already present — leaving it as is"
fi

# --- 7) bring the stack up / recreate BFFs ----------------------------------
if [ "$NO_UP" -eq 1 ]; then
  step "7) --no-up given. Prep done. Bring it up yourself with: ./start.sh"
  exit 0
fi

KC_RUNNING="$("${COMPOSE[@]}" ps --status running --services 2>/dev/null | grep -c '^keycloak$' || true)"
if [ "$RECREATE_BFF" -eq 1 ] || [ "${KC_RUNNING:-0}" -ge 1 ]; then
  step "7) Stack is up — recreate the BFFs so they re-import the corrected CA"
  # shellcheck disable=SC1090
  source "$ENVRC"
  "${COMPOSE[@]}" up -d --force-recreate bff-billing bff-trading
else
  step "7) Stack not running — full bring-up via ./start.sh"
  ./start.sh
fi

step "Done. Verify:"
cat <<EOF
  # CA imported into the BFF's Java truststore:
  ${ENGINE} exec keycloak-demo-bff-billing-1 sh -c 'keytool -list -cacerts -storepass changeit -alias mkcert' | head -3

  # no 'disabled' in the BFF log:
  ${COMPOSE[*]} logs --tail=50 bff-billing | grep -iE 'openid|disabled|Startup completed'

  # then open (with an active P1 session):
  #   https://billing.geowealth.int:5184/
  #   https://trading.geowealth.int:5185/
EOF
