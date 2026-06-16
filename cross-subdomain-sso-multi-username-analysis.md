# Cross-subdomain SSO — what happens if `tim1`/`tim5`/`tim10` are one physical person

> **Architecture update (2026-06-15).** This is a historical analysis written
> against the earlier model of one OIDC client + one BFF session per subdomain.
> The shipped cross-subdomain flow now runs through **one multi-tenant
> `token-handler`** with **one shared OIDC client `demo-shared-client`** and
> **one host-scoped cookie `GWSESSION`** (sessions in **Redis**); the
> per-domain clients are vestigial and the data BFFs are auth-unaware
> (forward-auth). The `users` domain was removed (2026-05-30). The gap analysis
> below (person-stable `personId`, per-tenant `tenantIdentity_*`/roles) is
> unchanged in substance and still applies; only the per-client / per-BFF
> framing is dated. Authoritative current state: **`CLAUDE.md`** and
> **`login-logout-algorithm.md` §7**. Inline notes flag the dated spots.

Thought-experiment companion to
[`cross-subdomain-sso-implementation.md`](cross-subdomain-sso-implementation.md).
The demo today logs in as a single P1 user (`tim1`) and the SAML
emission slices identity into a person-stable `personId` + per-tenant
`tenantIdentity_*` slots. This document asks the **next** question the
source document was really about (§ 0): what if the same physical
person has *three* P1 user records — `tim1` (billing), `tim5`
(trading), `tim10` (users) — each with its own credential, and we want
to keep cross-subdomain SSO?

The summary up front: the architecture we built is the right shape, but
P1 today has **no person-level concept** — `tim1`, `tim5`, `tim10` are
unrelated `User` rows. Three concrete gaps follow. Each is fixable
without disturbing the BFF, the realm, or the SPA — they're all in P1.

## 1. The two scenarios

P1 today does not let you author a "person owns these usernames"
mapping. The behaviour of the live system therefore splits on whether
the three users happen to share an email:

| Scenario | tim1/tim5/tim10 same email | tim1/tim5/tim10 different emails |
|---|---|---|
| First-broker-login → Keycloak user | **One** KC user (auto-link by email) | **Three** different KC users |
| `KEYCLOAK_IDENTITY` SSO cookie | shared across tim1/tim5/tim10 | per KC user → **no shared SSO** |
| Cross-subdomain SSO | works, but with quirks (see § 2) | **does not work** at all |

Scenario A is the only one where the cross-subdomain story is even
worth tracing — Scenario B fails at the SSO-cookie layer before any of
our claims plumbing matters. Section 4 walks through what would need
to change to make Scenario B work; sections 2–3 dissect Scenario A.

## 2. Scenario A — same email — what the user sees today

Trace: person A opens `billing.geowealth.int`, signs in to P1 as `tim1`,
then opens `trading.geowealth.int`.

### 2.1 First login on billing as `tim1`

1. SPA → `/auth/me` 401 → `/oauth/login/silent` → `/oauth/login/keycloak?silent=1` → KC `prompt=none` → KC has no session → `error=login_required` → `/auth/login-failed` upgrades to interactive → KC `kc_idp_hint=p1` → P1 SAML AuthnRequest.
2. P1's `IdpSsoAction.collectAttributes()` runs on `LoggedUser` = `tim1`:
   - `NameID` = `String.valueOf(tim1.getUserID())` → `"U-1001"`
   - `personId` = `"U-1001"` (same as NameID per current code)
   - `email` = `tim.a@geo.com`
   - `tenantIdentity_billing` = `"tim.a-billing"`
   - `tenantIdentity_trading` = `"tim.a-trading"`
   - `tenantIdentity_users` = `"tim.a-users"`
   - `roles` = `tim1`'s effective capabilities → e.g. `[client, advisor, billing-admin]`
3. KC first-broker-login: no fed-id for `(p1, U-1001)` → look up by email →
   no existing user → create KC user `kcU-A` with email `tim.a@geo.com`
   and fed-id link `(p1, U-1001) → kcU-A`. Realm roles set to the SAML
   roles. User attributes set to `personId=U-1001`, `firmCd=1`,
   `tenantIdentity_billing=tim.a-billing`, …
4. BFF code exchange → `/auth/me` returns `personId=U-1001`,
   `tenant_identity=tim.a-billing`, `active_tenant=billing`,
   `roles=[client, advisor, billing-admin]`.

### 2.2 Person A navigates to trading

