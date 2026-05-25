# P1 auth flow — how roles & permissions reach a logged-in user, and what the demo domains should do with that

Companion to [`sso-role-mapping.md`](sso-role-mapping.md). That doc decided the *vocabulary* (coarse capability roles + `firmCd` claim) the SSO layer should expose. This doc traces what P1 actually does today on `master` — both cold-login and warm-session paths — and then proposes how external domain BFFs (`billing`, `trading`, and future ones) should consume that information without re-implementing P1's authority model.

All `file:line` citations are against `~/geowealth` on `master` (commit `8654a034263`). The SAML IdP wiring (`com/geowealth/saml/idp/IdpSsoAction`, `KeycloakAttributeStatementMapper`) cited in `sso-role-mapping.md` lives on the POC branch and is **not** present on master; everything below describes the production code the POC grafts onto.

---

## Part 1 — Analysis

### 1.1 Login surfaces

Form login is one Struts2 action: `com.geowealth.web.common.action.LoginAction` (`src/main/resources/struts-react.xml:73-87`).

| URL | Action | Used by |
|---|---|---|
| `/login.do`, `/loginMobile.do` | `LoginAction.loginReact()` → `ReactIndexAction.login()` | React advisor SPA, mobile app |
| `/loginReact.do` | `ReactIndexAction.login()` | FE polling for the current login state |
| `/checkLogin.do` | `CheckLoginAction.execute()` | Trivial liveness ping (bypasses the gate, `LoginInterceptor.java:138`) |
| `/resendMfaToken.do` | `LoginAction.resendMfaToken()` | MFA token resend |
| `/logOut.do` | `LogOutAction.execute()` | Logout |
| `/ssoAuth.do` | `SSOAction.authenticate()` | **Inbound SAML consumer** — P1 acting as SP for an upstream IdP |
| `/loginAs.do`, `/impersonate.do` | impersonation actions | Build `LoggedUserImpostor` on top of an existing session |

`/saml/idp/*` (the P1-as-IdP surface used by the Keycloak demo) is on the POC branch, not master.

### 1.2 Cold login (no prior `HttpSession`)

A user lands on `/login.do` with no cookie, POSTs `username + password + firmCd`. The trace:

1. **Form lands at `LoginAction.loginReact()`** (`LoginAction.java:260`), which clears stale session state and delegates to `execute()` at `:94`.
2. **`Login.confirmLogin()`** (`Login.java:120`) calls `AuthenticationManager.getSole().getAuthenticatedUser(ldapUID, password, firmCd)`. The actor-system handler `AuthenticationTrait.when(Authenticate.class, …)` (`:54`) ultimately runs `NEntityDAO.getUserByAuthentication(...)` (`:6325`), which verifies the SHA-1 hash via `SHAPassword.check(...)` (`SHAPassword.java:38`).
3. **Session stash** (`LoginAction.loginUser`, `:341-393`):
   ```
   LOGGED_USER              = User              (raw entity)
   LOGGED_ADVISER           = new LoggedUser(user)
   LOGGED_USER_LOGIN_KEY    = LoginActivity UUID
   LOGGED_USER_CRM_URL      = ...
   WHO_IS_LOGGED_IN_KEY     = ...
   CONVERSATION_MANAGER_KEY = new ConversationManager()
   ```
   `LOGGED_USER_JTO_KEY` is explicitly **removed** so the next call to `getLoggedUserJTO()` rebuilds it.
4. **`LoggedUser` constructor** (`LoggedUser.java:47`) eagerly loads: `EntityProperty` map, `LoggedUserSpecialPermissions`, advisor flag (`user.isEmployee()`), `enableBalanceSheet` (firm check + a `PolicyRuleManager.canLoggedUserExecuteBalanceSheet(...)` call for advisors), authorised custodians, and the three tax-permission levels. **It does NOT load a role list or a permission map** — every authority check on `LoggedUser` is a wrapper that delegates back to `PolicyRuleManager` (`LoggedUser.java:117`).
5. **First call to `/loginReact.do`** triggers `ReactBasicResponceAction.getLoggedUserJTO()` (`:88`) which memoises the JTO on the session and runs the heavy build (`LoggedUserJTO` ctor at `:165`).
6. **The flat permission map** (`LoggedUserJTO.java:224`) is the only place the role graph is materialised:
   ```java
   this.permissions = PolicyRuleManager.getSole().loadCreateExecutePermissions(loggedUserID);
   ```
   The actor handler (`PolicyRuleManagerTrait.java:435`) issues **one** HQL with two `JOIN FETCH`es:
   ```java
   select distinct er from EntityRole er
     join fetch er.role r
     join fetch r.objectTypePermissions
     where er.entityId = :entityId
   ```
   Result is collected into `Map<String,Boolean>` with keys `"<objectTypeCd>_<permissionCd>"` (e.g. `"55_2"`).
7. **Response to the React SPA** = serialised `LoggedUserJTO` containing: the user, the firm, the whitelabel, custodians, business-intelligence metadata, `gwAdminFlag`, `isPowerAdvisor`, `isPowerAdmin`, `canModifyDashboard`, `enableBalanceSheet`, the typeCd, and the flat `permissions` map. The map also carries six **named** overlay keys (`PowerAdmin`, `PowerAdvisor`, `ModifyDashboard`, `ReportsAccess`, `BetaAccess`, `StrategiesTabAccess`) from `com.geowealth.web.portal.PermissionType`.

**DB-call shape end-to-end**: roughly 10–15 sequential round-trips, **no** N+1 loop in the hot path. The single `JOIN FETCH` does the permission load. `getOpenAccountApplicationAuthorisedCustodians(...)` runs twice (once in `LoggedUser`, once in `LoggedUserJTO.getCustodians(loggedUser)` at `:537`) — easy duplicate, but not catastrophic.

### 1.3 Warm session (existing cookie)

The Struts2 gate is `LoginInterceptor.intercept(...)` (`LoginInterceptor.java:51`). For JSON actions the predicate is `getLoggedUser() != null && getLoggedUserID() != null` (`BasicJsonResponseAction.java:275`). When that holds, the request flows; when it doesn't, the action's `getSessionExpiredMapping()` decides what the user sees (login redirect for HTML, JSON error for SPA traffic). Tomcat session timeout is set in `WebContent/WEB-INF/web.xml:149` → **`<session-timeout>420</session-timeout>` (7 hours of idle)**. There is no silent re-auth on timeout.

**Critical fact: `LoggedUser` is built once at login and never refreshed during a session.** No timer, no Nth-request rebuild, no signal-driven invalidation. The only post-login mutations:

