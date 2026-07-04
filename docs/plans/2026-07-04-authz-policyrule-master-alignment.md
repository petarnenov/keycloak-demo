# Refactoring plan: align demo authorization with GeoWealth `master` (retire the invented "Tier" taxonomy)

Date: 2026-07-04
Status: proposal (not yet implemented) — execution details in
[`2026-07-04-authz-policyrule-implementation.md`](2026-07-04-authz-policyrule-implementation.md)
Scope: `bff-core` (authz gate + client), the domain BFFs (`billing`, `trading`, plus the
`portfolio`/`custodian` scaffolds), the authz backend contract exposed by P1/`user-service`,
and the docs that describe the flow.

## 1. Purpose

The demo's fine-grained authorization is described with a **"Tier 1 / Tier 2 / Tier 3"**
taxonomy (`Tier23Gate`, `P1AuthzClient`, `p1-auth-flow.md §2.2/§2.9`, `DemoAuthz`). That
vocabulary **does not exist in the GeoWealth production monolith** (`~/geowealth`, branch
`master`). This document records a two-pass source analysis of `master`, reconciles the two
passes so nothing about current production behavior is lost, and specifies a concrete
refactor that makes the demo mirror the **real** GeoWealth authorization structure and
naming (the `PolicyRule` model) instead of the invented tiers.

Non-goal: changing what the demo *enforces* for billing/trading. The goal is structural and
nominal fidelity to `master`, plus closing the genuine semantic gaps found below.

## 2. Method

Two **independent** deep reads of `master` were performed and then cross-checked:

1. Pass A — mapped the authorization surface (`PolicyRuleManager`, `ObjectType`,
   `Permission`, `AccessSet`, call sites).
2. Pass B — an adversarial re-read specifically hunting for behavior Pass A could have
   misrepresented (login-time capability bundle, gwAdmin bypass location, `refineUUIDs`
   internals, permission overrides, caching, firm scoping).

Pass B **overturned one conclusion** from Pass A (see §5, correction C1) and added five
behaviors Pass A missed. Every claim below was confirmed by direct file reads with line
numbers.

Key evidence files in `~/geowealth` (all on `master`):
- `src/main/java/com/geowealth/service/policy/PolicyRuleManager.java` (runtime entry point)
- `src/main/java/com/geowealth/agent/policy/PolicyRuleHibernateDAO.java` (SQL + refresh)
- `src/main/java/com/geowealth/service/policy/PolicyRuleWrapper.java`
- `src/main/java/com/geowealth/service/policy/LoadCreateExecutePermissions.java`
- `src/main/java/com/geowealth/agent/policy/PolicyRuleManagerTrait.java`
- `src/main/java/com/geowealth/web/portal/LoggedUserJTO.java` (login → SPA bundle)
- `src/main/java/com/geowealth/model/authorisation/{ObjectType,Permission,AccessSet}.java`
- `src/main/java/com/geowealth/model/user/NEntity.java` (`permissionOverride`, `gwAdminFlag`)
- `src/main/java/com/geowealth/service/authorizationmanager/AuthorizationManager.java` (admin CRUD facade)

## 3. Headline finding

**`master` has no "Tier" concept.** `grep -rniE "tier[ _-]?[123]"` over `src/main/java`
returns zero authorization hits (only one unrelated report comment). The "Tier 1/2/3"
taxonomy is entirely a keycloak-demo invention.

The mechanism the demo calls "Tier 3 refine" **does** exist in `master`, under different
names: `PolicyRuleManager.refineUUIDs(...)` and `loadCustomerViewableAccounts(...)`.

Also relevant: the `P1AuthzMeAction` / `P1AuthzCanAction` / `P1AuthzRefineAction` REST
endpoints in the GeoWealth tree are **not in `master`**. They were added by branch commit
`564cdc6b26d "GEO-99999 SSO Phase 19: Tier 2 / Tier 3 BFF authorisation endpoints"`
(GEO-99999 is a placeholder ticket = the demo-integration branch) and were never merged to
`master`. They are demo-support scaffolding, not production authz.

## 4. The real `master` model (authoritative reference for the refactor)