5. SPA → `/auth/me` 401 (different BFF session) → `/oauth/login/silent` →
   KC `prompt=none`. **KC SSO cookie alive** → KC issues code for
   `demo-trading-client` immediately — no SAML hop, no P1 round-trip.
   *(Shipped model: the code is now issued for the shared `demo-shared-client`
   driven by the one multi-tenant `token-handler`; the "different BFF session"
   is now the host-scoped `GWSESSION` cookie resolved by the token-handler
   against Redis, not a separate per-domain BFF.)*
6. BFF code exchange. KC builds the token from `kcU-A`'s stored state:
   - `personId` = `U-1001` (user attribute set at first-broker-login)
   - `tenant_identity` from `tenantIdentity_trading` user attribute =
     `"tim.a-trading"`
   - `active_tenant` = `"trading"` (hardcoded per-client mapper)
   - `roles` = whatever realm roles `kcU-A` has → still
     `[client, advisor, billing-admin]`

### 2.3 What's broken

- **`personId` is per-user-record, not per-person.** Currently
  `personId = String.valueOf(user.getUserID())`. If later person A
  logs in as `tim5` first (say in a fresh browser), `personId` becomes
  `tim5`'s UUID, not `tim1`'s. The "stable per person" guarantee §3 of
  the source document requires doesn't hold across login choice.
- **`tenant_identity` reflects the first-broker-login user, not the
  person's per-tenant alias.** I derived the alias from the email
  local part of whoever logged in. So billing sees `tim.a-billing`
  (correct enough, by coincidence) but trading sees `tim.a-trading`
  rather than the document's intended `tim5`-derived value — the
  trading tenant doesn't actually know that person A's trading
  identity is `tim5`.