- `LoginAction.loginUser:354` removes `LOGGED_USER_JTO_KEY` → forces a JTO rebuild on next fetch (still reuses the same `LoggedUser` underneath).
- `LogOutAction.execute()` (`:54`) clears the whole session map on real logout. Impersonation-stop (`:62-72`) swaps `LOGGED_USER`/`LOGGED_ADVISER` back to the real user and removes the JTO.
- Out-of-band: `GeowealthSessionListener.invalidateSessionByLoginKey(userID)` (`:73`) nukes **all** sessions for a user when called by `WebServerTrait` after an admin permission change (`agent/web/WebServerTrait.java:31`). This bounces the user back through the front door rather than refreshing in place.

**Implication: permission changes mid-session are invisible until either (a) the JTO is rebuilt and the SPA reloads the shell, or (b) `invalidateSessionByLoginKey` is called.** Backends that need to honour a revocation immediately have to call the latter.

**Every fine-grained check re-queries `policy_rule_tbl`.** `BasicAction.canLoggedUserTradeAccount` (`:640`), `canLoggedUserViewAccount` (`:726`), `canLoggedUserAccessBackOffice` (`:748`) etc. all delegate to `PolicyRuleManager`, which issues a fresh actor message + HQL on each call. The flat `permissions` map in the JTO is **FE rendering hint only** — the backend authority layer never reads it. This is the main source of authorisation DB traffic in steady state.

### 1.4 Other LoggedUser-building entry points on master

| Entry | What it does | Session integrity |
|---|---|---|
| `LoginAction.loginUser` | Full eight-key session stash (see 1.2) | Complete |
| `SSOAction.authenticate()` (`:29`) — inbound SAML SP for `/ssoAuth.do` | `putToSession(LOGGED_ADVISER, new LoggedUser(user))` and nothing else | **Half-set: `LOGGED_USER` (raw User) is never written.** `BasicAction.getUser()` returns `null` for a session that came in this way; `GeowealthSessionListener.getUserIdFromSession:101` reads `LOGGED_USER` and will not see the user; logout activity is unbindable because `LOGGED_USER_LOGIN_KEY` was never stashed. |
| `LogOutAction.execute:69` (impersonation-stop) | Rebuilds `new LoggedUser(realUser)` after swapping back | Limited to the two LU keys + JTO clear |

Outbound SAML actions (FireLight `SSOFirelightAction.java:82`, 55IP, iCapital) all **consume** the existing P1 session's `LoggedUser`; they don't construct one.

OIDC silent re-auth, the OIDC callback (`oidc-callback.do`), and the back-channel logout endpoint cited in the Keycloak-demo `CLAUDE.md` **do not exist on master** — only on the POC branch.

### 1.5 What `LoggedUser` actually contains

(`LoggedUser.java:27-67`)

| Field | Loaded | Notes |
|---|---|---|
| `user` | passed-in | raw `User` |
| `entityProperties` | eager | `Map<EntityPropertyType,String>` via `EntityPropertyManager.loadByEntityId` |
| `specialPermissions` | eager | `LoggedUserSpecialPermissions` runs its own loads in its ctor |
| `advisor` | computed | `user.isEmployee()`, `final` |
| `enableBalanceSheet` | eager | firm flag + 1 policy check |
| `hasCustomSaaProducts` | hardcoded `true` | `@Deprecated(forRemoval = true)`, still serialised |
| `authorisedCustodians` | eager | per-firm custodian list |
| `tax{Equivalent,Budget,LossHarvesting}PermissionLevel` | from user | enum |

**There is no permission collection in `LoggedUser`.** There's no `hasPermission(ObjectType, Action)` method either — every check is a thin delegate to `PolicyRuleManager.getSole().canLoggedUserXxx(userID)`. `isGWAdmin()` (`:251`) is a pure field read of `user.getUserData().isGwAdminFlag()`.

The closest thing to a "permission set" is the `Map<String,Boolean> permissions` on `LoggedUserJTO`, but that's a wire DTO — **never read back by the Java authority layer**.

### 1.6 JSON/REST surfaces that expose authorisation state

**There is exactly one canonical endpoint**: `/loginReact.do` → `ReactIndexAction.login()` → `LoggedUserJTO`. That single fat JSON bundle contains:

- the user, the firm, the whitelabel
- `gwAdminFlag`, `isPowerAdvisor`, `isPowerAdmin`, `canModifyDashboard`, `enableBalanceSheet`, `canExecuteResourceCenter`, `canAccessModelCenter`, `canAccessReactPortal`, `canExecuteFiftyFiveIP`, ...
- the flat `permissions` map (numeric `"55_2"` keys + named `PermissionType` overlays)

The JTO is also embedded into many per-screen action responses. There is **no separate `/permissions` endpoint**, and no domain-specific REST surface (no billing-/trading-flavoured endpoint) that mirrors what the keycloak-demo BFFs are doing.

### 1.7 Data model

```
ENTITY_ROLE_TBL  (entityRoleCd PK, entityId UUID, roleCd FK → ROLE_TBL)
  └── ROLE_TBL  (roleCd PK, name, description, defaultFlag, firmCd FK → FIRM_TBL)
        └── ROLE_PERMISSION_TBL  (roleCd, objectTypePermissionCd)         -- m2m
              └── OBJECTTYPE_PERMISSION_TBL  (cd PK, objectTypeCd, permissionCd)

ENTITY_ROLE_ACCESS_SET_TBL  (entityRoleCd, accessSetCd)   -- object-scoped grants

POLICY_RULE_TBL  (entityId, objectType, permission, objectId/objectCd, firmCd)
  -- precomputed projection: "user U can do action P on object O"
```

`Role` is firm-scoped (`Role.hbm.xml:21-23` — `FIRM_CD` FK). `EntityRole` is the user↔role link. `ObjectTypePermission` is the `(ObjectType, Permission)` tuple referenced by roles.

`POLICY_RULE_TBL` is the **materialised** view. Mutating roles or AccessSets requires `PolicyRuleManagerTrait.refreshPolicyRulesForEntity(...)` (`:153-171`) or `RebuildFirmDefaultAccessSetAndRefreshPolicyRules` (`:173-188`) to regenerate the rows. There are ~20 mutation sites that call `refreshPolicyRulesForEntity` after a role/CRM change (e.g. `CrmManagerTrait.java:1543, 1765, 1881, 2095, 3565, 3725, 3819`; `AuthorizationManagerTrait.java:342`). Coverage is patchy and easy to miss — `NfAccountTblDAO.java:1572` has a commented-out call.

`Permission` (`Permission.java:13-17`) = the action enum: `VIEW=1 / MODIFY=2 / CREATE=3 / DELETE=4 / EXECUTE=5`. Drives authority decisions.

