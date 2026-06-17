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

# Data-tier endpoint config (Oracle / Elasticsearch / Memcached). Read from
# k8s/env/data-tier.<env>.env; each *_HOST is either the in-cluster Service
# name (default) or an external DNS hostname. data_tier_pre_align (run BEFORE
# kustomize apply) deletes a stale ExternalName Service so apply can re-create
# the in-cluster ClusterIP Service; data_tier_redirect (run AFTER apply) swaps
# any Service whose host is external to type=ExternalName and scales the
# in-cluster workload to 0. See k8s/env/data-tier.dev.env for the full story.
DATA_TIER_ENV="${DATA_TIER_ENV:-k8s/env/data-tier.dev.env}"
if [ -f "$DATA_TIER_ENV" ]; then
  set -a; . "$DATA_TIER_ENV"; set +a
fi
ORACLE_HOST="${ORACLE_HOST:-oracle}"
ORACLE_PDB="${ORACLE_PDB:-FREEPDB1}"
ORACLE_USER="${ORACLE_USER:-gp}"
ELASTICSEARCH_HOST="${ELASTICSEARCH_HOST:-elasticsearch}"
MEMCACHED_HOST="${MEMCACHED_HOST:-memcached}"

data_tier_pre_align() {
  # If env says in-cluster but live Service is ExternalName, delete it now —
  # otherwise kustomize apply will choke (Service .spec.type is immutable).
  local svc host
  for entry in "oracle:$ORACLE_HOST" "elasticsearch:$ELASTICSEARCH_HOST" "memcached:$MEMCACHED_HOST"; do
    svc=${entry%%:*}; host=${entry#*:}
    if [ "$host" = "$svc" ] && \
       [ "$(kc get svc "$svc" -o jsonpath='{.spec.type}' 2>/dev/null)" = "ExternalName" ]; then
      log "data-tier: restoring in-cluster svc/$svc (was ExternalName)"
      kc delete svc "$svc"
    fi
  done
}

data_tier_redirect_one() { # <svc> <host> <workload-kind/name>
  local svc=$1 host=$2 workload=$3
  [ "$host" = "$svc" ] && return  # in-cluster — kustomize default is correct
  log "data-tier: pointing svc/$svc at external host '$host' (scale $workload to 0)"
  kc scale "$workload" --replicas=0 2>/dev/null || true
  # Service .spec.type is immutable; the freshly-applied ClusterIP must be
  # replaced. kc delete is idempotent (--ignore-not-found); the apply that
  # follows recreates it as ExternalName.
  kc delete svc "$svc" --ignore-not-found
  kc apply -f - <<EOF
apiVersion: v1
kind: Service
metadata:
  name: $svc
  labels: { app.kubernetes.io/name: $svc }
spec:
  type: ExternalName
  externalName: $host
EOF
}

data_tier_redirect() {
  data_tier_redirect_one oracle        "$ORACLE_HOST"        statefulset/oracle
  data_tier_redirect_one elasticsearch "$ELASTICSEARCH_HOST" statefulset/elasticsearch
  data_tier_redirect_one memcached     "$MEMCACHED_HOST"     deployment/memcached
}

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
# Before the apply: if any data-tier Service is currently ExternalName (from a
# previous run) but the env file now says in-cluster, delete it so the apply
# can re-create the ClusterIP Service (Service.spec.type is immutable).
data_tier_pre_align
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

# --- 3c. data-tier redirects (Oracle / Elasticsearch / Memcached) -----------
# Driven by k8s/env/data-tier.<env>.env (override via DATA_TIER_ENV). For each
# service: in-cluster mode → no-op (the apply above set up the default); external
# mode → swap the Service to ExternalName and scale the in-cluster workload to
# 0. Done BEFORE wave-wait so wait_ready doesn't sit on a workload we just
# scaled down.
log "Aligning data-tier endpoints with ${DATA_TIER_ENV}"
data_tier_redirect

# JDBC config for P1 (SERVICE_NAME + schema user). Always re-applied so a swap
# of DATA_TIER_ENV (FREEPDB1 <-> ORCL12VM, etc.) re-drives the live ConfigMap.
# P1's containers consume this via envFrom; the entrypoint substitutes
# ${ORACLE_PDB}/${ORACLE_USER} into hibernate.properties at startup.
log "Applying oracle-config ConfigMap (ORACLE_PDB=${ORACLE_PDB}, ORACLE_USER=${ORACLE_USER})"
kc create configmap oracle-config \
  --from-literal=ORACLE_PDB="${ORACLE_PDB}" \
  --from-literal=ORACLE_USER="${ORACLE_USER}" \
  --dry-run=client -o yaml | kc apply -f -

# Password is a credential — only kept on the cluster if explicitly supplied
# via shell env on the up.sh invocation (e.g. ORACLE_PASSWORD=... ./k8s/up.sh).
# Absent: no Secret, and P1's entrypoint falls back to its default (gp123), which
# is the in-cluster baked-image password.
if [ -n "${ORACLE_PASSWORD:-}" ]; then
  log "Applying oracle-creds Secret (ORACLE_PASSWORD from shell env)"
  kc create secret generic oracle-creds \
    --from-literal=ORACLE_PASSWORD="${ORACLE_PASSWORD}" \
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
