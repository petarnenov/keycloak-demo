# SSO role mapping — how P1 permissions should become realm roles

How the P1 → Keycloak SAML bridge projects P1's authorization model onto
`demo-realm` roles. The recommendation below is grounded in an analysis of P1's
actual authorization model and its existing SAML integrations.

> **Implementation status (Decision 3 shipped).** The hardcoded `derivePocRoles`
> POC has been replaced by the data-driven translation this report recommends —
> geowealth `team/petarnenov/keycloak-whitelabel-poc`, *SSO Phase 18*:
> `IdpSsoAction.deriveCapabilities` → `SamlManager.deriveSsoCapabilities` →
> the pure `SsoRoleTranslator`, fed by per-firm `FirmSSOConfig.roleCapabilityMappings`.
> Coarse caps are derived from *effective* P1 authority; per-domain caps from the
> firm's authored mapping. The Keycloak realm + BFF side (coarse roles, `firmCd`
> claim, `@Secured` gating) shipped earlier. The section below describes the
> original POC the implementation superseded, for context.

## Original state (the POC, now superseded)

```
P1: derivePocRoles(LoggedUser)  ──►  SAML roles ∈ {client, user, admin}
Keycloak: 3× saml-role-idp-mapper (syncMode=FORCE)  ──►  realm role
BFF: @Secured("isAuthenticated()")  (roles not yet gated)
```

`derivePocRoles` (P1 `com/geowealth/saml/idp/IdpSsoAction.java`) added `client`
to everyone, `user` when `LoggedUser.isAdvisor()`, and `admin` when
`LoggedUser.canLoggedUserAccessBackOffice()`. Three problems it had (all now
addressed by the data-driven translation above):

1. **Logic, not data.** A new role needs a Java change and a P1 redeploy; firm
   onboarding cannot introduce a role without a developer.
2. **Split across two repos.** Half the mapping lives in `derivePocRoles` (P1),
   half in `keycloak/realm-export.json` (the `saml-role-idp-mapper` trio). They
   must stay in lockstep.
3. **Ignores tenancy.** `firmCd` already flows through SAML, but the derived
   roles are firm-blind: `admin` at firm A and `admin` at firm B are
   indistinguishable in the token.

## What P1's model actually looks like

Three findings from analysing the P1 codebase decide the design:

1. **P1 already has explicit, firm-scoped roles.**
   `com/geowealth/model/authorisation/Role.java` —
   `Role { roleCd, Firm firm, name, Set<ObjectTypePermission>, Set<EntityRole> }`.
   Each role belongs to exactly one firm; roles are provisioned per firm with no
   fixed global set. → A 1:1 projection of P1 roles onto realm roles would
   explode the realm and require dynamic role creation. It also rules out
   "Keycloak groups bundling composite roles per firm" — that duplicates a model
   P1 already owns.
   **Two exceptions** are baked into the model and exist for *every* firm:
   `Firm.adminsRole` (`"Admins"`) and `Firm.allEmployeesRole` (`"All Employees"`,
   `isDefault=true`) — the only `new Role(name,…)` sites in production
   (`Firm.java:58-59`), wired up at firm/user provisioning
   (`UserHibernateDAO.java:248-281`). Together with the cross-firm `gwAdminFlag`
   boolean they are the only role-like facts stable across firms, and they are
   exactly what the coarse `client`/`advisor`/`admin` vocabulary anchors onto.
   See `p1-auth-flow.md` §1.9.

2. **One firm per user per session.** `User.firmCd` is a single value
   (`User.java`); `LoggedUserJTO.firms[]` looks like a list but the constructor
   adds exactly one entry. `getWhitelabelFirmsList` is an admin listing, not
   "my firms". → The token never needs multi-firm roles. A single `firmCd`
   claim plus a few coarse roles is sufficient. This is the decisive argument
   against Keycloak groups: their value (bundling different role sets per tenant
   into one token) is moot here.

3. **Permissions are fine-grained and DB-computed, not an enum.**
   79 `ObjectType` values × 5 levels (`VIEW/MODIFY/CREATE/DELETE/EXECUTE`); the
   keys seen in `login.do` like `"55_2"` are `ObjectTypeCd_PermissionCd`,
   resolved from the database at login. The `PermissionType` enum (`PowerAdmin`,
   `StrategiesTabAccess`, …) is a UI classification, not the authority model;
   the real authority runs through `PolicyRuleManager`
   (`LoggedUser.canLoggedUserAccessBackOffice()` delegates to it). → These fine
   permissions do not belong in the token. Hundreds of combinations, variable
   per firm.

### No reusable mapping pattern exists

The other SAML integrations were checked for an established
"P1 entitlement → external role" pattern. There is none:

| SP | Role strategy |
|---|---|
| FireLight | hardcoded `USER_ROLE="Agent"` for every user (`SsoSAMLHelper.java:188-189`) |
| 55IP | sends no roles at all — identity + account/strategy data only (`FiftyFiveIpAttributeStatementBuilder.java:12-24`) |
| iCapital | has a `role` field but value is `""` — `// TODO: figure out where roles come from` (`ICapitalSamlAttributesHelper.java:37`) |
| Keycloak | `derivePocRoles` — conditional; the most advanced, but a self-declared POC (POC branch only; not on master) |

There **is** a precedent for *per-firm configuration in the database*:
`FirmSSO` / `FirmSSOConfig` (per-firm SSO metadata as JSON) and `CustomFieldHelper`
(per-firm, per-entity lookups). The replacement for `derivePocRoles` should
follow that shape rather than invent a greenfield table.

