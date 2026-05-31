# Person identity via the existing link/delink system

Analysis of `~/geowealth` **master** vs the cross-subdomain SSO person-identity
model we shipped on `team/petarnenov/keycloak-persons-registry`. Goal: reach the
same behaviour (one stable person across subdomains, per-tenant alias + roles)
with **minimal changes**, by reusing what master already has instead of the
parallel `PERSON_REGISTRY_TBL`.

## TL;DR

master **already ships a person-linking system** — `ENTITY_TBL.LINKED_GW_USER`
plus a GWAdmin link/delink admin screen. It is the production-grade equivalent
of our demo `PersonRegistry`. The only thing missing is that the **SAML emission
path does not follow the link**. So the minimal change is: re-implement
`PersonRegistry`'s three static methods on top of the existing `LINKED_GW_USER`
graph and delete the standalone `PERSON_REGISTRY_TBL` + DAO/Manager/Entity. The
SAML path (`IdpSsoAction`) keeps the same method calls and does not change.

## What master already has (the existing link/delink system)

| Concern | Location (master) |
|---|---|
| Link column | `ENTITY_TBL.LINKED_GW_USER` — `many-to-one` FK on `NEntity.linkedEntity` → another `NEntity`. Mapping: `NEntity.hbm.xml:228`. Nullable (NULL = not linked). |
| LINK / DELINK handler | `UserManagerTrait.java:8051` (`PerformUserLinkDelinkMsg`). LINK: `employeeEntity.setLinkedEntity(firm1Entity)`. DELINK: `setLinkedEntity(null)`. |
| Admin action (GWAdmin-gated) | `UserLinkDelinkAction.java` — `usersLinkDelink()` + `searchUsersByPrimaryEmail()`. Guard: `LoggedUser.isGWAdmin() && isFirmGeowealth()`. |
| Candidate search | `NEntityDAO.findUsersForLinkingByPrimaryEmail()` (`NEntityDAO.java:684`). Three HQL queries: (A) other-firm employees with the **same primary email** as a firm-1 employee; (B) other-firm employees **already linked** (follows `linkedEntity`); (C) the firm-1 roster as link targets. |
| Request/response shape | `UserLinkDelinkRequest(actionType, firm1EntityId, employeeEntityId)`; `UsersForLinkDelinkJTO` carries `userId`, `firmCd`, `ldapUid`, `linkedUserId`, `firm1EntityId`, `firm1EmailAddress`; grouped by primary email in `UsersForLinkDelinkMeta.groupedByPrimaryEmail`. |
| Login-time resolution | **Exists, but password-only.** Direct LDAP login follows the link to check the password against the firm-1 entity's hash (`NEntityDAO.java:6334` / `validatePasswordOfUser` ~`:6371`; `NEntity.toUser()` ~`:705`). The returned `User` keeps the **employee's** identity. Password-change swaps to the linked entity (`UserManagerTrait.java:7860`). |
| **SAML/SSO path** | **Does NOT follow the link** and skips `gwAdminFlag` entities. This is the gap. |

### The model in one line

A **Firm-1 entity is the person** (authoritative identity — the password lives
there). Employee entities in other firms (`firmCd != 1`) point at it via
`LINKED_GW_USER`. One firm-1 entity ← many linked employee entities, one per firm.

## What our feature branch added (the parallel we can retire)

`com.geowealth.saml.idp.*` is **entirely feature-branch-only** (absent on master),
including:

- `PersonRegistry` + `registry/{PersonRegistryDAO,PersonRegistryManager,PersonRegistryEntry}` backed by a **new** `PERSON_REGISTRY_TBL` (`keycloak-demo/scripts/create-person-registry-table.sql`).
- `IdpSsoAction.collectAttributes()` calls `PersonRegistry.resolvePersonId / resolveTenantAliases / resolveTenantRoles` to emit `personId`, `tenantIdentity_<slug>`, `roles_<slug>` SAML attributes.