- **Roles are the union of one user, not the per-(person, tenant)
  set.** Trading subdomain gets `tim1`'s roles, not `tim5`'s. If
  `tim5` is a `trading-trader` and `tim1` is not, trading lets person
  A in as a viewer at best. (`saml-role-idp-mapper.syncMode=FORCE`
  means a later actual `tim5` broker login would *overwrite* the role
  set — but in the silent re-auth path no broker login happens, so we
  stay frozen on `tim1`'s roles.)

### 2.4 What is correct

- **Cross-subdomain SSO mechanics.** The `KEYCLOAK_IDENTITY` cookie is
  scoped to `auth.geowealth.int`, every OIDC client points at the same
  realm, silent re-auth completes without an IdP hop. The mechanical
  glue from § 4.1–4.2 of the source document is in place.
- **`active_tenant`.** Hardcoded per OIDC client → never wrong.
- **The `tenantIdentity_*` *slot* shape.** Each tenant gets its own
  user-attribute slot read by its own client mapper. The
  infrastructure is there; only the *values* P1 populates are
  cosmetic.
- **Audience scoping (Approach A of § 4.3).** One client per
  subdomain, per-client protocol mappers, no token-exchange wiring
  needed for the SSO to work. *(Shipped model: now one shared
  `demo-shared-client` behind the multi-tenant `token-handler`; per-host
  tenant resolution replaces the per-client split, the per-domain clients
  are vestigial.)*

## 3. The three concrete gaps and the data-model changes that close them

All three live in P1 (geowealth). The keycloak-demo side — realm,
bff-core, SPA — is correct already.

### Gap A — `personId` must be person-stable, not user-stable

Today: `IdpSsoAction.collectAttributes()`:
```java
String uuid = String.valueOf(user.getUserID());
return new KeycloakUserAttributes(uuid /*NameID*/, uuid /*personId*/, …);
```

Required: a `Person` entity (or a "primary user ID" pointer on every
`User` row, picked deterministically — the lowest USERID among the
linked rows works as the canonical pointer).

```java
String personUuid = PersonResolver.resolvePersonId(user); // shared across tim1/tim5/tim10
String loginUuid  = String.valueOf(user.getUserID());     // tenant-bound login row
return new KeycloakUserAttributes(personUuid /*NameID*/, personUuid /*personId*/, …);
```

Knock-on effect on Keycloak: with `personUuid` as the NameID, KC's
broker-link table stores `(p1, personUuid) → kcU-A`. **Subsequent
logins as `tim5` or `tim10` find the same fed-id row** — auto-link by
email becomes a fallback, not the primary join. tim5/tim10 logins
land on the same KC user without depending on emails matching.

### Gap B — `tenantIdentity_*` must come from the person → tenant alias table

Today the value is `<email-local-part>-<slug>` — a placeholder that
worked for a single-user demo. With three usernames, the source of
truth has to be an explicit table. The map the source document
sketches:

```
person | tenant   | tenant_username | roles
P-A    | billing  | tim1            | tim1's caps
P-A    | trading  | tim5            | tim5's caps
P-A    | users    | tim10           | tim10's caps
```

Concrete P1 representation options:

1. **A `PersonTenantIdentity` table** keyed `(personId, tenantSlug)`
   pointing at a `User.userID`. `IdpSsoAction` looks each tenant slug
   up at SAML emission time. Cleanest.
2. **A `User.tenantSlug` column** plus `User.personId`. Reverse
   indexable: `WHERE personId = ? AND tenantSlug = ?` returns the
   user row that owns the (person, tenant) cell.
3. **Per-firm config in `FirmSSOConfig`** carrying a JSON map. Fine
   for low-volume demos, ugly at scale.

Whichever path: `collectAttributes()` becomes

```java
Map<String, String> tenantIdentities = new HashMap<>();
for (String slug : KEYCLOAK_DEMO_TENANT_SLUGS) {
    User tenantUser = personIdentityRepo.findByPersonAndTenant(personUuid, slug);
    if (tenantUser != null) tenantIdentities.put(slug, tenantUser.getLoginName());
}
```

No changes on the keycloak-demo side — the SAML attribute names and
the per-client `oidc-usermodel-attribute-mapper`s already read the
right slots.

### Gap C — roles must be per-(person, tenant), not per-login-user

This is the gap with the most architectural weight, because Keycloak's
role-broker model is single-stream.

Today, `IdpSsoAction.deriveCapabilities()` returns a flat
`List<String>` derived from the active `LoggedUser`. Keycloak's
`saml-role-idp-mapper` instances translate role names into realm
roles. Every brokered login (re-)evaluates this with
`syncMode=FORCE`.

Two valid paths:

1. **Union of per-tenant caps on the same SAML emission.** Build the
   role set as
   `tim1.caps ∪ tim5.caps ∪ tim10.caps` and let the namespace
   prefix (`billing-*`, `trading-*`, `users-*`) drive per-domain
   gating in the BFFs (the namespaces are already in use). Simple,
   but it loses the per-tenant boundary — every BFF technically sees
   every per-domain role, and the boundary becomes naming-discipline
   only.

2. **Per-tenant role attribute streams.** Emit `roles_billing`,
   `roles_trading`, `roles_users` as separate multi-valued SAML
   attributes. Per OIDC client, configure
   `saml-role-idp-mapper` instances reading only the matching
   attribute (or use a single attribute-mapper + a
   `oidc-usermodel-realm-role-mapper` with filtering). This is the
   structurally honest version and aligns with the source document
   most cleanly — the trading client mints a token whose `roles`
   claim is `tim5`'s capability set, not `tim1`'s.

Either path requires `IdpSsoAction.deriveCapabilities()` to gain a
per-tenant lookup (the same `(person, tenant)` join as Gap B), and the
realm to gain either a single union mapper (path 1) or three
per-tenant attribute/role mappers (path 2). The BFF
`@Secured` lists are already namespace-scoped, so neither path needs a
BFF change.

## 4. Scenario B — different emails — what's needed to recover SSO

If `tim1`/`tim5`/`tim10` have distinct emails, KC's auto-link-by-email
splits them into three KC users on first encounter — three SSO
sessions, no cross-subdomain story. Two ways out:

- **Fix Gap A.** Once NameID = `personUuid`, the broker link table
  joins by person, not email. Auto-link by email is no longer
  load-bearing. The three logins all converge on the same KC user.
  This is the recommended path and matches the source document's
  Option A from § 5.
- **Custom KC SPI / first-broker-login flow that joins on
  `personId` claim.** Heavier, requires shipping KC with a custom
  JAR, but doesn't need a P1 data-model change. Listed for
  completeness; not recommended for this demo.

## 5. What the demo would look like with the gaps closed

Given the data-model groundwork above:

1. Person A opens `billing.geowealth.int`. Silent fails → interactive
   → P1 login as `tim1`. P1 resolves person A from `tim1`, emits
   `personId=P-A`, `tenantIdentity_billing=tim1`,
   `tenantIdentity_trading=tim5`, `tenantIdentity_users=tim10`,
   `roles_billing=[…]`, `roles_trading=[…]`, `roles_users=[…]`.
2. KC creates `kcU-A` with the per-tenant slots and (path 2)
   per-tenant role attributes. Realm role assignment runs the
   per-tenant mappers → `kcU-A` ends up with the **union** of caps
   *as realm roles*, but each per-client mapper filters its own
   tenant's caps into the token's `roles` claim.
3. Billing SPA shows `personId=P-A`, `active_tenant=billing`,
   `tenant_identity=tim1`, `roles=[…billing caps…]`.
4. Person A opens `trading.geowealth.int`. Silent re-auth →
   `tenant_identity=tim5`, `roles=[…trading caps…]`, **same**
   `personId=P-A`. No P1 round-trip.
5. Open `users.geowealth.int`. Silent re-auth → `tenant_identity=tim10`,
   `roles=[…users caps…]`, **same** `personId=P-A`.
6. Person A signs out. RP-initiated KC logout fans out to all three
   BFFs (already shipped); P1 IdP-init SLO closes the P1 session. End
   state: every tab is signed out everywhere.

## 6.0 P1-side companion fix (landed 2026-05-29)

The `personId`-based lookup that Gap A and Gap B presume on the P1 side is
implemented in `BffUsersAction.resolveActor`:

```java
// 1) personId via PersonRegistry — architecturally correct path.
String personId = claims.optString("personId", null);
if (personId != null && !personId.isBlank()) {
    String primaryUsername = PersonRegistry.resolvePrimaryUsername(personId);
    if (primaryUsername != null) {
        User byPerson = UserManager.getSole().lookupByUsername(primaryUsername);
        if (byPerson != null) return byPerson;
    }
}
// 2) sub == P1 UUID — legacy compat (tim1-style local KC users).
// 3) preferred_username — same.
// 4) email + firmCd — last-resort person-stable identifier.
```

The reverse-lookup helper:

```java
// PersonRegistry.java
public static String resolvePrimaryUsername(String personId) {
    for (Map.Entry<String, String> e : USERNAME_TO_PERSON_ID.entrySet()) {
        if (personId.equals(e.getValue())) return e.getKey();
    }
    return null;
}
```

The two changes combined turn the demo's multi-username chain end-to-end:
SPA loads → `/auth/me` returns the person-stable claims → `/api/users/getUsers`
forwards the bearer to P1 → P1 resolves `P-tim` to `tim1` via the registry →
returns real rows. The 502 remap in users BFF's `P1Client` stays in place
as a defence-in-depth safety net for any future scenario where P1 again
rejects a bearer token.

**Production replacement:** add a `PERSON_ID` column to `ENTITY_TBL`,
populate it via batch from the existing email/login data, and replace
`PersonRegistry.resolvePrimaryUsername` with
`UserManager.lookupByPersonId(personId)`. The token shape and the BFF
resolution chain stay unchanged.

## 6. Scope of work to close the gaps

Roughly:

- **P1 (geowealth):**
  - Decide on the `(person → users)` representation (recommend a
    `Person` entity + `User.personId` FK).
  - Add `PersonResolver.resolvePersonId(User)` and a
    `(personId, tenantSlug) → User` repository accessor.
  - Update `IdpSsoAction.collectAttributes()` to call them; populate
    `tenantIdentity_*` from the table, not the email local-part.
  - Add per-tenant role attributes to `KeycloakUserAttributes` +
    `KeycloakAttributeStatementMapper` and emit them from
    `deriveCapabilities()` (which becomes
    `derivePerTenantCapabilities(person)`).
  - Seed demo data: a `Person` row for "Tim", three `User` rows
    (`tim1`, `tim5`, `tim10`) all pointing at it, with distinct
    per-tenant capability sets.
- **keycloak-demo realm:**
  - Add 3 `saml-user-attribute-idp-mapper`s for
    `roles_billing` / `roles_trading` / `roles_users` (mapping to
    user attributes), and one per-client
    `oidc-usermodel-attribute-mapper` reading the matching attribute
    into the `roles` claim (or three `saml-role-idp-mapper`s with
    realm-role assignment, depending on which path).
  - Re-run `scripts/apply-cross-subdomain-sso.sh` (extended) on the
    live realm.
- **bff-core:** none. The BFFs already read the `roles` claim
  generically; the per-domain `@Secured` lists already gate by
  namespace. *(Shipped model: the per-request authorization decision now
  lives in the multi-tenant `token-handler` `/auth/verify` (coarse + per-host
  Tier-2 gate); the data BFFs are auth-unaware and read identity from
  `X-Auth-*` headers, so the per-domain `@Secured` gate is now exercised in
  the token-handler, not per data BFF. Only the Tier-3 list `refine` still
  runs in the data BFF.)*
- **SPA:** none. The new `personId` / `tenant_identity` /
  `active_tenant` claims are already surfaced in `/auth/me` and
  rendered in the sidebar.

The disproportionate share of the work lives in P1, which matches the
source document's "account linking before Keycloak" recommendation
(§ 5, Option A): when the IdP is the source of truth for identity, the
join belongs there.
