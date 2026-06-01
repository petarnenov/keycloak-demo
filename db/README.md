# Database baseline — single source of truth

A Flyway migration that captures the full DDL of the GeoWealth **`GP`** application
schema, extracted from production. This is the authoritative definition of the
database structure: to change the schema, add a new `V<n>__*.sql` migration on top
of the baseline rather than editing live DDL by hand.

## Provenance

- **Source:** production Oracle `192.168.1.42:1521/ORCL12VM`, schema `GP`, Oracle 19c.
- **Method:** `DBMS_METADATA.GET_DDL` with owner-agnostic / storage-free transforms.
- **Generated:** 2026-06-01.
- **Re-extract with:** [`tools/extract-schema.sql`](tools/extract-schema.sql).

## Layout

```text
db/
├── migration/
│   └── V1__baseline_schema.sql   # the versioned baseline — Flyway applies this
├── optional/
│   └── env_objects.sql           # env-specific, apply by hand (NOT a Flyway location)
├── tools/
│   ├── extract-schema.sql        # regenerates the baseline from a live schema
│   ├── seed-pipeline.md          # reusable mechanism: seed a firm / referential closure
│   ├── find-related-tables.sql   # closure discovery (hub column + key values -> tables)
│   ├── scrub_faker.py            # PII scrub via Faker (deterministic per value)
│   └── assemble-seed.sh          # build a V<n> seed migration from scrubbed inserts
├── flyway.conf.example           # sample Flyway config
└── README.md
```

## Seed data (V2) — firm 1

`db/migration/V2__seed_firm1.sql` seeds a minimal but coherent slice of **firm 1**
so the platform is functional for one advisor. Extracted from prod for real
FK/code consistency, then **PII-scrubbed** for this public repo (IDs/codes/
relationships are real; names, emails, tax ids, account numbers and the password
hash are synthetic/blanked). ≤10 rows per table.

| Table | Rows | What |
|---|---|---|
| `FIRM_TBL` | 1 | firm 1 |
| `ENTITY_TBL` (+`USER_DETAIL`/`HOUSEHOLD_DETAIL`/`ENTITY_EMAIL`) | 3 | advisor `tim1`, 1 household, 1 client |
| `ACCOUNT_DETAILS_TBL` / `NF_ACCOUNT_TBL` | 3 / 3 | the household's accounts |
| `ENTITY_ACCOUNT_ROLE_TBL` | 6 | owner (role 1) + household (role 5) links |
| `POLICY_RULE_TBL` | 5 | `tim1`'s view grants (the directory visibility gate) |
| `INSTRUMENT_MASTER_TBL` / `CRNT_TAXLOT_POSITIONS_TBL` | 7 / 10 | holdings |

**Login (`tim1` / gwAdmin):** the advisor entity is `LDAP_UID='tim1'`,
`GW_ADMIN_FLAG=1` (this flag *is* "gwAdmin"), MFA off, active. The P1 password is
**not** committed — `LDAP_PSWD_HASH` is blanked; SAML-broker login works via the
email auto-link (`tim1@geowealth.int`, adjust to match P1). For direct DB login,
set the SHA hash locally (see the V2 header).

Applied and validated on the `stack/` container: `flyway migrate` takes it to
v2 with zero errors, and the BE's real queries resolve (login lookup,
policy-gated visibility, household→accounts, positions→instruments). Caveat: the
React client/account **directories are Elasticsearch-backed** — re-index
(`RefreshClientSearcherTool`) for the list views; login, detail pages and the
policy-gated SQL reports work from this Oracle data directly.

```bash
cd db/stack && docker compose run --rm flyway \
  -url=jdbc:oracle:thin:@//oracle:1521/FREEPDB1 -user=GP -password=gp123 migrate
```

### Firm 3 (V3)