`PermissionType` (`PermissionType.java`) = a UI category enum (`PowerAdmin`, `PowerAdvisor`, `ModifyDashboard`, `ReportsAccess`, `BetaAccess`, `StrategiesTabAccess`). **Used only as additional keys in the JTO's `permissions` map for FE rendering.** Backend never reads it.

`gwAdminFlag` is **not honored inside `PolicyRuleManager`** — zero hits. Callers apply it manually at the call site as `loggedUser.isGwAdminFlag() || PolicyRuleManager.getSole().canLoggedUserXxx(...)` (see `LoggedUserJTO.java:228-229`, `ReportCenterManagerTrait.java:279/691/734/777/820/863/906/949/981`). A new check that forgets the `gwAdmin ||` prefix silently locks out GW admins.

### 1.7a Role catalogue — what dropdowns the admin UI gets when creating a user

`getManageUsersDropdownsByFirm.do` (`struts-platformOne.xml:1654`, action `ManageUserAction.getManageUsersDropdownsByFirm()` at `:39`) is the canonical "list every role assignable inside firm X" endpoint. It's the dropdown source on the admin user-create / user-edit screen. The full body fits in one paragraph:

```java
public String getManageUsersDropdownsByFirm() {
    return withAccessAndFirmCheck(() -> {                                  // gate: gwAdmin for that firm
        List<RoleWrapper> availableRoles =
            AuthorizationManager.getSole().loadRolesByFirm(getFirmCd());   // 1 actor msg → 1 HQL
        Map<Integer, NameAndDefaultFlagOfRole> rolesMap = availableRoles.stream()
            .collect(Collectors.toMap(RoleWrapper::getRoleCd,
                w -> new NameAndDefaultFlagOfRole(w.getName(), w.isDefault())));
        List<WhitelabelCodeAndName> whitelabel =
            UserManager.getSole().getWhitelabelLight(getFirmCd());
        Map<String,String> whitelabelsMap = whitelabel.stream()
            .collect(Collectors.toMap(WhitelabelCodeAndName::whitelabelCode,
                                       WhitelabelCodeAndName::whitelabelName));
        ManageUserJTO j = new ManageUserJTO();
        j.setMetaData(new ManageUserMetaJTO(rolesMap, whitelabelsMap));
        return success(j);
    });
}
```

**The gate.** `withAccessAndFirmCheck` (`ManageUserAction.java:127-137`):
- `firmCd` parameter required, must be ≥ 1, else `fail("Missing or invalid firmCd")`.
- Caller must satisfy `isLoggedUserGwAdminFromFirm(firmCd)` — **firm-scoped gwAdmin only**, no normal advisor / no global gwAdmin without firm scope. Else throws `AccessDeniedException`.

**The role lookup.** `AuthorizationManager.loadRolesByFirm` (`:49`) sends a `LoadRolesByFirm` actor message. The handler (`AuthorizationManagerTrait.java:127-148`) opens a Hibernate session and calls `AuthorizationHibernateDAO.loadRolesByFirm(firmCd)` (`:132`):

```java
public List<Role> loadRolesByFirm(Integer firmCd) {
    Query q = getSession().createQuery(
        "select distinct x from Role x " +
        " left join fetch x.entityRoles" +
        " where x.firm.firmCd = :firmCd");
    q.setParameter("firmCd", firmCd);
    HibernateSessionFactory.distinctNoPassThrough(q);
    return q.list();
}
```

Each `Role` is then wrapped in `RoleWrapper(role, isMandatory=false)` (hard-coded false) and returned to the action. The `left join fetch x.entityRoles` pulls every user-assignment for every role in the firm — wasteful for a "list catalogue" call, but that's an existing optimisation gap, not a design constraint.

**The wire shape.** `ManageUserMetaJTO` (`:1-13` of `ManageUserMetaJTO.java`):

```json
{
  "metaData": {
    "roles": {
      "127": { "name": "BillingPowerUser", "defaultFlag": false },
      "129": { "name": "AdvisorL2",        "defaultFlag": true  },
      "204": { "name": "ReadOnly",         "defaultFlag": false }
    },
    "customWhitelabelCodes": { "default": "Default", "wl-2": "Acme" }
  }
}
```

`NameAndDefaultFlagOfRole` (a `record`) carries only `(name, defaultFlag)` per role. **No permissions, no description, no audit info.** Permission detail is reachable via separate fetch (`Role.getObjectTypePermissions()`) but is not part of this payload.

**Five critical facts for any external design**:

1. **Role names are firm-defined strings, not a fixed enum.** A firm onboarded with custom role names (`BillingPowerUser`, `OpsAnalyst`, `JuniorAdvisor`) sees those strings here. There is no global canonical vocabulary at the `ROLE_TBL` level — `Role.name` is just a `varchar`.
2. **Each firm has its own role catalogue.** Firm 1's "Admin" and firm 2's "Admin" are two distinct `roleCd`s with different `ObjectTypePermission` sets. Same name, different authority.
3. **`defaultFlag` marks the roles auto-assigned to a new user in the firm.** This is the closest thing to a "baseline" role in the per-firm catalogue.
4. **There is no endpoint that returns the catalogue's `ObjectTypePermission` graph.** The admin UI sees only role *names*. To know what permissions a role grants, you'd need either a custom endpoint or to hit the DB directly.
5. **`isMandatory` is wire-set to `false` everywhere.** The flag exists in `RoleWrapper` but `loadRolesByFirm` always passes `false`. It's only set to `true` by `createEmptyRole` (`:154`) — a different code path. Treat it as "always false" from this endpoint.

### 1.7b Role ↔ permission internals — what's behind the back-office matrix

`bo/manageRolePermissions.do` (`struts-bo.xml:312`, action `ManageRolesAction.manageRolePermissions()` at `:132`) is the back-office screen that lets a firm admin tick checkboxes in an `ObjectType × Permission` matrix to define what a single role can do. Unlike §1.7a it's a **Struts2-Tiles screen, not JSON** — the action method just validates the gate and selects `.bo.manageRolePermission` tile; the JSP renders by calling getters on the action instance during the request lifecycle.

**Gates.** Class-level `canExecuteAction()` (`:298`) = `loggedUser != null && loggedUser.canLoggedUserAccessBackOffice()` (the standard back-office filter). Method-level: `roleCd != -1` AND `PolicyRuleManager.canUserViewSingleRole(loggedUserID, roleCd)` (`:136`). The sibling `updateRolePermissions()` mutation tightens further to `canLoggedUserModifyRole(loggedUserID, roleCd)` (`:147`).

**How the matrix is rendered.** The JSP iterates `getObjectTypes() × getPermissions()` and asks two questions per cell:

