# Cross-domain SSO — verification plan

Companion to [`cross-domain-sso.md`](cross-domain-sso.md). This document is the executable verification plan for the Phase-1 implementation that landed on:

- `~/nodejs/geowealth` — branch `team/petarnenov/cross-domain-sso-linked-identity`
- `~/keycloak-demo` — branch `petarnenov/cross-domain-sso-linked-identity`

Goal: prove that a user logged into one demo domain who clicks the cross-domain link ends up authenticated in the sibling domain **as the bound identity, with that domain's roles, without a login screen**, when an administrative binding exists.

---

## 1. What's shipped

### Phase 1 — silent swap mechanism

| Layer | Change | File |
|---|---|---|
| P1 — identity resolver | `LinkedIdentityResolver` returns target user UUID from the `(source UUID, targetClient)` pair. DB lookup first; env-var fallback for dev / unit-test. | `src/main/java/com/geowealth/saml/idp/LinkedIdentityResolver.java` |
| P1 — IdP action | `IdpSsoAction` accepts `?targetClient=...`, swaps to bound identity, POSTs to KC's client-scoped endpoint. Source identifier is the P1 entity UUID (`User.getID()`). | `src/main/java/com/geowealth/saml/idp/IdpSsoAction.java` |
| P1 — refactor | `collectAttributes` + `deriveCapabilities` read firmCd from the LoggedUser parameter (not the HttpSession), so the swap path uses the target's firm | same file |
| P1 — audit log | `SECURITY_EVENT: SAML_LINKED_IDENTITY_SWAP` with both `source` and `target` UUIDs; `SAML_LINKED_IDENTITY_NO_BINDING` for the 403 fallback | same file |
| P1 — tests | `LinkedIdentityResolverTest` (14 cases — DAO path disabled via `daoDisabledForTests`), `IdpSsoActionTest` (helper-level) | `src/test/java/com/geowealth/saml/idp/` |
| keycloak-demo SPAs | Billing layout shows a "Switch to Trading →" link; trading layout shows a "Switch to Billing →" link. Both build `…/saml/idp/sso.do?targetClient=…&RelayState=…` | `domains/{billing,trading}/web/src/components/Layout.tsx` |

### Phase 2 — persistent storage + admin CRUD

| Layer | Change | File |
|---|---|---|
| P1 — Oracle migration | `LINKED_IDENTITY_TBL` (composite PK `SOURCE_USER_UUID + TARGET_CLIENT`, `TARGET_USER_UUID`, `MFA_REQUIRED`, `ACTIVE`, audit cols) + 2 indices | `db_migrations/MIGRATIONS/V20260528_28999_01__create_linked_identity_tbl.sql` |
| P1 — Hibernate entity | `LinkedIdentity` + `LinkedIdentityPK` (composite key via `@IdClass`) | `src/main/java/com/geowealth/saml/idp/LinkedIdentity{,PK}.java` |
| P1 — Hibernate config | Mapping declared in `hibernate.cfg.xml` | `src/main/resources/hibernate.cfg.xml` |
| P1 — DAO | `LinkedIdentityDAO` with hot-path `findActiveBinding`, `findAnyBinding`, `findActiveBySource`, `findAllOrdered` | `src/main/java/com/geowealth/saml/idp/LinkedIdentityDAO.java` |
| P1 — resolver wiring | DAO consulted first (active rows only); env-var bindings remain as fallback for unit tests | `LinkedIdentityResolver.java` |
| P1 — admin action | `LinkedIdentityAdminAction` — Bearer-auth + `gwAdminFlag` gating; ops: `list / get / upsert / delete` (soft + hard). Validates target user exists; rejects self-binding. Audit-logs every mutation with actor UUID + verb. | `src/main/java/com/geowealth/saml/idp/LinkedIdentityAdminAction.java` |
| P1 — Struts wiring | Endpoint registered at `/saml/idp/linked-identity-admin.do` | `src/main/resources/struts-tiles.xml` |

What this **does not** ship yet (deliberately deferred — see `cross-domain-sso.md` §4 phases):

- Custom `AuthnContextClassRef` URI for the swap path (Phase 4)
- BFF-side `acr`-aware step-up enforcement (Phase 4)
- `physical_person_id` modelling on `USER_TBL` (§7 prerequisite — bindings are keyed by `User.getID()` UUID instead of a physical-person abstraction)
- Admin UI in P1's React app for binding CRUD (Bearer endpoint is the API; the UI is a separate frontend task)

---

## 2. Pre-flight checks

### 2.1 Compile and unit tests (P1)

```bash
cd ~/nodejs/geowealth
./gradlew --no-daemon compileJava
./gradlew --no-daemon test --tests "com.geowealth.saml.idp.LinkedIdentityResolverTest"
./gradlew --no-daemon test --tests "com.geowealth.saml.idp.IdpSsoActionTest"
```

Expected:
- Compile clean (no new warnings from the diff).
- Both test classes green.

### 2.2 Build the keycloak-demo SPAs

The demo's Vite preview is the production-shape FE container. Build first so we catch import / type errors before bringing the stack up:

```bash
cd ~/keycloak-demo
podman compose up -d --build --force-recreate demo-billing demo-trading
```

Expected: both containers come up healthy on ports 5184 / 5185.

### 2.3 Procure two test user UUIDs

The binding env var needs two P1 user UUIDs that represent the same physical person. For the local-dev demo the simplest source is the P1 admin UI / DB.

```bash
# Connect to the local P1 DB (Oracle) and list two candidate users for the
# same physical person. If the model doesn't yet carry a physical_person_id
# column, pick any two test users with overlapping email/last name and document
# them as "this physical person's billing identity" and "trading identity".

# Example (your DB and column names may differ):
#   SELECT user_uuid, ldap_uid, firm_cd, surname
#   FROM netfolio_user_tbl
#   WHERE ldap_uid IN ('tim1', 'tim2')
#   ORDER BY surname;
```

For this verification we use `tim1` as the billing identity (the existing local-dev test login — see `~/.claude/projects/-home-petar-keycloak-demo/memory/p1-test-credentials.md`) and a second account `tim2` (or any second seeded user) as the trading identity. The exact UUIDs come from the DB query.

