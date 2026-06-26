# user-service ↔ Oracle Schema Contract

**Date:** 2026-06-26
**Status:** Frozen for Phase 1 (user-service standalone)
**Source of truth:** P1's Hibernate `.hbm.xml` files in
`nodejs/geowealth/src/main/java/com/geowealth/util/hibernate/`

This document fixes the exact Oracle column shape user-service depends on.
Columns the live schema actually uses; **column names ≠ what the plan's
prose suggested**. Authoritative names from the hbm files.

## Tables

### `ENTITY_TBL`

Defined in `NEntity.hbm.xml`. Discriminator-mapped (`ENTITY_TYPE_CD`) but
user-service treats it as a flat table for its read needs.

| Column | Oracle type | Java type | Nullable | Notes |
|---|---|---|---|---|
| `ENTITY_ID` | `CHAR(32)` | `String` (32-char UUID, no hyphens) | NOT NULL (PK) | All UUIDs in P1 are stored unhyphenated |
| `FIRM_CD` | `NUMBER` | `Integer` | nullable in column, NOT NULL in practice | Tenant FK to `FIRM_TBL` |
| `LDAP_UID` | `VARCHAR2` | `String` | nullable | Login username; unique per firm |
| `LDAP_PSWD_HASH` | `VARCHAR2` | `String` | nullable | **NOTE: `_PSWD_` not `_PASSWORD_`** |
| `ENTITY_TYPE_CD` | `NUMBER` | `Integer` | NOT NULL | Discriminator; user-service filters on this when looking up "users" (e.g. `IN (1, 4, 17)`) |
| `ENTITY_ACTIVE_FLAG` | `NUMBER(1)` | `boolean` | NOT NULL | Soft delete |
| `LOGIN_INACTIVATED_FLAG` | `NUMBER(1)` | `boolean` | nullable, default 0 | Locked flag |
| `LOGIN_INACTIVATED_DATE` | `DATE` | `java.util.Date` | nullable | Lock timestamp |
| `LOGIN_INACTIVED_REASON_CD` | `NUMBER` | `Integer` | nullable | **NOTE: legacy typo `INACTIVED` not `INACTIVATED`** — preserved exactly |
| `GW_ADMIN_FLAG` | `NUMBER(1)` | `boolean` | default 0 | Cross-firm admin override; lowercase `gw_admin_flag` in hbm but Oracle uppercases unquoted ids |
| `LINKED_GW_USER` | `CHAR(32)` | `String` (FK → `ENTITY_TBL.ENTITY_ID`) | nullable | Multi-firm linked-user pointer to firm-1 person entity |
| `MFA_TOKEN` | `VARCHAR2` | `String` | nullable | SHA1 hash of the 6-digit OTP |
| `MFA_TOKEN_EXPIRATION_DATE` | `TIMESTAMP` | `java.util.Date` | nullable | OTP lifetime (5 min) |
| `MFA_REQUIRED_FLAG` | `NUMBER(1)` | `Boolean` (boxed) | nullable | Boxed because tri-state (null = inherit firm default) |
| `PSWD_EXPIRATION_DATE` | `DATE` | `java.util.Date` | nullable | **NOTE: `PSWD` not `PASSWORD`** |

### `ENTITY_ROLE_TBL`

Defined in `EntityRole.hbm.xml`.

| Column | Oracle type | Nullable | Notes |
|---|---|---|---|
| `ENTITY_ROLE_CD` | `NUMBER(22)` | NOT NULL (PK) | Sequence `ENTITY_ROLE_SEQ` |
| `ENTITY_ID` | `CHAR(32)` | NOT NULL | FK to `ENTITY_TBL.ENTITY_ID` |
| `ROLE_CD` | `NUMBER(22)` | NOT NULL | FK to `ROLE_TBL.ROLE_CD` — **NUMBER, not UUID** |
| `CREATED_DATE` | `DATE` | nullable | |
| `CREATED_BY` | `VARCHAR2(32)` | nullable | |
| `LAST_UPDATED_DATE` | `DATE` | nullable | |
| `LAST_UPDATED_BY` | `VARCHAR2(32)` | nullable | |

### `ROLE_TBL`

Defined in `Role.hbm.xml`.

| Column | Oracle type | Nullable | Notes |
|---|---|---|---|
| `ROLE_CD` | `NUMBER(22)` | NOT NULL (PK) | Sequence `ROLE_SEQ` |
| `FIRM_CD` | `NUMBER(22)` | NOT NULL | FK to `FIRM_TBL.FIRM_CD`; roles are firm-scoped |
| `NAME` | `VARCHAR2(50)` | nullable | The literal role name user-service returns as a Keycloak realm role |
| `DESCRIPTION` | `VARCHAR2(255)` | nullable | |
| `DEFAULT_FLAG` | `NUMBER(1)` | nullable | |
| `CREATED_DATE` / `CREATED_BY` / `LAST_UPDATED_DATE` / `LAST_UPDATED_BY` | audit | nullable | |

