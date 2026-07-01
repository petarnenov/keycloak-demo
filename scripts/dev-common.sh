#!/usr/bin/env bash
# Shared helpers for ./scripts/dev-fe.sh and ./scripts/dev-be.sh — hybrid dev on
# top of the minikube stack (infra in-cluster, hot-reload on the host).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NS="${K8S_NAMESPACE:-geowealth-demo}"
GEOWEALTH_DIR="${GEOWEALTH_DIR:-$HOME/geowealth}"
PIDDIR=/tmp/k8s-pf
MKCERT_ROOT="${MKCERT_ROOT:-$REPO_ROOT/proxy/certs/rootCA.pem}"

dev_log() { printf '\033[1;36m>>> %s\033[0m\n' "$*"; }
dev_warn() { printf '\033[1;33m>>> %s\033[0m\n' "$*" >&2; }
dev_die() { printf '\033[1;31m>>> %s\033[0m\n' "$*" >&2; exit 1; }

kc() { kubectl -n "$NS" "$@"; }

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || dev_die "Missing '$1' on PATH."
}

require_cluster() {
  require_cmd kubectl
  kc get ns "$NS" >/dev/null 2>&1 || \
    dev_die "Namespace '$NS' not found. Run ./k8s/up.sh first."
}

# True when host_port is intentionally owned outside kubectl port-forward
# (e.g. local Tomcat dev on :8080, Vite dev on :5184/:5185).
pf_port_owned_locally() {
  local name="$1" host_port="$2"
  case "$name" in
    p1-tomcat)
      pgrep -f "org.apache.catalina.startup.Bootstrap" >/dev/null 2>&1
      ;;
    web-billing|web-trading|web-portfolio)
      # Vite/webpack dev servers bind 0.0.0.0; kubectl pf binds 127.0.0.1 only.
      ss -lnt 2>/dev/null | grep -q "0.0.0.0:${host_port} "
      ;;
    *)
      return 1
      ;;
  esac
}

# Start one kubectl port-forward if not already listening on host_port.
# Usage: ensure_pf <name> <host_port> <svc> <target_port>
ensure_pf() {
  local name="$1" host_port="$2" svc="$3" target_port="$4"
  mkdir -p "$PIDDIR"
  local pidf="$PIDDIR/${name}.pid" logf="/tmp/pf-${name}.log"

  if [ -f "$pidf" ]; then
    local oldpid
    oldpid=$(cat "$pidf" 2>/dev/null || true)
    if [ -n "${oldpid:-}" ] && kill -0 "$oldpid" 2>/dev/null; then
      ss -lnt 2>/dev/null | grep -q ":${host_port} " && return 0
    fi
    rm -f "$pidf"
  fi

  if ss -lnt 2>/dev/null | grep -q ":${host_port} "; then
    if pf_port_owned_locally "$name" "$host_port"; then
      return 0
    fi
    # Stale/orphan kubectl port-forward — reclaim the port.
    pkill -f "kubectl -n ${NS} port-forward.*:${host_port}" 2>/dev/null || true
    sleep 0.5
  fi

  dev_log "port-forward $svc -> 127.0.0.1:${host_port}"
  nohup kubectl -n "$NS" port-forward --address 127.0.0.1 \
    "$svc" "${host_port}:${target_port}" >"$logf" 2>&1 &
  echo $! >"$pidf"
  for _ in $(seq 1 20); do
    ss -lnt 2>/dev/null | grep -q ":${host_port} " && return 0
    sleep 0.25
  done
  dev_die "Port-forward '$name' failed (see $logf)"
}

scale_deployment() {
  local deploy="$1" replicas="$2"
  if kc get deploy "$deploy" >/dev/null 2>&1; then
    dev_log "scale deploy/$deploy -> $replicas (free host port / avoid duplicate)"
    kc scale "deploy/$deploy" --replicas="$replicas"
  fi
}

