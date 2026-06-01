# Local backing services (`gw-stack`)

Spins up the infrastructure the GeoWealth backend depends on, locally, and applies
the [schema baseline](../migration/V1__baseline_schema.sql) into a throwaway Oracle.

| Service | Image | Host:port | Used by |
|---------|-------|-----------|---------|
| `gw-oracle` | `gvenzl/oracle-free:23-slim` | `127.0.0.1:1521` (PDB `FREEPDB1`, user `GP`/`gp123`) | DB — baseline target |
| `gw-elasticsearch` | `elasticsearch:7.17.28` | `127.0.0.1:9200` | search (matches geowealth's 7.17 client) |
| `gw-memcached` | `memcached:1.6-alpine` | `127.0.0.1:11211` | hibernate L2 cache |
| `gw-flyway` | `flyway/flyway:10-alpine` | one-shot | applies the baseline |

## One command (recommended)

A `Makefile` here wraps the whole fresh-DB provision — start services, load the V1
baseline via sqlplus, record the Flyway baseline, run seeds V2..V15, apply the
gitignored login hash, and verify:

```bash
cd db/stack
make provision   # on an empty/new Oracle volume
make fresh       # wipe the volume first, then provision (guaranteed clean slate)
make info        # check status: which migrations are applied vs still pending
make update      # apply any NEW/pending migrations to an already-running DB (idempotent)
make verify      # smoke-check an existing DB
make help        # list all targets
```

Day-to-day, after adding a `Vxx__*.sql` to `../migration`, run **`make update`** — it
shows the Flyway status, applies only the not-yet-applied migrations (and re-asserts
the repeatable `db/local` login hash), then verifies. It is idempotent: with nothing
pending it prints "Schema is up to date. No migration necessary." `make info` is the
read-only check on its own.

`make provision` is the codified form of the manual steps below (it exists because
`flyway migrate` alone cannot build the schema — V1 is a BASELINE migration). The
login password hash lives in the **gitignored** `../local/R__local_login_hash.sql`;
`make local-hash` applies it and warns (without failing) if that file is absent.
Override the engine with `make CE=podman DC="podman compose" provision`.

## Manual steps (what `make provision` automates)

```bash
cd db/stack
docker compose up -d oracle elasticsearch memcached   # Oracle takes ~1-2 min to init
```

Then apply the baseline into the Oracle container. Flyway OSS's parser cannot
handle the baseline's PL/SQL, so load it with sqlplus and record it with Flyway
`baseline` (see ../README.md "Applying it"):

```bash
# 1. EXTENDED mode (one-time, as SYSDBA) - the schema needs MAX_STRING_SIZE=EXTENDED
docker exec -i gw-oracle sqlplus -s sys/oracle_sys_pw@//localhost:1521/FREE as sysdba <<'EOF'
ALTER PLUGGABLE DATABASE FREEPDB1 CLOSE IMMEDIATE;
ALTER PLUGGABLE DATABASE FREEPDB1 OPEN UPGRADE;
ALTER SESSION SET CONTAINER=FREEPDB1;
ALTER SYSTEM SET MAX_STRING_SIZE=EXTENDED;
@?/rdbms/admin/utl32k.sql
ALTER SESSION SET CONTAINER=CDB$ROOT;
ALTER PLUGGABLE DATABASE FREEPDB1 CLOSE IMMEDIATE;
ALTER PLUGGABLE DATABASE FREEPDB1 OPEN;
ALTER PLUGGABLE DATABASE FREEPDB1 SAVE STATE;
EOF

# 2. GP needs EXECUTE on SYS.DBMS_CRYPTO (used by MAKEUUID and callers)
docker exec -i gw-oracle sqlplus -s sys/oracle_sys_pw@//localhost:1521/FREEPDB1 as sysdba <<'EOF'
GRANT EXECUTE ON SYS.DBMS_CRYPTO TO GP;
EOF

# 3. Load the baseline
docker cp ../migration/V1__baseline_schema.sql gw-oracle:/tmp/V1.sql
docker exec -i gw-oracle sqlplus -s GP/gp123@//localhost:1521/FREEPDB1 <<'EOF'
SET DEFINE OFF
WHENEVER SQLERROR CONTINUE
@/tmp/V1.sql
EOF

# 4. Record it in Flyway
docker compose run --rm flyway -url=jdbc:oracle:thin:@//oracle:1521/FREEPDB1 \
  -user=GP -password=gp123 -baselineVersion=1 baseline
```

Validated result: ~5290 valid objects, ~130 invalid (cross-schema / DB-link
dependencies absent in a single-schema copy; many are INVALID in prod too).

Tear down (keep data): `docker compose down`. Wipe volumes: `docker compose down -v`.

## Pointing the GeoWealth backend at these

The backend reads hostnames, not localhost, from `etc/dev-petar-akka.conf` and
`HibernateSessionFactory`. Map them to the published containers:

1. `/etc/hosts` (sudo):

   ```text
   127.0.0.1 memcached
   127.0.0.1 dev-elastic.geowealth.com
   # 127.0.0.1 qa4db   # ONLY if you want the BE on the empty baseline DB instead of qa4
   ```

2. Elasticsearch is started without TLS/security, so the dev config must use
   plain `http` and no auth. In `etc/dev-petar-akka.conf` set
   `elasticsearch.scheme=http` (a `.bak` is kept).

3. Memcached L2 cache is only active when the backend runs with
   `-Dhibernate.secondLevelCacheType=MEMCACHED` (default is EHCACHE). Add that
   JVM flag to the petar server profile to actually use `gw-memcached`.

> Oracle: the baseline DB is **schema-only (no business data)**. Repoint `qa4db`
> to it only for schema/DDL work — leave it pointed at real qa4 for normal use.

## Smoke checks

```bash
docker exec gw-oracle sqlplus -s GP/gp123@//localhost:1521/FREEPDB1 <<< "select count(*) from user_tables;"
curl -s localhost:9200/_cluster/health | jq .status
printf 'stats\r\nquit\r\n' | nc localhost 11211 | head
```