## Recommended approach

> Coarse, capability-oriented **global** realm roles via **value mappers** +
> `firmCd` as a separate claim for tenancy + a **data-driven** translation on
> the P1 side. **Not** groups, **not** a 1:1 projection of P1 roles, **not** fine
> permissions in the token.

```
P1 (owns the authority model: PolicyRuleManager / Role / ObjectTypePermission)
        │  data-driven translation (replaces derivePocRoles):
        │  (firmCd, P1-capability) ──► capability-role name
        │  authored per firm, in the DB — modelled on FirmSSOConfig
        ▼  SAML AttributeStatement:  firmCd=1 ; roles=[client, advisor, admin]
Keycloak (stock image, no SPI)
        │  saml-user-attribute-idp-mapper: firmCd → user attribute
        │  saml-role-idp-mapper × N (FORCE): value → realm role     ← value mappers
        ▼  JWT { firmCd, roles:[...] }
BFF
        │  @Secured(coarse role) for coarse gating
        │  + reads firmCd for tenant scoping
        │  fine permissions: queried from an authoritative source on demand
```

The four decisions and their justification:

1. **Tenancy via the `firmCd` claim, not via roles or groups.** The session is
   single-firm, so encode the firm once, in its own claim, and keep roles
   firm-agnostic. (The current POC drops this: it emits `client/user/admin` with
   no link to `firmCd`, so the same coarse role spans all firms unless the BFF
   reads `firmCd` itself.)

2. **A small global capability vocabulary** (`client`/`advisor`/`admin`, or
   per-domain `billing-admin`/`trading-trader` if billing and trading need
   different gates) → **value mappers suffice and are simpler**. Groups would
   only win if Keycloak had to bundle different role sets per firm — which this
   design deliberately avoids.

3. **The translation lives on the P1 side, data-driven**, replacing
   `derivePocRoles` with a per-firm DB table modelled on `FirmSSOConfig`. The
   knowledge of permissions lives in P1 (`PolicyRuleManager`, the 79
   `ObjectType`s); the translation belongs there, behind an anti-corruption
   layer. A new role becomes a table row plus a one-time value mapper, with no
   P1 redeploy for the mapping itself.

4. **Fine permissions stay out of the token.** They remain in P1; the BFF
   fetches them when it actually needs them. The token stays thin and stable.

5. **Keep `syncMode=FORCE`** (already the case in `realm-export.json`): roles are
   recomputed on every login, so a revocation in P1 takes effect immediately.
   `IMPORT` would let a federated user keep a stale role forever.

## Why not the alternatives

| Alternative | Why it is rejected |
|---|---|
| Groups per firm with composite roles | Duplicates P1's already firm-scoped Role model; pointless under single-firm sessions; adds Keycloak-side state to maintain. |
| 1:1 projection of P1 Role → realm role | Firm-scoped and unbounded → role explosion + dynamic role creation in Keycloak. |
| Whole `permissions` set as claims/roles | Hundreds of values, variable per firm; fat token; breaks on every new feature. |
| Custom Keycloak mapper SPI | CLAUDE.md is explicit: stock image, no SPI (would need a `Dockerfile.keycloak`). Not worth it here. |
| Keep `derivePocRoles` hardcoded | A new role needs a Java change + P1 redeploy; ignores `firmCd`; does not scale with onboarding. |

## Risks and caveats

- **`gwAdminFlag` is a global override boolean**, outside the permission graph.
  Decide deliberately whether it becomes a separate global role
  (`gw-superadmin`); do not fold it into the per-firm table.
- **The BFF must actually read `firmCd`** for tenant scoping. The controllers
  today use only `authentication.getName()`; any fine-grained authz must also
  check the firm, or the capability roles are effectively global.
- **Value mappers scale linearly with the vocabulary.** Keep the vocabulary
  small (capability, not feature). If it ever grows to dozens, re-evaluate
  groups — but not before then.

## Key evidence (file:line)

| Finding | Location |
|---|---|
| POC role derivation | P1 `com/geowealth/saml/idp/IdpSsoAction.java:390` (`derivePocRoles`) |
| POC flagged for replacement | same file, `:386` (`// Production should replace this with a P1 → realm-role table`) |
| Explicit firm-scoped roles | P1 `com/geowealth/model/authorisation/Role.java` (`Firm firm` field) |
| Single firm per user | P1 `com/netfolio/model/user/User.java` (`firmCd`); `LoggedUserJTO.java` (one firm added) |
| Permission codes / object types | P1 `com/geowealth/model/authorisation/Permission.java`, `ObjectType.java` (79 types) |
| Authority engine | P1 `PolicyRuleManager` via `LoggedUser.canLoggedUserAccessBackOffice()` |
| Per-firm DB-config precedent | P1 `com/geowealth/model/organization/FirmSSO.java` (`FirmSSOConfig`), `CustomFieldHelper` |
| SAML attribute mapper (current) | P1 `com/geowealth/saml/idp/KeycloakAttributeStatementMapper.java` |
| Realm-side role mappers | `keycloak/realm-export.json` — `roles-{user,client,admin}-from-saml` (`saml-role-idp-mapper`, `syncMode=FORCE`) |
| BFF role config | `domains/<name>/bff/src/main/resources/application.yml` (`token.roles-name: roles`) |
