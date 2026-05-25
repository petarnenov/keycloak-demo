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

### 1.8 Things worth knowing before designing anything on top

- **SHA-1 (`SHAPassword.java:38`) is the only password verifier.** Empty default salt. No bcrypt/PBKDF2/argon. Security review material.
- **`SSOAction` sets only `LOGGED_ADVISER`.** Any code reading `BasicAction.getUser()` on an SSO-logged session gets `null` (1.4 above).
- **Two parallel session keys** for "current user" (`LOGGED_USER` + `LOGGED_ADVISER`) that drift in and out of sync. The well-disciplined create path sets both via `LoginAction.loginUser`.
- **`LoggedUserJTO` makes ~10 separate `policy_rule_tbl` queries** (`canLoggedUserExecuteResourceCenter`, `canAccessLaserApp`, `canAccessStrategiesTab`, `canLoggedUserExecuteReportingCenterV2`, …) even though the just-loaded `permissions` map could satisfy most of them. Low-hanging optimisation.
- **`PolicyRuleManager.loadPolicyRules:89` has a `1111111111111111111L` ms timeout** (~35 million years). If the actor system stalls, every authorisation check hangs forever.
- **`PolicyRuleManagerTrait.refreshFirm` (`:489-510`) is a permanent booby-trap.** It opens with `if (3==3) throw new RuntimeException("UNSAFE");`. Yet `main()` at `:512` calls it directly. Wire it in by accident → instant production breakage.
- **`derivePocRoles`-style coarse role projection does NOT exist on master.** The closest analogue is the flat `permissions` map. There is **no "role" field** on `LoggedUserJTO`; downstream consumers infer coarse identity from `adviser`, `gwAdminFlag`, `isPowerAdmin`, `isPowerAdvisor`, and the `typeCd` (`LoggedUserJTO:50`).
- **Firm scoping is single-firm by construction.** `User.firmCd` is one `int`, and `Role.firm` is one FK. Switching firms = log in as a different `entity_tbl` row. `LoggedUserJTO.firms[]` is a list but the ctor always adds exactly one entry.

---

## Part 2 — Proposal: how external domains should obtain roles & permissions

The Keycloak-demo BFFs (`billing`, `trading`, future N) face the exact tension Part 1 surfaces:

- **The token must stay thin.** Fine permissions are 79 ObjectTypes × 5 actions = ~395 keys per firm, with per-firm variability — too big and too churny for a JWT.
- **The authority lives in P1.** `policy_rule_tbl` is the source of truth; duplicating it anywhere else means cache invalidation hell.
- **The check has to be fast.** Per-request actor messages from a BFF to a Java monolith are not viable for hot paths.

The current `sso-role-mapping.md` design already solved the first half (coarse capability roles + `firmCd` in the token). Below is the proposed shape for the second half — **how a domain BFF reads fine permissions when the coarse role isn't enough**.

### 2.1 Two-tier authorisation, sharp split

```
Tier 1 — coarse, in the token                  Tier 2 — fine, fetched
─────────────────────────────────────          ─────────────────────────────
JWT carries:                                    BFF fetches on first request:
  firmCd                                         GET /api/p1-authz/me
  roles: [client, advisor, admin,                  → { firmCd, permissions: {…},
          billing-admin, billing-viewer,                gwAdminFlag, capabilities: {…} }
          trading-trader, trading-viewer]
                                                cache per-(sub, firmCd) for ≤ 60s
@Secured("billing-admin") gates the                in-process; refresh on miss
endpoint at all — coarse                        permissionService.has(OT.ACCOUNT, MODIFY)
                                                gates each operation — fine
```

**Tier 1** is the gate that says "is this user even *allowed near* this domain." `@Secured({"billing-admin","billing-viewer","admin"})` is correct as-is; nothing more is needed at the controller-annotation level.

**Tier 2** is for operations where `billing-admin` isn't fine enough — e.g. "can this user modify *invoices*, but not *plans*?", "can this advisor see account `ACCT-42` belonging to firm B?". The BFF asks an authorisation endpoint, caches the answer briefly, and consults it inline.

### 2.2 The authorisation endpoint

**Add one endpoint to P1, modelled on `/loginReact.do` but stripped down**: `GET /api/p1-authz/me`, accepting a Bearer JWT issued by Keycloak for any of the domain clients. Validation:

- Signature against Keycloak's JWKS (already wired for the BFFs themselves; same path).
- `sub` claim → P1 user UUID (the federated-identity mapping P1 already stores).
- `firmCd` claim → cross-check against the looked-up user's `firmCd`; reject on mismatch.

