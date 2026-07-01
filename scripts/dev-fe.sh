#!/usr/bin/env bash
# One command to start frontend hot-reload dev against the K8s stack.
#
#   ./scripts/dev-fe.sh billing     # Vite HMR on https://billing.geowealth.int:5184
#   ./scripts/dev-fe.sh trading     # Vite HMR on https://trading.geowealth.int:5185
#   ./scripts/dev-fe.sh portfolio   # Vite HMR on https://portfolio.geowealth.int:5186
#   ./scripts/dev-fe.sh p1          # GeoWealth webpack-dev-server :8888 → P1 Tomcat
#
# Prerequisite: ./k8s/up.sh (infra running). Scales the in-cluster web Deployment
# to 0 so the host port is free for Vite/webpack.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$REPO_ROOT/scripts/dev-common.sh"

TARGET="${1:-billing}"

require_cluster

case "$TARGET" in
  billing)
    ensure_billing_pfs
    stop_pf web-billing
    free_host_port 5184
    scale_deployment web-billing 0
    mkcert_paths_for billing.geowealth.int
    export DEV_FORWARD_AUTH=1
    export TOKEN_HANDLER_URL=http://127.0.0.1:9080
    export BFF_BILLING_URL=http://127.0.0.1:8084
    dev_log "Starting billing Vite dev (HMR) — open https://billing.geowealth.int:5184"
    cd "$REPO_ROOT/domains/billing/web"
    exec npm run dev
    ;;
  trading)
    ensure_trading_pfs
    stop_pf web-trading
    free_host_port 5185
    scale_deployment web-trading 0
    mkcert_paths_for trading.geowealth.int
    export DEV_FORWARD_AUTH=1
    export TOKEN_HANDLER_URL=http://127.0.0.1:9080
    export BFF_TRADING_URL=http://127.0.0.1:8085
    dev_log "Starting trading Vite dev (HMR) — open https://trading.geowealth.int:5185"
    cd "$REPO_ROOT/domains/trading/web"
    exec npm run dev
    ;;
  portfolio)
    ensure_portfolio_pfs
    stop_pf web-portfolio
    free_host_port 5186
    scale_deployment web-portfolio 0
    mkcert_paths_for portfolio.geowealth.int
    export DEV_HOST=portfolio.geowealth.int
    export DEV_FORWARD_AUTH=1
    export TOKEN_HANDLER_URL=http://127.0.0.1:9080
    export BFF_PORTFOLIO_URL=http://127.0.0.1:8086
    dev_log "Starting portfolio Vite dev (HMR) — open https://portfolio.geowealth.int:5186"
    cd "$REPO_ROOT/domains/portfolio/web"
    exec npm run dev
    ;;
  p1)
    require_cmd node
    ensure_pf p1-tomcat 8080 svc/p1-tomcat 8080
    [ -d "$GEOWEALTH_DIR/WebContent/react/app" ] || \
      dev_die "GeoWealth not found at $GEOWEALTH_DIR (expected symlink to nodejs/geowealth)"
    dev_log "Starting P1 React webpack-dev-server — open http://localhost:8888"
    dev_log "Backend proxied to http://127.0.0.1:8080 (p1-tomcat port-forward)"
    cd "$GEOWEALTH_DIR/WebContent/react/app"
    exec npm run dev:local
    ;;
  -h|--help|help)
    sed -n '2,12p' "$0"
    ;;
  *)
    dev_die "Unknown target '$TARGET' (billing|trading|portfolio|p1)"
    ;;
esac
