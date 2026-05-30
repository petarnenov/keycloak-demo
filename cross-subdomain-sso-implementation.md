# Cross-subdomain SSO — implementation in this demo

How the architecture described in **`cross-subdomain-sso-keycloak (1).md`**
(person-stable Keycloak subject + per-tenant identity claims, silent re-auth
between subdomains) maps onto `keycloak-demo` and `geowealth` as actually
implemented. Reads like a section-by-section gloss on the source document.

Pairs with [`oidc-primer.md`](oidc-primer.md) (the OIDC layer underneath),
[`sso-role-mapping.md`](sso-role-mapping.md) (the per-firm role projection),
[`p1-auth-flow.md`](p1-auth-flow.md) (the P1-side authorization model), and
[`cross-subdomain-sso-multi-username-analysis.md`](cross-subdomain-sso-multi-username-analysis.md)
(the multi-username case the source document was really about, **now
implemented** — see § "Multi-username support" below).

## TL;DR

| Source document concept | What ships in this repo |
|---|---|
| One realm SSO session at "person" level | `demo-realm` on `auth.geowealth.int:5180`; the `KEYCLOAK_IDENTITY` cookie covers all three subdomains via the realm. |
| Keycloak `sub` = `person_A`, **not** `a1` | `sub` resolves to a Keycloak user whose `FEDERATED_IDENTITY.federated_user_id` is the P1 user UUID (stable per **person**). Echoed as the explicit `personId` claim too. |
| `tenant_identity = a1 / a2` per tenant | `tenant_identity` claim per OIDC client; P1 emits one `tenantIdentity.<slug>` SAML attribute per known tenant; each client maps its own slug. |
| `active_tenant = A` claim | Hardcoded protocol mapper per OIDC client (`billing` / `trading` / `users`). |
| Account linking inside the IdP (Option A, §5) | P1 does the resolution at SAML emission time in `IdpSsoAction.collectAttributes()`. Keycloak never sees a per-tenant username as identity. |
| Silent re-auth via `prompt=none` redirect (§4.2) | `BFF` exposes `/oauth/login/silent` — KC authorize URL with `prompt=none&kc_idp_hint=p1`; SPA tries it on cross-subdomain navigation. Falls back to interactive login on `error=login_required`. |
| Per-domain capability roles | Already shipped — see [`sso-role-mapping.md`](sso-role-mapping.md). Per-domain roles ride on the same SAML `roles` attribute. |

The net effect: log into `billing.geowealth.int` as P1 user `alice`,
navigate to `trading.geowealth.int`, no IdP round-trip is visible, and the
trading dashboard renders with `tenant_identity = alice-trader`,
`active_tenant = trading`, **same `personId` as on billing**.

## Document § 0–2 — the identity model

The source document's most load-bearing constraint:

> SSO сесията живее на ниво **person**, … Tenant identity (`a1`/`a2`/…) и
> ролите стават **claims/атрибути**, резолвнати per `(person, tenant)`.

This demo realizes that constraint as follows:

- **Person-level Keycloak subject.** The SAML `NameID` from P1 is the P1
  user UUID with `nameid-format:persistent` — see
  `geowealth/src/main/java/com/geowealth/saml/idp/IdpSsoAction.java`
  (`collectAttributes()` → `String.valueOf(user.getUserID())`). On
  first-broker-login Keycloak creates one local user keyed off email and
  stores `(p1-uuid)` as the federated-identity link. The UUID — not the
  username — is what travels through the broker link, so the KC user (and
  therefore the OIDC `sub` claim) is stable per P1 person.

- **Tenant identity as a claim, not identity.** What the SPA sees as the
  active "username" is the `tenant_identity` claim, emitted per OIDC
  client. The document's `a1, a2, … an` shape maps to one SAML attribute
  per tenant slot — `tenantIdentity.billing`, `tenantIdentity.trading`,
  `tenantIdentity.users` — populated by P1 and read by each client's own
  `oidc-usermodel-attribute-mapper`.

- **`active_tenant` is derived per client.** Since each subdomain has its
  own OIDC client (`demo-billing-client`, `demo-trading-client`,
  `demo-users-client`), each client adds one hardcoded protocol mapper
  emitting `active_tenant` with its own slug. No need to round-trip the
  tenant to P1 — Keycloak already knows which client started the flow.