### 4.1 Four layers (capability-based access control, not "tiers")

```
Role (ROLE_TBL)
  -> ObjectTypePermission (ROLE_PERMISSION_TBL)      "what a role may do": (ObjectType, Permission) pairs
    -> AccessSet + AccessSetMember (ACCESS_SET_*)     "over which concrete objects"
      -> POLICY_RULE_TBL (pre-materialized rows)      entity_id, object_id, object_cd, object_type_cd, permission_cd, firm_cd
        -> PolicyRuleManager.*                         runtime decisions
```

- `AuthorizationManager` is an **admin CRUD facade only** (create/update roles, access sets,
  entity-role assignments). It is **not** on the runtime authz path.
- `PolicyRuleManager.getSole()` (in-process singleton) is the **runtime enforcement** entry
  point.
- POLICY_RULE is a **pre-materialized** projection. `refreshPolicyRulesForEntity(entity, firm)`
  wipes and rebuilds an entity's rows from its roles + access sets + overrides. Runtime
  checks are **fresh DB reads** of that table (see 4.5 — no per-request cache).

### 4.2 Catalogs

- `Permission`: `VIEW=1, MODIFY=2, CREATE=3, DELETE=4, EXECUTE=5`.
- `ObjectType`: ~95 codes, partitioned into three sets in `ObjectType.java`:
  - `REAL_OBJECTS` — concrete data rows keyed by UUID (`CLIENT`, `ACCOUNT`, `ACCOUNT_GROUP`,
    `OBJECT_GROUP`, `ACCESS_SET`, `ROLE`, `VENDOR_CONTACT`, `EMPLOYEE`, `CUSTOM_REPORT`,
    `CRM_CALL`, `CRM_OPPORTUNITY`, `CRM_TASK`). Many others are commented out.
  - `WEB_SECTION_OBJECTS` — UI sections/features gated by EXECUTE with a null object
    (`INVESTMENT_MANAGEMENT`, `FIRM_ADMINISTRATION`, `GLOBAL_ADMINISTRATION`,
    `SECURITY_ADMINISTRATION`, `INSTRUMENT_MANAGEMENT`, `REPORT_MANAGEMENT`,
    `CUSTODIAN_CONTROL`, `MODIFY_DASHBOARD`, `CUSTOM_REPORTS_ACCESS`, **`BILLING_CENTER`**, ...).
  - `ALL_OBJECTS`.

### 4.3 The three real check KINDS (this is what the refactor must mirror)

All three go through the same primitive `loadPolicyRules(user, objectType, permission[, id])`:

- **(a) Section / capability gate** — WEB_SECTION_OBJECT + `EXECUTE`, null identifier, grant
  on non-empty. Examples in `PolicyRuleManager`: `canLoggedUserAccessBackOffice`,
  `canLoggedUserAccessInvestmentManagement`, `canLoggedUserAccessFirmAdministration`
  (`loadPolicyRules(user, SECTION, EXECUTE)` then `size() > 0`).
- **(b) Single-object check** — REAL_OBJECT + specific id. Generic private method:
  ```java
  private boolean canUserDoObject(UserID entityUUID, int objectType, int permission, Object identifier) {
      List<PolicyRuleWrapper> policyRuleWrappers = loadPolicyRules(entityUUID, objectType, permission, identifier);
      return policyRuleWrappers.size() > 0;
  }
  ```
  Specializations delegate to it: `canLoggedUserViewAccount(id)` ->
  `canUserDoObject(user, ACCOUNT, VIEW, id.getID())`; likewise
  `canLoggedUserExecuteAccount`, `canLoggedUserModifyClient`, `canLoggedUserModifyRole`,
  `canLoggedUserCreateX` (identifier = null for CREATE), etc. `identifier` is `UUID` for
  UUID-keyed objects or `Integer` for code-keyed objects (`ROLE`, `ACCESS_SET`, `EBROKER`, ...).