```java
// columns
public List<Permission> getPermissions() {
    if (permList == null) permList = AuthorizationManager.getSole().loadSortedPermissions();
    return permList;  // 5 rows from PERMISSION_TBL: VIEW=1, MODIFY=2, CREATE=3, DELETE=4, EXECUTE=5
}

// rows
public List<ObjectType> getObjectTypes() {
    if (objtList == null) objtList = AuthorizationManager.getSole().loadSortedObjectTypes();  // 78 rows
    if (getLoggedUserFirmCd() != 1) {
        // Should filter GLOBAL_ADMINISTRATION here for non-firm-1 admins.
        // The check is COMMENTED OUT at :206-208 — every firm admin currently sees it. Existing leak.
        List<ObjectType> tempObjectTypes = new ArrayList<>();
        for (ObjectType ot : objtList) {
//          if (ot.getObjectTypeCd() != ObjectType.GLOBAL_ADMINISTRATION) {
                tempObjectTypes.add(ot);
//          }
        }
        objtList = tempObjectTypes;
    }
    return objtList;
}

// per-cell: is (otCd, pCd) a legal combination?
public boolean checkForPossibleObjectTypePermission(String otpCd, String pCd) {
    if (keysForObjectTypes == null)
        keysForObjectTypes = AuthorizationManager.getSole().loadKeysFromObjectTypePermissions();
    return keysForObjectTypes.containsKey(otpCd + "_" + pCd);
}

// per-cell: is the box checked for THIS role?
public boolean roleHasThisObjectTypePermission(String otpCd, String pCd) {
    if (keysForRole == null)
        keysForRole = AuthorizationManager.getSole().loadObjectTypePermissionKeysForRole(roleCd);
    return keysForRole.contains(otpCd + "_" + pCd);
}
```

**The connection model.** `Role.permissions` is a `Set<ObjectTypePermission>` Hibernate relation. `loadObjectTypePermissionKesForRole` (`AuthorizationHibernateDAO.java:471`) just walks it:

```java
Role role = (Role) getSession().load(Role.class, roleCd);
for (ObjectTypePermission otp : role.getObjectTypePermissions())
    result.add(otp.getObjectType().getObjectTypeCd() + "_" + otp.getPermission().getPermissionCd());
```