## Document § 3 — which model we picked

**Model 1.** Keycloak subject = `person_id`. Confirmed by inspecting the
`USER_FEDERATED_IDENTITY` table on the demo Postgres after a login: each
P1 person produces exactly one row keyed by `federated_user_id = p1-uuid`,
regardless of which subdomain entered the flow first.

Model 2 (subject = tenant username) is explicitly **not** in scope and
would require keeping per-tenant federated identities, which Keycloak
doesn't really support without an SPI on top.

## Document § 4 — architecture

### § 4.1 Identity Brokering: Keycloak → IdP App

Already shipped. `keycloak/realm-export.json#/identityProviders` has the
`p1` SAML IdP, signed AuthnRequests, `nameid-format:persistent`. The
`p1-first-broker-login` flow auto-links by email and silently completes
the broker login.

### § 4.2 Cross-subdomain SSO mechanics

Already shipped in the structural sense — the `KEYCLOAK_IDENTITY` cookie
is set on `auth.geowealth.int` and every subdomain's authorize redirect
goes to that same host. What this change **adds** is the *silent-first*
variant the document calls out:

> За безшевност (без видим flash) → `prompt=none` silent authentication,
> за предпочитане redirect-based (не iframe).

Implementation:

1. The SPA's `AuthProvider` first calls `/auth/me`. If 401, it navigates
   to `/oauth/login/silent` instead of `/oauth/login/keycloak`.
2. `/oauth/login/silent` is a thin BFF endpoint that 302s the browser to
   the Keycloak authorize URL with `prompt=none&kc_idp_hint=p1`.
3. **If a realm SSO session exists** Keycloak immediately issues a code
   and the BFF completes the flow — no IdP round trip, no SAML hop, no UI.
4. **If not** Keycloak responds with `error=login_required` on the
   redirect URI. The BFF's `/oauth/callback` filter detects the error and
   redirects the browser to the interactive `/oauth/login/keycloak`
   (which carries `kc_idp_hint=p1` already via `IdpHintFilter`). One
   extra redirect, no broken UX.

We deliberately do **not** use a hidden iframe (Safari ITP / Chrome
third-party cookie restrictions break that — exactly the warning in § 7
of the source document).

### § 4.3 Per-subdomain tenant identity + roles

We use **Approach A: audience-scoped tokens** (one Keycloak client per
subdomain). The three OIDC clients are already configured; this change
adds the per-client protocol mappers that emit
`active_tenant` + `tenant_identity` + `personId`.

Approach B (Standard Token Exchange / RFC 8693) is **out of scope** for
this demo — none of the BFFs need to act on behalf of another BFF.

## Document § 5 — account linking

**Option A.** P1 resolves `a1 … an → person_A` at SAML emission time. The
KC realm has no SPI extending the broker logic; everything happens in P1.
Concretely:

- The SAML NameID is the P1 UUID (`String.valueOf(user.getUserID())`).
- `tenantIdentity.<slug>` is computed per known tenant slot. For demo
  data the alias defaults to `<username>-<slug>` (e.g. `tim1-trader`,
  `tim1-billing`); a richer mapping would read from a P1 user-attribute
  table, but the shape of the SAML emission is unchanged.
- KC first-broker-login still auto-links by email (so a fresh demo seeds
  to one KC account). Subsequent logins go through the federated-identity
  table directly — no profile prompt.

Option B (Keycloak federated_identity linking from multiple usernames to
one user) is **not used**.

## Document § 6 — resolving tenant identity and roles

The source document keeps `(person, tenant) → (username, roles)` in the
IdP app. This demo follows that, with one practical tweak:

- **Tenant identity** lives in P1, emitted as
  `tenantIdentity.<slug>` SAML attributes — each tenant in its own slot.
- **Roles** stay where they are today: P1's `deriveCapabilities()` emits
  the per-domain capability vocabulary in the SAML `roles` attribute,
  Keycloak's existing `saml-role-idp-mapper` trio assigns realm roles,
  and the BFF's `@Secured` lists gate by them. See
  [`sso-role-mapping.md`](sso-role-mapping.md) Decision 3 for the data
  model.