- **(c) List refine (intersect)** — the "refine" primitive:
  ```java
  public Set<UUID> refineUUIDs(Set<UUID> myListOfUUIDs, UserID loggedUserID, int objectTypeCd, int permissionCd) {
      List<PolicyRuleWrapper> listOfRules = loadPolicyRules(loggedUserID, objectTypeCd, permissionCd);
      HashSet<UUID> result = new HashSet<UUID>();
      for (PolicyRuleWrapper prw : listOfRules) {
          UUID uuid = prw.getObjectId();
          if (myListOfUUIDs.contains(uuid)) result.add(uuid);
      }
      return result;
  }
  ```
  Loads the user's rule set **once**, intersects in memory (no N+1). Typed variants:
  `loadCustomerViewableAccounts(Collection<Account>, user)`, `loadViewableClients(user)`,
  `loadViewableClientIDs`, `loadViewableAccountIDs`, `loadEmployeesForView`.

### 4.4 Login-time capability map (the corrected finding — do NOT call this "Tier 2 invention")

`master` **does** ship a permission map to the React SPA at login:

- `LoggedUserJTO.permissions` (`Map<String,Boolean>`) is set at login (line 217) to
  `PolicyRuleManager.getSole().loadCreateExecutePermissions(loggedUserID)`.
- Key format is **`<objectTypeCd>_<permissionCd>`** (identical to the demo's wire
  convention), value always `true` when present.
- Built in `PolicyRuleManagerTrait` from role capabilities only:
  ```java
  roles.stream().map(Role::getObjectTypePermissions).flatMap(Collection::stream).distinct()
       .collect(Collectors.toMap(v -> v.getObjectType().getObjectTypeCd()+"_"+v.getPermission().getPermissionCd(),
                                 v -> true, (x1,x2)->x1));
  ```
- It is **role-level capability only** (no object_id/object_cd) and **VIEW/CREATE/EXECUTE
  only** (`LoadCreateExecutePermissions` doc comment: "map of (VIEW, CREATE and EXECUTE
  ONLY) permissions"; MODIFY/DELETE are deliberately omitted and enforced server-side per
  object).
- Its role is a **client-side UI hint** (show/hide features). The **authoritative** decision
  for a concrete object is always the server-side per-object check (b) or the section gate
  (a). MODIFY/DELETE of a specific object is never authorized from this map.

`LoggedUserJTO` additionally computes ~15 individual booleans at login
(`canExecuteResourceCenter`, `isPowerAdmin`, `isPowerAdvisor`, `canModifyDashboard`, ...),
each from a `PolicyRuleManager` check ORed with the gwAdmin flag.

### 4.5 gwAdmin bypass is CALLER-SIDE

`PolicyRuleManager` does **not** short-circuit for gwAdmin. Callers write
`user.getUserData().isGwAdminFlag() || PolicyRuleManager.getSole().canLoggedUserX(...)`.
`gwAdminFlag` lives on `NEntity`. Consequence: the bypass is a **convention at each call
site**, not a property of the gate.

### 4.6 Permission overrides

`NEntity.permissionOverride` (`Map<ObjectTypePermission, Boolean>`) grants (`true`) or
revokes (`false`) specific capabilities relative to role defaults. Applied at **refresh
time** (merged into the materialized POLICY_RULE rows), and **only for the entity's default
role** (`PolicyRuleHibernateDAO` `mergePermissionWithOverrides`). Multi-role users get
asymmetric override behavior.

### 4.7 firm scoping and caching

- `firm_cd` is baked into POLICY_RULE rows at **insert/refresh** time; runtime checks filter
  by (entity, objectType, permission) and do not re-scope by firm. Users are single-firm;
  cross-firm reach is only via the caller-side gwAdmin bypass.
- **No per-request or per-user cache** on the read path — each check is a `LoadPolicyRules`
  agent message + Hibernate query against the materialized table.

### 4.8 Known constraint / latent bug in `master`

`refineUUIDs` intersects only on `PolicyRuleWrapper.getObjectId()` (UUID). Code-keyed
objects (`objectCd`, e.g. `ROLE`, `ACCESS_SET`, `EBROKER`) have a null `objectId` in the
wrapper, so refining a list of those returns empty. Refine is effectively **UUID-only**. A
faithful re-implementation should preserve this scoping (refine = REAL_OBJECTS/UUID) and not
silently "fix" it without a decision.

