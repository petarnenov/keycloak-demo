#!/usr/bin/env bash
# Measure dev-loop latency against the ≤60s acceptance metric.
#
#   ./scripts/dev-verify.sh fe-billing   # touch a React file, measure HMR
#   ./scripts/dev-verify.sh be-th        # touch bff-core, measure restart
#   ./scripts/dev-verify.sh preflight    # cluster + port-forwards only
#
# Exit 0 when all checks pass within MAX_SECONDS (default 60).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$REPO_ROOT/scripts/dev-common.sh"

MAX_SECONDS="${DEV_VERIFY_MAX_SECONDS:-60}"
CHECK="${1:-preflight}"

pass() { printf '\033[1;32mPASS\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31mFAIL\033[0m %s\n' "$*" >&2; exit 1; }

wait_http() {
  local url="$1" expect="$2" deadline=$((SECONDS + MAX_SECONDS))
  while [ "$SECONDS" -lt "$deadline" ]; do
    body=$(curl -sk --max-time 2 "$url" 2>/dev/null || true)
    if echo "$body" | grep -q "$expect"; then
      printf '%s' "$((SECONDS - START))"
      return 0
    fi
    sleep 1
  done
  return 1
}

wait_tcp() {
  local port="$1" deadline=$((SECONDS + MAX_SECONDS))
  while [ "$SECONDS" -lt "$deadline" ]; do
    ss -lnt 2>/dev/null | grep -q ":${port} " && return 0
    sleep 0.5
  done
  return 1
}

case "$CHECK" in
  preflight)
    require_cluster
    for row in \
      "keycloak:5180" \
      "redis:6379" \
      "p1-tomcat:8080" \
      "token-handler:9080" \
      "bff-billing:8084" \
      "web-billing:5184"; do
      port=${row#*:}
      if ss -lnt | grep -q ":${port} "; then
        pass "port :$port listening"
      else
        fail "port :$port not listening — run ./k8s/portforward.sh or dev-fe/dev-be"
      fi
    done
    pass "preflight complete"
    ;;
  fe-billing)
    MARKER="__DEV_VERIFY_${RANDOM}__"
    FILE="$REPO_ROOT/domains/billing/web/src/pages/OverviewPage.tsx"
    [ -f "$FILE" ] || fail "missing $FILE"
    wait_tcp 5184 || fail "Vite not on :5184 — run ./scripts/dev-fe.sh billing"
    cp "$FILE" "${FILE}.bak"
    trap 'mv -f "${FILE}.bak" "$FILE"' EXIT
    START=$SECONDS
    sed -i "s|<h1>Overview</h1>|<h1>${MARKER}</h1>|" "$FILE"
    # Vite dev serves source modules directly — no auth/session needed.
    elapsed=$(wait_http "https://127.0.0.1:5184/src/pages/OverviewPage.tsx" "$MARKER" || echo fail)
    [ "$elapsed" != fail ] || fail "HMR did not reflect change within ${MAX_SECONDS}s"
    [ "$elapsed" -le "$MAX_SECONDS" ] || fail "HMR took ${elapsed}s (> ${MAX_SECONDS}s)"
    pass "billing FE change live in ${elapsed}s (≤ ${MAX_SECONDS}s)"
    ;;
  be-th)
    MARKER="dev-verify-${RANDOM}"
    FILE="$REPO_ROOT/token-handler/src/main/java/demo/bff/core/AuthController.java"
    [ -f "$FILE" ] || fail "missing $FILE"
    wait_tcp 9080 || fail "token-handler not on :9080 — run ./scripts/dev-be.sh token-handler"
    cp "$FILE" "${FILE}.bak"
    trap 'mv -f "${FILE}.bak" "$FILE"' EXIT
    START=$SECONDS
    sed -i "s|m.put(\"marker\", \".*\");|m.put(\"marker\", \"${MARKER}\");|" "$FILE"
    elapsed=$(wait_http "http://127.0.0.1:9080/auth/th-version" "$MARKER" || echo fail)
    [ "$elapsed" != fail ] || fail "token-handler did not pick up change within ${MAX_SECONDS}s"
    [ "$elapsed" -le "$MAX_SECONDS" ] || fail "BE restart took ${elapsed}s (> ${MAX_SECONDS}s)"
    pass "token-handler change live in ${elapsed}s (≤ ${MAX_SECONDS}s)"
    ;;
  -h|--help|help)
    sed -n '2,10p' "$0"
    ;;
  *)
    fail "Unknown check '$CHECK' (preflight|fe-billing|be-th)"
    ;;
esac