`loadKeysOfObjectTypePermissions` (`:454`) returns the **legal-combo catalogue** — every row in `OBJECTTYPE_PERMISSION_TBL` keyed by `"otCd_pCd"`. This matters: **not every (78 ObjectType × 5 Permission) = 390 combination is valid.** Only the rows pre-registered in `OBJECTTYPE_PERMISSION_TBL` are possible role permissions. The actual count is smaller (the data wasn't queryable here, but the matrix renders disabled cells for impossible combos).

**Mutation.** `updateRolePermissions()` (`:146`) is the form-submit handler:

```java
if (!PolicyRuleManager.canLoggedUserModifyRole(loggedUserID, roleCd)) throw ...AccessDenied;
Set<UUID> entitiesToUpdate = PolicyRuleManager.getSole().getSetOfEntitiesInRole(roleCd);
AuthorizationManager.getSole().setNewObjectTypePermissionsToRole(roleCd, Arrays.asList(newPermissions), loggedUserID);
ArrayList<UUID> newUsersUUIDs = new ArrayList<>();
for (String s : getNewUsersInRole()) { newUsersUUIDs.add(new UUID(s)); entitiesToUpdate.add(new UUID(s)); }
AuthorizationManager.getSole().updateEntityRolesForRole(roleCd, newUsersUUIDs, loggedUserID, entitiesToUpdate, getLoggedUserFirmCd());
```

`setNewObjectTypePermissionsToRole` is documented at `AuthorizationManager.java:209` as **"completely removes old set and sets new"** — there's no diff/patch semantic; it's wholesale replacement of `Role.objectTypePermissions`. The wire payload is a list of `objectTypePermissionCd` (PKs from `OBJECTTYPE_PERMISSION_TBL`), not `(otCd, pCd)` tuples — the FE has already resolved each ticked cell to its `objectTypePermissionCd` via `getObjectTypePermissionCdFromKey(otpCd, pCd)` (`:234`).

**Permission propagation is asynchronous via a Task.** `updateEntityRolesForRole` builds a `UpdateEntityRolesForRole` actor message; the handler at `AuthorizationManagerTrait.java:437-471` enqueues a task of type `MANAGE_ROLES_TASK_TYPE` via `taskExecutor.executeTask(...)`. The task is responsible for calling `refreshPolicyRulesForEntity(entityId, firmCd)` for every affected user (the `entitiesToUpdate` set passed in). **There is a window between "form submitted" and "POLICY_RULE_TBL regenerated" during which the affected users' authority is stale.** P1 itself absorbs this because its own permission checks query `policy_rule_tbl` directly and the next request after the task completes sees the new state.

**Three takeaways the rest of this document needs to reflect:**

1. **The `permissions` map in `LoggedUserJTO` (§1.2) and any future `/api/p1-authz/me` response is bounded by the `OBJECTTYPE_PERMISSION_TBL` legal-combo count** — not 78×5=390, smaller (precise number depends on the data, but it's the row count of that table).
2. **`"<objectTypeCd>_<permissionCd>"` is the **only** wire key convention** — used by `LoggedUserJTO.permissions`, by the back-office matrix, by `setNewObjectTypePermissionsToRole`'s reverse-resolution, by everything. The `/api/p1-authz/me` design rightly reuses it (§2.2); the BFFs can therefore use a stable mapping `OT.INVOICE.cd() + "_" + Permission.MODIFY` without a per-firm translation.
3. **Permission mutations are eventually-consistent.** The task-based refresh means the BFF cache TTL (§2.3) doesn't have to fight for the strongest consistency — P1 itself can't promise sub-task-latency consistency. The BFF cache just needs to be shorter than typical task duration; 60s is comfortably above.

### 1.8 Things worth knowing before designing anything on top

- **SHA-1 (`SHAPassword.java:38`) is the only password verifier.** Empty default salt. No bcrypt/PBKDF2/argon. Security review material.
- **`SSOAction` sets only `LOGGED_ADVISER`.** Any code reading `BasicAction.getUser()` on an SSO-logged session gets `null` (1.4 above).
- **Two parallel session keys** for "current user" (`LOGGED_USER` + `LOGGED_ADVISER`) that drift in and out of sync. The well-disciplined create path sets both via `LoginAction.loginUser`.
- **`LoggedUserJTO` makes ~10 separate `policy_rule_tbl` queries** (`canLoggedUserExecuteResourceCenter`, `canAccessLaserApp`, `canAccessStrategiesTab`, `canLoggedUserExecuteReportingCenterV2`, …) even though the just-loaded `permissions` map could satisfy most of them. Low-hanging optimisation.
- **`PolicyRuleManager.loadPolicyRules:89` has a `1111111111111111111L` ms timeout** (~35 million years). If the actor system stalls, every authorisation check hangs forever.
- **`PolicyRuleManagerTrait.refreshFirm` (`:489-510`) is a permanent booby-trap.** It opens with `if (3==3) throw new RuntimeException("UNSAFE");`. Yet `main()` at `:512` calls it directly. Wire it in by accident → instant production breakage.
- **`derivePocRoles`-style coarse role projection does NOT exist on master.** The closest analogue is the flat `permissions` map. There is **no "role" field** on `LoggedUserJTO`; downstream consumers infer coarse identity from `adviser`, `gwAdminFlag`, `isPowerAdmin`, `isPowerAdvisor`, and the `typeCd` (`LoggedUserJTO:50`).
- **Firm scoping is single-firm by construction.** `User.firmCd` is one `int`, and `Role.firm` is one FK. Switching firms = log in as a different `entity_tbl` row. `LoggedUserJTO.firms[]` is a list but the ctor always adds exactly one entry.

### 1.9 How many "main" roles exist — and how master's other SAML SPs handle the question

§1.7a established that `ROLE_TBL.name` is a firm-defined `varchar` with no global enum. But the catalogue is **not** entirely unbounded: two roles are first-class in the domain model, constructed for every firm and reachable through typed getters, while everything else is dynamic.

**The two firm-universal roles** (`Firm.java:58-59`):

```java
private Role adminsRole       = new Role(this, "Admins",        "Firm Administrators",                 false);
private Role allEmployeesRole = new Role(this, "All Employees", "All employees of the firm has this role", true);  // isDefault=true
```

These are the **only** `new Role(name, ...)` sites in production code (every other role is created data-driven, by `roleCd`/name lookup — `AuthorizationHibernateDAO.java:70` builds a blank `new Role()` to populate from a row). They are the only roles with typed accessors (`Firm.getAdminsRole()` / `getAllEmployeesRole()`), and firm/user provisioning wires permissions and `EntityRole` links to **exactly these two** (`UserHibernateDAO.java:248-281`): every employee is joined to `All Employees`, firm admins additionally to `Admins`, and both get an `AccessSetMember` against the default AccessSet. `All Employees` is also the hardcoded default role for new users (`CreateEmployeesTool.java:208,210` → `setDefaultRoleCd(firm.getAllEmployeesRole().getRoleCd())`), which is exactly the "All Employees (Mandatory)" + "Default Role" the admin user-edit screen shows.

Above the firm-scoped model sits one more cross-firm anchor: **`gwAdminFlag`** — a boolean on the user, not a `Role`, not honored inside `PolicyRuleManager` (§1.7/§1.8). It is the GW-internal super-admin override.

So the count that matters for a coarse, cross-firm vocabulary is small and stable:

| Tier | Identity | Guaranteed for every firm? | Source |
|---|---|---|---|
| Firm baseline | **All Employees** (`isDefault`) | Yes — every employee | `Firm.java:59`, `UserHibernateDAO.java:270` |
| Firm admin | **Admins** | Yes — model-level | `Firm.java:58`, `UserHibernateDAO.java:260` |
| Global super | **`gwAdminFlag`** | Cross-firm boolean, outside `ROLE_TBL` | `LoggedUserJTO:228-229` |
| Everything else | `BillingPowerUser`, `OpsAnalyst`, … | No — firm-defined, variable count & names | `ROLE_TBL` rows (§1.7a) |

**→ Two firm-universal roles plus one global flag are the only role-like facts that mean the same thing across all firms.** That is the empirical ceiling on a fixed coarse vocabulary: the shipped `client`/`advisor`/`admin` triad is precisely this anchor set (All Employees → `client`/`advisor` split by `isAdvisor()`; Admins → `admin`; `gwAdminFlag` → the open `gw-superadmin` question in §2.7). Per-domain roles (`billing-admin`, `trading-trader`) are demo refinements layered on top, not P1 universals.

**The triad is additive, not a hierarchy — the three anchors come from independent axes.** Verified on master, `client`/`advisor`/`admin` do **not** nest (no `admin ⊃ advisor ⊃ client`); each derives from a different dimension:

| Coarse role | P1 source | Axis | Evidence |
|---|---|---|---|
| `client` | the baseline given to everyone | universal — the **only** real containment (every advisor/admin also holds `client`) | n/a — POC `derivePocRoles` adds it unconditionally |
| `advisor` | `LoggedUser.advisor = user.isEmployee()` | **identity type** (employee vs end-client), fixed at construction | `LoggedUser.java:49,247-248` |
| `admin` | a `(BACK_OFFICE, EXECUTE)` row in `policy_rule_tbl` | **computed permission**, data-driven | `LoggedUser.java:117-118` → `PolicyRuleManager.java:544-550` |

Because `advisor` is an identity flag and `admin` is a materialised permission, nothing in code enforces `admin ⟹ advisor`: they are two independent checks. In practice back-office EXECUTE is granted through the firm `Admins` role (employees), so admins are *usually* advisors — but that is **data convention, not a model-level hierarchy.** This is why the realm roles are deliberately **flat (non-composite)** and gating is **OR-of-set** at the BFF (`@Secured({"trading-trader","trading-viewer","advisor","admin"})`, `BillingController`/`TradingController`): the "who covers whom" decision lives at the call site, exactly like the `gwAdminFlag || canX()` convention in P1 (§1.8), not in a composite-role tree that would impose a total order the P1 model does not have.

**How master's other SAML SPs answer the same question** (master has no Keycloak/`derivePocRoles` integration — that is POC-branch only, §1.8 — so these three are the only precedents):

| SP | Role strategy on master | Evidence |
|---|---|---|
| **FireLight** | Hardcoded constant — `USER_ROLE = "Agent"` for every assertion (alongside `USER_RIGHTS="Full"`, `ORGANIZATION_ID="CRO"`) | `SsoSAMLHelper.java:188-189` |
| **55IP** | **No role attribute at all** — the statement carries only identity + account/strategy data (`GeoWealthUserId`, `Strategy`, `AccountNumber`, `Custodian`, …) | `FiftyFiveIpAttributeStatementBuilder.java:12-24` |
| **iCapital** | Has a `role` SAML attribute, but the value is `""` with `//TODO: figure out where roles come from` (same for `team`) | `ICapitalAttributeStatementBuilder.java:18`, `ICapitalSamlAttributesHelper.java:37-38` |

Two things this settles:

1. **No existing SP projects P1's firm-defined roles.** They send a constant, nothing, or a stubbed-empty field. There is no precedent for forwarding the per-firm `ROLE_TBL` catalogue over SAML — which is exactly why a 1:1 projection is the wrong default and a small fixed vocabulary is the right one.
2. **The per-firm-config precedent the proposal leans on is already live in iCapital.** `ICapitalSamlAttributesHelper` resolves the per-firm SSO `firm_id` through `CustomFieldHelper.getCustomFieldString(firmCd, …)` (`:60-61`) — the same `CustomFieldHelper`/`FirmSSOConfig` shape `sso-role-mapping.md` recommends for the data-driven `derivePocRoles` replacement. The translation layer is not greenfield; it follows a pattern P1 SSO code already uses for firm-scoped lookups.

---

## Part 2 — Proposal: how external domains should obtain roles & permissions

The Keycloak-demo BFFs (`billing`, `trading`, future N) face the exact tension Part 1 surfaces, plus a new constraint surfaced by §1.7a:

- **The token must stay thin.** Fine permissions are 79 ObjectTypes × 5 actions = ~395 keys per firm, with per-firm variability — too big and too churny for a JWT.
- **The authority lives in P1.** `policy_rule_tbl` is the source of truth; duplicating it anywhere else means cache invalidation hell.
- **The check has to be fast.** Per-request actor messages from a BFF to a Java monolith are not viable for hot paths.
- **Role names are firm-dynamic strings, not a fixed enum** (§1.7a). Firm 1 might have `BillingPowerUser`; firm 2 might have `BillingOps`; firm 3 might have neither. A `@Secured("BillingPowerUser")` annotation in the BFF would silently lock out firms 2 and 3 even when their roles are semantically equivalent. **The BFF cannot gate on P1 role names directly.**

The current `sso-role-mapping.md` design already solved the first half (coarse capability roles + `firmCd` in the token). Below is the proposed shape for the second half — **how a domain BFF reads fine permissions when the coarse role isn't enough**, with the firm-dynamic-role constraint front and centre.

### 2.1 Two-tier authorisation with a P1-side translation layer

```
P1 (per-firm role catalogue — firm-dynamic strings)
  ROLE_TBL (firmCd, name, ObjectTypePermissions[])
  Firm 1: "BillingPowerUser", "AdvisorL2", "ReadOnly"
  Firm 2: "BillingOps", "Senior Advisor", "Viewer"
  Firm 3: ...
       │
       │  translateToCapabilities(firmCd, P1-role) → set of canonical capabilities
       │  (the "data-driven derivePocRoles replacement" from sso-role-mapping.md §C-D —
       │   per-firm DB table modelled on FirmSSOConfig)
       ▼
SAML AttributeStatement (canonical capabilities only)
  roles=["advisor", "billing-admin"]   ← fixed vocabulary, every firm uses the same names
  firmCd="1"
       ▼
Keycloak (fixed realm role catalogue, never grows per firm)
  saml-role-idp-mapper × N → realm roles
       ▼
JWT
  roles: ["advisor", "billing-admin"]    Tier 1 — coarse, in the token
  firmCd: 1
       ▼
Domain BFF
  @Secured("billing-admin")              Tier 1 — coarse gate at controller
  if (!authz.has(OT.INVOICE, MODIFY))    Tier 2 — fine, fetched from P1, cached ≤60s
      throw 403;
```

The vocabulary in the JWT is the **canonical capability set** (`client/advisor/admin/billing-admin/billing-viewer/trading-trader/trading-viewer/...`), the same one already in `sso-role-mapping.md` Phase 1. P1's firm-specific role names (`BillingPowerUser`, `AdvisorL2`) **never leave P1** as themselves — they go through the translation layer first.

**Tier 1** is the gate that says "is this user even *allowed near* this domain." `@Secured({"billing-admin","billing-viewer","admin"})` keys off the canonical capabilities, not P1 role names. The BFF stays decoupled from per-firm role naming.

**Tier 2** is for operations where `billing-admin` isn't fine enough — e.g. "can this user modify *invoices*, but not *plans*?", "can this advisor see account `ACCT-42` belonging to firm B?". The BFF asks `GET /api/p1-authz/me`, caches the answer briefly, and consults it inline. **Permission keys (`"55_2"` — `objectTypeCd_permissionCd`) ARE stable across firms** (`ObjectType` is a global enum) — only role NAMES are firm-dynamic. So tier 2 can use a fixed vocabulary even while tier 1's underlying P1 roles vary.

This is what makes the two-tier split work: the firm-dynamic layer is collapsed into canonical capabilities on the P1 side (tier 1), and the stable-keyed permission map is fetched on demand for fine checks (tier 2). The BFF never has to know that firm 1 calls it `BillingPowerUser` and firm 2 calls it `BillingOps`.

### 2.2 The authorisation endpoint

**Add one endpoint to P1, modelled on `/loginReact.do` but stripped down**: `GET /api/p1-authz/me`, accepting a Bearer JWT issued by Keycloak for any of the domain clients. Validation:

- Signature against Keycloak's JWKS (already wired for the BFFs themselves; same path).
- `sub` claim → P1 user UUID (the federated-identity mapping P1 already stores).
- `firmCd` claim → cross-check against the looked-up user's `firmCd`; reject on mismatch.

Response shape (deliberately a subset of `LoggedUserJTO`, plus the P1 role names for transparency):

```json
{
  "sub": "…",
  "firmCd": 1,
  "gwAdminFlag": false,
  "capabilities": {
    "isPowerAdvisor": false,
    "isPowerAdmin": false,
    "canAccessModelCenter": true,
    "canExecuteFiftyFiveIP": false
  },
  "p1Roles": [
    { "roleCd": 127, "name": "BillingPowerUser" },
    { "roleCd": 129, "name": "AdvisorL2" }
  ],
  "permissions": {
    "55_2": true,
    "12_5": true
  },
  "issuedAt": 1779700000,
  "expiresAt": 1779700060
}
```

The `permissions` keys reuse P1's existing `"<objectTypeCd>_<permissionCd>"` convention (§1.7b — the **only** wire-key shape in P1 for fine permissions; bounded by `OBJECTTYPE_PERMISSION_TBL` legal-combo rows, ObjectType is a global enum). Keys are **stable across firms** — even though the firm-specific *role* that grants a key is dynamic (§1.7a), the *key itself* (`"55_2"`) is identical for every firm. Nothing new has to be invented and the same fetch can serve every domain. A BFF can hard-code the key it needs (`ObjectType.INVOICE.cd() + "_" + Permission.MODIFY`) without ever touching firm-specific naming.

The `p1Roles` array carries the user's **firm-specific role names** (the same strings `getManageUsersDropdownsByFirm` returns to the admin UI). The BFF should treat these as **diagnostic-only** — useful for logging, support tickets, and admin debug screens, **never for gating**. Gating goes through `capabilities` (boolean caps, the `LoggedUserJTO` style) or `permissions` (`objectTypeCd_permissionCd` map). This is the firm-dynamic-role constraint made explicit: P1 role names are visible to the BFF for transparency, but routing authorisation decisions through them would re-introduce per-firm coupling that the canonical-capabilities translation was designed to eliminate.

**Why one endpoint, not per-domain**: the fine permissions are P1-owned. Re-projecting them per domain would duplicate authority into N places. One endpoint, N consumers.

### 2.3 BFF caching

Each domain BFF holds a small `Caffeine`/`Map` cache keyed by `(sub, firmCd)`:

- **TTL ≤ 60s.** Bounded by how stale a fine permission is allowed to be. Note from §1.7b that **P1 itself is eventually consistent here**: a role-permission mutation enqueues an async `MANAGE_ROLES_TASK_TYPE` task that refreshes `POLICY_RULE_TBL`. Until the task completes, P1's own warm session also sees stale rules. The 60s BFF cache only needs to be *longer than typical task latency* (seconds), not "strongest consistency" — there is no stronger consistency available upstream to chase. P1's own warm session goes 7h without re-reading roles (§1.3); 60s on the BFF side is a strict improvement.
- **Miss → fetch from `/api/p1-authz/me` with the user's own JWT as bearer.** Don't issue an admin token to P1 — let the user's authority gate the fetch itself.
- **Negative cache on 401/403** for the same TTL, but with a short floor (5s) so a permission grant in P1 propagates quickly when the next request comes in.

The cache lives **inside the BFF process**, not in a shared Redis. Reasons: (a) the BFFs are tiny per-domain singletons; (b) per-process caches mean a BFF redeploy clears the slate naturally; (c) avoids a new infrastructure dependency for the demo.

### 2.4 Revocation signal

P1 already has `GeowealthSessionListener.invalidateSessionByLoginKey(userID)` which nukes a user's HttpSessions. The equivalent for the BFFs is **drop the cached authz row for that user**.

Mechanism: when P1 invalidates a user, it ALSO calls the same Keycloak back-channel-logout endpoint already used in the SSO POC (the `BackChannelLogoutAction` referenced in the demo `CLAUDE.md`). The BFFs subscribe (or, simpler for the demo: poll Keycloak for active sessions) and evict the matching cache entries.

For the demo, **a 60s TTL alone is good enough** — revocation propagates within a minute without any extra wiring. The back-channel hook is a follow-up if/when sub-minute revocation matters.

### 2.5 What the controller code looks like

```java
@Controller("/api")
public class BillingController {

    @Inject P1AuthzClient authz;            // wraps GET /api/p1-authz/me + cache

    @Get("/invoices")
    @Secured({"billing-admin", "billing-viewer", "admin"})       // tier 1
    public Map<String,Object> invoices(Authentication authn) {
        var perms = authz.getFor(authn);                         // tier 2 (cached)
        if (!perms.has(ObjectType.INVOICE, Permission.VIEW)) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN);
        }
        // …per-row filter by firmCd if the underlying data is multi-tenant
        return billingService.invoicesForFirm(perms.firmCd());
    }
}
```

The tier-1 gate is unchanged from what's already on the branch (`sso-role-mapping.md` Phase 1). Tier 2 is a single inject + one if. The `P1AuthzClient` is the only new dependency.

### 2.6 What stays out

| Pattern | Why rejected |
|---|---|
| **Put the full permissions map in the JWT.** | ~395 keys × per-firm variation = fat token that changes shape on every grant. Also re-derives authority in two places. |
| **Project P1 role names directly into realm roles** (e.g. `BillingPowerUser@firmA`, `BillingOps@firmB`). | §1.7a: role names are firm-defined strings, unbounded across firms. Auto-creating realm roles per onboarded firm explodes the realm and creates a control-plane dependency (P1 admin → Keycloak admin API on every role mutation). Auto-deletion is even worse. The whole point of the canonical capability vocabulary is to *collapse* this dimension before SAML emission. |
| **`@Secured("BillingPowerUser")` in BFF controllers.** | Same root cause: locks the BFF to one firm's vocabulary. If firm 2 calls the same capability `BillingOps`, every BFF needs a code change to keep them in sync. Use canonical capabilities at the gate; route firm-specific knowledge through the P1-side translation layer. |
| **Replicate `policy_rule_tbl` into a BFF-local store.** | Authority duplication. P1 already has weak coverage of `refreshPolicyRulesForEntity` (~20 mutation sites, one commented out — `NfAccountTblDAO.java:1572`). The BFF would inherit every stale-projection bug. |
| **Per-domain `/api/p1-authz/{domain}` endpoints.** | Forces P1 to know about external domains. The domain-agnostic `/api/p1-authz/me` lets the BFF decide which `objectTypeCd_permissionCd` keys it cares about. |
| **Skip the cache, fetch on every request.** | P1 is already the bottleneck — each domain doubling P1's request load is hostile. 60s cache hits 99% of requests. |
| **Fetch a Keycloak admin token from the BFF and use it to call P1.** | Loses user-bound authority. P1 would have to re-derive who the request is about. Always send the user's own JWT. |
| **Long-lived (>5min) cache.** | Permission revocation goes invisible. The whole point of P1's `invalidateSessionByLoginKey` discipline is fast revocation; the BFF must not undo it. |
| **Encode `gwAdminFlag` as a realm role.** | The doc already covered this: it's a *call-site* convention in P1, not an engine-level short-circuit. Surfacing it as a single global realm role (e.g. `gw-superadmin`) IS the right move for tier 1, but `gwAdminFlag || hasX()` patterns still need to live in the BFF's authz layer. |
| **Expose `getManageUsersDropdownsByFirm.do` to the BFFs.** | It's gated on `isLoggedUserGwAdminFromFirm` (§1.7a) — only firm-scoped gwAdmin can call it. The BFF runs as the end user, not as an admin. Also semantically wrong: BFFs need the *current user's* permissions, not the firm's catalogue. If a future admin BFF needs the catalogue, add a **separate** endpoint `GET /api/p1-authz/firm/{firmCd}/role-catalogue` and gate it the same way. |

### 2.7 Risks & open questions

- **`/api/p1-authz/me` adds a new P1 surface that has to be hardened.** Rate-limit per user, log every call to `LoginActivity` analogues, audit how it interacts with impersonation (`LoggedUserImpostor`) — does the BFF see the real user or the impostor?
- **The `sub` → P1 user mapping needs to be unambiguous.** Today P1 looks up users by `UUID` (`UserManager.lookupByUserUUID`). The federated-identity mapping in Keycloak stores P1's UUID, so the JWT `sub` carries the right value — but worth a one-shot end-to-end sanity check before relying on it.
- **The `SSOAction` half-set-session bug (1.4) means SP-logged users may not see their permissions even today** via `/loginReact.do` if it ever runs without a complete session. The new endpoint should follow the well-disciplined create path (`LoginAction.loginUser`), not the `SSOAction` shortcut, or it'll inherit the same bug.
- **`PolicyRuleManager`'s effectively-infinite timeout (1.8) becomes a BFF problem.** Wrap the `P1AuthzClient` call in a hard 2s timeout on the BFF side; let it fail closed (403 / "service degraded") rather than hang the request thread.
- **What does the BFF do with the response when the user is not yet in P1?** First-broker-login on the SSO side creates the federated identity in Keycloak before the user exists as a P1 entity. The endpoint should return a sentinel (`{ exists: false }`) and the BFF should treat it as "no permissions" rather than 500.
- **The translation layer (firm-specific P1 role → canonical capability) is the new single point of failure.** It runs on the P1 side, replacing `derivePocRoles`. A bug there means every firm gets the wrong tier-1 capabilities and the BFF will silently 403 (or, worse, silently 200) the wrong users. Mitigations: per-firm row in the translation table is auditable, the translation is pure (no side effects → easy to unit-test), and the `p1Roles` field in `/api/p1-authz/me` (§2.2) gives ops a way to see what P1 thought the user's firm-specific roles were when authorisation feels wrong.
- **Canonical-vocabulary drift across BFFs.** Two BFFs hard-coding `"billing-admin"` is fine; six BFFs hard-coding their own variants of similar strings is how `billing-admin` / `bill-admin` / `billingadmin` slip in. Mitigation: keep the canonical capability list in a single doc (`sso-role-mapping.md`) and reference it from each BFF's controller-level `@Secured` annotations via a shared constants source if/when the count of BFFs makes the duplication real.
- **`getManageUsersDropdownsByFirm` returns `ObjectType.ROLE` for `getObjectTypeCd()` on `RoleWrapper` (§1.7a).** Suggests a parallel authorisation graph where `Role` itself is an `ObjectType` — i.e. there are `ObjectTypePermission`s on the `Role` ObjectType (CREATE/MODIFY/DELETE a role). When `/api/p1-authz/me` exposes the user's permission keys, those role-mutation permissions will be in there too. The BFFs should treat them as P1-internal (no demo domain cares about role CRUD).
- **Async-task staleness window between "permissions changed in P1" and "BFF sees the change" is the sum of three lags:** task-queue latency (§1.7b — `MANAGE_ROLES_TASK_TYPE` enqueue → completion), BFF cache TTL (60s), and Keycloak session lifespan if the user is mid-session. Worst case ≈ 60s + task latency on a cache miss; best case ≈ task latency on a forced eviction (Phase E). For the demo domains this is fine — billing/trading don't have second-level permission churn. Anything that does (an admin BFF revoking access to PII, say) needs a synchronous invalidation hook, not just TTL.
- **The "wholesale replace" semantic of `setNewObjectTypePermissionsToRole` (§1.7b)** means a single misclick in the back-office admin UI can blank an entire role's permission set. The BFF cannot detect this — it just sees "user lost all `billing-*` permissions" and 403s. Worth logging the user's `p1Roles` (§2.2 diagnostic field) on every 403 so support can see whether it was a deliberate revocation or a fat-finger admin edit.
- **The commented-out `GLOBAL_ADMINISTRATION` filter in `ManageRolesAction.java:206-208`** is a real authorisation leak inside P1 — non-firm-1 admins currently see and can tick GLOBAL_ADMINISTRATION cells in the matrix. Not a BFF issue, but worth flagging up the food chain because any permission key minted under that ObjectType could leak through `/api/p1-authz/me` to a BFF that wasn't expecting it.

### 2.8 Suggested phasing

1. **Phase A (Keycloak demo, no P1 work):** keep the current Phase 1 tier-1 gating with the canonical capability vocabulary + the `user → advisor` legacy transition mapper. Demonstrates the SSO + coarse-role pipeline; no fine checks.
2. **Phase B (P1 PR — translation layer):** the data-driven replacement of `derivePocRoles` (`IdpSsoAction.java:390`). New per-firm DB table modelled on `FirmSSOConfig`:
   ```sql
   CREATE TABLE firm_sso_role_translation (
     firmCd INT, p1RoleCd INT, capability VARCHAR(64),  -- e.g. "billing-admin"
     PRIMARY KEY (firmCd, p1RoleCd, capability)
   );
   ```
   Onboarding a firm = inserting rows that map their firm-specific `Role.name`s onto the canonical capability vocabulary. `derivePocRoles` becomes `derivCapabilities(loggedUser) = joinTranslationTable(loggedUser.firmCd, loggedUser.entityRoles)`. The SAML `roles` attribute now emits canonical strings, never firm-specific ones. The `roles-legacy-user-to-advisor-from-saml` Keycloak mapper can be removed once every firm has translation rows.
3. **Phase C (P1 PR — authz endpoint):** add `GET /api/p1-authz/me` (§2.2), validating a Keycloak JWT, returning the existing `LoggedUserJTO`-derived permission map + `capabilities` + `p1Roles` for diagnostics. One endpoint, every BFF consumes it.
4. **Phase D (BFFs):** add `P1AuthzClient` + the 60s in-process cache (§2.3) + one or two tier-2 checks in `BillingController` / `TradingController` to prove the model. Hard 2s upstream timeout, fail-closed on hang (§2.7).
5. **Phase E (revocation):** plug the BFF cache into Keycloak's back-channel logout. Cache TTL can drop further (e.g. 5min) once a real revocation signal exists.
6. **Phase F (admin BFF, future):** if/when a back-office admin domain needs the per-firm role catalogue, add a **separate** `GET /api/p1-authz/firm/{firmCd}/role-catalogue` endpoint mirroring §1.7a's gate (`isLoggedUserGwAdminFromFirm`). Response shape: one entry per role with the firm-specific name, default flag, AND its `ObjectTypePermission` set (the same `"<objectTypeCd>_<permissionCd>"` keys §1.7b emits) — so an admin BFF can render a JSON equivalent of `bo/manageRolePermissions.do`'s matrix without scraping the JSP. Mutation endpoints (`PUT …/role/{roleCd}/permissions`, `PUT …/role/{roleCd}/users`) wrap `setNewObjectTypePermissionsToRole` + `updateEntityRolesForRole` and inherit P1's eventual-consistency model — caller must accept the async-task delay. Do not stretch `/api/p1-authz/me` to serve admin use cases.