`PERSON_REGISTRY_TBL` stores, per person: `usernames[]`, `aliases{slug→username}`,
`roles{slug→[role]}`. **This is exactly the `LINKED_GW_USER` graph re-encoded in a
second place** — a duplicate source of truth with its own (not-yet-built) admin UI.

## The mapping (demo registry → existing system)

| `PersonRegistry` concept | Existing master equivalent |
|---|---|
| `personId` (`P-tim`) | the **firm-1 entity's `ENTITY_ID`** (the `linkedEntity`) |
| `usernames[]` | the firm-1 entity + every entity whose `LINKED_GW_USER` points at it |
| `aliases{tenant→username}` | each linked entity's `ldapUid`, keyed by its **`firmCd`** |
| `roles{tenant→[role]}` | each linked entity's **own role assignment in its own firm** |
| `resolvePrimaryUsername(personId)` | the firm-1 entity's `ldapUid` |
| candidate discovery / email-match | `findUsersForLinkingByPrimaryEmail` (already exists) |

The seam is clean because `PersonRegistry`'s **method signatures are exactly the
abstraction boundary**. Swap the body (PERSON_REGISTRY_TBL → `LINKED_GW_USER`
queries); `IdpSsoAction` and the rest of the SAML path are untouched.

## Minimal-change plan

1. **Re-implement `PersonRegistry` on the link graph (no new storage).**
   - `resolvePersonId(user)`: `user.linkedEntity != null ? linkedEntity.entityId : user.entityId`. Person-stable across all linked accounts; matches how direct-login already resolves password rights to the firm-1 entity.
   - `resolveTenantAliases(personId)`: load the firm-1 entity by id, then its linked-sibling entities (reuse the reverse-link query already in `NEntityDAO`, i.e. the body of Query B), key each by `firmCd`.
   - `resolveTenantRoles(personId)`: per sibling entity, read its own roles (wire through the existing `SsoRoleTranslator` instead of hardcoded lists).
   - `resolvePrimaryUsername(personId)`: firm-1 entity's `ldapUid`.

2. **Delete the parallel registry**: `registry/PersonRegistry{DAO,Manager,Entity}`, `create-person-registry-table.sql`, and the Demo Users persons-grid admin page — the link/delink admin screen already covers writes.

3. **Make the SAML path follow the link** (the one real gap). `IdpSsoAction` already calls the `PersonRegistry` methods, so step 1 covers emission. Confirm the path no longer hard-skips `gwAdminFlag` when the *logged-in* user is a linked employee (login is the employee, not the firm-1 admin) — verify against `IdpSsoAction` / `SamlManagerTrait` skip logic.

## Decisions to confirm before coding

- **tenant = firm.** In the demo, tenant slugs (`billing/trading/users`) are
  decoupled from `firmCd`; in master the natural tenant axis is the firm. Either
  (a) define `tenant_identity` per `firmCd` and map the three demo slugs to three
  firms, or (b) keep a thin slug→firmCd lookup. The demo data currently has
  tim1/tim5/tim10 all in `firmCd=1`, so to exercise `LINKED_GW_USER` honestly the
  demo seed must spread the accounts across firms and link them.
- **Person = firm-1 entity** is master's existing authority choice (password
  lives there). Keep it; do not introduce a separate person id space.
- **Roles source**: per-firm entity role assignment via `SsoRoleTranslator`,
  replacing the hardcoded `roles{slug→[...]}` payload.
- **Unlinked users**: `linkedEntity == null` → person = self; single-tenant. Same
  graceful fallback `PersonRegistry` already has for unknown persons.

## Net effect

- **Removed**: one table, one DAO/Manager/Entity trio, one admin UI, one seed script — a whole duplicate identity store.
- **Added**: link-graph queries inside `PersonRegistry` (most of which already exist in `NEntityDAO`).
- **Unchanged**: `IdpSsoAction` and the SAML emission contract; the Keycloak realm mappers; the BFFs; the SPAs. The `personId / tenant_identity / roles` claim shape on the token is identical.
- **Operational win**: person linking becomes a first-class, audited, GWAdmin admin action that already exists in production, instead of hand-seeded JSON.