## Document § 7 — critical pitfalls (mapping)

| Pitfall | How the demo avoids it |
|---|---|
| Modelling `a1…an` as separate KC subjects | NameID = stable P1 UUID; tenant identity rides as a claim. |
| Multiple passwords | The demo's P1 has one credential per person. The realistic per-tenant alias data is decorative. |
| Logout semantics | Already addressed by `AuthController.logout` — RP-initiated KC end-session + P1 IdP-initiated SLO. See `bff-core/.../AuthController.java`. |
| Silent auth + third-party cookies | Redirect-based silent auth, not iframe. See § 4.2 above. |
| Token-exchange audience validation | Out of scope (Approach A). |
| Legacy V1 token exchange | N/A. |

### Logout fan-out — the front-channel trap

Every OIDC client in the realm must have `frontchannelLogout=false`. With it
set to `true`, Keycloak's `AuthenticationManager.backchannelLogoutAllClients`
silently skips the back-channel HTTP POST and just records *"Some clients
have not been logged out…"* in the server log — the BFFs receive nothing,
and a sibling subdomain's `/auth/me` keeps returning 200 until
`TokenRefreshFilter` independently revalidates the KC session 30 seconds
later. This was a latent failure mode in the realm export from before the
multi-subdomain work — the legacy front-channel HTML page is what the
original session-poll flow used. With BFF / Token Handler, back-channel is
the only thing that matters, so we flip the flag.

`scripts/apply-cross-subdomain-sso.sh` handles it on a live realm; the
`realm-export.json` carries the same value for fresh imports. The
[`logout.spec.ts`](../e2e/tests/logout.spec.ts) E2E test confirms that
after the flip, `/auth/me` on sibling subdomains returns 401 within a
couple of seconds of a sign-out call.

## Document § 8 — final shape

Concrete artefacts that ship with this implementation:

1. **P1 side.**
   - `KeycloakUserAttributes` gains `personId` + `tenantIdentities`
     (`Map<String, String>`).
   - `KeycloakAttributeStatementMapper` emits `personId` and
     `tenantIdentity.<slug>` SAML attributes.
   - `IdpSsoAction.collectAttributes()` populates the map from the active
     `LoggedUser` for each known tenant slug.

