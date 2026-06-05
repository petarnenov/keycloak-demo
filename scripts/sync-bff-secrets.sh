#!/usr/bin/env bash
# Sync the BFF confidential-client secrets from the live Keycloak realm into
# .envrc, then recreate the BFFs so they pick them up.
#
# Why: keycloak/realm-export.json does NOT carry the demo-billing-client /
# demo-trading-client secrets, so a fresh `--reset` seed makes Keycloak
# generate random ones. The BFFs then run with a blank OAUTH_CLIENT_SECRET
# (compose warns "variable is not set"), the server-side OIDC code exchange
# fails, and the keycloak OpenID bean ends up disabled. This script reads the
# real secrets out of Keycloak and writes them back into .envrc.
#
# Idempotent: re-running just refreshes the values. Safe to run any time the
# stack is up.
#
# Usage:
#   ./scripts/sync-bff-secrets.sh              # read secrets, update .envrc, recreate BFFs
#   ./scripts/sync-bff-secrets.sh --no-restart # only update .envrc, don't touch containers
#
# Env overrides (all optional):
#   KC_BASE            default https://auth.geowealth.int:5180
#   KC_REALM           default demo-realm
#   KC_ADMIN_USER      default admin
#   KC_ADMIN_PASSWORD  default admin
#   CONTAINER_ENGINE   docker|podman (auto-detected, docker preferred)
set -euo pipefail

cd "$(dirname "$0")/.."   # repo root
REPO="$PWD"
ENVRC="$REPO/.envrc"

KC_BASE="${KC_BASE:-https://auth.geowealth.int:5180}"
KC_REALM="${KC_REALM:-demo-realm}"
KC_ADMIN_USER="${KC_ADMIN_USER:-admin}"
KC_ADMIN_PASSWORD="${KC_ADMIN_PASSWORD:-admin}"

RESTART=1
[ "${1:-}" = "--no-restart" ] && RESTART=0

# --- pick the (clientId -> .envrc var) pairs to sync ------------------------
# Add a domain here when you add a new BFF.
PAIRS=(
  "demo-billing-client:BILLING_OAUTH_CLIENT_SECRET"
  "demo-trading-client:TRADING_OAUTH_CLIENT_SECRET"
)

step() { printf '\n==> %s\n' "$*"; }

# --- detect container engine + compose command ------------------------------
ENGINE="${CONTAINER_ENGINE:-}"
if [ -z "$ENGINE" ]; then
  if command -v docker >/dev/null 2>&1; then ENGINE=docker
  elif command -v podman >/dev/null 2>&1; then ENGINE=podman
  else echo "Error: neither docker nor podman on PATH." >&2; exit 1; fi
fi
case "$ENGINE" in
  docker) COMPOSE=(docker compose) ;;
  podman) COMPOSE=(podman compose) ;;
  *) echo "Error: unknown CONTAINER_ENGINE='$ENGINE'." >&2; exit 1 ;;
esac

# --- 1) admin token ---------------------------------------------------------
step "1) Get a Keycloak admin token from $KC_BASE"
TOKEN="$(curl -sk --fail \
  -d "client_id=admin-cli&grant_type=password&username=${KC_ADMIN_USER}&password=${KC_ADMIN_PASSWORD}" \
  "${KC_BASE}/realms/master/protocol/openid-connect/token" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')" || {
    echo "Error: could not get admin token. Is Keycloak up at $KC_BASE?" >&2; exit 1; }
echo "  token: ${#TOKEN} chars"

# --- 2) ensure .envrc exists ------------------------------------------------
if [ ! -f "$ENVRC" ]; then
  step "2) .envrc not found — creating a template at $ENVRC"
  cat > "$ENVRC" <<'EOF'
# Local config — gitignored.
export RESEND_API_TOKEN=""
export POC_BRANDING_API_TOKEN=""

# BFF Tier 2/3 fine-grained authz. Needs P1 reachable on the host (:8888).
# Set to "false" to run coarse @Secured only (no P1 dependency).
export AUTHZ_FINE_ENABLED="true"
export P1_AUTHZ_URL="http://host.docker.internal:8888"

# Confidential OIDC client secrets — synced from the live realm by
# scripts/sync-bff-secrets.sh.
EOF
else
  step "2) Using existing .envrc at $ENVRC"
fi

# --- helper: upsert `export VAR="value"` into .envrc ------------------------
upsert_envrc() {
  local var="$1" val="$2"
  python3 - "$ENVRC" "$var" "$val" <<'PY'
import sys, re
path, var, val = sys.argv[1], sys.argv[2], sys.argv[3]
line = f'export {var}="{val}"'
with open(path) as f:
    text = f.read()
pat = re.compile(rf'^[ \t]*export[ \t]+{re.escape(var)}=.*$', re.M)
if pat.search(text):
    text = pat.sub(line, text)
else:
    if text and not text.endswith("\n"):
        text += "\n"
    text += line + "\n"
with open(path, "w") as f:
    f.write(text)
PY
}

# --- 3) read each secret from KC and write it into .envrc -------------------
step "3) Read client secrets from realm '$KC_REALM' and update .envrc"
for pair in "${PAIRS[@]}"; do
  client_id="${pair%%:*}"
  var="${pair##*:}"

  uuid="$(curl -sk --fail -H "Authorization: Bearer $TOKEN" \
    "${KC_BASE}/admin/realms/${KC_REALM}/clients?clientId=${client_id}" \
    | python3 -c 'import sys,json;a=json.load(sys.stdin);print(a[0]["id"] if a else "")')"
  if [ -z "$uuid" ]; then
    echo "  ! client '$client_id' not found in realm — skipping" >&2
    continue
  fi

  secret="$(curl -sk --fail -H "Authorization: Bearer $TOKEN" \
    "${KC_BASE}/admin/realms/${KC_REALM}/clients/${uuid}/client-secret" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin).get("value",""))')"
  if [ -z "$secret" ]; then
    echo "  ! '$client_id' has no secret (is it a public client?) — skipping" >&2
    continue
  fi

  upsert_envrc "$var" "$secret"
  echo "  $client_id -> $var  (${#secret} chars)"
done

# --- 4) recreate the BFFs with the new env ----------------------------------
if [ "$RESTART" -eq 1 ]; then
  step "4) Recreate bff-billing + bff-trading with the synced secrets"
  # shellcheck disable=SC1090
  source "$ENVRC"
  "${COMPOSE[@]}" up -d --force-recreate bff-billing bff-trading
  echo
  echo "Done. Tail the logs to confirm the OpenID bean initialised:"
  echo "  ${COMPOSE[*]} logs --tail=40 bff-billing | grep -iE 'openid|disabled|started'"
else
  step "4) --no-restart given; .envrc updated, containers untouched"
  echo "Apply later with:  source .envrc && ${COMPOSE[*]} up -d --force-recreate bff-billing bff-trading"
fi
