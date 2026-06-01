#!/usr/bin/env bash
# Assemble a Flyway seed migration from scrubbed sqlformat-insert files.
#
# Usage:
#   assemble-seed.sh <scrubbed_dir> <output.sql> "<header line>"
#
# Concatenates every *.sql in <scrubbed_dir> (output of scrub_faker.py), strips the
# SQLcl artifacts (REM / SET DEFINE OFF), adds a section banner per file and a final
# COMMIT, and writes the migration to <output.sql>. Order is the lexical file order,
# so name the scrubbed files with a numeric/role prefix to control dependency order
# (parents/lookups before children).
set -euo pipefail
SRC="${1:?scrubbed_dir}"; OUT="${2:?output.sql}"; HDR="${3:-seed migration}"

{
  echo "--------------------------------------------------------------------------------"
  echo "-- $(basename "$OUT")"
  echo "--"
  echo "-- $HDR"
  echo "-- Real prod rows, PII-scrubbed with Faker (scrub_faker.py). IDs/codes/keys real."
  echo "--------------------------------------------------------------------------------"
} > "$OUT"

for f in "$SRC"/*.sql; do
  [ -s "$f" ] || continue
  grep -qi 'Insert into' "$f" || continue
  printf '\n-- ===== %s =====\n' "$(basename "$f" .sql)" >> "$OUT"
  grep -vE '^REM INSERTING into|^SET DEFINE OFF;$' "$f" >> "$OUT"
done

printf '\nCOMMIT;\n' >> "$OUT"
echo "WROTE $OUT  (inserts: $(grep -c '^Insert into' "$OUT"), tables: $(grep -c '^-- =====' "$OUT"))"
