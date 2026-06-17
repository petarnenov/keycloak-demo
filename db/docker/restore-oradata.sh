#!/usr/bin/env bash
# restore-oradata.sh — runs at CONTAINER START. The gvenzl base image declares
# /opt/oracle/oradata as a VOLUME, so Docker mounts a fresh empty anonymous
# volume there on first run and the schema+seeds we baked into the image at
# /opt/oracle/baked-oradata are not visible to Oracle. Seed the empty volume
# from the baked snapshot, then exec the gvenzl entrypoint — it sees an
# initialized DB and just opens it (~10s) instead of running createDatabase.sh
# again (~2-3 min).

set -euo pipefail

ORADATA=/opt/oracle/oradata
BAKED=/opt/oracle/baked-oradata

if [ -z "$(ls -A "${ORADATA}" 2>/dev/null)" ]; then
  echo ">>> restore-oradata: empty volume detected, seeding from ${BAKED}"
  echo ">>> restore-oradata: this is a one-time cost per volume (~30-60s for several GB)"
  shopt -s dotglob
  cp -a "${BAKED}"/* "${ORADATA}/"
  shopt -u dotglob
  echo ">>> restore-oradata: done"
else
  echo ">>> restore-oradata: volume already populated, skipping seed"
fi

exec "$@"
