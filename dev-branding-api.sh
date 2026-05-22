#!/usr/bin/env bash
# Launch the fake GeoWealth branding API on the host. Lets the Keycloak
# white-labeling SPI exercise its full cache/lookup/fetch loop against a real
# HTTP endpoint while the real geowealth backend isn't reachable from this
# machine.
#
# Usage:
#   ./dev-branding-api.sh           # foreground, ctrl-c to stop
#   PORT=18080 ./dev-branding-api.sh
#
# Prereqs:
#   * .envrc exports POC_BRANDING_API_TOKEN (the same token Keycloak's SPI
#     uses, set on the keycloak service in docker-compose.yml).
#   * Python 3.8+ on PATH.
#
# Keycloak in the compose stack reaches the host on port 8080 via
# host.docker.internal (macOS) / host.containers.internal (Podman Linux).
# The compose env var KC_SPI_LOGIN_FREEMARKER_GEOWEALTH_BRANDING_API_URL is
# already wired to http://host.docker.internal:8080 — no edits needed.
#
# Contract: contracts/branding-api.openapi.yaml

set -euo pipefail

cd "$(dirname "$0")"

if [ -f .envrc ]; then
  # shellcheck disable=SC1091
  source .envrc
fi

if [ -z "${POC_BRANDING_API_TOKEN:-}" ]; then
  echo "Error: POC_BRANDING_API_TOKEN is unset. Add it to .envrc:" >&2
  echo "    export POC_BRANDING_API_TOKEN=\"\$(openssl rand -base64 32)\"" >&2
  exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "Error: python3 is required but not on PATH." >&2
  exit 1
fi

HOST_BIND="${HOST:-127.0.0.1}"
PORT_BIND="${PORT:-8080}"

# Sanity: warn if the port is already taken (probably stale fake or geowealth
# Tomcat). Don't kill anything — that's the user's call.
if command -v lsof >/dev/null 2>&1 && lsof -nP -iTCP:"$PORT_BIND" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Warning: port $PORT_BIND is already in use. Existing listeners:" >&2
  lsof -nP -iTCP:"$PORT_BIND" -sTCP:LISTEN >&2 || true
  echo "Refusing to start. Stop the other listener or set PORT=<other>." >&2
  exit 1
fi

cat <<EOF
==> Fake branding API
    bind:    http://${HOST_BIND}:${PORT_BIND}
    firms:   changepath, cca (default)
    token:   POC_BRANDING_API_TOKEN (length=${#POC_BRANDING_API_TOKEN})

    Keycloak compose env (already set in docker-compose.yml):
      KC_SPI_LOGIN_FREEMARKER_GEOWEALTH_BRANDING_API_URL=http://host.docker.internal:${PORT_BIND}
      KC_SPI_LOGIN_FREEMARKER_GEOWEALTH_BRANDING_API_TOKEN=<from .envrc>

    Test from the host:
      curl -H "Authorization: Bearer \$POC_BRANDING_API_TOKEN" \\
        http://${HOST_BIND}:${PORT_BIND}/branding-api/keycloak/whitelabel/changepath

EOF

exec env \
  HOST="$HOST_BIND" \
  PORT="$PORT_BIND" \
  POC_BRANDING_API_TOKEN="$POC_BRANDING_API_TOKEN" \
  python3 "$(dirname "$0")/dev-branding-api/server.py"
