#!/usr/bin/env bash
# One command to start backend hot-reload dev against the K8s stack.
#
#   ./scripts/dev-be.sh token-handler   # Micronaut continuous on :9080
#   ./scripts/dev-be.sh bff-billing     # Micronaut continuous on :8084
#   ./scripts/dev-be.sh bff-trading     # Micronaut continuous on :8085
#   ./scripts/dev-be.sh p1-tomcat       # GeoWealth gradle watch + local Tomcat
#
# Scales the matching in-cluster Deployment to 0 to avoid port / logic conflicts.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$REPO_ROOT/scripts/dev-common.sh"

TARGET="${1:-token-handler}"
TOMCAT_HOME="${TOMCAT_HOME:-$HOME/tools/tomcat9}"
TOMCAT_WEBAPP="${TOMCAT_WEBAPP:-$TOMCAT_HOME/webapps/ROOT}"
DEV_NAME="${DEV_NAME:-petar}"

require_cluster
require_cmd java

watch_bff_core() {
  local port="$1"
  dev_log "Watching bff-core/src — recompile + restart on save"
  exec bash -c '
    APP_PID=""
    STAMP=$(mktemp)
    touch "$STAMP"
    start() {
      [ -n "$APP_PID" ] && kill "$APP_PID" 2>/dev/null && wait "$APP_PID" 2>/dev/null || true
      ./gradlew :bff-core:compileJava run --no-daemon -q \
        -Dmicronaut.server.port="'"$port"'" &
      APP_PID=$!
    }
    trap "[ -n \"$APP_PID\" ] && kill \"$APP_PID\" 2>/dev/null" EXIT
    start
    if command -v inotifywait >/dev/null; then
      while inotifywait -r -e modify,create,delete \
          ../bff-core/src/main/java ../bff-core/src/main/resources 2>/dev/null; do
        echo "[dev-be] change detected $(date +%H:%M:%S) — rebuilding..."
        start
      done
    else
      echo "[dev-be] inotifywait not found — polling every 2s (install inotify-tools)"
      while sleep 2; do
        if find ../bff-core/src/main/java ../bff-core/src/main/resources -type f -newer "$STAMP" -print -quit | grep -q .; then
          touch "$STAMP"
          echo "[dev-be] change detected $(date +%H:%M:%S) — rebuilding..."
          start
        fi
      done
    fi
  '
}

run_micronaut() {
  local module="$1" port="$2" deploy="$3" app_name="$4"
  micronaut_dev_env
  stop_pf token-handler
  free_host_port "$port"
  scale_deployment "$deploy" 0
  ensure_infra_pfs
  export MICRONAUT_SERVER_PORT="$port"
  export MICRONAUT_APPLICATION_NAME="$app_name"
  dev_log "Starting $module on :$port (./gradlew run --continuous)"
  dev_log "Cluster deploy/$deploy scaled to 0"
  cd "$REPO_ROOT/$module"
  if [ "$module" = token-handler ]; then
    watch_bff_core "$port"
  fi
  exec ./gradlew run --continuous --no-daemon \
    -Dmicronaut.server.port="$port"
}

case "$TARGET" in
  token-handler)
    run_micronaut token-handler 9080 token-handler token-handler
    ;;
  bff-billing)
    micronaut_dev_env
    scale_deployment bff-billing 0
    ensure_billing_pfs
    export MICRONAUT_SERVER_PORT=8084
    export MICRONAUT_APPLICATION_NAME=billing-bff
    export OAUTH_CLIENT_ID=demo-billing-client
    cd "$REPO_ROOT/domains/billing/bff"
    dev_log "Starting billing BFF on :8084 (./gradlew run --continuous)"
    exec ./gradlew run --continuous --no-daemon -Dmicronaut.server.port=8084
    ;;
  bff-trading)
    micronaut_dev_env
    scale_deployment bff-trading 0
    ensure_trading_pfs
    export MICRONAUT_SERVER_PORT=8085
    export MICRONAUT_APPLICATION_NAME=trading-bff
    export OAUTH_CLIENT_ID=demo-trading-client
    cd "$REPO_ROOT/domains/trading/bff"
    dev_log "Starting trading BFF on :8085 (./gradlew run --continuous)"
    exec ./gradlew run --continuous --no-daemon -Dmicronaut.server.port=8085
    ;;
  p1-tomcat)
    require_cmd curl
    [ -d "$GEOWEALTH_DIR" ] || dev_die "GeoWealth not found at $GEOWEALTH_DIR"
    [ -x "$TOMCAT_HOME/bin/startup.sh" ] || \
      dev_die "Local Tomcat not found at TOMCAT_HOME=$TOMCAT_HOME (set env or install tomcat9)"
    ensure_infra_pfs
    scale_deployment p1-tomcat 0
    dev_log "Building GeoWealth devClasses (first run may take minutes)..."
    cd "$GEOWEALTH_DIR"
    JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
    ./gradlew devClasses -Ptarget="$DEV_NAME" --no-daemon
    dev_log "Syncing devBuild/classes -> $TOMCAT_WEBAPP/WEB-INF/classes"
    mkdir -p "$TOMCAT_WEBAPP/WEB-INF/classes"
    rsync -a --delete "$GEOWEALTH_DIR/devBuild/classes/" "$TOMCAT_WEBAPP/WEB-INF/classes/"
    if pgrep -f "org.apache.catalina.startup.Bootstrap" >/dev/null; then
      dev_log "Restarting local Tomcat..."
      "$TOMCAT_HOME/bin/shutdown.sh" >/dev/null 2>&1 || true
      sleep 3
    fi
    "$TOMCAT_HOME/bin/startup.sh" >/dev/null 2>&1
    dev_log "Watching Java sources — rsync + Tomcat reload on change"
    exec bash -c '
      cd "'"$GEOWEALTH_DIR"'" && \
      ./gradlew -t devClasses -Ptarget="'"$DEV_NAME"'" --no-daemon &
      GPID=$!
      trap "kill $GPID 2>/dev/null" EXIT
      while inotifywait -r -e modify,create,delete \
          src/main/java src/main/resources WebContent/WEB-INF 2>/dev/null; do
        ./gradlew devClasses -Ptarget="'"$DEV_NAME"'" --no-daemon -q && \
        rsync -a --delete "'"$GEOWEALTH_DIR"'/devBuild/classes/" "'"$TOMCAT_WEBAPP"'/WEB-INF/classes/" && \
        echo "[dev-be] synced $(date +%H:%M:%S) — reload Tomcat context or wait for auto-reload"
      done
    '
    ;;
  -h|--help|help)
    sed -n '2,12p' "$0"
    ;;
  *)
    dev_die "Unknown target '$TARGET' (token-handler|bff-billing|bff-trading|p1-tomcat)"
    ;;
esac
