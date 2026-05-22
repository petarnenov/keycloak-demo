#!/usr/bin/env bash
# Level 3 dev workflow in one command: run a BFF on the host with Gradle's
# continuous-build mode, with the shell's Vite proxy already pointed at
# this host process. The local loop is ~3s per save vs ~90s for compose
# `--build --recreate`.
#
# Usage:
#   ./dev-bff.sh client|ops|admin
#
# What this script does (in order):
#   1. Detect docker vs podman + the matching host-gateway hostname.
#   2. Stop the compose BFF for the chosen role (frees the host port).
#   3. Recreate the shell with `BFF_<WHICH>_URL=http://<host>:<port>` set in
#      its env, using docker-compose.dev.yml so the variable is honoured.
#   4. exec `./gradlew run -t --no-daemon` with all the env vars the BFF
#      would normally get from compose — KEYCLOAK_AUTH_SERVER_URL points at
#      the *browser*-facing Keycloak URL (http://localhost:8898) so the
#      issuer claim in browser-minted tokens matches.
#
# Prereqs:
#   * `./start.sh` (or `./start.sh --dev`) is up so Keycloak/Postgres/
#     user-service/other-BFFs/shell are reachable.
#
# To restore the demo state when you're done:
#   docker compose up -d bff-client      # (or bff-ops / bff-admin)
# That brings the compose BFF back up; the shell's BFF_<WHICH>_URL override
# survives in the running shell container until you also recreate it without
# the override (e.g. `./start.sh` will do this).

set -euo pipefail

which="${1:-}"
if [ -z "$which" ]; then
  echo "usage: $0 client|ops|admin" >&2
  exit 1
fi

case "$which" in
  client) name="bff-client"; port=8081; roles=""; env_var="BFF_CLIENT_URL"; alt_env_var="BFF_SHELL_URL" ;;
  ops)    name="bff-ops";    port=8082; roles="user,admin"; env_var="BFF_OPS_URL"; alt_env_var="" ;;
  admin)  name="bff-admin";  port=8083; roles="admin"; env_var="BFF_ADMIN_URL"; alt_env_var="" ;;
  -h|--help|help) sed -n '2,30p' "$0"; exit 0 ;;
  *) echo "unknown bff: '$which' (expected client|ops|admin)" >&2; exit 1 ;;
esac

cd "$(dirname "$0")"

if [ -f .envrc ]; then
  # shellcheck disable=SC1091
  source .envrc
fi

# --- Detect engine + the right host-gateway hostname -----------------------
ENGINE="${CONTAINER_ENGINE:-}"
if [ -z "$ENGINE" ]; then
  if command -v docker >/dev/null 2>&1; then
    ENGINE=docker
  elif command -v podman >/dev/null 2>&1; then
    ENGINE=podman
  else
    echo "Error: neither 'docker' nor 'podman' found on PATH." >&2
    exit 1
  fi
fi

case "$ENGINE" in
  docker) COMPOSE=(docker compose); HOST_GW="host.docker.internal" ;;
  podman) COMPOSE=(podman compose); HOST_GW="host.containers.internal" ;;
  *) echo "Error: unknown CONTAINER_ENGINE='$ENGINE'." >&2; exit 1 ;;
esac

FILES=(-f docker-compose.yml -f docker-compose.dev.yml)
host_bff_url="http://${HOST_GW}:${port}"

# --- Sanity check that the rest of the stack is up -------------------------
if ! "${COMPOSE[@]}" "${FILES[@]}" ps --services --filter "status=running" 2>/dev/null | grep -q keycloak; then
  echo "Error: Keycloak isn't running. Start the stack first:" >&2
  echo "    ./start.sh --dev" >&2
  exit 1
fi

# --- 1. free the compose BFF's port ----------------------------------------
echo "==> Stopping compose '$name' to free :$port"
"${COMPOSE[@]}" "${FILES[@]}" stop "$name" 2>/dev/null || true

# --- 2. recreate the shell with the proxy redirected -----------------------
# bff-client also serves /api/whoami for the shell; redirect both BFF_SHELL_URL
# and BFF_CLIENT_URL when we're playing with client.
echo "==> Recreating shell with $env_var=$host_bff_url"
extra_env=("$env_var=$host_bff_url")
if [ -n "$alt_env_var" ]; then
  extra_env+=("$alt_env_var=$host_bff_url")
fi
env "${extra_env[@]}" "${COMPOSE[@]}" "${FILES[@]}" up -d --force-recreate shell

cat <<EOF

------------------------------------------------------------------
Level 3 dev for $name is now wired:

  ./gradlew run -t  →  http://localhost:$port  ←  shell /api/<which>/*

Save bff/src/**/*.java → ~3s Gradle incremental → Micronaut restart.

To restore the demo BFF when done:
  docker compose up -d $name
  ./start.sh        # (rebuilds the shell without the host override)
------------------------------------------------------------------

EOF

# --- 3. exec the local BFF in continuous build mode ------------------------
cd bff
exec env \
  APP_SOURCE="$name" \
  MICRONAUT_APPLICATION_NAME="$name" \
  CORS_ORIGIN="http://localhost:5173" \
  KEYCLOAK_AUTH_SERVER_URL="http://localhost:8898/realms/demo-realm" \
  APP_ALLOWED_ROLES="$roles" \
  MICRONAUT_SERVER_PORT="$port" \
  ./gradlew run -t --no-daemon