2. **Keycloak realm.**
   - `saml-user-attribute-idp-mapper` x N for `personId` and each
     `tenantIdentity.<slug>`.
   - Per OIDC client: `oidc-usermodel-attribute-mapper` for `personId`,
     `oidc-usermodel-attribute-mapper` for `tenant_identity` (reads the
     client's own tenant slug), `oidc-hardcoded-claim-mapper` for
     `active_tenant`.
   - A live-patch script at `scripts/apply-cross-subdomain-sso.sh` writes
     these to a running realm via admin API (realm import is
     `IGNORE_EXISTING`).

3. **bff-core.**
   - `KeycloakAuthenticationMapper` and `TokenRefreshFilter` stash the
     three new claims in the session.
   - `AuthController#me` exposes them in `/auth/me`.
   - New `SilentLoginController` adds `GET /oauth/login/silent` which
     302s to the KC authorize URL with `prompt=none&kc_idp_hint=p1`.

4. **Per-domain SPAs.**
   - `AuthProvider.tsx` calls `/oauth/login/silent` on first-load 401
     instead of starting an interactive login directly.
   - Each dashboard surfaces `personId`, `active_tenant`,
     `tenant_identity` in the header so the visual proof of the model
     is one click away.

## Verification walkthrough

The expected end-to-end:

1. Browse to `https://billing.geowealth.int:5184`.
2. `AuthProvider` calls `/auth/me`, gets 401.
3. It hits `/oauth/login/silent` → KC sees no SSO cookie → returns
   `error=login_required` → BFF callback redirects to the interactive
   `/oauth/login/keycloak` → KC brokers to P1 → P1 SAML response →
   KC builds OIDC code → BFF exchanges it → session cookie set →
   billing dashboard renders with `personId`, `tenant_identity =
   tim1-billing`, `active_tenant = billing`.
4. Open `https://trading.geowealth.int:5185` in a new tab.
5. `AuthProvider` calls `/auth/me` → 401 (different BFF session).
6. It hits `/oauth/login/silent` → KC sees the SSO cookie for this
   person → returns a code immediately → BFF exchanges it. **No P1
   round trip, no UI.** Trading dashboard renders with `personId =
   <same as billing>`, `tenant_identity = tim1-trader`,
   `active_tenant = trading`.

Same flow, two visible differences:

- Billing's `tenant_identity` is `tim1`; trading's is `tim5`.
  Same person, different tenant aliases (resolved by `PersonRegistry`).
- The second tab opens with no visible IdP interaction.

## Multi-username support — closing the document's central gap

The source document is fundamentally about the case where a physical
person has separate login identities per tenant (`tim1` for billing,
`tim5` for trading, `tim10` for users). The companion
[`cross-subdomain-sso-multi-username-analysis.md`](cross-subdomain-sso-multi-username-analysis.md)
walks through what would break in a naïve implementation; this section
lists what shipped to close those gaps.

**Gap A — person-stable `personId` (was per-login-user).**
`geowealth/.../PersonRegistry.java` is the demo's
`(user → person)` mapping. `IdpSsoAction.collectAttributes()` now
resolves the SAML `NameID` and the `personId` claim through it:

```java
String personId = PersonRegistry.resolvePersonId(user); // "P-tim" for all of tim1/tim5/tim10
return new KeycloakUserAttributes(
    personId, // NameID — person-stable
    personId, // personId claim
    …
);
```

So Keycloak's broker-link table joins on the person ID; logging in as
`tim5` later finds the same `(p1, "P-tim") → kcU-A` row that `tim1`
created.

**Gap B — `tenantIdentity_<slug>` from the (person, tenant) table, not
the email local part.** `PersonRegistry.resolveTenantAliases(personId,
fallback)` returns the explicit table — `P-tim → {billing:tim1,
trading:tim5, users:tim10}`. A demo person not in the registry falls
back to their own P1 username (so federation never breaks for unseeded
users).

**Gap C — per-tenant `roles` claim.** `PersonRegistry.resolveTenantRoles`
returns one role list per tenant slug. `KeycloakAttributeStatementMapper`
emits each as a multi-valued SAML attribute `roles_billing` /
`roles_trading` / `roles_users`. The realm's
`saml-user-attribute-idp-mapper` instances persist them as KC user
attributes; each OIDC client now carries an
`oidc-usermodel-attribute-mapper` named `roles-tenant-scoped` that
reads only its own slot into the access token's `roles` claim. The
legacy `realm-roles-flat` mapper (which exposed all realm roles to
every client) is deleted by `scripts/apply-cross-subdomain-sso.sh`.

The realm's existing `roles` SAML attribute is kept for backward
compatibility — `IdpSsoAction.collectAttributes()` emits the union of
the per-tenant role lists into it so the 8 existing
`saml-role-idp-mapper` instances continue to assign realm roles
correctly.

**Verified end-to-end.** With this in place, signing in as `tim1` on
billing produces:

| Subdomain | personId | active_tenant | tenant_identity | roles |
|---|---|---|---|---|
| billing | `P-tim` | `billing` | `tim1` | `[client, advisor, billing-admin]` |
| trading | `P-tim` | `trading` | `tim5` | `[client, advisor, trading-trader]` |
| users   | `P-tim` | `users`   | `tim10` | `[client, advisor, users-viewer]` |

Same physical person (`P-tim`), distinct per-tenant identity, distinct
per-tenant role set. Cross-subdomain navigation is silent — no IdP
round-trip beyond the first interactive login. This is the shape § 3
of the source document describes as Model 1.

### One-time data migration

Switching `NameID` from the P1 user UUID to the person-stable
identifier orphans existing federated-identity links in Keycloak.
On a live realm the cleanest recovery is to delete the affected KC
users via admin API — they're recreated automatically on next P1
login through the standard first-broker-login + auto-link-by-email
path. The `scripts/apply-cross-subdomain-sso.sh` patch script is
idempotent and safe to re-run; the user deletion step is intentionally
left manual so it's an explicit choice, not a side effect.