Response shape (deliberately a subset of `LoggedUserJTO`):

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
  "permissions": {
    "55_2": true,
    "12_5": true
  },
  "issuedAt": 1779700000,
  "expiresAt": 1779700060
}
```

The `permissions` keys reuse P1's existing `"<objectTypeCd>_<permissionCd>"` convention so nothing new has to be invented and the same fetch can serve every domain.

**Why one endpoint, not per-domain**: the fine permissions are P1-owned. Re-projecting them per domain would duplicate authority into N places. One endpoint, N consumers.

### 2.3 BFF caching

Each domain BFF holds a small `Caffeine`/`Map` cache keyed by `(sub, firmCd)`:

- **TTL ≤ 60s.** Bounded by how stale a fine permission is allowed to be. P1's own warm session goes 7h without re-reading roles; 60s on the BFF side is a strict improvement.
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
| **Replicate `policy_rule_tbl` into a BFF-local store.** | Authority duplication. P1 already has weak coverage of `refreshPolicyRulesForEntity` (~20 mutation sites, one commented out — `NfAccountTblDAO.java:1572`). The BFF would inherit every stale-projection bug. |
| **Per-domain `/api/p1-authz/{domain}` endpoints.** | Forces P1 to know about external domains. The domain-agnostic `/api/p1-authz/me` lets the BFF decide which `objectTypeCd_permissionCd` keys it cares about. |
| **Skip the cache, fetch on every request.** | P1 is already the bottleneck — each domain doubling P1's request load is hostile. 60s cache hits 99% of requests. |
| **Fetch a Keycloak admin token from the BFF and use it to call P1.** | Loses user-bound authority. P1 would have to re-derive who the request is about. Always send the user's own JWT. |
| **Long-lived (>5min) cache.** | Permission revocation goes invisible. The whole point of P1's `invalidateSessionByLoginKey` discipline is fast revocation; the BFF must not undo it. |
| **Encode `gwAdminFlag` as a realm role.** | The doc already covered this: it's a *call-site* convention in P1, not an engine-level short-circuit. Surfacing it as a single global realm role (e.g. `gw-superadmin`) IS the right move for tier 1, but `gwAdminFlag || hasX()` patterns still need to live in the BFF's authz layer. |

### 2.7 Risks & open questions

- **`/api/p1-authz/me` adds a new P1 surface that has to be hardened.** Rate-limit per user, log every call to `LoginActivity` analogues, audit how it interacts with impersonation (`LoggedUserImpostor`) — does the BFF see the real user or the impostor?
- **The `sub` → P1 user mapping needs to be unambiguous.** Today P1 looks up users by `UUID` (`UserManager.lookupByUserUUID`). The federated-identity mapping in Keycloak stores P1's UUID, so the JWT `sub` carries the right value — but worth a one-shot end-to-end sanity check before relying on it.
- **The `SSOAction` half-set-session bug (1.4) means SP-logged users may not see their permissions even today** via `/loginReact.do` if it ever runs without a complete session. The new endpoint should follow the well-disciplined create path (`LoginAction.loginUser`), not the `SSOAction` shortcut, or it'll inherit the same bug.
- **`PolicyRuleManager`'s effectively-infinite timeout (1.8) becomes a BFF problem.** Wrap the `P1AuthzClient` call in a hard 2s timeout on the BFF side; let it fail closed (403 / "service degraded") rather than hang the request thread.
- **What does the BFF do with the response when the user is not yet in P1?** First-broker-login on the SSO side creates the federated identity in Keycloak before the user exists as a P1 entity. The endpoint should return a sentinel (`{ exists: false }`) and the BFF should treat it as "no permissions" rather than 500.

### 2.8 Suggested phasing

1. **Phase A (Keycloak demo, no P1 work):** keep the current Phase 1 tier-1 gating. No fine-grained checks yet. Demonstrates the SSO + coarse-role pipeline only.
2. **Phase B (P1 PR):** add `/api/p1-authz/me`, wired to validate a Keycloak JWT, returning the existing `LoggedUserJTO` permission map. Single endpoint, no per-domain logic.
3. **Phase C (BFFs):** add the `P1AuthzClient` + the 60s cache + one or two tier-2 checks in `BillingController` / `TradingController` to prove the model.
4. **Phase D:** when P1 ships the data-driven replacement of `derivePocRoles` (the "per-firm DB table modelled on `FirmSSOConfig`" from `sso-role-mapping.md`), the tier-1 roles become firm-meaningful and `billing-admin@firmA ≠ billing-admin@firmB`. Tier 2 is unaffected — it was already firm-scoped via the user's `policy_rule_tbl` rows.
5. **Phase E (revocation):** plug the BFF cache into Keycloak's back-channel logout. Cache TTL can drop to 5min once a real revocation signal exists.