### No `PERSON_TBL`

GeoWealth superseded `PERSON_REGISTRY_TBL` with the link-graph model:
a person is a firm-1 entity row in `ENTITY_TBL`, and other firm-N entities
point to it via `LINKED_GW_USER`. `PersonRegistry` walks the graph by
following `entity.getLinkedEntity()` repeatedly. user-service replicates
this with a recursive CTE (see §3.2 of the source plan for the SPI
attribute contract; query body in §SQL below).

## SHAPassword — verify-only

Source: `nodejs/geowealth/src/main/java/com/netfolio/util/SHAPassword.java`
lines 55–76. Algorithm is **NOT plain SHA1**:

1. Strip prefix `{SHA}` (5 chars) or `{SSHA}` (6 chars), case-insensitive.
2. Base64-decode the remainder → bytes.
3. Bytes split at offset 20: first 20 bytes = SHA-1 hash, remaining bytes = salt.
4. Compute `SHA1(plaintext.getBytes() + salt)` and compare via
   `MessageDigest.isEqual`.

user-service copies this class verbatim into
`com.gw.userservice.security.SHAPassword`. Do NOT reimplement from scratch
— the salt handling and label-prefix subtlety is non-obvious.

## SQL the user-service issues

All queries are read-only. Use bind parameters. No HQL/JPA — plain JDBC.

### `findByUsernameAndFirm(username, firmCd)`

```sql
SELECT ENTITY_ID, FIRM_CD, LDAP_UID, LDAP_PSWD_HASH, ENTITY_TYPE_CD,
       ENTITY_ACTIVE_FLAG, LOGIN_INACTIVATED_FLAG, LOGIN_INACTIVED_REASON_CD,
       GW_ADMIN_FLAG, LINKED_GW_USER, MFA_TOKEN, MFA_TOKEN_EXPIRATION_DATE,
       MFA_REQUIRED_FLAG, PSWD_EXPIRATION_DATE
  FROM ENTITY_TBL
 WHERE LDAP_UID = ?
   AND FIRM_CD = ?
   AND ENTITY_ACTIVE_FLAG = 1
```

### `findByEntityId(entityId)`

```sql
SELECT … same columns …
  FROM ENTITY_TBL
 WHERE ENTITY_ID = ?
   AND ENTITY_ACTIVE_FLAG = 1
```

### `findRolesByEntityId(entityId)`

```sql
SELECT R.NAME
  FROM ENTITY_ROLE_TBL ER
  JOIN ROLE_TBL R ON R.ROLE_CD = ER.ROLE_CD
 WHERE ER.ENTITY_ID = ?
 ORDER BY R.NAME
```

### Memberships — walk LINKED_GW_USER graph

```sql
-- Resolve person root: follow LINKED_GW_USER chain
WITH person_root (ENTITY_ID) AS (
  SELECT NVL(LINKED_GW_USER, ENTITY_ID)
    FROM ENTITY_TBL
   WHERE ENTITY_ID = ?
)
SELECT E.FIRM_CD, E.LDAP_UID
  FROM ENTITY_TBL E
  JOIN person_root P
    ON E.ENTITY_ID = P.ENTITY_ID OR E.LINKED_GW_USER = P.ENTITY_ID
 WHERE E.ENTITY_ACTIVE_FLAG = 1
 ORDER BY E.FIRM_CD
```

Result formatted as `String[]` of `"<firmCd>:<ldapUid>"` to match the shape
P1's `PersonRegistry#resolveMemberships` emits.

## Credential-check semantics

`verifyCredentials(entityId, plaintext)` returns one of:

| `valid` | `reason` | When |
|---|---|---|
| `true` | `OK` | `LOGIN_INACTIVATED_FLAG = 0` AND `LDAP_PSWD_HASH` non-null AND `SHAPassword.check(plaintext, hash) == true` |
| `false` | `LOCKED` | `LOGIN_INACTIVATED_FLAG = 1` (regardless of password match) |
| `false` | `BAD_PASSWORD` | Active, but hash mismatch or hash is null |
| `false` | `UNKNOWN_USER` | Row not found by entityId |

Phase 1 does NOT increment failed-attempt counters or auto-lock —
`LoginActivityManager` lives in P1 and remains there until a future
"user-service write paths" follow-up MD.

## Cache policy

KC User Storage SPI caches user lookups (`EVICT_DAILY`). Credential
validation is NEVER cached (Keycloak respects this). Roles are part of the
user attributes blob and are cache-stale until daily eviction — acceptable
for the demo because role changes are rare.

## Why this doc exists

The plan's prose used aspirational column names (`LDAP_PASSWORD_HASH`,
`PASSWORD_EXPIRATION_DATE`, `LOGIN_INACTIVATED_REASON_CD`,
`ROLE_ID/UUID`). The live schema uses abbreviations and one legacy typo
(`LOGIN_INACTIVED_*`). user-service code must match the live schema, not
the plan's prose. Future schema changes update this doc; the plan stays
the design.
