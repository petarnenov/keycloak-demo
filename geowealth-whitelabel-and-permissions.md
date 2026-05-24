# GeoWealth: how the React page gets its branding and permissions

Analysis of two parallel mechanisms in `~/geowealth/` that together
decide what the user sees and what they can do when their browser
loads the React UI. Written cold from a code walkthrough — file paths
and method names are accurate as of 2026-05-24 against the
`team/petarnenov/keycloak-whitelabel-poc` branch.

Both flows converge on one object — `LoggedUserJTO` — which the React
UI receives once at login and treats as the source of truth in Redux.
Branding lives in its `wlcode` (and the URL detection that derives
that code pre-login); permissions live in its `permissions` map plus
a dozen derived booleans.

## 1. Whitelabel (per-firm branding)

### Server-side firm detection

Every request enters `BasicAction` (the Struts base class). On first
access to firm-related state the action calls
`getUrlWhitelabelInformation()`, which is hostname-driven:

```
HTTP request
  ↓
BasicAction.getUrlWhitelabelInformation()
  ↓ request.getRequestURL().toString()
  ↓ → "https://newadvisoryservices.geowealth.com/..." (or qa2.geowealth.com, cca.geowealth.com, …)
  ↓
AuthorizationManager.identifyFirmByUrl(url)
  ↓ DB lookup on Firm.systemBaseUrl / clientPortalBaseUrl
  ↓
UrlWhitelabelInformation { firmCd, whitelabelKeyword, systemBaseUrl, … }
  ↓
setUpFirmPropertiesInSession()  ← caches result in HttpSession
```

The session keys it caches:

- `FIRM_CODE_SESSION_VAR` — firm's short code (e.g. `cca`,
  `newadvisoryservices`)
- `FIRM_CD_SESSION_VAR` — numeric firm ID
- `FIRM_NAME_SESSION_VAR` — display name
- `FIRM_DTO_SESSION_VAR` — full `FirmDTO`
- `WHITELABEL_KEYWORD_SESSION_VAR` — whitelabel asset folder name
  (often equals the firm code, but a firm can have its assets in a
  separate folder for shared-branding cases)

Fallback when no URL match: `GEOWEALTH_FIRM_CD` (whitelabel folder
`cca`). This is the default GeoWealth theme.

### Exposing to React UI

Two channels carry the firm into the React layer.

**Pre-login** — `ReactJsonIndexAction.indexCommonAsJSON()` (URL
`/react/indexCommonAsJSON.do`) returns a `LoginResponseJTO`-shaped
JSON with:

- `urlWhitelableBase = "whitelabel/" + getFirmKeyword()` — relative
  base for image/font/etc. references
- `firmKeyword` — the same code as a top-level field
- `firmNameCd` — numeric ID

**Post-login** — `LoggedUserJTO` carries `wlcode`:

```java
wlcode = urlWlInfo.getWhitelabelKeyword();  // default: URL-detected
if (loggedUser is client-portal) {
    wlcode = userData.getCpWhitelabelKeyword();  // override for CP users
}
```

The client-portal override matters because a single firm can run two
themed surfaces (advisor portal at `cca.geowealth.com`, client portal
at `clients.cca.geowealth.com`) and each surface gets its own folder.

### React UI consumption

Redux state stores both sources separately:

- `app.urls.firmKeyword` — from indexCommonAsJSON, present before
  login
- `app.loggedUser.data.wlcode` — from LoggedUserJTO, present after
  login

The selector that resolves which one to use:

```js
// _selectors/appSelectors.js
export const getWlcode = (state) =>
    state.getIn(['app', 'loggedUser', 'data', 'wlcode'])
    || state.getIn(['app', 'urls', 'firmKeyword'])
    || '';
```

So priority is `loggedUser.wlcode > urls.firmKeyword > empty`. The
logged-in user's wlcode wins whenever it's loaded, which is how the
client-portal override actually reaches the UI.

`App.js` watches the resolved wlcode and triggers the theme load:

```js
if (this.props.wlcode && this.props.wlcode !== prevProps.wlcode) {
    themeService.makeRequest(this.props.wlcode);
}
```

`themeService.makeRequest(wlcode)` GETs

```
/whitelabel/{wlcode}/{wlcode}_color_theme.json?v={timestamp}
```