## 5. Reconciliation of the two analysis passes

- **C1 (correction, important).** Pass A claimed the demo's "Tier 2 permission map" has *no
  analog in master / is invented*. **False.** `loadCreateExecutePermissions` →
  `LoggedUserJTO.permissions` is a direct analog with the **same `<objType>_<perm>` key
  format**. What differs is its *role*: in master it is a client-side UI hint (role-level,
  VIEW/CREATE/EXECUTE); the demo uses the same-shaped map **server-side** to gate requests.
- **C2 (added).** gwAdmin bypass is caller-side in master (§4.5); the demo bakes it into the
  gate.
- **C3 (added).** `refineUUIDs` is UUID-only (§4.8).
- **C4 (added).** Permission overrides exist (§4.6); the demo has none.
- **C5 (added).** No read-path cache in master (§4.7); the demo caches `/me` for <=60s.
- **Confirmed by both passes.** No tier terminology; 4-layer model; three check kinds;
  `canUserDoObject` grants on `size() > 0`; section gate = EXECUTE non-empty.

## 6. Current demo implementation (what exists today)

- `bff-core/.../P1AuthzClient.java` — HTTP client to P1: `GET /saml/idp/p1-authz-me.do`
  (permission map, cached <=60s), `POST /p1-authz-can.do` (single), `POST
  /p1-authz-refine.do` (list). Fail-closed. Gated by `app.authz.fine-enabled` (default false).
- `bff-core/.../Tier23Gate.java` — `require(auth, objType, perm)` (reads the cached map),
  `can(...)` (single, unused by controllers), `refine(auth, items, idOf, objType, perm)`.
  gwAdmin bypass is baked in.
- `domains/<d>/bff/.../DemoAuthz.java` — placeholder ObjectType codes (`INVOICE=9101`,
  `ORDER=9201`) + `PERM_*` mirroring `Permission`.
- Controllers call `gate.require(...)` (capability) then `gate.refine(...)` (list). Neither
  calls `gate.can(...)`.
- `HeaderIdentity` / `AuthClaims` — read identity from the forward-auth `X-Auth-*` headers.

## 7. Gap analysis (demo -> master), corrected

| Demo today | master reality | Verdict |
|---|---|---|
| "Tier 1/2/3" naming | no tiers; PolicyRule model, 3 check kinds | rename |
| `/p1-authz-me.do` permission map | `loadCreateExecutePermissions` (same `<objType>_<perm>` keys) | KEEP; rename + re-scope to role-level, VIEW/CREATE/EXECUTE, and treat as UI hint |
| map used **server-side** to gate (`gate.require`) | server-side gate is fresh per-object check / section EXECUTE; map is a client hint | separate the two roles |
| `can()` single (unused) | `canUserDoObject(objType, perm, id)` — the primary object gate | rename + actually use for object actions |
| `refine()` list | `refineUUIDs(set, user, objType, perm)` | KEEP semantics; rename; document UUID-only |
| `Tier23Gate` class | `PolicyRuleManager` runtime facade | rename |
| gwAdmin baked into gate | caller-side `gwAdminFlag \|\| canX()` | move to caller convention (or keep but document divergence) |
| placeholder ObjectType ints | `ObjectType` catalog w/ REAL vs WEB_SECTION split | introduce catalog; map billing->`BILLING_CENTER` section + `INVOICE` real object |
| no overrides | `permissionOverride` (default-role, refresh-time) | add to backend contract if fidelity required |
| caches `/me` <=60s | no read-path cache (materialized table) | keep cache as a demo optimization; document divergence |
| `Permission` VIEW=1..EXECUTE=5 | identical | already faithful |

## 8. Refactoring plan (phased)

Each phase is independently shippable. Effort is concentrated in `bff-core`; because the
active auth flow runs in the token-handler, a `bff-core` change ships by rebuilding the
token-handler image (and the two data BFFs still bundle `Tier23Gate`/`P1AuthzClient`, so
they rebuild too — see CLAUDE.md "Applying changes").