### 2.4 Provision the bindings

```bash
# In the P1 container's environment (docker-compose / podman-compose / direct JVM):
export P1_LINKED_IDENTITY_BINDINGS="\
  <tim1-uuid>:demo-trading-client:<tim2-uuid>,\
  <tim2-uuid>:demo-billing-client:<tim1-uuid>"
```

Two directional rows — one per cross-domain direction. Without both, "Switch to Trading" works but "Switch back to Billing" hits the 403 fallback.

Restart P1 after setting the env var.

---

## 3. Functional verification — happy path

### 3.1 Billing → Trading

1. **Cold sign-in to billing** as `tim1`.
   - Browser: `https://billing.geowealth.int:5184/`
   - The billing SPA's `AuthProvider` redirects through KC → P1 login.
   - Enter `tim1` / `c0w&ch1k3n`.
   - Land on the billing dashboard.
   - **Assert (UI)**: sidebar shows `username = tim1` and the billing-side roles (typically including `client`; whatever role mix `tim1`'s P1 record yields).
   - **Assert (audit)**: P1 log shows `SECURITY_EVENT: SAML_ISSUED user=<tim1-uuid> ...` (not the swap line — this is a normal cold sign-in).

2. **Click "Switch to Trading →"** in the sidebar.
   - Browser navigates top-level to
     `http://localhost:8888/saml/idp/sso.do?targetClient=demo-trading-client&RelayState=https%3A%2F%2Ftrading.geowealth.int%3A5185%2F`.
   - **Assert (visual)**: no login form appears. A momentary "Continuing sign-in…" auto-submit page may flash; that's the SAML auto-submit form, expected.
   - **Assert (audit, P1)**: log line
     `SECURITY_EVENT: SAML_LINKED_IDENTITY_SWAP source=<tim1-uuid> target=<tim2-uuid> targetClient=demo-trading-client firm=<tim2-firmCd> acs=…/clients/demo-trading-client …`
   - **Assert (audit, KC)**: KC log shows a brokered login completing for client `demo-trading-client`, federated user being the KC user linked to `<tim2-uuid>`.
   - **Assert (target UI)**: the trading SPA loads with `username = tim2` and the trading-side roles (typically including `trading-trader` or whatever the binding target's P1 record yields).

3. **Verify session independence** — go back to the billing tab.
   - **Assert**: `tim1` is still logged in. Reload — sidebar still shows `tim1`, billing roles. The trading login did not interfere.

### 3.2 Trading → Billing (reverse direction)

1. From the trading tab, click "Switch to Billing →".
2. **Assert**: no login screen, billing tab loads with `tim1` (the bound identity).
3. **Assert (audit, P1)**: another `SAML_LINKED_IDENTITY_SWAP` line, this time `source=<tim2-uuid> target=<tim1-uuid> targetClient=demo-billing-client`.

### 3.3 Audit correlation

For each swap, the four expected log entries (`cross-domain-sso.md` §4.5):

| Source | Log line |
|---|---|
| Source BFF | client-side: a `Switch to Trading` click leaves no BFF log; future Phase-5 adds a `cross_app_navigation_initiated` event. |
| P1 IdP | `SAML_LINKED_IDENTITY_SWAP source=… target=… targetClient=…` |
| Keycloak | `LOGIN: identityProvider=p1 client=demo-trading-client …` (KC's standard event log) |
| Target BFF | `auth.AuthController#me` logs the new session being established (existing logging). |

Reconstruction: align by timestamp + browser session — confirms the full chain.

---

## 4. Functional verification — error paths

### 4.1 Missing binding → 403 "no access"

1. Provision only the billing→trading binding; leave the reverse out.
2. From the billing tab, click "Switch to Trading →" — succeeds (as in §3.1).
3. From the trading tab, click "Switch to Billing →".
4. **Assert (UI)**: a plain 403 page with the message
   `No linked identity provisioned for target audience 'demo-billing-client'. Contact your administrator.`
5. **Assert (audit, P1)**:
   `SECURITY_EVENT: SAML_LINKED_IDENTITY_NO_BINDING source=<tim2-uuid> targetClient=demo-billing-client remote=…`

### 4.2 Binding references unknown user

1. Set the binding env var with a deliberately bogus target UUID:
   `P1_LINKED_IDENTITY_BINDINGS="<tim1-uuid>:demo-trading-client:00000000-0000-0000-0000-000000000000"`
2. Click "Switch to Trading →".
3. **Assert (UI)**: 500 with the message `Linked identity binding references an unknown user.`
4. **Assert (audit, P1)**: a `LOG.warn` line `IdpSsoAction: linked-identity binding for source=… targetClient=… points at unknown target=…`.

### 4.3 No P1 session → login resume

1. Open a private browser window. Navigate directly to
   `http://localhost:8888/saml/idp/sso.do?targetClient=demo-trading-client&RelayState=https%3A%2F%2Ftrading.geowealth.int%3A5185%2F`.
2. **Assert**: redirected to the P1 login page (`REDIRECT_MAPPING` stash already includes the original `targetClient`).
3. Enter `tim1` credentials.
4. **Assert**: after login, the SPA opens `/saml/idp/sso.do` with the original `targetClient` + `RelayState`, and the swap completes silently as in §3.1.

### 4.4 Standard cold sign-in still works (regression)

1. Open `https://trading.geowealth.int:5185/` in a private window.
2. The SPA hits `keycloak.login({ idpHint: 'p1' })`, KC sends SP-init SAML AuthnRequest to P1.
3. **Assert**: P1 login form (no targetClient in the query), enter credentials.
4. **Assert (audit)**: `SECURITY_EVENT: SAML_ISSUED ...` (not the swap line).
5. **Assert (UI)**: trading SPA loads as `tim1` — the swap branch is bypassed because no `targetClient` was supplied.

### 4.5 firmCd / roles isolation (the critical SoD test)

This is the test that proves the swap actually changes the federated identity, not just the URL.

1. Pre-condition: `tim1` and `tim2` belong to **different firms** in P1, or at minimum have **different role sets** (one has `billing-admin`, the other has `trading-trader`).
2. Log in to billing as `tim1`. Capture roles + firmCd shown in the sidebar.
3. Click "Switch to Trading →". Land on trading.
4. **Assert**: trading sidebar shows the `tim2`-set, NOT `tim1`-set. Concretely:
   - `auth.username === 'tim2'` (different `sub` claim entirely)
   - `auth.firmCd === <tim2's firmCd>` (≠ `tim1`'s)
   - `auth.roles` reflects `tim2`'s realm roles in KC, NOT `tim1`'s
5. **Assert** at the BFF level — hit `/api/...` on trading and confirm the BFF's `@Secured` gate evaluates against `tim2`'s roles.

If this test fails (trading shows `tim1`'s data), the swap is broken: either the resolver returned wrong data, or `IdpSsoAction` built attributes from the wrong `LoggedUser`. Both are P1-side bugs.

---

## 5. Smoke-only checks (skip the browser)

Useful when iterating on P1 server-side only:

### 5.1 Direct curl against the IdP-init endpoint

With a known-good P1 JSESSIONID cookie (obtained from a real login):

```bash
curl -sv -b "JSESSIONID=<sid>" \
  "http://localhost:8888/saml/idp/sso.do?targetClient=demo-trading-client&RelayState=https%3A%2F%2Ftrading.geowealth.int%3A5185%2F" \
  -o response.html
grep -o 'SAMLResponse[^"]*' response.html | head -c 80
grep -o 'action="[^"]*"' response.html
```

Expected:
- HTTP 200 with `Content-Type: text/html`.
- Response body is the auto-submit form. The `action="…"` value is the **client-scoped** ACS URL `…/broker/p1/endpoint/clients/demo-trading-client` (not the bare `/broker/p1/endpoint`).
- `SAMLResponse` is non-empty base64.

### 5.2 Decode + spot-check the SAMLResponse

```bash
python3 -c "import base64,sys; print(base64.b64decode(sys.argv[1]).decode())" "<SAMLResponse value>" \
  | xmllint --xpath "//*[local-name()='NameID']/text()" -
```

Expected output: the **target** user's UUID (not the source's). This is the single most direct evidence the swap happened.

### 5.3 Missing-binding curl

```bash
curl -sv -b "JSESSIONID=<sid>" \
  "http://localhost:8888/saml/idp/sso.do?targetClient=demo-billing-client" \
  -w "\nHTTP %{http_code}\n"
```

Expected: `HTTP 403` and the "no linked identity provisioned" body.

---

## 5a. Phase 2 — admin CRUD verification

### 5a.1 Acquire a gw-admin Bearer token

The admin endpoint expects the actor's KC access token in `Authorization: Bearer …`. The simplest path on local-dev:

```bash
# Sign in to billing as a gw-admin user in the browser, then read the BFF
# session for the access token (the BFF stashes it in its server-side session;
# easiest grab is via direct keycloak token endpoint with the resource-owner
# password grant against admin-cli for a dev account).

TOKEN=$(curl -sk \
  -d "client_id=admin-cli" \
  -d "grant_type=password" \
  -d "username=<gw-admin-kc-username>" \
  -d "password=<password>" \
  https://auth.geowealth.int:5180/realms/demo-realm/protocol/openid-connect/token \
  | jq -r .access_token)
```

Any KC user linked to a P1 `User` whose `gwAdminFlag = true` works.

### 5a.2 Create a binding

```bash
curl -s -X POST "http://localhost:8888/saml/idp/linked-identity-admin.do?op=upsert" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "sourceUserUuid": "<tim1-uuid>",
    "targetClient":   "demo-trading-client",
    "targetUserUuid": "<tim2-uuid>",
    "mfaRequired":    false,
    "active":         true
  }' | jq .
```

Expected:
- HTTP 200, response body is the created row including `provisionedBy` / `provisionedAt` populated with the actor's UUID + now.
- P1 audit log line `SECURITY_EVENT: LINKED_IDENTITY_ADMIN_CREATE actor=… source=… targetClient=demo-trading-client target=… mfaRequired=false active=true remote=…`.

### 5a.3 List + filter

```bash
# All bindings (admin overview)
curl -s "http://localhost:8888/saml/idp/linked-identity-admin.do?op=list" \
  -H "Authorization: Bearer $TOKEN" | jq '.bindings | length'

# Bindings for one source
curl -s "http://localhost:8888/saml/idp/linked-identity-admin.do?op=list&sourceUserUuid=<tim1-uuid>" \
  -H "Authorization: Bearer $TOKEN" | jq '.bindings[].targetClient'
```

### 5a.4 Update existing row (re-target / toggle MFA / deactivate)

Re-post `upsert` with the same `(sourceUserUuid, targetClient)` key. Response should show the row with new fields and an updated `lastModifiedBy / lastModifiedAt`; the audit line is `LINKED_IDENTITY_ADMIN_UPDATE`.

### 5a.5 Delete (soft)

```bash
curl -s -X POST "http://localhost:8888/saml/idp/linked-identity-admin.do?op=delete" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sourceUserUuid":"<tim1-uuid>","targetClient":"demo-trading-client"}' | jq .
```

Expected:
- HTTP 200, response shows `active: false` plus updated `lastModifiedBy`.
- The next "Switch to Trading →" click falls into the 403 "no linked identity" path (resolver only returns active rows).
- Audit line: `LINKED_IDENTITY_ADMIN_DELETE_SOFT`.

To reactivate: `upsert` the same row with `"active": true`.

### 5a.6 Validation paths

- `op=upsert` with body missing any of the three required UUIDs → 400.
- `op=upsert` with `sourceUserUuid == targetUserUuid` → 400 (self-binding blocked).
- `op=upsert` with a `targetUserUuid` that does not exist in P1 → 400 (validated before persist).
- Any `op` from a token whose `sub` resolves to a non-gw-admin user → 403, audit line `LINKED_IDENTITY_ADMIN_DENIED`.

### 5a.7 End-to-end: DB row controls the silent flow

After provisioning the binding via the admin endpoint (no env-var change), repeat §3.1 (browser flow). The resolver now reads from the DB row. Unsetting the env var (or never setting it) is fine — the DAO path takes precedence.

---

## 5b. Phase 4 — AuthnContextClassRef + BFF step-up verification

### 5b.1 What's wired

| Layer | Change | File |
|---|---|---|
| P1 — SAML builder | `AbstractSamlAuthenticationResponseBuilder.createSamlAuthorizationResponse` gains an optional `authnContextClassRefOverride` parameter; cold callers (FireLight/55IP/iCapital, P1 cold sign-in) keep their wire format. | `src/main/java/com/geowealth/util/opensaml/AbstractSamlAuthenticationResponseBuilder.java` |
| P1 — IdpSsoAction | Swap path emits `urn:geowealth:ac:classes:linked-identity-from-prior-session` as both the SAML `AuthnContextClassRef` AND a SAML attribute `linkedIdentityAcr` (belt-and-suspenders). Audit log includes `acr=...`. | `src/main/java/com/geowealth/saml/idp/IdpSsoAction.java` |
| P1 — attribute mapper | `KeycloakAttributeStatementMapper` emits the `linkedIdentityAcr` SAML attribute when present in `KeycloakUserAttributes`. | `src/main/java/com/geowealth/saml/idp/KeycloakAttributeStatementMapper.java` |
| KC realm | New SAML IdP mapper `linked-identity-acr-from-saml` (FORCE syncMode — clears on cold logins) writes the SAML attribute to KC user attribute `linkedIdentityAcr`. Each OIDC client gets a `linked-identity-acr-claim` protocol mapper that emits the user attribute as the `linked_identity_acr` claim. | `keycloak/realm-export.json` |
| BFFs | `KeycloakAuthenticationMapper` + `TokenRefreshFilter` ferry the `linked_identity_acr` claim into the BFF session attributes. `StepUpGuard.requireFreshAuthOr401(auth)` returns 401 + `WWW-Authenticate: Bearer error="insufficient_user_authentication"` (RFC 9470) when the session is linked-identity. Wired to `/api/invoices` (billing) and `/api/orders` (trading) as the firm-classified-sensitive endpoints. | `domains/{billing,trading}/bff/src/main/java/demo/{billing,trading}/{KeycloakAuthenticationMapper,TokenRefreshFilter,StepUpGuard,*Controller}.java` |
| SPAs | `api.ts` detects the RFC 9470 step-up signal on 401 and throws `StepUpRequiredError`. `InvoicesPage` / `OrdersPage` render an explicit "Re-authenticate" CTA that navigates to `/oauth/login/keycloak?prompt=login` — KC forwards `prompt=login` upstream → SAML AuthnRequest carries `ForceAuthn=true` → P1 re-prompts for credentials → fresh ACR clears the `linkedIdentityAcr` user attribute. | `domains/{billing,trading}/web/src/{api.ts,pages/{Invoices,Orders}Page.tsx}` |

### 5b.2 Step-up happy path (billing → trading → invoices)

1. Sign in to **billing** cold as `tim1`. Open Invoices. **Assert**: page loads; sidebar shows `tim1` + billing roles.
2. Navigate **back to Overview** (so the next click is from billing's home view).
3. Click **"Switch to Trading →"**.
4. **Assert (UI)**: silent landing on trading as `tim2` (no login).
5. Click "Orders" in trading.
6. **Assert (UI)**: instead of the orders table you see the step-up panel: *"This page requires fresh authentication… [Re-authenticate]"*. No order data is loaded.
7. **Assert (curl-equivalent)**: open DevTools → Network → `/api/trading/orders` → 401 with header `WWW-Authenticate: Bearer error="insufficient_user_authentication", error_description="…", acr_values="urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport"`. Body JSON: `{"error":"insufficient_user_authentication","acr_required":"…","acr_current":"urn:geowealth:ac:classes:linked-identity-from-prior-session"}`.
8. **Assert (BFF log)**: ID token claim `linked_identity_acr` is the linked-identity URI on the session.
9. Click **Re-authenticate**.
10. The browser navigates to `/oauth/login/keycloak?prompt=login` → KC → P1 with `ForceAuthn=true` → P1 prompts for credentials.
11. Enter `tim2`'s password (the trading identity).
12. **Assert (UI)**: lands back on trading; Orders now renders the table.
13. **Assert (KC token)**: the new `id_token` no longer has `linked_identity_acr` (FORCE-syncMode IdP mapper cleared the user attribute on the cold federation).

Repeat the symmetric path billing → trading → switch back to billing → Invoices → step-up.

### 5b.3 Cold-flow regression — sensitive endpoint works unprompted

1. Open a private window. Sign in cold to billing as `tim1`.
2. Click Invoices.
3. **Assert**: table renders. No step-up panel.
4. **Assert (Network)**: `/api/billing/invoices` returns 200 with the invoice list. `id_token.linked_identity_acr` is `undefined` / `null`.

### 5b.4 P1 audit line shape

```
SECURITY_EVENT: SAML_LINKED_IDENTITY_SWAP source=<src-uuid> target=<tgt-uuid> targetClient=demo-trading-client firm=<tgt-firmCd> acs=.../broker/p1/endpoint/clients/demo-trading-client acr=urn:geowealth:ac:classes:linked-identity-from-prior-session remote=… relayState=… roles=…
```

The `acr=...` field is the new addition over the Phase 1 line shape and is the audit-side evidence that the AuthnContextClassRef override took effect.

### 5b.5 Unit-test floor

```bash
# P1
./gradlew --no-daemon -q :test --tests "com.geowealth.saml.idp.LinkedIdentityResolverTest" --tests "com.geowealth.saml.idp.IdpSsoActionTest"

# BFFs
./gradlew --no-daemon -q :domains:billing:bff:test --tests "demo.billing.StepUpGuardTest"
./gradlew --no-daemon -q :domains:trading:bff:test --tests "demo.trading.StepUpGuardTest"
```

Phase 4 ships 8 new BFF unit tests (4 billing + 4 trading covering `freshAuth_returnsNull_letsRequestThrough`, `freshAuth_missingAttribute_returnsNull`, `linkedSwap_returns401WithStepUpHeader`, `unknownLinkedAcrUri_alsoBlocks_failClosed`). The P1-side AuthnContextClassRef override is exercised indirectly via the existing `IdpSsoAction` integration test and the `cross-domain-sso-verification.md` §3 manual flow — direct unit testing of the builder requires `IdpKeyStore`, which is integration-test surface.

---

## 5c. Phase 4 MFA branch — `mfa_required` per binding

### 5c.1 What's wired

| Layer | Change | File |
|---|---|---|
| P1 — resolver | New `resolveBinding(source, client)` returns the full `LinkedIdentity` row (including `mfa_required`). The legacy `resolveTargetUserUuid` is preserved as a thin wrapper. Env-var bindings always return `mfa_required = false` (env-var format has no MFA column). | `LinkedIdentityResolver.java` |
| P1 — IdpSsoAction | Reads `mfa_required` from the binding. Per-target session attributes `li.mfaOkAt.<client>` (timestamp) and `li.mfaAwait.<client>` (Boolean) drive the freshness check. Window: 5 minutes. On stale/missing: stash await marker + redirect to login. On resume: consume the await marker + stamp `okAt` + proceed. MFA-satisfied swaps emit `urn:oasis:names:tc:SAML:2.0:ac:classes:MultiFactor` as the AuthnContextClassRef AND omit the `linkedIdentityAcr` SAML attribute, so the BFFs treat the session as fresh-equivalent and the step-up guard passes. | `IdpSsoAction.java` |
| P1 — audit | Audit line gains `acr=<actual-uri> mfaSatisfied=<bool>` so the linked-identity vs. MFA-elevated distinction is visible to log scrapers. New event `SECURITY_EVENT: SAML_LINKED_IDENTITY_MFA_CHALLENGE` on stale/missing path; `SAML_LINKED_IDENTITY_MFA_VALIDATED` on resume. | `IdpSsoAction.java` |
| Tests | 4 new resolver tests covering `resolveBinding` (env-var hit returns full row with `mfaRequired=false`, miss, null/blank args, parity with the UUID wrapper). | `LinkedIdentityResolverTest.java` |

### 5c.2 Happy path — provision an MFA-required binding

```bash
# Promote the existing tim1 → demo-trading-client binding to mfa_required.
curl -s -X POST "http://localhost:8888/saml/idp/linked-identity-admin.do?op=upsert" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "sourceUserUuid": "<tim1-uuid>",
    "targetClient":   "demo-trading-client",
    "targetUserUuid": "<tim2-uuid>",
    "mfaRequired":    true,
    "active":         true
  }' | jq .
```

### 5c.3 First click after promotion → MFA challenge

1. Open a fresh private window. Sign in to billing as `tim1` (cold flow, no MFA required by binding — `tim1`'s own login may or may not require MFA per firm config, that's orthogonal).
2. Click **"Switch to Trading →"**.
3. **Expected (UI)**: instead of silent landing, the browser redirects to P1's login page (`#login` route).
4. **Expected (P1 audit log)**:
   ```
   SECURITY_EVENT: SAML_LINKED_IDENTITY_MFA_CHALLENGE source=<tim1-uuid> targetClient=demo-trading-client remote=…
   SECURITY_EVENT: SAML_LINGIN_REQUIRED remote=… relayState=… hasSAMLRequest=false redirect=http://localhost:8888/#login
   ```
5. Enter `tim1`'s credentials again — firm-configured MFA challenge fires.
6. After login, the React SPA reads `REDIRECT_MAPPING` and resumes navigation to `/saml/idp/sso.do?targetClient=demo-trading-client&RelayState=…`.
7. **Expected (P1 audit log on resume)**:
   ```
   SECURITY_EVENT: SAML_LINKED_IDENTITY_MFA_VALIDATED source=<tim1-uuid> targetClient=demo-trading-client remote=…
   SECURITY_EVENT: SAML_LINKED_IDENTITY_SWAP source=… target=… targetClient=demo-trading-client … acr=urn:oasis:names:tc:SAML:2.0:ac:classes:MultiFactor mfaSatisfied=true …
   ```
8. **Expected (trading UI)**: lands as `tim2` on the trading dashboard.
9. **Expected (sensitive endpoint)**: click "Orders" → table renders without step-up. The session was MFA-elevated; `id_token.linked_identity_acr` is absent. `StepUpGuard.requireFreshAuthOr401(auth)` returns `null`.

### 5c.4 Subsequent navigations within the 5-minute window

10. Click "Sign out" on trading → logged out of trading only (billing session intact).
11. Back on billing → click "Switch to Trading →" again within 5 min.
12. **Expected**: silent swap (no login prompt). The session's `li.mfaOkAt.demo-trading-client` timestamp is still fresh.
13. **Expected (P1 audit log)**: `SAML_LINKED_IDENTITY_SWAP … mfaSatisfied=true …` — same shape as step 7 but no preceding challenge/validated event.

### 5c.5 Stale window — re-challenge

14. Wait > 5 minutes. Click "Switch to Trading →".
15. **Expected**: MFA challenge re-triggers (steps 3–7 repeat). The freshness window is `MFA_FRESHNESS_MILLIS = 5 * 60 * 1000`.

### 5c.6 Switch back to a non-MFA binding while MFA-tracked

16. With `(tim2 → demo-billing-client)` provisioned with `mfaRequired=false` (default):
17. Click "Switch to Billing →" from the trading tab.
18. **Expected**: silent swap (no challenge); session lands as `tim1` on billing with `acr=linked-identity-from-prior-session`. The `tim2` session's `mfaOkAt.demo-trading-client` marker is not consulted.

### 5c.7 Unit-test floor

`LinkedIdentityResolverTest` now has 18 cases (14 from Phase 1 + 4 new for `resolveBinding`). `IdpSsoActionTest` still has 3 (helper-level only — the MFA flow is integration-test surface; the unit-test floor proves the resolver shape).

---

## 5d. Admin UI — React CRUD over `linked-identity-admin.do`

### 5d.1 What's wired

| Layer | Change | File |
|---|---|---|
| Users BFF — proxy client | `LinkedIdentityAdminClient` (`@Singleton`) — thin HTTP client over P1's `/saml/idp/linked-identity-admin.do`, forwarding the user's KC access token. Surfaces P1's 4xx verbatim. | `domains/users/bff/src/main/java/demo/users/LinkedIdentityAdminClient.java` |
| Users BFF — controller | `LinkedIdentityAdminController` (`@Secured("gwAdmin")`, `@Controller("/api/linked-identities")`) — endpoints: `GET /`, `GET /get`, `POST /upsert`, `POST /delete`. Token Handler model: SPA carries no token, BFF pulls access token from session and proxies. | same dir, `LinkedIdentityAdminController.java` |
| Users SPA — Vite proxy | New entry `/api/linked-identities → BFF_URL` (no rewrite). | `domains/users/web/vite.config.ts` |
| Users SPA — API client | `linkedIdentitiesApi` (`list`, `upsert`, `remove`) + types `LinkedIdentityBinding`, `UpsertBindingInput`, `LinkedIdentityListResponse`. | `domains/users/web/src/api.ts` |
| Users SPA — TanStack hooks | `useLinkedIdentities`, `useUpsertLinkedIdentity`, `useDeleteLinkedIdentity`. Invalidate the `linked-identities/list` key on every mutation so the table refreshes. | `domains/users/web/src/queries.ts` |
| Users SPA — page | `LinkedIdentitiesPage` — split view: left = active+inactive bindings table with `Edit` / `Soft-delete` actions; right = upsert form (create OR edit, same form). UUIDs shortened in the table for readability. Hard-delete deliberately not surfaced — admins should use soft-delete; the hard path stays a curl escape hatch for GDPR. | `domains/users/web/src/pages/LinkedIdentitiesPage.tsx` |
| Users SPA — routing | New route `/linked-identities`. Nav link in `Layout` is **only rendered for users with the `gwAdmin` realm role** — pure UX hide, the BFF + P1 still re-check on every call. | `domains/users/web/src/{App,components/Layout}.tsx` |

### 5d.2 Reach the admin page

1. Provision (or confirm) that the test user has `gwAdminFlag = true` in P1. Without it, the BFF returns 403 and the SPA renders the "no access" branch.
2. Open `https://users.geowealth.int:5186/`. Sign in via P1.
3. **Assert**: sidebar shows the new "Linked identities" nav link below "Users & Access". Click it.
4. **Assert (UI)**: empty state ("No bindings provisioned yet.") plus the upsert form on the right.

### 5d.3 Create a binding via the UI

1. In the upsert form on the right:
   - **Source user UUID**: `<tim1-uuid>`
   - **Target OIDC client**: `demo-trading-client` (default)
   - **Target user UUID**: `<tim2-uuid>`
   - **MFA required**: unchecked
   - **Active**: checked
2. Click **Create**.
3. **Assert (UI)**: the row appears in the table on the left. Form clears.
4. **Assert (BFF audit)**: P1 emits `SECURITY_EVENT: LINKED_IDENTITY_ADMIN_CREATE actor=<gw-admin-uuid> source=… targetClient=demo-trading-client target=… mfaRequired=false active=true remote=…`.
5. **Assert (DAO)**: `SELECT * FROM linked_identity_tbl WHERE source_user_uuid='<tim1-uuid>' AND target_client='demo-trading-client'` returns one row with the gw-admin's UUID in `provisioned_by` + `last_modified_by`.

### 5d.4 Edit + soft-delete

1. Click **Edit** on the new row.
2. The form on the right pre-fills with the row's values; the source UUID + target client inputs become read-only (composite PK is immutable).
3. Check **MFA required**, click **Update**.
4. **Assert (UI)**: the row updates in place; `MFA` column now shows `required`.
5. **Assert (BFF audit)**: `LINKED_IDENTITY_ADMIN_UPDATE` line.
6. Click **Soft-delete**, confirm the prompt.
7. **Assert (UI)**: row remains visible but dimmed (`opacity: 0.5`); `Active` column shows `soft-deleted`; `Soft-delete` button becomes disabled. Re-activating is done by clicking **Edit**, ticking **Active**, and saving.
8. **Assert (audit)**: `LINKED_IDENTITY_ADMIN_DELETE_SOFT`.

### 5d.5 Authorization regression

1. Sign out, sign back in as a non-gw-admin user (any tim* without `gwAdminFlag`).
2. **Assert (UI)**: the "Linked identities" nav link is **absent** from the sidebar.
3. Manually navigate to `https://users.geowealth.int:5186/linked-identities`.
4. **Assert (UI)**: the page renders but the API call returns 403 → the page shows "You don't have gw-admin access".
5. **Assert (Network)**: `/api/linked-identities` returns 403 (BFF's `@Secured("gwAdmin")`) BEFORE the request leaves the BFF. P1 logs are untouched.
6. As a hostile-token test, hand-edit the SPA bundle to bypass the nav-hide and call `fetch('/api/linked-identities/upsert', ...)` from DevTools. **Assert**: 403 from the BFF.
7. If you have the ability to forge a JWT with the `gwAdmin` role but a `sub` that maps to a non-gw-admin P1 user, the BFF lets it through but P1 returns 403 (`gwAdmin required`), audit line `LINKED_IDENTITY_ADMIN_DENIED actor=<sub> op=… remote=…`. **Assert**: defence-in-depth holds.

### 5d.6 Build + type-check floor

```bash
cd ~/keycloak-demo/domains/users/web && npx tsc --noEmit                          # SPA types
cd ~/keycloak-demo/domains/users/bff && ./gradlew --no-daemon -q compileJava      # BFF compile
```

Both must pass. (No new unit tests in this phase — the BFF controller is pure delegation to the client; behavior coverage lives in the P1-side admin endpoint tests + this manual flow.)

---

## 5e. Discoverability — pre-flight hide of cross-domain links

### 5e.1 What's wired

| Layer | Change | File |
|---|---|---|
| P1 — discovery endpoint | `LinkedIdentityDiscoveryAction` — Bearer-auth, caller-scoped (no role required). Resolves the token's `sub` to a P1 user, calls `LinkedIdentityDAO.findActiveBySource`, returns `{"sub":"…","targets":["demo-trading-client",…]}`. | `src/main/java/com/geowealth/saml/idp/LinkedIdentityDiscoveryAction.java` |
| P1 — Struts wiring | New action `linked-identity-discovery` in the `samlIdpTiles` package at `/saml/idp/linked-identity-discovery.do`. | `src/main/resources/struts-tiles.xml` |
| Billing + Trading BFF | `P1AuthzClient.linkedTargets(authorizationHeader)` — fail-closed list of target client IDs. Empty on outage / non-2xx (which hides the link rather than rendering a broken one). | `domains/{billing,trading}/bff/src/main/java/demo/{billing,trading}/P1AuthzClient.java` |
| Billing + Trading BFF | `AuthController#me` now returns `linkedTargets: [...]`. One extra P1 call per `/auth/me` (called once at SPA boot — acceptable cost). | `domains/{billing,trading}/bff/src/main/java/demo/{billing,trading}/AuthController.java` |
| Billing + Trading SPA | `AuthProvider` exposes `linkedTargets` on the context. `Layout` renders the "Switch to …" link only when its target client ID is in the list. | `domains/{billing,trading}/web/src/{auth/AuthProvider,components/Layout}.tsx` |

### 5e.2 Happy path — link visible when binding exists

1. Provision a binding `tim1 → demo-trading-client → tim2` via the admin UI (§5d.3) or the curl path (§5a.2).
2. Sign in to billing as `tim1` (fresh window).
3. **Assert (UI)**: sidebar shows the "Switch to Trading →" link.
4. **Assert (Network)**: `/auth/me` response body contains `"linkedTargets": ["demo-trading-client"]`. The BFF made one extra call to `http://<p1>/saml/idp/linked-identity-discovery.do` with `Authorization: Bearer <tim1's KC token>`.

### 5e.3 Negative path — link hidden when no binding

1. Sign out, sign back in as a user who has no provisioned linked identities (or use the admin UI to soft-delete the existing binding first).
2. **Assert (UI)**: the sidebar does **not** render the "Switch to Trading →" link. Just Overview + Invoices.
3. **Assert (Network)**: `/auth/me` returns `"linkedTargets": []`.

### 5e.4 Reverse direction

1. With both directional bindings provisioned (`tim1 ↔ tim2`), sign in to trading as `tim2`.
2. **Assert (UI)**: "Switch to Billing →" is rendered.
3. Soft-delete the `tim2 → demo-billing-client` binding via admin UI.
4. Refresh the trading tab — the link disappears (re-fetch on page reload).

### 5e.5 Fail-closed on P1 outage

1. Stop P1 (`docker compose stop p1` or similar).
2. Refresh the billing tab — the BFF's `/auth/me` still works (it does not depend on P1 for the core user data, only for the `linkedTargets` enrichment), but `linkedTargets` is `[]`.
3. **Assert (UI)**: the "Switch to Trading →" link disappears. This is intentional — clicking a link that would fail mid-flight is worse UX than missing one.
4. Restart P1; refresh; the link reappears.

### 5e.6 Smoke commands

```bash
# P1 compile
cd ~/nodejs/geowealth && ./gradlew --no-daemon -q :compileJava

# Direct curl against the new endpoint (with a valid KC token)
curl -s "http://localhost:8888/saml/idp/linked-identity-discovery.do" \
  -H "Authorization: Bearer $TOKEN" | jq .
# expected: {"sub":"<uuid>","targets":["demo-trading-client",…]}

# BFFs
cd ~/keycloak-demo/domains/billing/bff && ./gradlew --no-daemon -q compileJava
cd ~/keycloak-demo/domains/trading/bff && ./gradlew --no-daemon -q compileJava

# SPAs (type-check; the page-render assertions live in §5e.2–§5e.4 above)
cd ~/keycloak-demo/domains/billing/web && npx tsc --noEmit
cd ~/keycloak-demo/domains/trading/web && npx tsc --noEmit
```

---

## 5f. Logout semantics — per-audience for swap sessions

### 5f.1 The bug being fixed

Before this iteration, the BFF logout flow was a single path that always ended with a redirect to P1's IdP-initiated SLO (`http://localhost:8888/saml/idp/initiate-slo.do`). For a primary (cold-login) session this is correct: P1 invalidates its HttpSession, emits SAML LogoutRequest to KC, KC ends the SSO session and back-channel-logouts every OIDC client in it.

For a swap-origin session the same path was wrong. P1's HttpSession belongs to the **source** identity (§3.5: the swap never mutates the source session). The SAML LogoutRequest P1 emits carries the **source** identity's NameID. KC ends the source's SSO session and back-channel-logouts the source's BFF — destroying the *other* domain's session as a side effect of logging out of *this* one. That defeats the SoD model the linked-identity design exists to enforce.

### 5f.2 What's wired

| Layer | Change | File |
|---|---|---|
| P1 — attribute carrier | `KeycloakUserAttributes` + `KeycloakAttributeStatementMapper` gain `linkedIdentitySource`. | `src/main/java/com/geowealth/saml/idp/{KeycloakUserAttributes,KeycloakAttributeStatementMapper}.java` |
| P1 — IdP action | `IdpSsoAction` emits `linkedIdentitySource = <source-uuid>` on **every** swap (MFA-elevated or not). It is orthogonal to step-up: tells the BFF "this session came in via a swap", regardless of authentication strength. | `IdpSsoAction.java` |
| KC realm | New SAML IdP mapper `linked-identity-source-from-saml` (FORCE syncMode) → KC user attribute. Each OIDC client gets a `linked-identity-source-claim` protocol mapper → OIDC claim `linked_identity_source`. | `keycloak/realm-export.json` |
| BFFs | `KeycloakAuthenticationMapper` + `TokenRefreshFilter` plumb the claim into session attribute `linkedIdentitySource`. `AuthController#logout` branches: when the attribute is non-empty, skip the P1 SLO redirect and 303 to `/?logged_out=swap` instead. Primary sessions keep the existing SLO cascade. | `domains/{billing,trading}/bff/src/main/java/demo/{billing,trading}/{KeycloakAuthenticationMapper,TokenRefreshFilter,AuthController}.java` |
| SPAs | `AuthProvider` detects `?logged_out=swap`, sets `signedOutAfterSwap=true`, **suppresses the auto-login redirect**. `App.tsx` renders an explicit "signed out / sign back in" landing page instead of bouncing through KC → re-SSO-as-source-identity (which would silently log the user in with the wrong identity for this audience). | `domains/{billing,trading}/web/src/{auth/AuthProvider,App}.tsx` |

### 5f.3 Happy path — swap-session logout does NOT cascade

1. Provision the directional bindings `tim1 ↔ tim2`.
2. Sign in cold to billing as `tim1` in tab A.
3. Click "Switch to Trading →" in tab A — opens trading (in same tab) authenticated as `tim2`.
4. Open billing in a NEW tab B. **Assert**: tab B is authenticated as `tim1` (uses the same billing BFF session).
5. In tab A (now on trading as `tim2`), click "Sign out".
6. **Expected (Network)**: trading BFF returns 303 to `/?logged_out=swap`. Trading audit log shows `Swap-origin logout for sid=<...> source=<tim1-uuid>: skipping P1 SLO chain (§8.5)`.
7. **Expected (UI tab A)**: the trading SPA renders "You've been signed out of this domain. Your other domain session (if any) is unaffected — that's the SoD guarantee."
8. **Refresh tab B** (billing).
9. **Assert (UI tab B)**: still authenticated as `tim1`. The cascade is broken; billing's session was not touched.

### 5f.4 Primary-session logout STILL cascades

1. In tab B (billing as `tim1` — primary session, not swap), click "Sign out".
2. **Expected (Network)**: billing BFF returns 303 to `http://localhost:8888/saml/idp/initiate-slo.do` (the P1 SLO endpoint — unchanged from before).
3. P1 invalidates the HttpSession, emits SAML LogoutRequest, KC ends the SSO session, KC back-channel-logouts to billing (and to any other client in the same KC SSO session for `tim1`).
4. **Assert**: the user lands on P1's login form. `tim1`'s sessions everywhere are gone.

### 5f.5 Sign-back-in from the swap-logout landing

1. From the "signed out" landing in tab A (trading), click "Sign back in".
2. The browser navigates to `/oauth/login/keycloak` (the normal OIDC login).
3. **Expected**: KC has no SSO session for `tim2` (we ended it). KC brokers to P1; P1 has `tim1`'s session alive.
4. **Three sub-cases**, depending on the binding configuration:
   - If `tim1 → demo-trading-client` binding exists, but the SPA didn't pass `targetClient` → P1 emits assertion for `tim1` (current session). KC creates/links a federated user for `tim1` under `demo-trading-client`. **Result**: trading shows as `tim1`. Whether the page works depends on `tim1`'s realm roles — typically `tim1` lacks `trading-*` roles, so `/api/portfolio` returns 403 and the UI renders the "no access" branch.
   - To return as `tim2`, the user must navigate back to billing and click "Switch to Trading →" again.
5. This UX is intentional. After explicit per-audience sign-out, KC + P1 do not know which identity the user wanted — they default to the active P1 HttpSession (the source identity). Clear sign-back-in needs the explicit cross-domain entry.

### 5f.6 Audit shape

| Source | Event |
|---|---|
| BFF (swap-origin) | `Swap-origin logout for sid=<sid> source=<src-uuid>: skipping P1 SLO chain (§8.5)` |
| BFF (primary)    | (existing log shape — RP-init to KC, redirect to P1 SLO) |
| KC               | RP-init logout event for the BFF's SSO session — unchanged either way |
| P1 (swap-origin) | **No `initiate-slo.do` hit**. The source session lives on. |
| P1 (primary)     | `SECURITY_EVENT: User Logout …` — unchanged from before |

The diagnostic difference between the two flows is whether P1 sees a hit to `/saml/idp/initiate-slo.do` after the BFF logout. Soft confirmation: tail P1 logs while clicking sign-out in tab A — no SLO hit appears.

### 5f.7 Smoke commands

```bash
cd ~/nodejs/geowealth && ./gradlew --no-daemon -q :compileJava            # P1
cd ~/keycloak-demo/domains/billing/bff && ./gradlew --no-daemon -q compileJava
cd ~/keycloak-demo/domains/trading/bff && ./gradlew --no-daemon -q compileJava
cd ~/keycloak-demo/domains/billing/web && npx tsc --noEmit
cd ~/keycloak-demo/domains/trading/web && npx tsc --noEmit
```

---

## 6. Out-of-scope after the logout-semantics phase — explicit non-tests

These checks belong to later phases and are documented here so reviewers do not flag them as gaps:

- `physical_person_id` modelling on `USER_TBL` (§7 prerequisite). Bindings are keyed by `User.getID()` UUID. This works for the two-identity case; richer many-to-many physical-person modelling needs a dedicated entity per the design doc §3.1.
- MFA re-challenge during the session-overwrite case: the current implementation relies on P1's `LoginAction` to enforce MFA when the user reaches the login form. If the firm has MFA disabled or the user has a "remember device" cookie, the swap will proceed with `acr=MultiFactor` even though no MFA was actually presented. Firm-policy decision.
- Hard-delete from the admin UI — admins must use the curl path (`{"hard": true}`). Soft-delete preserves the audit trail; hard is GDPR escape hatch.
- UUID picker UX — admins currently type UUIDs by hand in the admin form.
- `/auth/me` caching of `linkedTargets` — each `/auth/me` call hits P1 once. A short TTL cache would cut load if `/auth/me` is ever polled.
- "Cascade-all-linked" opt-in logout. Some firms may want the *opposite* of per-audience: a single sign-out button that nukes every linked identity's session. Implementation would add a follow-on `POST /auth/logout-all-linked` endpoint that walks the binding table and calls KC RP-init for each linked KC user. Out of scope here — per-audience is the SOC2-defensible default.
- End-to-end browser run on the full demo stack with two identities provisioned.

---

## 7. Rollback

The change is fully reversible:

- Drop the two sub-branches.
- Unset `P1_LINKED_IDENTITY_BINDINGS`.
- The standard SP-init flow (KC → P1, no `targetClient`) is untouched; the demo continues to work exactly as it did before.

No data migration ran; nothing on disk persists between runs.
