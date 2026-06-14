#!/usr/bin/env bash
# k8s/up.sh — ONE command to stand up the ENTIRE system on Kubernetes:
# data (Oracle seeded from db/ + ES + memcached + kc-postgres + redis) + identity
# (Keycloak + token-handler + BFFs + web) + legacy P1 (Tomcat + Akka agents).
#
#   ./k8s/up.sh            # build images, ensure cluster, deploy in ordered waves
#   ./k8s/up.sh --down     # tear the whole namespace down
#
# Idempotent: re-running re-applies + re-waits. Secrets (realm, mkcert certs, SAML
# keystore, DB login hash) are loaded from the local repo files, never baked.
set -euo pipefail
cd "$(dirname "$0")/.."

NS=geowealth-demo
OVERLAY=k8s/overlays/full-stack
PROFILE=geowealth
NEED_MEM_MIB=12288        # Oracle+ES+P1+KC+rest need a sizeable cluster

kc() { kubectl -n "$NS" "$@"; }
log() { printf '\n\033[1;36m>>> %s\033[0m\n' "$*"; }

if [ "${1:-}" = "--down" ]; then
  log "Tearing down namespace $NS"
  kubectl delete ns "$NS" --ignore-not-found
  exit 0
fi

# --- 0. preflight: Docker memory must hold the heavy stack -------------------
DOCKER_MEM=$(docker info --format '{{.MemTotal}}' 2>/dev/null || echo 0)
DOCKER_MEM_MIB=$(( DOCKER_MEM / 1024 / 1024 ))
if [ "$DOCKER_MEM_MIB" -lt $((NEED_MEM_MIB + 2048)) ]; then
  echo "WARNING: Docker has ${DOCKER_MEM_MIB} MiB; the full heavy stack (Oracle+ES+P1)"
  echo "         wants a ~${NEED_MEM_MIB} MiB minikube + headroom. Raise Docker Desktop"
  echo "         memory (Settings > Resources) or expect Oracle/ES/P1 to be unschedulable."
fi

# --- 1. ensure a minikube cluster sized for the stack -----------------------
if ! minikube -p "$PROFILE" status >/dev/null 2>&1; then
  log "Starting minikube ($PROFILE, ${NEED_MEM_MIB}MiB/4cpu)"
  minikube start -p "$PROFILE" --driver=docker --cpus=4 --memory="$NEED_MEM_MIB"
fi
minikube -p "$PROFILE" addons enable ingress >/dev/null 2>&1 || true
eval "$(minikube -p "$PROFILE" docker-env)"

# --- 2. build + (images already in minikube's docker via docker-env) ---------
log "Building images into the minikube docker daemon"
docker build -f k8s/images/db-seed/Dockerfile -t keycloak-demo-db-seed:latest .
# Domain images (compose builds them; here we build directly).
docker build -t keycloak-demo-token-handler:latest -f token-handler/Dockerfile .
docker build -t keycloak-demo-billing-bff:latest   -f domains/billing/bff/Dockerfile .
docker build -t keycloak-demo-trading-bff:latest   -f domains/trading/bff/Dockerfile .
docker build -t keycloak-demo-billing-web:latest   domains/billing/web
docker build -t keycloak-demo-trading-web:latest   domains/trading/web
# P1 image (heavy — gradle devClasses). Skip if the geowealth repo isn't present.
if [ -d "${GEOWEALTH_DIR:-$HOME/geowealth}" ]; then
  log "Building P1 (geowealth) image — heavy"
  docker build -t keycloak-demo-geowealth:latest -f "${GEOWEALTH_DIR:-$HOME/geowealth}/k8s/Dockerfile" "${GEOWEALTH_DIR:-$HOME/geowealth}" || \
    echo "WARNING: P1 image build failed/skipped — P1 pods will be unschedulable."
fi
eval "$(minikube -p "$PROFILE" docker-env -u)"

