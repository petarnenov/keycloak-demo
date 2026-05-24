#!/usr/bin/env bash
# Tear down any running stack and bring it back up with a full image rebuild.
#
# Persistence:
#   By default this preserves the Postgres and Keycloak data volumes, so
#   every user created through the Keycloak admin UI, every federated
#   identity built by the P1 SAML broker on first login, and every realm
#   tweak made via the admin API survives a re-run. `docker compose down`
#   (without `-v`) and `docker compose restart` are equally safe.
#
#   To wipe everything and re-seed the realms from the JSON exports,
#   pass `--reset` (opt-in, never the default). This calls
#   `docker compose down -v` and is the only path in this repo that
#   destroys user data.
#
# Modes:
#   ./start.sh           Bring up the full domain stack (postgres, keycloak,
#                        user-service, all domain web + bff images).
#   ./start.sh --reset   DESTRUCTIVE. Wipes all volumes (Postgres + Keycloak
#                        data) before rebuilding. Use this only when you want
#                        a clean realm seed from the JSON exports.
#
# Works with either Docker or Podman: the container engine is auto-detected
# (Docker preferred). Override with CONTAINER_ENGINE=docker|podman.
set -euo pipefail

cd "$(dirname "$0")"

# --- Parse args ------------------------------------------------------------
RESET=0
for arg in "$@"; do
  case "$arg" in
    ""|--default|default) ;;
    --reset|reset)        RESET=1 ;;
    -h|--help|help)
      sed -n '2,17p' "$0"
      exit 0 ;;
    *)
      echo "Error: unknown argument '$arg' (expected '--reset' or nothing)." >&2
      echo "Run '$0 --help' for usage." >&2
      exit 1 ;;
  esac
done

# Pull DOCKER_HOST + RESEND_API_TOKEN in for users who don't have direnv.
if [ -f .envrc ]; then
  # shellcheck disable=SC1091
  source .envrc
fi

# --- Detect the container engine and its compose command -------------------
# Sets COMPOSE to an array, e.g. (docker compose) or (podman compose).
ENGINE="${CONTAINER_ENGINE:-}"

if [ -z "$ENGINE" ]; then
  if command -v docker >/dev/null 2>&1; then
    ENGINE=docker
  elif command -v podman >/dev/null 2>&1; then
    ENGINE=podman
  else
    echo "Error: neither 'docker' nor 'podman' found on PATH." >&2
    echo "Install one, or set CONTAINER_ENGINE=docker|podman." >&2
    exit 1
  fi
fi

case "$ENGINE" in
  docker)
    if docker compose version >/dev/null 2>&1; then
      COMPOSE=(docker compose)
    elif command -v docker-compose >/dev/null 2>&1; then
      COMPOSE=(docker-compose)
    else
      echo "Error: 'docker' found but no compose ('docker compose' or 'docker-compose')." >&2
      exit 1
    fi
    ;;
  podman)
    if podman compose version >/dev/null 2>&1; then
      COMPOSE=(podman compose)
    elif command -v podman-compose >/dev/null 2>&1; then
      COMPOSE=(podman-compose)
    else
      echo "Error: 'podman' found but no compose ('podman compose' or 'podman-compose')." >&2
      exit 1
    fi
    ;;
  *)
    echo "Error: unknown CONTAINER_ENGINE='$ENGINE' (expected 'docker' or 'podman')." >&2
    exit 1 ;;
esac

FILES=(-f docker-compose.yml)

echo "==> Using ${COMPOSE[*]} ${FILES[*]} (engine: $ENGINE)"

PROJECT="$(basename "$PWD")"

# Block until a compose service reports a healthy container (or fail loudly).
# Used only on the podman path — see below.
wait_healthy() {
  local svc="$1" timeout="${2:-240}" elapsed=0 cid status
  echo "    waiting for '$svc' to become healthy (timeout ${timeout}s)..."
  while true; do
    cid="$(podman ps -aq \
      --filter "label=com.docker.compose.project=$PROJECT" \
      --filter "label=com.docker.compose.service=$svc" | head -1)"
    if [ -n "$cid" ]; then
      status="$(podman inspect "$cid" \
        --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
        2>/dev/null || echo '')"
      case "$status" in
        healthy)
          echo "    '$svc' is healthy."
          return 0 ;;
        exited|dead)
          echo "    '$svc' $status before becoming healthy — recent logs:" >&2
          podman logs --tail 40 "$cid" 2>&1 | sed 's/^/      /' >&2 || true
          return 1 ;;
      esac
    fi
    if [ "$elapsed" -ge "$timeout" ]; then
      echo "    '$svc' did not become healthy within ${timeout}s." >&2
      return 1
    fi
    sleep 5
    elapsed=$((elapsed + 5))
  done
}

if [ "$RESET" = 1 ]; then
  echo "==> --reset: wiping volumes (Postgres + Keycloak data)"
  echo "    All users, federated identities, sessions, and live realm edits"
  echo "    will be lost. Realms will be re-seeded from the JSON exports."
  "${COMPOSE[@]}" "${FILES[@]}" down -v --remove-orphans || true
else
  echo "==> Stopping any running containers (volumes preserved)"
  "${COMPOSE[@]}" "${FILES[@]}" down --remove-orphans || true
fi

echo "==> Rebuilding all images (keycloak + user-service + domain bff/web)"
"${COMPOSE[@]}" "${FILES[@]}" build

if [ "$ENGINE" = podman ]; then
  # podman-compose does NOT honour `depends_on: condition: service_healthy`,
  # so bring the data + identity tier up first and wait, then the rest.
  echo "==> Starting data + user store (postgres, user-service)"
  "${COMPOSE[@]}" "${FILES[@]}" up -d postgres user-service
  wait_healthy postgres
  wait_healthy user-service

  echo "==> Starting Keycloak"
  "${COMPOSE[@]}" "${FILES[@]}" up -d keycloak
  wait_healthy keycloak 300

  echo "==> Starting the rest (web + bff)"
  "${COMPOSE[@]}" "${FILES[@]}" up -d
else
  # docker compose honours depends_on health conditions itself.
  echo "==> Starting stack"
  "${COMPOSE[@]}" "${FILES[@]}" up -d
fi

echo
echo "==> Status:"
"${COMPOSE[@]}" "${FILES[@]}" ps
echo
echo "Tail logs with: ${COMPOSE[*]} ${FILES[*]} logs -f"
