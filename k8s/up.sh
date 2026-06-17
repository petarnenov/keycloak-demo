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
# Minikube profile. Override (MINIKUBE_PROFILE) to run a second cluster or to dodge
# a stale profile of the same name created with a different driver (e.g. a leftover
# rootful-podman "geowealth" blocks a docker "geowealth" with GUEST_DRIVER_MISMATCH).
PROFILE="${MINIKUBE_PROFILE:-geowealth}"
# Cluster size. Defaults fit the base stack on a modest host; bump on a big box to
# schedule more P1 agents / replicas, e.g. `MINIKUBE_MEM_MIB=49152 MINIKUBE_CPUS=12
# ./k8s/up.sh`. NOTE: minikube fixes memory/CPU at cluster CREATION — to resize an
# existing cluster you must `minikube -p geowealth delete` first, then re-run.
NEED_MEM_MIB="${MINIKUBE_MEM_MIB:-12288}"   # Oracle+ES+P1+KC+rest need a sizeable cluster
MINIKUBE_CPUS="${MINIKUBE_CPUS:-4}"

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
  log "Starting minikube ($PROFILE, ${NEED_MEM_MIB}MiB/${MINIKUBE_CPUS}cpu)"
  minikube start -p "$PROFILE" --driver=docker --cpus="$MINIKUBE_CPUS" --memory="$NEED_MEM_MIB"
else
  # Cluster already exists — warn if its memory differs from the requested size,
  # since `minikube start` will NOT resize a running cluster (delete + re-create).
  RUNNING_MEM="$(minikube -p "$PROFILE" config view 2>/dev/null | awk '/^- memory:/{print $3}')"
  if [ -n "$RUNNING_MEM" ] && [ "$RUNNING_MEM" != "$NEED_MEM_MIB" ]; then
    echo "NOTE: cluster '$PROFILE' was created with ${RUNNING_MEM} MiB; requested ${NEED_MEM_MIB} MiB"
    echo "      has NO effect on a running cluster. To apply: minikube -p $PROFILE delete && re-run."
  fi
fi
minikube -p "$PROFILE" addons enable ingress >/dev/null 2>&1 || true
eval "$(minikube -p "$PROFILE" docker-env)"

# --- 2. build + (images already in minikube's docker via docker-env) ---------
log "Building images into the minikube docker daemon"
# Baked Oracle: schema + Flyway seeds + R__local_login_hash pre-loaded into
# datafiles at build time. Replaces the old gvenzl-base + Flyway-Job flow that
# ran on every fresh up.sh. First run: ~3-5 min build. Re-run: Docker cache
# short-circuits to seconds. db/local/R__local_login_hash.sql, if present in
# the build context, gets baked in (image lives only in this minikube docker).
docker build -t keycloak-demo-db:seeded -f db/Dockerfile db
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
# CRITICAL ORDER: the overlay ships a PLACEHOLDER Secret for the SAML keystore.
# Load the real value AFTER applying it so it overrides the placeholder. (The
# DB login hash USED to live here too; it's now baked into the seeded Oracle
# image at docker-build time via db/local/R__local_login_hash.sql — see the
# build above; nothing to load here for the DB.)
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

# Wave 1: Oracle (baked image — first pod copies datafiles from the image's
# baked snapshot into the empty PVC ~30-60s; pod-restart on an existing PVC is
# ~10s). No separate seed Job: the schema + Flyway seeds are already in the
# datafiles inside the image.
wait_ready statefulset/oracle 300s
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

# --- 4.5 env-driven URL reconcile -------------------------------------------
# Everything URL/port-specific lives in k8s/env/urls.<env>.env (single source).
URLS_ENV="${URLS_ENV:-k8s/env/urls.dev.env}"

# (a) The token-handler does OIDC discovery against the BROWSER-facing KC issuer
#     (auth.geowealth.int), which doesn't resolve inside the pod — point it at the
#     in-cluster kc-ext Service via a hostAlias (its ClusterIP is only known now).
log "Pointing token-handler at kc-ext for in-cluster issuer discovery"
KCEXT_IP="$(kc get svc kc-ext -o jsonpath='{.spec.clusterIP}')"
kc patch deployment/token-handler --type merge \
  -p "{\"spec\":{\"template\":{\"spec\":{\"hostAliases\":[{\"ip\":\"${KCEXT_IP}\",\"hostnames\":[\"auth.geowealth.int\"]}]}}}}"
kc rollout status deployment/token-handler --timeout=180s || true

# (b) Reconcile the realm's env-specific URLs (IdP SAML endpoints + client
#     redirect/web-origin/post-logout/back-channel) from the same env file. Realm
#     import is IGNORE_EXISTING, so this is the only path that drives a running
#     realm. Reached via a short-lived admin port-forward (the browser-facing KC
#     host isn't routable from this script), so override KC_ADMIN_BASE.
log "Reconciling realm URLs from ${URLS_ENV}"
kc port-forward svc/keycloak 18080:8080 >/dev/null 2>&1 &
PF_PID=$!
sleep 3
KC_ADMIN_BASE="http://localhost:18080" ./scripts/reconcile-realm.sh "$URLS_ENV" || \
  log "WARNING: realm reconcile failed — check ${URLS_ENV} and KC admin creds"
kill "$PF_PID" 2>/dev/null || true

# --- 5. report --------------------------------------------------------------
IP=$(minikube -p "$PROFILE" ip)
log "DONE. Add to /etc/hosts:"
echo "  $IP billing.geowealth.int trading.geowealth.int auth.geowealth.int p1.geowealth.int"
kc get pods
