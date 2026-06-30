#!/usr/bin/env bash
# k8s/monitoring.sh — install kube-prometheus-stack (Prometheus + Grafana +
# Alertmanager + node-exporter + kube-state-metrics) into namespace `monitoring`.
# Sized for a single-node minikube; tuned to coexist with the geowealth-demo
# stack (Oracle + ES + P1 + Keycloak) on a modest host.
#
#   ./k8s/monitoring.sh           # install or upgrade (idempotent)
#   ./k8s/monitoring.sh --down    # uninstall release + delete namespace
#   ./k8s/monitoring.sh --open    # port-forward Grafana to localhost:3000
#   ./k8s/monitoring.sh --status  # show pods + how to reach Grafana
#
# Defaults:
#   namespace        monitoring
#   release          kps
#   grafana admin    admin / admin   (override: GRAFANA_ADMIN_PASSWORD=...)
#   chart version    pinned via CHART_VERSION (default: latest in helm repo)
set -euo pipefail

NS="${MONITORING_NS:-monitoring}"
RELEASE="${MONITORING_RELEASE:-kps}"
CHART="prometheus-community/kube-prometheus-stack"
CHART_VERSION="${CHART_VERSION:-}"
GRAFANA_ADMIN_PASSWORD="${GRAFANA_ADMIN_PASSWORD:-admin}"
VALUES_FILE="$(dirname "$0")/monitoring-values.yaml"

log() { printf '\n\033[1;36m>>> %s\033[0m\n' "$*"; }
die() { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

ensure_helm() {
  if command -v helm >/dev/null 2>&1; then return; fi
  log "helm not found — installing"
  if command -v snap >/dev/null 2>&1; then
    sudo snap install helm --classic
  elif command -v brew >/dev/null 2>&1; then
    brew install helm
  else
    curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
  fi
  command -v helm >/dev/null 2>&1 || die "helm install failed"
}

ensure_repo() {
  if ! helm repo list 2>/dev/null | awk '{print $1}' | grep -qx prometheus-community; then
    log "adding prometheus-community helm repo"
    helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
  fi
  helm repo update prometheus-community >/dev/null
}

write_values() {
  # Right-sized for minikube. Disable bits that don't apply to minikube
  # (controller-manager / scheduler / etcd / kube-proxy ServiceMonitors fail
  # on minikube because the components don't expose metrics on the bind addr
  # the chart probes). 7-day retention keeps PV small.
  cat > "$VALUES_FILE" <<'YAML'
fullnameOverride: kps

defaultRules:
  create: true

alertmanager:
  alertmanagerSpec:
    resources:
      requests: { cpu: 10m,  memory: 64Mi }
      limits:   { cpu: 200m, memory: 256Mi }
    storage:
      volumeClaimTemplate:
        spec:
          accessModes: ["ReadWriteOnce"]
          resources:
            requests:
              storage: 2Gi

prometheus:
  prometheusSpec:
    retention: 7d
    retentionSize: "8GB"
    resources:
      requests: { cpu: 100m, memory: 512Mi }
      limits:   { cpu: 1,    memory: 2Gi }
    storageSpec:
      volumeClaimTemplate:
        spec:
          accessModes: ["ReadWriteOnce"]
          resources:
            requests:
              storage: 10Gi
    # Scrape ServiceMonitors in any namespace (so future demo apps can ship one).
    serviceMonitorSelectorNilUsesHelmValues: false
    podMonitorSelectorNilUsesHelmValues: false
    ruleSelectorNilUsesHelmValues: false

grafana:
  adminPassword: __REPLACE_ME__
  defaultDashboardsTimezone: browser
  persistence:
    enabled: true
    size: 2Gi
  resources:
    requests: { cpu: 50m,  memory: 128Mi }
    limits:   { cpu: 500m, memory: 512Mi }
  service:
    type: ClusterIP
  sidecar:
    dashboards:
      enabled: true
      searchNamespace: ALL
    datasources:
      enabled: true

kube-state-metrics:
  resources:
    requests: { cpu: 10m, memory: 32Mi }
    limits:   { cpu: 100m, memory: 128Mi }

prometheus-node-exporter:
  resources:
    requests: { cpu: 10m, memory: 32Mi }
    limits:   { cpu: 100m, memory: 128Mi }

# Components minikube doesn't expose metrics for — disable to avoid noisy
# "context deadline exceeded" scrape errors. Re-enable on a real cluster.
kubeControllerManager:
  enabled: false
kubeScheduler:
  enabled: false
kubeEtcd:
  enabled: false
kubeProxy:
  enabled: false
YAML
  # Substitute admin password without leaking it into the file via heredoc.
  sed -i.bak "s|__REPLACE_ME__|${GRAFANA_ADMIN_PASSWORD}|" "$VALUES_FILE" && rm -f "${VALUES_FILE}.bak"
}

install() {
  ensure_helm
  ensure_repo
  kubectl get ns "$NS" >/dev/null 2>&1 || kubectl create namespace "$NS"
  write_values
  log "helm upgrade --install $RELEASE $CHART (ns=$NS)"
  local args=(upgrade --install "$RELEASE" "$CHART"
              --namespace "$NS"
              --values "$VALUES_FILE"
              --wait --timeout 10m)
  [[ -n "$CHART_VERSION" ]] && args+=(--version "$CHART_VERSION")
  helm "${args[@]}"
  status
}

uninstall() {
  log "helm uninstall $RELEASE"
  helm uninstall "$RELEASE" --namespace "$NS" || true
  log "deleting PVCs (chart leaves them behind)"
  kubectl -n "$NS" delete pvc --all --ignore-not-found
  log "deleting namespace $NS"
  kubectl delete ns "$NS" --ignore-not-found
}

status() {
  log "pods in $NS"
  kubectl -n "$NS" get pods
  cat <<EOF

Grafana:    kubectl -n $NS port-forward svc/${RELEASE}-grafana 3000:80
            http://localhost:3000   (admin / ${GRAFANA_ADMIN_PASSWORD})
Prometheus: kubectl -n $NS port-forward svc/${RELEASE}-kube-prom-prometheus 9090:9090
Alertmgr:   kubectl -n $NS port-forward svc/${RELEASE}-kube-prom-alertmanager 9093:9093
EOF
}

open_grafana() {
  log "port-forwarding Grafana to http://localhost:3000  (admin / ${GRAFANA_ADMIN_PASSWORD})"
  exec kubectl -n "$NS" port-forward "svc/${RELEASE}-grafana" 3000:80
}

case "${1:-install}" in
  ""|install|up)   install ;;
  --down|down|uninstall) uninstall ;;
  --status|status) status ;;
  --open|open)     open_grafana ;;
  -h|--help|help)
    sed -n '2,20p' "$0" ;;
  *) die "unknown arg: $1 (try --help)" ;;
esac