load_urls_env() {
  local f="${URLS_ENV:-$REPO_ROOT/k8s/env/urls.dev.env}"
  [ -f "$f" ] || dev_die "Missing $f"
  # Do NOT `source` the whole file — SPA_HOSTS contains unquoted spaces/colons
  # that break bash. Pull only the keys the dev scripts need.
  while IFS= read -r line; do
    [[ "$line" =~ ^[A-Z_]+= ]] || continue
    [[ "$line" =~ ^# ]] && continue
    case "$line" in
      KEYCLOAK_ISSUER=*|KEYCLOAK_AUTH_SERVER_URL=*|P1_AUTHZ_URL=*|APP_P1_INITIATE_SLO_URL=*)
        export "$line"
        ;;
    esac
  done < "$f"
}

redis_uri_from_cluster() {
  local pw
  pw=$(kc get secret redis-auth -o jsonpath='{.data.password}' 2>/dev/null | base64 -d || true)
  [ -n "$pw" ] || pw="${REDIS_PASSWORD:-dev-redis-pw-change-me}"
  printf 'redis://:%s@127.0.0.1:6379' "$pw"
}

micronaut_dev_env() {
  load_urls_env
  export REDIS_URI="${REDIS_URI:-$(redis_uri_from_cluster)}"
  export OAUTH_CLIENT_ID="${OAUTH_CLIENT_ID:-demo-shared-client}"
  export OAUTH_CLIENT_SECRET="${OAUTH_CLIENT_SECRET:-Sh4r3dMultiTenantDemoSecret012345}"
  export POC_BRANDING_API_TOKEN="${POC_BRANDING_API_TOKEN:-Br4nd1ngP0CDemoSh4r3dT0k3n0123456789}"
  export KEYCLOAK_ISSUER="${KEYCLOAK_ISSUER:-https://auth.geowealth.int:5180/realms/demo-realm}"
  export KEYCLOAK_AUTH_SERVER_URL="${KEYCLOAK_AUTH_SERVER_URL:-http://keycloak:8080/realms/demo-realm}"
  export P1_AUTHZ_URL="${P1_AUTHZ_URL:-http://127.0.0.1:8080}"
  export BRANDING_API_BASE_URL="${BRANDING_API_BASE_URL:-http://127.0.0.1:8080}"
  export AUTHZ_FINE_ENABLED="${AUTHZ_FINE_ENABLED:-true}"
  export SESSION_COOKIE_NAME="${SESSION_COOKIE_NAME:-GWSESSION}"
  # Dev-safe logout fallback (mirrors the full-stack K8s overlay): with no id_token
  # to hint KC, land on the request's own SPA origin instead of the P1 SLO URL —
  # P1 is not running in FE/BE-only dev, so its SLO target would refuse.
  export APP_P1_FALLBACK_TO_SPA="${APP_P1_FALLBACK_TO_SPA:-true}"
}

ensure_infra_pfs() {
  ensure_pf keycloak   5180 svc/kc-ext         5180
  ensure_pf redis        6379 svc/redis          6379
  ensure_pf p1-tomcat    8080 svc/p1-tomcat      8080
  ensure_pf oracle       1521 svc/oracle         1521
  ensure_pf elasticsearch 9200 svc/elasticsearch  9200
}

ensure_auth_pfs() {
  ensure_infra_pfs
  ensure_pf token-handler 9080 svc/token-handler 8080
}

ensure_billing_pfs() {
  ensure_auth_pfs
  ensure_pf bff-billing 8084 svc/bff-billing 8080
}

ensure_trading_pfs() {
  ensure_auth_pfs
  ensure_pf bff-trading 8085 svc/bff-trading 8080
}

ensure_portfolio_pfs() {
  ensure_auth_pfs
  ensure_pf bff-portfolio 8086 svc/bff-portfolio 8080
}

stop_pf() {
  local name="$1"
  local pidf="$PIDDIR/${name}.pid"
  [ -f "$pidf" ] || return 0
  local pid
  pid=$(cat "$pidf" 2>/dev/null || true)
  if [ -n "${pid:-}" ] && kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    sleep 0.2
    kill -9 "$pid" 2>/dev/null || true
  fi
  rm -f "$pidf"
}

free_host_port() {
  local port="$1"
  for row in \
    "web-billing:5184" \
    "web-trading:5185" \
    "web-portfolio:5186" \
    "token-handler:9080" \
    "bff-billing:8084" \
    "bff-trading:8085" \
    "bff-portfolio:8086"; do
    local name="${row%%:*}" p="${row#*:}"
    [ "$p" = "$port" ] && stop_pf "$name"
  done
  # Orphan kubectl port-forwards (started outside portforward.sh or stale pid files).
  pkill -f "kubectl -n ${NS} port-forward.*:${port}" 2>/dev/null || true
  sleep 0.3
}

mkcert_paths_for() {
  local host="$1"
  export HTTPS_CERT_PATH="${HTTPS_CERT_PATH:-$REPO_ROOT/proxy/certs/${host}.crt}"
  export HTTPS_KEY_PATH="${HTTPS_KEY_PATH:-$REPO_ROOT/proxy/certs/${host}.key}"
  [ -f "$HTTPS_CERT_PATH" ] && [ -f "$HTTPS_KEY_PATH" ] || \
    dev_die "Missing TLS cert for $host — run mkcert for proxy/certs/${host}.{crt,key}"
}