Ordering note: Phase A0 (the structural extraction) can land before or after Phase A (the
rename). Doing A0 first means the new service is born with PolicyRule vocabulary and the
later phases only touch clients; doing A first keeps each step smaller. A0 is written to
own the endpoint contract that Phases B/C otherwise assign to P1 — once A0 lands, the
`/policy/*` endpoints belong to `authz-service`, not P1.

### Phase A0 — Extract the authz decision path into its own domain (`authz-service`)

Rationale: login/logout were already extracted out of P1 into the token-handler, and
identity/roles were extracted into `user-service` (a standalone read-only Oracle DAO —
`RoleDao` already joins `ENTITY_ROLE_TBL x ROLE_TBL`). Fine-grained authorization, however,
still calls **back into P1 at runtime**: `P1AuthzClient` (`@Client(id="p1authz")`) hits P1's
`/saml/idp/p1-authz-{me,can,refine}.do`. So a `/api/**` data request currently depends on P1
being up. Extracting the authz **decision** path removes P1 from the runtime authz path
entirely, completing the same decoupling the auth extraction started, and mirrors master's
`PolicyRuleManager` as its own runtime (across an HTTP boundary).

"Domain" here means a **bounded context / identity-tier service**, NOT a `domains/<slug>/`
demo tenant. `domains/<slug>/` is the mold for gated data-products fronted by the
token-handler; authz is a cross-cutting **authority** the tier consults, a sibling of
`user-service` / `token-handler`. Do not scaffold it with `add-domain.sh`.

Placement decision (resolve before implementing):
- **Option 1 (recommended): a dedicated `authz-service`.** It IS PolicyRuleManager-as-a-
  service. Its call pattern (per-request, on the `/api/**` data path, from the BFFs /
  token-handler) differs from `user-service` (per-login, from the Keycloak SPI), which
  justifies an independent runtime + HPA. Cleanest structural fidelity.
- **Option 2: extend `user-service`** (rename to an identity+authz service). Reuses the
  Oracle connection, DAO layer, and deployment; fewer moving parts. Cost: mixes two call
  patterns and two audiences in one service. Defensible for a demo, less clean.

What moves INTO the authz domain (the authority — fully extractable):
1. **The `ObjectType` + `Permission` catalogs** (with the REAL_OBJECTS vs WEB_SECTION_OBJECTS
   split), replacing the per-domain `DemoAuthz` placeholder ints (`INVOICE=9101`,
   `ORDER=9201`). The catalog becomes the single source of truth for `<objType>_<perm>`.
2. **The decision logic + POLICY_RULE read DAO**: `loadPolicyRules`, `canUserDoObject`
   (single), `refineUUIDs` (list intersect), `loadCreateExecutePermissions` (the capability
   map). Read-only Oracle access to `POLICY_RULE_TBL` (and role tables for the capability
   map), fail-closed.
