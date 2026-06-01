# Goal: V2 seed migration for firm 1 (functional database)

The block below is the `/goal` condition (kept under 4000 chars — copy it verbatim
into `/goal`). Rationale and deliberate choices follow underneath.

## Goal condition

```text
Create a Flyway seed migration db/migration/V2__seed_firm1.sql (on top of the V1
baseline) that seeds FIRM 1 (FIRM_CD=1, the special firm) with a COHERENT,
FUNCTIONAL dataset: households, accounts, clients/entities, proposals and
everything the platform manages for that firm's assets (positions, instruments,
models, performance/reporting, billing, etc. — per the real relationships).

SOURCE OF TRUTH: extract REAL firm-1 rows from prod (192.168.1.42 / ORCL12VM,
schema GP — reachable only via sqlcl on 192.168.1.223, creds in
hibernate-localhostQA4.properties) so values are realistic and FK-consistent.
Do not invent data.

SCOPE — discover it, don't guess: analyze (1) the FK graph in GP (start from
firm/household/account/entity/proposal tables and walk dependencies), (2) the
geowealth BE code (~/geowealth — which tables/queries load the
household/account/client/proposal views, which lookups they need), (3) the FE
code (which entities the asset-management UI renders). Derive the table list to
seed and their FK order from that.

LOGIN-ABLE FIRM-1 USER: seed tim1 as a working firm-1 user with the gwAdmin
role/capability — active account, valid email (missing email breaks MFA), MFA
DISABLED, tied to FIRM_CD=1 and the matching entity/person, so the platform
recognizes it as a firm-1 admin and loads the firm's data. Discover from the BE
how users/roles/credentials are stored (which table, how gwAdmin maps, how local
geowealth authenticates — direct DB login vs SAML broker via P1). PASSWORD: tim1
must be able to log in with its known P1 password, but the literal password MUST
NOT be written into any committed file (never-commit rule for P1 creds). If auth
is via P1/SAML the seed stores only the profile+role (the password stays in P1);
if geowealth keeps a DB password, apply it via the platform's own mechanism in a
SEPARATE local, non-committed step (or a parameter), leaving a placeholder + note
in V2.

HARD LIMITS:
- MAX 10 rows per table (for everything).
- Seed reference/lookup tables only as much as firm-1 data needs to resolve
  (still <=10/table).
- Respect FK order, NOT NULL and constraints — zero FK/constraint violations.
- Do not seed ISEQ$$ / system objects; do not touch V1.

DEFINITION OF DONE = FUNCTIONAL DATABASE: apply V2 on the gw-oracle container
(after V1) and prove the platform actually works for this firm — tim1 (gwAdmin)
authenticates, and firm 1's households, accounts, clients and proposals list/load
through the queries the BE/FE really run (spot-check those queries), with no
broken FKs. Register the migration in Flyway (migrate, not baseline).

DOCS: everything in the repo in English (CLAUDE.md rule). A short README/comment
on which tables were seeded and why, how tim1/gwAdmin was seeded, and how it was
validated.
```

## Deliberate choices

- **Real prod data, not fixtures.** FK consistency and "functional" are illusory
  otherwise — so extract firm-1 rows (capped <=10/table) in FK order.
- **Scope is discovered** from the FK graph + BE + FE, not enumerated up front:
  with 1828 tables you cannot guess which are relevant to one firm's asset
  management.
- **`migrate`, not `baseline`.** V2 is a real migration on top of V1; being small
  (<=10 rows) Flyway OSS's parser handles it fine (unlike V1).
- **No table names hardcoded** (HOUSEHOLD/ACCOUNT/ENTITY/PROPOSAL…) — the executor
  confirms them from the schema/code, not from a guess.
- **tim1's password is never committed.** It is a P1 credential. The executor
  first determines how local geowealth authenticates: via P1/SAML → the seed holds
  only profile+role; via a DB password → applied locally, outside git, with a
  placeholder in V2. The literal lives only in P1 / local creds.
- **MFA off + valid email + bound to entity/person** — to avoid the known issues
  (tim1 with no email breaks MFA; see `e2e/scripts/disable-mfa-for-tim1.sh`).
- **gwAdmin mapping is read from the BE code**, not assumed.

## Prerequisites already in place

- `db/migration/V1__baseline_schema.sql` applied to the `gw-oracle` container
  (see `db/stack/`), MAX_STRING_SIZE=EXTENDED, ~5290 valid objects.
- Prod GP reachable via sqlcl on 192.168.1.223.
