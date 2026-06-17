#!/usr/bin/env bash
# bake.sh — runs at IMAGE BUILD TIME inside the gvenzl/oracle-free image.
# Boots Oracle in the background, runs db/stack/Makefile's provision flow inline
# (prepare-oracle -> V1 baseline via sqlplus -> flyway baseline -> flyway migrate
# V2..VN + R__local_login_hash), shuts Oracle down cleanly, then snapshots
# /opt/oracle/oradata into /opt/oracle/baked-oradata. oradata is a VOLUME in the
# base image so writes there don't survive layer commit; baked-oradata is not,
# so it does, and restore-oradata.sh seeds the empty anonymous volume from it
# on first container start.

set -euo pipefail

ORADATA=/opt/oracle/oradata
BAKED=/opt/oracle/baked-oradata
PDB=${ORACLE_DATABASE:-FREEPDB1}
SYS_PW=${ORACLE_PASSWORD:-oracle_sys_pw}
APP_USER=${APP_USER:-GP}
APP_PW=${APP_USER_PASSWORD:-gp123}

CDB_URL="//localhost:1521/FREE"
PDB_URL="//localhost:1521/${PDB}"

log() { printf '>>> bake: %s\n' "$*"; }

# --- 1. start Oracle in background; gvenzl entrypoint creates the DB on first run
log "starting Oracle in background"
mkdir -p /tmp/bake
/opt/oracle/container-entrypoint.sh > /tmp/bake/oracle.log 2>&1 &
ORA_PID=$!

# --- 2. wait for "DATABASE IS READY TO USE!" (init scripts not used; init is the
#        clean gvenzl flow)
log "waiting for first-boot DB init (typically 60-180s)"
for i in $(seq 1 180); do
  if grep -q "DATABASE IS READY TO USE!" /tmp/bake/oracle.log 2>/dev/null; then
    log "Oracle reports ready (after ${i}*5s)"
    break
  fi
  if ! kill -0 ${ORA_PID} 2>/dev/null; then
    log "ERROR: entrypoint exited before reporting ready. Log tail:"
    tail -200 /tmp/bake/oracle.log
    exit 1
  fi
  sleep 5
done
if ! grep -q "DATABASE IS READY TO USE!" /tmp/bake/oracle.log; then
  log "ERROR: timeout. Log tail:"
  tail -200 /tmp/bake/oracle.log
  exit 1
fi

# --- 3. prepare-oracle: MAX_STRING_SIZE=EXTENDED + DBMS_CRYPTO grant ------------
log "MAX_STRING_SIZE=EXTENDED on ${PDB}"
sqlplus -s "sys/${SYS_PW}@${CDB_URL}" as sysdba <<SQL
WHENEVER SQLERROR CONTINUE
ALTER PLUGGABLE DATABASE ${PDB} CLOSE IMMEDIATE;
ALTER PLUGGABLE DATABASE ${PDB} OPEN UPGRADE;
ALTER SESSION SET CONTAINER=${PDB};
ALTER SYSTEM SET MAX_STRING_SIZE=EXTENDED;
@?/rdbms/admin/utl32k.sql
ALTER SESSION SET CONTAINER=CDB\$ROOT;
ALTER PLUGGABLE DATABASE ${PDB} CLOSE IMMEDIATE;
ALTER PLUGGABLE DATABASE ${PDB} OPEN;
ALTER PLUGGABLE DATABASE ${PDB} SAVE STATE;
EXIT
SQL

log "GRANT EXECUTE ON SYS.DBMS_CRYPTO TO ${APP_USER}"
sqlplus -s "sys/${SYS_PW}@${PDB_URL}" as sysdba <<SQL
WHENEVER SQLERROR CONTINUE
GRANT EXECUTE ON SYS.DBMS_CRYPTO TO ${APP_USER};
EXIT
SQL

# --- 4. V1 baseline via sqlplus (Flyway OSS cannot parse it) --------------------
log "loading V1 baseline (~1792 tables) via sqlplus"
sqlplus -s "${APP_USER}/${APP_PW}@${PDB_URL}" <<'SQL'
SET DEFINE OFF
WHENEVER SQLERROR CONTINUE
@/flyway/sql/V1__baseline_schema.sql
EXIT
SQL

log "dropping the empty flyway_schema_history that V1 created"
sqlplus -s "${APP_USER}/${APP_PW}@${PDB_URL}" <<'SQL'
WHENEVER SQLERROR CONTINUE
DROP TABLE "flyway_schema_history" CASCADE CONSTRAINTS PURGE;
EXIT
SQL

# --- 5. Flyway baseline + V2..VN (+ R__ local hash if present) ------------------
log "recording V1 as the Flyway baseline"
flyway -url="jdbc:oracle:thin:@${PDB_URL}" \
       -user="${APP_USER}" -password="${APP_PW}" \
       -baselineVersion=1 -baselineDescription=baseline_schema \
       baseline

LOC=filesystem:/flyway/sql
if [ -f /flyway/local/R__local_login_hash.sql ]; then
  LOC="${LOC},filesystem:/flyway/local"
  log "including db/local/R__local_login_hash.sql (login hash will be set)"
else
  log "WARNING: /flyway/local/R__local_login_hash.sql absent — NO user can log in (schema only)"
fi

log "applying V2..VN (+ R__ if present)"
flyway -url="jdbc:oracle:thin:@${PDB_URL}" \
       -user="${APP_USER}" -password="${APP_PW}" \
       -locations="${LOC}" \
       migrate

# --- 6. SHUTDOWN IMMEDIATE -> datafiles are consistent -> snapshot --------------
log "SHUTDOWN IMMEDIATE on CDB"
sqlplus -s "sys/${SYS_PW}@${CDB_URL}" as sysdba <<'SQL'
WHENEVER SQLERROR CONTINUE
SHUTDOWN IMMEDIATE
EXIT
SQL

log "stopping background entrypoint (tails alert.log forever, otherwise)"
kill -TERM ${ORA_PID} 2>/dev/null || true
for i in $(seq 1 30); do
  kill -0 ${ORA_PID} 2>/dev/null || break
  sleep 1
done
kill -KILL ${ORA_PID} 2>/dev/null || true
wait ${ORA_PID} 2>/dev/null || true

log "snapshotting ${ORADATA} -> ${BAKED}"
shopt -s dotglob
cp -a ${ORADATA}/* ${BAKED}/ 2>/dev/null || true
shopt -u dotglob

log "bake complete: $(du -sh ${BAKED} | cut -f1) of seeded datafiles staged"
rm -rf /tmp/bake