3. **The endpoint contract** (owned here, not by P1):
   - `GET  /policy/capabilities?sub=…`  → `{ "<objType>_<perm>": true, … }` (role-level,
     VIEW/CREATE/EXECUTE only) — feeds `/auth/me` per Phase B step 5a.
   - `POST /policy/can`      `{ objectType, permission, objectId }` → `{ allowed: bool }`.
   - `POST /policy/refine`   `{ objectType, permission, ids[] }`    → `{ allowed: [ids…] }`.
   Authority stays user-bound: the caller forwards the user's own access token; no admin
   token. (These replace P1's `p1-authz-{me,can,refine}.do`.)

What STAYS OUT (distributed by nature — cannot be centralized into the authz domain):
4. **The `refine` call-site** remains in each data BFF. `refineUUIDs` filters the domain's
   OWN rows (invoices/orders); the id list exists only in the BFF. The BFF calls
   `authz-service.refine(objType, perm, ids)`; the **decision** moves, the **invocation** and
   the row data stay with the domain.
5. **Materialization of POLICY_RULE** (refresh from roles + access-sets + overrides) stays in
   P1 (prod) / Flyway seed (demo). The authz domain only READS the materialized table.
   (See §4.6/§4.7 and the "materialization" boundary.)
6. **Enforcement points stay distributed**: token-handler `/auth/verify` (coarse + section
   gate) and the domain controllers CONSULT `authz-service` but the deny happens at the edge
   of each request.

Client side after A0: `bff-core`'s `P1AuthzClient` becomes `PolicyRuleClient` (Phase A) but
now points at `authz-service` instead of P1 (`@Client(id="authz")`); `Tier23Gate` /
`PolicyRuleGate` is unchanged in shape — it just calls the new service. Wire the new service
into `docker-compose.yml` and `k8s/` alongside `user-service`; add its base URL to the
token-handler and data-BFF config; keep `app.authz.fine-enabled` as the opt-in switch.

Deliverable of A0: P1 no longer appears on any runtime authz path; the identity tier is
self-contained (KC→user-service for identity, token-handler for auth, authz-service for
decisions).

### Phase A — Rename to the PolicyRule vocabulary (no behavior change)

1. `Tier23Gate` -> `PolicyRuleGate` (client-side facade mirroring `PolicyRuleManager`).
2. `P1AuthzClient` -> `PolicyRuleClient`.
3. Method renames on the gate:
   - `require(auth, objType, perm)` -> split into the two real capability shapes:
     - `requireSection(auth, sectionObjectType)` — mirrors `canLoggedUserAccess*`
       (WEB_SECTION + EXECUTE, non-empty).
     - `requireCapability(auth, objType, CREATE)` — mirrors `canLoggedUserCreateX`.
   - add `canUserDoObject(auth, objType, perm, objectId)` — mirrors the single-object gate
     (currently `can()`), and make object actions use it.
   - `refine(...)` -> `refineUUIDs(...)`, semantics unchanged; Javadoc notes UUID/REAL_OBJECT
     scoping (§4.8).
4. Strip all "Tier 2/Tier 3" wording from Javadoc/comments; replace with PolicyRule terms.

### Phase B — Split the capability map's two roles

5. Keep the `<objType>_<perm>` permission map but reframe it as `loadCreateExecutePermissions`
   (role-level, VIEW/CREATE/EXECUTE), documented as a **client-side UI hint**, not the
   server gate. Rename the endpoint `/saml/idp/p1-authz-me.do` -> a PolicyRule name
   (e.g. `/policy/capabilities`).
5a. **Ship the map to the SPA at login, matching P1's `LoggedUserJTO.permissions`.** Today
   the demo's login-response equivalent — token-handler `GET /auth/me` — returns identity
   only (`authenticated, username, email, personId, memberships, firmCd, tenantIdentity,
   activeTenant`) and **no** capability map, so the SPA currently receives nothing like P1's
   `<objectTypeCd>_<permissionCd>` bundle. To reach parity, `/auth/me` (or a sibling
   `GET /auth/capabilities`) must call `/policy/capabilities` and include the
   `<objType>_<perm> -> true` map (role-level, VIEW/CREATE/EXECUTE only) in its JSON, and the
   billing/trading web apps must read it for UI show/hide. Without this explicit step the map
   stays server-side and the SPA does NOT get it — the rename in step 5 alone is not enough.
   Keep it a UI hint: authoritative object decisions still run server-side (steps 6-7).
6. Server-side gating stops reading the capability map for object decisions. Object actions
   use `canUserDoObject(objType, perm, id)`; section access uses `requireSection(section)`.
   The map remains only for (optional) coarse "is this feature unlocked at all" checks and
   for shipping to the SPA.
7. Rename remaining endpoints to mirror `PolicyRuleManager` operations:
   `/policy/can` (single), `/policy/refine` (list). (Applies to the GeoWealth demo-branch
   actions too — they should expose `PolicyRuleManager` operations, not "authz-me".)

### Phase C — Catalogs and data shape

8. Introduce real `ObjectType` + `Permission` catalogs in the demo, including the
   **REAL_OBJECTS vs WEB_SECTION_OBJECTS** distinction. Replace `DemoAuthz` placeholders:
   - billing: section `BILLING_CENTER` (WEB_SECTION, EXECUTE) for page access + `INVOICE`
     (REAL_OBJECT, VIEW/MODIFY) for row actions/refine.
   - trading: a trading WEB_SECTION for page access + `ORDER` (REAL_OBJECT) for rows.
9. Shape the authz backend store as **POLICY_RULE rows**
   (`entity_id, object_id, object_cd, object_type_cd, permission_cd, firm_cd`) so `refine`
   is a genuine set intersection over materialized rules, matching master. Keep `firm_cd`
   baked at write time.

### Phase D — gwAdmin and overrides (fidelity)

10. Move the gwAdmin bypass to the **caller** convention (`gwAdminFlag || canX()`) instead of
    baking it into the gate, matching master §4.5 — or, if keeping it in the gate for demo
    ergonomics, document the deliberate divergence.
11. (Optional, only if strict fidelity is required) add per-entity `permissionOverride`
    (default-role, merged at refresh) to the backend contract. Otherwise document its
    absence.

### Phase E — Docs

12. Rewrite `p1-auth-flow.md` §2.2/§2.9 and all "Tier" references in `CLAUDE.md`,
    `bff-core/README.md`, and Javadoc to the PolicyRule vocabulary. Add a short
    "master fidelity" note listing the two accepted divergences (remote vs in-process; the
    <=60s cache).

## 9. Faithful mapping (target state)

| PolicyRule concept (master) | Demo target |
|---|---|
| `PolicyRuleManager` (runtime engine, in-process) | `authz-service` (standalone; owns POLICY_RULE reads + catalogs) — Phase A0 |
| `PolicyRuleManager` (call surface) | `PolicyRuleGate` (client facade) + `PolicyRuleClient` (HTTP to `authz-service`) |
| `loadCreateExecutePermissions` -> SPA | `authz-service GET /policy/capabilities` map, surfaced via `/auth/me`, client-side UI hint |
| `canUserDoObject(objType, perm, id)` | `PolicyRuleGate.canUserDoObject(...)` via `authz-service POST /policy/can` |
| `canLoggedUserAccess<Section>` | `PolicyRuleGate.requireSection(section)` (WEB_SECTION EXECUTE) via `/policy/*` |
| `refineUUIDs(set, user, objType, perm)` | `PolicyRuleGate.refineUUIDs(...)` via `authz-service POST /policy/refine`; call-site stays in the data BFF |
| `ObjectType` REAL vs WEB_SECTION | owned by `authz-service` catalog (same split), replaces per-domain `DemoAuthz` |
| `Permission` VIEW..EXECUTE | already identical |
| `gwAdminFlag \|\| canX()` (caller) | caller convention in controllers |
| POLICY_RULE materialization / refresh | stays in P1 (prod) / Flyway seed (demo); `authz-service` only reads |

## 10. Accepted divergences (cannot/should not be removed)

- **Remote vs in-process.** master calls `PolicyRuleManager.getSole()` in-process; the demo
  is a distributed BFF calling `authz-service` over HTTP (Phase A0; P1 before A0). "Faithful
  to structure" means the same model and vocabulary across an HTTP boundary, not co-locating
  the engine.
- **Read-path cache.** The demo's <=60s `/me`-style cache is a network optimization master
  does not need (materialized table + local agent). Keep it, but document it.
- **refine UUID-only.** Preserve master's REAL_OBJECT/UUID scoping for `refineUUIDs` rather
  than silently generalizing to code-keyed objects.

## 11. Verification

- Unit: port `Tier23GateTest` -> `PolicyRuleGateTest`; add cases for `requireSection`,
  `canUserDoObject`, and `refineUUIDs` UUID-only scoping; keep fail-closed assertions.
- E2E: re-run the existing suite under `e2e/` (claims, silent-first chain, token-handler
  invariants, logout fan-out) to confirm no behavior regression for billing/trading with
  `app.authz.fine-enabled` toggled.
- Grep gate: `grep -rniE "tier[ _-]?[123]"` over the demo repo returns zero authz hits after
  Phase E.
- A0 decoupling: with P1 scaled to 0 (`k8s/toggle.sh legacy down`), a `/api/**` request with
  `app.authz.fine-enabled=true` still resolves capabilities/can/refine against `authz-service`
  (no `p1authz` client calls remain — grep `bff-core`/token-handler for `p1-authz` returns
  nothing). Confirms P1 is off the runtime authz path.
