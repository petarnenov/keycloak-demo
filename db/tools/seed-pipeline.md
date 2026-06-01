# Seed pipeline — reusable mechanism

How the firm seed migrations (V2 firm1, V3 firm3, V4 firm40) and the referential
closure (V5/V6) were built, so the same mechanism can be reused for another firm or
another closure round. All artifacts here are generic; only the key values change.

## Pieces

| File | Role |
|------|------|
| `find-related-tables.sql` | discovery — given a hub column + seeded key values, list base tables that contain them |
| `scrub_faker.py` | replace PII column values with realistic, deterministic Faker data |
| `assemble-seed.sh` | concatenate scrubbed inserts into a `V<n>__*.sql` migration |
| `extract-schema.sql` | (separate) regenerate the V1 DDL baseline |

## Prerequisites

- **Prod source**: `192.168.1.42:1521/ORCL12VM`, schema `GP`. No Oracle client on
  the Mac — reach it via **SQLcl on 192.168.1.223** (creds read from
  `~/nodejs/geowealth/build/resources/main/hibernate-localhostQA4.properties`).
  Helper on 223 (recreate if `/tmp/sqlcl` was wiped — see git history of this repo's
  memory): `run-sql.sh` = `sql -S <user>/<pwd>@<host:port:SID> @"$1"`, creds grepped
  from that properties file. Files move 223→Mac with
  `sshpass -e scp -o PubkeyAuthentication=no -o PreferredAuthentications=password`.
- **Target**: the `gw-oracle` container in `db/stack/` (V1 baseline applied,
  `MAX_STRING_SIZE=EXTENDED`, `GRANT EXECUTE ON SYS.DBMS_CRYPTO TO GP`). Flyway tracks
  it from the V1 baseline.
- **Faker**: `pip3 install faker` locally.

## A. Seed one firm (the V2/V3/V4 pattern)

1. **Discover the firm's shape** on prod: does it have households (ENTITY_TYPE_CD=5)
   and positions, or is it institutional (companies, no households)?
   ```sql
   select entity_type_cd, count(*) from entity_tbl where firm_cd=:f group by 1;
   select account_role_cd, count(*) from entity_account_role_tbl r
     join nf_account_tbl a on a.nf_account_id=r.nf_account_id and a.firm_cd=:f group by 1;
   ```
2. **Pick a small connected slice** (<=10 rows/table): a household (or a client),
   its accounts (`ENTITY_ACCOUNT_ROLE_TBL`, role 5=household / 1=owner), the owner
   entities, a few positions (`CRNT_TAXLOT_POSITIONS_TBL`) + their instruments
   (`INSTRUMENT_MASTER_TBL`), `ACCOUNT_DETAILS_TBL` (NF_ACCOUNT's only real FK →
   `ACCOUNT_DETAILS_TBL.ACCOUNT_ID`), and the advisor that has `POLICY_RULE_TBL`
   grants to the client/accounts.
3. **Extract real rows** with SQLcl: `set sqlformat insert` + `select * from T where
   <key> in (...) and rownum<=10;` spooled one file per table.
4. **Scrub** locally: `python3 scrub_faker.py <seed_dir> <meta_file>`
   (meta = `TABLE|COLUMN|DATATYPE|CHARLEN|NULLABLE`, dump from `user_tab_columns`).
5. **Assemble** in FK/dependency order (parents first): name files `NN_...` and run
   `assemble-seed.sh <scrubbed_dir> db/migration/V<n>__seed_firm<f>.sql "<header>"`.
6. **Login user**: pick the firm's advisor entity, set in the migration
   `GW_ADMIN_FLAG=1` (this flag *is* gwAdmin), `MFA_REQUIRED_FLAG=0`,
   `ENTITY_ACTIVE_FLAG=1`, `LDAP_PSWD_HASH=NULL` (never commit the password — SAML
   login auto-links by `ENTITY_EMAIL_TBL.EMAIL`).
7. **Apply + validate**: `docker compose run --rm flyway ... migrate`; then run the
   BE's real queries (login lookup by `LDAP_UID`+`FIRM_CD`, the `POLICY_RULE`
   visibility join, household→accounts, positions→instruments).

## B. Referential closure (the V5/V6 pattern)

Grow an existing firm seed until the chain closes:

1. **Collect the hub key values** already in the seed (real, un-faked: IDs are not
   scrubbed): `ENTITY_ID`, `NF_ACCOUNT_ID`, `INSTRUMENT_MASTER_ID`, `EBASKET_ID`, …
2. **Discover** related tables per hub with `find-related-tables.sql` (set `HUB` +
   `VALS`). Run in the background; it probes every candidate table.
3. **Extract** `<=10` matching rows per *new* table (skip already-seeded ones and
   views/MV/temp/backup). **Bound the scope**: follow the *specific key values*, not
   `FIRM_CD` wholesale, or you pull the whole firm.
4. **Scrub** with `scrub_faker.py`, **assemble** into `V<n>__seed_firm<f>_closure*.sql`.
5. **Apply** with `flyway migrate`. If a row's FK parent is outside the slice
   (`ORA-02291`), either seed that parent or **defer/log** that table.
6. **Repeat** on the new key values each round introduces, until a round adds no new
   table/row (fixpoint). Large time-series hubs (`*_PRICE*`, daily/EOD history) are
   normally **deferred** — volume, not functional coherence.

## Gotchas (learned the hard way)

- **PII → Faker, never NULL/REDACTED** — the latter breaks NOT NULL/type/length/
  unique/check. Faker values are type/length-correct and deterministic per value.
- **Strip SQLcl artifacts** (`REM INSERTING into`, `SET DEFINE OFF;`) so Flyway OSS
  parses the migration. (`assemble-seed.sh` does this.) Flyway OSS *can* run the
  small seed migrations but **cannot** parse the huge V1 DDL — V1 stays a `baseline`.
- **System-generated `SYS_C#####` constraint names** must be stripped from DDL or you
  hit `ORA-02264` on a fresh DB (relevant to V1, not the seeds).
- **Dry-run + ROLLBACK is unreliable** for validation: insert triggers persist rows
  via autonomous transactions, so a re-apply hits `ORA-00001`. Validate by a full
  reset + the `flyway migrate` chain instead.
- **`<=10 rows/table` is per seed/round** — shared tables (positions, instruments)
  accumulate across firms/rounds, which is expected.