`db/migration/V3__seed_firm3.sql` does the same for **firm 3 ("CF Inc")**, adapted
to its shape: firm 3 is a small **institutional** firm with **no households/CAGs**
and **no positions** — its 8 accounts are owned directly by 7 companies and 1
individual. Seeds 1 firm, 9 entities (advisor `tim3`/gwAdmin + 7 companies + 1
individual), 8 accounts (+details), 8 owner roles, 8 policy grants. Same PII
scrubbing (the 7 real RIA company names → `Demo Company 1..7`), same login model
(`tim3` / `GW_ADMIN_FLAG=1`, MFA off, `tim3@geowealth.int`, password not committed).
Applied via `flyway migrate` (schema → v3), validated the same way.

### Firm 40 (V4)

`db/migration/V4__seed_firm40.sql` — firm 40 ("ClearPath"), a household-based firm
(like firm 1): 1 household, 2 clients, 3 accounts with real positions. Seeds 4
entities (advisor `tim40`/gwAdmin + household + 2 clients), 3 accounts (+details),
6 roles, 6 policy grants, 8 instruments, 10 positions. Same PII scrubbing and
login model (`tim40`, `tim40@geowealth.int`). Applied via `flyway migrate`
(schema → v4). Note: the `<=10 rows/table` cap is per firm seed — across firms the
shared tables (positions, instruments) accumulate as expected.

### Firm 40 referential closure (V5)

`db/migration/V5__seed_firm40_closure.sql` grows the firm-40 seed by **referential
closure** (round 1): starting from V4's firm-40 entities and accounts, it follows
the key columns (`ENTITY_ID`, `NF_ACCOUNT_ID`) — declared FKs *and* logical joins,
since the schema has only ~84 real FKs — to every related table and seeds **≤10
matching rows** from each. 52 tables, 313 rows: addresses, phones, tax/billing
data, daily/EOM balances & values, transactions, ebaskets, cost basis, proposals,
performance, service requests, dashboards, permissions, etc.

