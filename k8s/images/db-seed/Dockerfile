# db-seed — Flyway with the GP migrations baked in.
#
# Why baked (not a ConfigMap): V1__baseline_schema.sql is 5.9 MB / 121K lines and
# the migrations total ~6.8 MB — far over the 1 MB ConfigMap limit. So the image
# carries db/migration/ at /flyway/sql.
#
# This one image serves TWO roles in the cluster:
#   1. Oracle StatefulSet initContainer — stages V1__baseline_schema.sql into a
#      shared volume so the gvenzl init script can load it SERVER-SIDE (the
#      EXTENDED string-size + utl32k.sql dance only works inside the DB pod).
#   2. oracle-seed Job — runs `flyway baseline` (records V1) then `flyway migrate`
#      (V2..V18), and applies the gitignored login hash from a mounted Secret.
#
# The login hash (db/local/R__local_login_hash.sql) is a CREDENTIAL and is NOT
# baked — it is mounted at runtime from a Secret into /flyway/local.
#
# Build context = repo root:  docker build -f k8s/images/db-seed/Dockerfile .
FROM flyway/flyway:10-alpine

# The full versioned migration set (V1 baseline + V2..V18 seeds).
COPY db/migration/ /flyway/sql/

# Default flyway conf is provided per-invocation (-url/-user/-password) by the Job.