# --- 3. namespace + apply the whole overlay (declarative) -------------------
kubectl get ns "$NS" >/dev/null 2>&1 || kubectl create ns "$NS"
log "Applying the full-stack overlay"
kubectl kustomize --load-restrictor LoadRestrictionsNone "$OVERLAY" | kc apply -f -

# --- 3b. real secrets from local files, AFTER the overlay --------------------
# CRITICAL ORDER: the overlay ships PLACEHOLDER Secrets (db-login-hash, SAML
# keystore). Load the real values AFTER applying it so they override the
# placeholders BEFORE the seed Job's flyway container runs (it waits ~10 min on
# Oracle's initContainer, by which time the updated Secret volume has propagated).
# Loading before the apply — as an earlier version did — let the placeholder win,
# so the seed ran with NO login hash and every user's LDAP_PSWD_HASH stayed NULL.
if [ -f db/local/R__local_login_hash.sql ]; then
  log "Loading DB login hash Secret from db/local/"
  kc create secret generic db-login-hash \
    --from-file=R__local_login_hash.sql=db/local/R__local_login_hash.sql \
    --dry-run=client -o yaml | kc apply -f -
else
  echo "  WARNING: db/local/R__local_login_hash.sql absent — no user can log in."
fi
load_tls() { # <secret> <crt> <key>
  [ -f "$2" ] && [ -f "$3" ] && kc create secret tls "$1" --cert="$2" --key="$3" \
    --dry-run=client -o yaml | kc apply -f - || echo "  (skip $1: certs absent)"
}
log "Loading mkcert TLS Secrets"
load_tls web-billing-tls proxy/certs/billing.geowealth.int.crt proxy/certs/billing.geowealth.int.key
load_tls web-trading-tls proxy/certs/trading.geowealth.int.crt proxy/certs/trading.geowealth.int.key
load_tls auth-tls        proxy/certs/auth.geowealth.int.crt    proxy/certs/auth.geowealth.int.key
load_tls p1-tls          proxy/certs/billing.geowealth.int.crt proxy/certs/billing.geowealth.int.key
if [ -f /tmp/p1-idp-dev.p12 ]; then
  log "Loading SAML keystore Secret from /tmp/p1-idp-dev.p12"
  kc create secret generic p1-saml-keystore --from-file=idp.p12=/tmp/p1-idp-dev.p12 \
    --dry-run=client -o yaml | kc apply -f -
fi

# --- 4. wave-wait -----------------------------------------------------------

wait_ready() { # <kind/name> <timeout>
  log "Waiting for $1 (${2})"
  kc rollout status "$1" --timeout="$2" || kc get pods
}
wait_job() { kc wait --for=condition=complete "job/$1" --timeout="$2" || kc logs "job/$1" --tail=40; }

# Wave 1: Oracle (slow first-init: EXTENDED + 5.9MB V1) -> seed -> infra
wait_ready statefulset/oracle 900s
wait_job  oracle-seed 600s
wait_ready statefulset/elasticsearch 300s
wait_ready statefulset/kc-postgres 120s
wait_ready deployment/memcached 120s
wait_ready statefulset/redis 120s
# Wave 2: Keycloak (realm import)
wait_ready statefulset/keycloak 300s
# Wave 3: P1 — coordinator (seed) then agents then Tomcat
wait_ready statefulset/p1-coordinator 300s || true
wait_ready deployment/p1-samlmanager 300s || true
wait_ready statefulset/p1-tomcat 600s || true
# Wave 4: auth + data + web
wait_ready deployment/token-handler 180s
wait_ready deployment/bff-billing 180s
wait_ready deployment/bff-trading 180s
wait_ready deployment/web-billing 120s
wait_ready deployment/web-trading 120s

# --- 5. report --------------------------------------------------------------
IP=$(minikube -p "$PROFILE" ip)
log "DONE. Add to /etc/hosts:"
echo "  $IP billing.geowealth.int trading.geowealth.int auth.geowealth.int p1.geowealth.int"
kc get pods
