#!/usr/bin/env bash
# k8s/portforward.sh — local-dev port-forwards for everything you usually need
# in a browser or a CLI. Persistent (nohup), idempotent (kills its own stale
# pids first), logs to /tmp/pf-<name>.log. Each forward is independent — one
# crash doesn't kill the others.
#
#   ./k8s/portforward.sh           # start all
#   ./k8s/portforward.sh --stop    # stop everything this script started
#   ./k8s/portforward.sh --status  # PIDs + listen state
#   ./k8s/portforward.sh --open    # start + open the UIs in browser
#
# Forwards (host:port -> svc:port [namespace]):
#   3000 -> kps-grafana:80                    [monitoring]   Grafana UI
#   9090 -> kps-prometheus:9090               [monitoring]   Prometheus UI
#   9093 -> kps-kube-prom-alertmanager:9093   [monitoring]   Alertmanager UI
#   9121 -> redis-exporter:9121               [monitoring]   raw redis metrics
#   9187 -> pg-exporter:80                    [monitoring]   raw postgres metrics
#   8080 -> p1-tomcat:8080                    [geowealth-demo] P1 web (SPA)
#   5180 -> kc-ext:8080                       [geowealth-demo] Keycloak admin
#   6379 -> redis:6379                        [geowealth-demo] redis-cli (needs auth)
#   1521 -> oracle:1521                       [geowealth-demo] sqlplus / DBeaver
#   9200 -> elasticsearch:9200                [geowealth-demo] ES REST
set -euo pipefail

export PATH="$HOME/.local/bin:$PATH"

PIDDIR=/tmp/k8s-pf
mkdir -p "$PIDDIR"

# name  host_port  svc                              ns                target_port
FWDS=(
  "grafana       3000  svc/kps-grafana                         monitoring      80"
  "prometheus    9090  svc/kps-prometheus                      monitoring      9090"
  "alertmanager  9093  svc/kps-alertmanager                    monitoring      9093"
  "redis-exp     9121  svc/redis-exporter-prometheus-redis-exporter monitoring 9121"
  "pg-exp        9187  svc/pg-exporter-prometheus-postgres-exporter monitoring 80"
  "p1-tomcat     8080  svc/p1-tomcat                           geowealth-demo  8080"
  "keycloak      5180  svc/kc-ext                              geowealth-demo  5180"
  "redis         6379  svc/redis                               geowealth-demo  6379"
  "oracle        1521  svc/oracle                              geowealth-demo  1521"
  "elasticsearch 9200  svc/elasticsearch                       geowealth-demo  9200"
  # Domain SPA front-ends — nginx in the pod serves PLAIN HTTP on the
  # Service port (TLS terminates at the cluster Ingress). Browsers treat
  # `localhost` as a secure context, so keycloak-js works even on HTTP.
  "web-billing   5184  svc/web-billing                         geowealth-demo  5184"
  "web-trading   5185  svc/web-trading                         geowealth-demo  5185"
  "web-portfolio 5186  svc/web-portfolio                       geowealth-demo  5186"
  "token-handler 9080  svc/token-handler                       geowealth-demo  8080"
  "authz-service 8090  svc/authz-service                       geowealth-demo  8080"
  "bff-billing   8084  svc/bff-billing                         geowealth-demo  8080"
  "bff-trading   8085  svc/bff-trading                         geowealth-demo  8080"
  "bff-portfolio 8086  svc/bff-portfolio                       geowealth-demo  8080"
  "web-custodian 5187 svc/web-custodian geowealth-demo 5187"
  "bff-custodian 8087 svc/bff-custodian geowealth-demo 8080"
  # >>> add-domain.sh inserts new domain port-forwards below this line <<<
)

log() { printf '\033[1;36m>>> %s\033[0m\n' "$*"; }

# True when host_port is intentionally owned outside kubectl port-forward.
pf_port_owned_locally() {
  local name="$1" host_port="$2"
  case "$name" in
    p1-tomcat)
      # Skip only when *host* dev Tomcat (dev-be.sh) owns :8080 — not catalina
      # inside the geowealth Docker VM, which does not publish the port.
      local tomcat_home="${TOMCAT_HOME:-$HOME/tools/tomcat9}"
      ss -lnt 2>/dev/null | grep -q ':8080 ' && \
        pgrep -f "Dcatalina.home=${tomcat_home}" >/dev/null 2>&1
      ;;
    web-billing|web-trading)
      ss -lnt 2>/dev/null | grep -q "0.0.0.0:${host_port} "
      ;;
    *)
      return 1
      ;;
  esac
}