The JSON contains CSS variable values, primary/accent colors,
specific overrides, and a logo URL block. On success
`updateWhiteLabel()` writes the CSS variables onto the document and
swaps any references to brand assets. Image references throughout
the UI use literal `/whitelabel/${wlcode}/platform_images/...` paths
so they automatically resolve to the correct folder on the static
file server (Tomcat's webapp or whatever CDN fronts it in prod).

### Edge cases

- **QA hosts** (`qa2.geowealth.com`, `qatrd.geowealth.com`, …)
  inherit a firmKeyword that doesn't correspond to a real
  `/whitelabel/` folder. `isQaDeployWhitelabelFolderCode()` detects
  the pattern and bypasses caching during sessionStorage writes, so
  the logout page can still render the previously-loaded real firm's
  theme instead of defaulting to `cca`.
- **Default fallback**: `themeService.makeRequest` triggers a
  one-shot retry with `cca` if the requested theme file 404s. So a
  misconfigured firm never breaks page rendering — at worst the user
  sees the GeoWealth default branding.
- **First-paint flash**: the order matters — `urls.firmKeyword`
  arrives before `loggedUser.data.wlcode`, so any pre-login URL load
  fires a theme request immediately. After login the wlcode usually
  resolves to the same value (since it's the same firm), so no
  visual swap; only impersonation or client-portal switching
  triggers a second theme fetch.

## 2. Permissions / Roles

### Server-side: a DB-backed boolean map

After a successful login, `LoggedUser` exists in HttpSession. The
permission set isn't on `LoggedUser` directly — it's built into
`LoggedUserJTO` at construction time:

```java
// LoggedUserJTO constructor
this.permissions =
    PolicyRuleManager.getSole().loadCreateExecutePermissions(loggedUserID);
```

`PolicyRuleManager.loadCreateExecutePermissions` sends an Akka
message (`LoadCreateExecutePermissions`) and awaits the response;
the actor that handles it queries the DB and returns a
`Map<String, Boolean>` where keys are `"<ObjectType>_<Permission>"`
strings:

```
PROPOSAL_FEE_RATE_MODIFY              → true
GLOBAL_ADMINISTRATION_EXECUTE         → false
REPORTING_CENTER_V2_EXECUTE           → true
DOCUMENT_VAULT_CRUD_EXECUTE           → true
...
```

The constructor then augments the map with derived flags:

```java
isPowerAdvisor = checkObjectTypePermission(GLOBAL_ADMINISTRATION, EXECUTE)
              || checkObjectTypePermission(INVESTMENT_MANAGEMENT, EXECUTE);
permissions.put(PermissionType.PowerAdvisor.name(), isPowerAdvisor);

enableBalanceSheet = checkObjectTypePermission(BALANCE_SHEET, EXECUTE);
isInsuranceAgent   = userData.isInsuranceAgentFlag();

if (firm.isReportingV2Enabled()) {
    permissions.put(REPORTING_CENTER_V2_EXECUTE, true);
}
permissions.put(REPORTING_CENTER_LEGACY_EXECUTE, false);
permissions.put(ModifyDashboard.name(), canModifyDashboard);
permissions.put(DOCUMENT_VAULT_CRUD_EXECUTE, true); // insurance agent override
// …
```

Firm-level overrides also land here — e.g., if the firm has
`isRequiresEditPermissionProposalFeeRateFlag() == false`, every
user gets `PROPOSAL_FEE_RATE_MODIFY = true` regardless of
individual policy.

The result is a flat boolean map with both raw permissions and
high-level UI flags coexisting in the same dict.

### Bridge to React UI

`LoggedUserJTO` is returned (serialized to JSON) from:

- `/react/login.do` — on successful credential submit
- `/react/isUserLoggedIn.do` — on app boot if a session exists
- `/react/verifyUserSession.do` — periodic alive check

Fields the React layer reads for authorization:

| Field | Type | What it means |
|---|---|---|
| `permissions` | `Map<String, Boolean>` | Primary map, the policy lookup |
| `adviser` | `boolean` | Advisor vs Client (impersonation-aware) |
| `isPowerAdvisor` | `boolean` | Derived, short-circuit for global-admin UI |
| `enableBalanceSheet` | `boolean` | Feature flag derived from permission |
| `isInsuranceAgent` | `boolean` | Drives insurance-specific UI |
| `developer` | `boolean` | Dev mode override (env-driven) |
| `betaAccess` | `boolean` | Beta feature gate |
| `canExecuteResourceCenter` | `boolean` | Resource Center access |

### React UI consumption

Permissions land in Redux as `app.loggedUser.data.permissions`
(Immutable.Map). Two layers wrap the lookup:

**`utils/helpers/permissionsHelper.js`** — boolean inspectors that
take a `loggedUser` and return `boolean`:

```js
export const hasPermission = (loggedUser, permissionType) => {
    const permissions = loggedUser?.get('permissions') || new Map();
    return permissions.get(permissionType) || false;
};

export const hasPermissionPowerAdvisor = (loggedUser) =>
    hasPermission(loggedUser, PermissionType.POWER_ADVISOR);

// dozens of named variants — hasPermissionModifyAccount,
// hasPermissionExecuteLegacyReports, hasPermissionBeneficiariesUiView, …
```

**`_selectors/userSelectors.js`** — Redux selectors that pull the
logged user out of state and delegate to the helper:

```js
export const hasPermissionExecuteReportingCenterV2 = (state) =>
    permissionsHelper.hasPermissionExecuteReportingCenterV2(getLoggedUserData(state));
```

UI components use the selectors via `connect()` or `useSelector()`
and conditionally render:

```jsx
{hasPermissionExecuteReportingCenterV2 && <NavLink to="/reports">Reports</NavLink>}
```

There is **no global route guard** (`<ProtectedRoute>`,
`<RequirePermission>`, etc.). Each menu item, button, and page wires
its own check inline.

### Double-gating: UI checks are hints, server is the gate

The FE permission checks exist only for UX (hide the button, dim the
nav link). The actual security boundary is the server:

- Every Struts action overrides `canExecuteAction()`. `LoginInterceptor`
  runs it before dispatching to the action method.
- DB-backed permissions are re-checked on every modifying request
  via `PolicyRuleManager` from inside actions.
- Akka actors independently validate authorization on cross-process
  state changes.

A user with a tampered Redux state can hide-show whatever UI they
want; they still can't bypass the server check.

## How they intersect

The single object that carries both:

```
LoggedUserJTO {
    wlcode:                  "newadvisoryservices",
    systemBaseUrl:           "https://newadvisoryservices.geowealth.com",
    clientPortalBaseUrl:     "https://clients.newadvisoryservices.geowealth.com",
    permissions: {
        PROPOSAL_FEE_RATE_MODIFY:    true,
        GLOBAL_ADMINISTRATION_EXECUTE: false,
        PowerAdvisor:                false,
        ModifyDashboard:             true,
        ...
    },
    adviser:                 true,
    isPowerAdvisor:          false,
    enableBalanceSheet:      true,
    isInsuranceAgent:        false,
    developer:               false,
    ...
}
```

This is the "init manifest" — React UI receives it once at login,
holds it in Redux as the source of truth, and:

- `App.js` watches `wlcode` → triggers theme load
- Every navigation/UI component reads `permissions` at render time
  via the helper layer

## Impersonation

When an admin impersonates a client (or a portal user impersonates
another):

- `LoggedUser` becomes `LoggedUserImpostor` wrapping the real
  advisor + the impersonated user
- The next `LoggedUserJTO` construction rebuilds from scratch:
  - `wlcode` re-evaluates — typically from
    `userData.getCpWhitelabelKeyword()` for client-portal
    impersonation, switching the theme to the firm the client
    belongs to
  - `permissions` re-load from the impersonated user's policy
  - Derived flags reflect the new identity

React UI receives the new JTO via `/react/isUserLoggedIn.do` and the
wlcode change triggers `themeService.makeRequest()` so the theme
swaps in place. No special impersonation code in the FE — same
manifest plumbing.

## Production caveats worth knowing

- **Firm-by-URL detection in dev**: `localhost` doesn't match any
  firm's `systemBaseUrl`, so dev environments always default to
  `cca` (the GeoWealth default). For testing other whitelabels in
  dev, either hit a hostname configured in `Firm.systemBaseUrl` or
  log in as a user whose firm has a non-default `wlcode`.
- **Theme JSON 404 doesn't break the app** — `themeService` retries
  once with `cca`. Worst case is the user sees the default brand.
  But a missing `cca_color_theme.json` would brick the page.
- **Permissions cache is per-JTO-build** — there is no in-memory
  cache that survives session timeout. Every `/react/login.do`
  triggers a fresh `loadCreateExecutePermissions` Akka round-trip.
  Fast (~10s of ms) but worth knowing for capacity planning at the
  PolicyRuleManager actor.
- **Developer / beta access** are env-driven (server-side
  `DeveloperUtils.isDevelopmentModeEnabled`) and bake into the JTO
  at construction. Toggling them at runtime requires JVM restart;
  the React UI can't override them.