- **PII via Faker, not NULL/REDACTED** (the latter breaks NOT NULL/type/unique).
  [`db/tools/scrub_faker.py`](tools/scrub_faker.py) replaces every PII-named column
  value (name/email/phone/address/tax/account#/…) with a realistic, **deterministic-
  per-value** fake (so one entity keeps one fake name across tables); IDs, codes,
  dates, flags and keys stay real.
- **Bounding:** expansion follows the *specific key values* of seeded rows (this
  household/clients/accounts), not `firm_cd=40` wholesale; views, MVs, temp/backup
  tables and ISEQ$$ are skipped. One table (`ENTITY_MODEL_ACC_SET_PERM_TBL`) is
  **deferred/logged** — its FK points to an access-set outside the slice.
- Applied via `flyway migrate` (schema → v5) with zero FK/constraint errors;
  validated (sample tables ≤10 rows each, tim40 intact, invalid-object count
  unchanged at 134).

**Round 2** — `db/migration/V6__seed_firm40_closure_r2.sql` — follows the new keys
round 1 introduced: 2 instrument_master rows referenced by round-1 transactions
(holdings parents), 1 linked advisor entity (+detail/email) so the household-
advisor link resolves, and the `EBASKET_ID` hub → 10 ebasket-side tables (billing,
orders, taxlots, benchmark/sleeve perf, EOD/component values, executed orders),
≤10 rows each. Same Faker scrub. `flyway migrate` → v6, zero errors.

**Closure status:** after 2 rounds the entity / account / ebasket hubs are covered
and — because every Flyway migration applied with **zero FK errors** — all
DB-enforced (required) references resolve to seeded parents: the *required* chain
is closed. The remaining frontier is **deferred/logged**: the `INSTRUMENT_MASTER_ID`
price/history hub (large time-series tables — `*_PRICE*`, daily/EOD history) is
intentionally not expanded (it would add volume, not functional coherence, and is
capped/irrelevant for a ≤10-row functional seed). Further rounds would use the
identical mechanism on those keys.

## What the baseline contains (V1)

Emitted in dependency-safe order; section banners inside the file mark each block.

| # | Section | Objects |
|---|---|---|
| 00 | Object types (specs) | 95 |
| 02 | Sequences | 186 (ISEQ$$ identity sequences excluded) |
| 03 | Tables (inline PK / UNIQUE / CHECK) | 1828 |
| 04 | Indexes (non constraint-backed) | 898 |
| 05 | Foreign key constraints | 84 |
| 06 | Views | 227 |
| 07 | Functions | 137 |
| 08 | Procedures | 88 |
| 09 | Packages (spec + body) | 67 / 66 |
| 11 | Triggers | 328 |
| 12 | Private synonyms | 29 |

A final `DBMS_UTILITY.COMPILE_SCHEMA` call recompiles anything left invalid by
forward references between types / packages / views.

## What is intentionally **not** in the baseline

Kept in [`optional/env_objects.sql`](optional/env_objects.sql) because they are
tied to a concrete deployment and need editing before they work elsewhere:

- **Database links** (3) — the dictionary does not expose stored passwords, so the
  `CREATE DATABASE LINK` statements have no credentials.
- **DBMS_SCHEDULER jobs** (25) — review schedules before enabling.
- **Materialized views** (11) — depend on baseline tables; create after V1.

Not reproducible from DDL at all:

- **Java** (21 classes + 1 resource) — loaded as compiled binaries; there is no
  `JAVA SOURCE` to extract. Reload with `loadjava` from the original jar.

Also excluded by design: `ISEQ$$` identity sequences (auto-created by IDENTITY
columns) and constraint-backed indexes (auto-created by their PK/UNIQUE constraints).

## Applying it

Two prerequisites on the target, then apply with a **native Oracle runner** and
record the result in Flyway.

1. **Connect as a schema named `GP`** — the object DDL is owner-agnostic, but a
   few PL/SQL bodies, one dynamic-SQL string and one trigger `ON` clause carry
   hardcoded `"GP".` references. For another schema name, grep `V1` for `"GP".`.

2. **Put the PDB in `MAX_STRING_SIZE=EXTENDED`** (the schema has VARCHAR2 up to
   32767 and per-column `COLLATE`; without EXTENDED every such table fails with
   `ORA-43929`). One-time, as SYSDBA on a fresh PDB:

   ```sql
   ALTER PLUGGABLE DATABASE <pdb> CLOSE;
   ALTER PLUGGABLE DATABASE <pdb> OPEN UPGRADE;
   ALTER SYSTEM SET MAX_STRING_SIZE=EXTENDED;
   @?/rdbms/admin/utl32k.sql
   ALTER PLUGGABLE DATABASE <pdb> CLOSE;
   ALTER PLUGGABLE DATABASE <pdb> OPEN;
   ```

3. **Apply with sqlplus / SQLcl** — Flyway OSS's parser cannot handle this volume
   of enterprise PL/SQL (it miscounts block depth in pipelined packages and aborts
   with *"unable to decrease block depth below 0"*). The file is SQL\*Plus-format,
   so run it natively:

   ```bash
   sqlplus GP/<pwd>@//host:1521/PDB <<'EOF'
   SET DEFINE OFF
   WHENEVER SQLERROR CONTINUE
   @V1__baseline_schema.sql
   EOF
   ```

4. **Register it in Flyway** so future `V2+` migrations are tracked from here
   (the standard pattern for adopting Flyway on a large existing schema):

   ```bash
   flyway -url=... -user=GP -password=... -baselineVersion=1 baseline
   ```

A fully working, validated apply (Oracle Free 23ai container) lives in
[`stack/`](stack/) — `docker compose run --rm flyway baseline` after the sqlplus load.

### Expected result

~5290 valid objects. ~130 objects stay **INVALID** — these reference other prod
schemas (`QP`/`CP`/`AP`/…) and DB-link targets that don't exist in a single-schema
copy, and many are already INVALID in production itself (231 there). That is
faithful reproduction, not a defect in the baseline.

## Regenerating the baseline

```bash
# as the GP owner, from db/tools/
mkdir -p out
sql -S gp/<pwd>@192.168.1.42:1521:ORCL12VM @extract-schema.sql
# then concatenate out/*.sql in the table order above into V1 (+ banners),
# and out/90,91,93 into optional/env_objects.sql.
```