stop_one() {
  local name="$1"
  local pidf="$PIDDIR/${name}.pid"
  [ -f "$pidf" ] || return 0
  local pid; pid=$(cat "$pidf" 2>/dev/null || true)
  if [ -n "${pid:-}" ] && kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    # Give it a moment, then SIGKILL if still alive.
    sleep 0.2
    kill -9 "$pid" 2>/dev/null || true
  fi
  rm -f "$pidf"
}

start_one() {
  local name="$1" host_port="$2" svc="$3" ns="$4" target_port="$5"
  stop_one "$name"
  if pf_port_owned_locally "$name" "$host_port"; then
    printf '  \033[1;33mSKIP\033[0m %-13s :%s owned by local dev process\n' "$name" "$host_port"
    return 0
  fi
  if ss -lnt 2>/dev/null | grep -q ":${host_port} "; then
    pkill -f "kubectl -n ${ns} port-forward.*:${host_port}" 2>/dev/null || true
    sleep 0.5
  fi
  local logf="/tmp/pf-${name}.log"
  # `kubectl port-forward` blocks; nohup + & detaches from this shell so
  # the forward survives the script exit. stdout/stderr goes to its log.
  nohup kubectl -n "$ns" port-forward --address 127.0.0.1 \
    "$svc" "${host_port}:${target_port}" \
    >"$logf" 2>&1 &
  local pid=$!
  echo "$pid" > "$PIDDIR/${name}.pid"
  # Quick liveness check — give kubectl 2s to either bind the port or die.
  sleep 2
  if ! kill -0 "$pid" 2>/dev/null; then
    printf '  \033[1;31mFAIL\033[0m %-13s %-50s (see %s)\n' "$name" "${svc} -> :${host_port}" "$logf"
    rm -f "$PIDDIR/${name}.pid"
    return 1
  fi
  if ! ss -lnt 2>/dev/null | grep -q ":${host_port} "; then
    printf '  \033[1;33mWARN\033[0m %-13s not listening on :%s (yet?) — log %s\n' "$name" "$host_port" "$logf"
  else
    printf '  \033[1;32mOK  \033[0m %-13s 127.0.0.1:%-5s -> %s @ %s\n' "$name" "$host_port" "$svc" "$ns"
  fi
}

cmd_start() {
  log "starting port-forwards (logs at /tmp/pf-*.log, pids at $PIDDIR)"
  local ok=0 fail=0
  for row in "${FWDS[@]}"; do
    # shellcheck disable=SC2086
    set -- $row
    if start_one "$1" "$2" "$3" "$4" "$5"; then ok=$((ok+1)); else fail=$((fail+1)); fi
  done
  log "summary: $ok up, $fail failed"
}

cmd_stop() {
  log "stopping port-forwards"
  for row in "${FWDS[@]}"; do
    # shellcheck disable=SC2086
    set -- $row
    stop_one "$1"
    printf '  stopped %s\n' "$1"
  done
}

cmd_status() {
  printf '%-15s %-7s %-8s %s\n' NAME PORT PID STATE
  for row in "${FWDS[@]}"; do
    # shellcheck disable=SC2086
    set -- $row
    local name="$1" host_port="$2"
    local pidf="$PIDDIR/${name}.pid"
    local pid='-' state='down'
    if [ -f "$pidf" ]; then
      pid=$(cat "$pidf" 2>/dev/null || echo '-')
      if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        state='running'
        ss -lnt 2>/dev/null | grep -q ":${host_port} " && state='listening'
      else
        state='dead'
      fi
    fi
    printf '%-15s %-7s %-8s %s\n' "$name" "$host_port" "$pid" "$state"
  done
}

cmd_open() {
  cmd_start
  for url in http://localhost:3000 http://localhost:9090 http://localhost:9093; do
    xdg-open "$url" >/dev/null 2>&1 &
  done
  disown -a 2>/dev/null || true
  log 'opened Grafana / Prometheus / Alertmanager in browser'
}

case "${1:-start}" in
  start|up|"")     cmd_start ;;
  stop|down)       cmd_stop ;;
  status|st)       cmd_status ;;
  open|browser)    cmd_open ;;
  restart)         cmd_stop; cmd_start ;;
  -h|--help|help)  sed -n '2,20p' "$0" ;;
  *) printf 'unknown: %s\n' "$1" >&2; exit 2 ;;
esac
