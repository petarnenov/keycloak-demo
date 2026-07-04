# Implementation & verification plan: PolicyRule alignment (executes the 2026-07-04 alignment plan)

Date: 2026-07-04
Status: ready to execute (no code changed yet)
Basis: [`2026-07-04-authz-policyrule-master-alignment.md`](2026-07-04-authz-policyrule-master-alignment.md)
(the "what and why"). This document is the "how, in what order, and how we prove it".

Suggested branch: `petarnenov/authz-policyrule-alignment` (off `petarnenov/auth-extraction` or
`main`, whichever carries the current token-handler state).

## 0. Decisions locked for this implementation

| # | Decision | Choice | Rationale |
|---|---|---|---|
| D1 | A0 placement (dedicated service vs extend user-service) | **Dedicated `authz-service`** (alignment plan Option 1) | Different call pattern (per-request data path vs per-login SPI); independent HPA; cleanest fidelity. |
| D2 | Phase order | **A0 first, then A, B, C, D, E** | The service is born with `/policy/*` + PolicyRule vocabulary; later phases only touch clients. Avoids renaming P1-pointing code that is about to be retargeted anyway. |
| D3 | Caller authentication to `authz-service` | **Validate the forwarded KC access token (JWKS)**, extract `sub` | Alignment plan §A0.3: "authority stays user-bound". Same JWKS config pattern the token-handler already uses. |
| D4 | Demo row ids vs UUID-only refine | **Give demo stub rows real UUID ids**; keep `number`/display keys separate | master's `refineUUIDs` is UUID-only (alignment §4.8); faithful means the demo refines on UUIDs, not invoice numbers. |
| D5 | `ObjectType`/`Permission` catalog location | **Authoritative copy in `authz-service`; compile-time constants mirror in `bff-core`** (`demo.bff.core.ObjectType`, `demo.bff.core.Permission`) | Call sites need the ints at compile time; a shared artifact is overkill for the demo. Document the duplication. |
| D6 | `permissionOverride` (alignment §4.6) | **Not implemented; documented as absent** | The demo has no entity-admin UI; overrides are materialization-side, which stays out of scope. |
| D7 | gwAdmin bypass | **Moved caller-side** (alignment Phase D), helper `AuthClaims.isGwAdmin(auth)` | Matches master §4.5 exactly; the divergence note becomes unnecessary. |
| D8 | Env var name for the authz endpoint | **New `AUTHZ_URL`**, `P1_AUTHZ_URL` deleted in the same phase | No transition period needed — the demo is single-branch; keeping both invites drift. |

Scope correction vs the alignment doc: the repo now has **four** domains
(`billing`, `trading`, `portfolio`, `custodian`), not two. `P1_AUTHZ_URL` is wired into
**five** compose services (token-handler + 4 data BFFs) and each domain's
`application.yml` has a `p1authz` client block. All four are in scope; portfolio/custodian
are scaffolds that follow billing mechanically.

Current-state facts the steps below rely on (verified 2026-07-04):
- `token-handler/src/main/resources/application.yml` — `micronaut.http.services.p1authz.url:
  ${P1_AUTHZ_URL:...}`; `app.tenants.*` per-host gate **already uses real P1 codes**
  (billing/portfolio/custodian: object-type 59 + permission 5; trading: 5 + 5);
  `app.authz.fine-enabled` default `false`.
- `bff-core` — `Tier23Gate`, `P1AuthzClient` (paths `/saml/idp/p1-authz-{me,can,refine}.do`),
  `AuthClaims`, `HeaderIdentity`; tests `Tier23GateTest`, `P1AuthzClientTest`.
- `user-service/` — the structural template for `authz-service` (module-dir Docker build, no
  bff-core dependency, Oracle env `JDBC_URL`/`ORACLE_USER`/`ORACLE_PASSWORD`).
- `/auth/me` returns identity only — no permissions map (verified in `AuthController.me`).
- e2e: 17 Playwright specs under `e2e/`, including `api-authorization.spec.ts`.
- Demo Oracle already carries the GP schema incl. `POLICY_RULE_TBL` (`db/migration/V1` baseline;
  role/permission seeds V12/V14/V15). Flyway numbering: use the next free `V<n>`.

---

## Phase 0 — Baseline (half a day)

Work:
1. Create the branch.
2. Record a green baseline: build everything and run the full e2e suite BEFORE touching code.

```bash
# builds
(cd bff-core && ./gradlew test) || (cd bff-core && gradle test)
for d in billing trading portfolio custodian; do (cd domains/$d/bff && gradle shadowJar -x test); done
(cd token-handler && gradle test shadowJar)
(cd user-service && gradle test shadowJar)
# stack + e2e
./start.sh
(cd e2e && npx playwright test)
```

Verification gate G0: all builds pass; e2e suite green (or a recorded list of pre-existing
failures to exclude from later comparisons). Capture the output — every later phase is
compared against this baseline.

---

## Phase A0 — `authz-service` (2–3 days)

### A0.1 New module `authz-service/` (template: `user-service/`)

```
authz-service/
  settings.gradle                    rootProject.name = 'authz-service'
  build.gradle.kts                   io.micronaut.application, com.gradleup.shadow 8.3.5,
                                     mainClass com.gw.authzservice.Application,
                                     deps: micronaut-security-jwt, jdbc-hikari, oracle driver
                                     (copy user-service's versions)
  Dockerfile                         copy user-service/Dockerfile, s/user-service/authz-service/
  src/main/java/com/gw/authzservice/
    Application.java
    api/PolicyController.java        the /policy/* endpoints
    catalog/ObjectType.java          master-mirroring codes + REAL_OBJECTS / WEB_SECTION_OBJECTS
    catalog/Permission.java          VIEW=1 MODIFY=2 CREATE=3 DELETE=4 EXECUTE=5
    dao/PolicyRuleDao.java           read-only POLICY_RULE_TBL / role-permission queries
    domain/*.java                    request/response records
  src/main/resources/application.yml
  src/test/java/...                  DAO + controller tests
```

### A0.2 Endpoint contract (from alignment §A0.3)

| Endpoint | Request | Response | Mirrors (master) |
|---|---|---|---|
| `GET /policy/capabilities` | `Authorization: Bearer <user access token>` | `{"permissions": {"<objType>_<perm>": true, ...}}` — role-level, **VIEW/CREATE/EXECUTE only** | `loadCreateExecutePermissions` (`PolicyRuleManagerTrait`) |
| `POST /policy/can` | `{objectType, permission, objectId?}` (+ bearer) | `{"allowed": bool}` — `objectId` null ⇒ capability/section semantics (`loadPolicyRules` non-empty) | `canUserDoObject` incl. null-identifier form |
| `POST /policy/refine` | `{objectType, permission, ids: [uuid...]}` (+ bearer) | `{"allowed": [subset]}` — parse-UUID, drop unparseable (fail closed), intersect | `refineUUIDs` (UUID-only, §4.8) |
| `GET /health` | — | `{"status":"UP"}` | — |

Semantics rules (must-hold, tested):
- `sub` comes from the **validated JWT**, never from a query param (D3).
- Fail closed everywhere: DB error / empty ids / unparseable UUID ⇒ deny/omit.
- No gwAdmin logic here (D7 — caller-side).
- capabilities filters `permission_cd IN (1,3,5)`; can/refine accept any permission.

### A0.3 DAO queries (read-only)

- capabilities: `ENTITY_ROLE_TBL × ROLE_PERMISSION_TBL` (role-level; same join family as
  `user-service` `RoleDao`), keys formatted `<objectTypeCd>_<permissionCd>`.
- can/refine: `POLICY_RULE_TBL` by `(entity_id, object_type_cd, permission_cd [, object_id |
  object_cd])` — the demo mirror of `loadPolicyRules`. The mapping login-`sub` → `entity_id`
  reuses whatever join `user-service` uses to resolve the KC subject to `ENTITY_TBL`
  (personId/ldapUid — confirm at implementation time in `EntityDao`).

### A0.4 Wiring

- `docker-compose.yml`: add `authz-service` (clone the `user-service` block: same Oracle envs,
  image `keycloak-demo-authz-service:latest`, no host port). Replace `P1_AUTHZ_URL` with
  `AUTHZ_URL: http://authz-service:8080` in all five consumer services (D8).
- `bff-core/P1AuthzClient`: retarget — `@Client(id = "authz")`, paths → `/policy/capabilities`,
  `/policy/can`, `/policy/refine`. Config blocks in token-handler + 4 domain
  `application.yml`s: `p1authz:` → `authz:` with `url: ${AUTHZ_URL:http://localhost:8087}`.
  (Class renames wait for Phase A — one concern per phase.)
- k8s: new `k8s/base/authz-service.yaml` (Deployment + ClusterIP Service, model on
  `user-service.yaml`, envFrom `oracle-config`/`oracle-creds`); register in
  `k8s/base/kustomization.yaml` + the full-stack overlay; replace `P1_AUTHZ_URL` with
  `AUTHZ_URL` in `k8s/env/urls.{dev,qa,prod}.env` and the token-handler/data-BFF config maps;
  add a forward in `k8s/portforward.sh` (e.g. `:8087`).
- Rebuild rule (CLAUDE.md gotcha): any `bff-core` edit ⇒ `--no-cache` rebuild of
  token-handler **and** all four data BFFs.

### A0.5 Verification (gate G1)

- Unit: `authz-service` DAO tests (H2-or-testcontainer per user-service convention) +
  controller tests: JWT required (401 without), fail-closed cases, VIEW/CREATE/EXECUTE filter.
- Direct probes against the compose stack (get a real user token via the SPA session or KC
  direct grant):
  ```bash
  TOKEN=... # tim1 access token
  curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8087/policy/capabilities | jq .
  curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{"objectType":59,"permission":5}' http://localhost:8087/policy/can | jq .   # billing section
  ```
  Expected: capabilities contains `"59_5": true` for tim1 (BILLING_CENTER EXECUTE — the same
  gate the tenant map already passes); `can` → `{"allowed": true}`.
- **Decoupling proof** (alignment §11): with P1 **not running** and
  `AUTHZ_FINE_ENABLED=true`, login → billing → `/api/billing/invoices` still 200 and refine
  runs against `authz-service`. `grep -rn "p1-authz\|p1authz" bff-core token-handler domains`
  returns nothing.
- Full e2e re-run == G0 baseline.

---

## Phase A — PolicyRule vocabulary renames (1 day)

### A.1 `bff-core` renames

| Old | New |
|---|---|
| `Tier23Gate` | `PolicyRuleGate` |
| `P1AuthzClient` | `PolicyRuleClient` |
| `Tier23Gate.require(auth, objType, perm)` | `requireSection(auth, sectionObjType)` (EXECUTE implied) + `requireCapability(auth, objType, perm)` |
| `Tier23Gate.can(...)` (unused) | `canUserDoObject(auth, objType, perm, objectId)` |
| `Tier23Gate.refine(...)` | `refineUUIDs(auth, items, idOf, objType, perm)` |
| `Tier23GateTest` / `P1AuthzClientTest` | `PolicyRuleGateTest` / `PolicyRuleClientTest` |

Javadoc: strip every "Tier 2/Tier 3" phrase; reference `PolicyRuleManager` semantics and the
alignment doc instead. Keep behavior byte-identical in this phase (including the baked-in
gwAdmin check — it moves in Phase D, not here).

### A.2 Call-site updates (4 domains)

`BillingController` / `TradingController` / `PortfolioController` / `CustodianController`:
constructor + field type + `gate.require(...)` → `requireCapability(...)` (temporary — Phase C
re-shapes these calls onto the section/object split), `gate.refine(...)` → `refineUUIDs(...)`.

### A.3 Verification (gate G2)

- `gradle test` in bff-core (ported tests green).
- Grep: `grep -rn "Tier23\|P1AuthzClient" --include=*.java .` → zero hits.
- Rebuild (--no-cache) token-handler + 4 BFFs; e2e == baseline. No functional change expected.

---

## Phase B — Capability map: two roles + SPA delivery (1–2 days)

### B.1 Server side stops gating objects off the map

- `PolicyRuleGate.requireSection(...)` → `POST /policy/can {objectType, permission: EXECUTE,
  objectId: null}` (fresh check, master's runtime shape) — NOT the cached capabilities map.
- `canUserDoObject` / `refineUUIDs` — unchanged (`/policy/can`, `/policy/refine`).
- The `SubdomainAuthorizer` per-host tenant gate in `/auth/verify` switches from the cached
  map lookup to the same `/policy/can` call (it already holds `(object-type, permission)` per
  host in `app.tenants.*`). The <=60s cache may be kept for this hot path (accepted
  divergence, alignment §10) — but keyed per `(sub, objType, perm)` decision, not as a
  permission map used for object decisions.

### B.2 Ship the map to the SPA (alignment step 5a — the parity item)

- `AuthController.me(...)` (bff-core → token-handler): when `app.authz.fine-enabled=true`,
  call `PolicyRuleClient.capabilities(bearer)` and add to the JSON:
  `"permissions": { "<objType>_<perm>": true, ... }` (absent or `{}` when fine is off —
  document which in the Javadoc and test it).
- SPA (4 × `domains/<d>/web`): `AuthProvider` stores `permissions` from `/auth/me`; add one
  visible consumer per domain as the demo (e.g. billing hides its "Modify" affordance unless
  `59_5`/section is present — pick per domain at implementation). This mirrors P1's
  `LoggedUserJTO.permissions` consumption.

### B.3 Verification (gate G3)

- Unit: `/auth/me` test — with fine-enabled + a stubbed `PolicyRuleClient`, response contains
  the map; with fine disabled it does not.
- e2e (new): `policy-capabilities.spec.ts` — login tim1 at billing, assert
  `GET /auth/me` JSON has `permissions["59_5"] === true`; assert a UI element toggles on it.
- e2e re-run == baseline.

---

## Phase C — Catalogs and data shape (1–2 days)

### C.1 Catalog constants

- `authz-service/catalog/ObjectType.java` (authoritative) + mirror constants in
  `bff-core` `demo.bff.core.ObjectType` (D5): master codes used by the demo
  (`TRADE=5`, `BILLING_CENTER=59`, plus the section set), and **demo-extension REAL_OBJECTS**
  clearly marked: `INVOICE=9101`, `ORDER=9201` (master has no invoice/order object type —
  keep the demo codes but register them centrally). `Permission` mirrors master exactly.
- Delete all four `DemoAuthz.java`; controllers switch to the shared constants:
  - billing: `requireSection(ObjectType.BILLING_CENTER)` + rows
    `refineUUIDs(..., ObjectType.INVOICE, Permission.VIEW)`;
  - trading: `requireSection(ObjectType.TRADE /* section semantics per tenant map */)` + rows
    `ORDER`; portfolio/custodian follow billing.

### C.2 UUID row ids (D4)

Stub invoices/orders get a stable `id` (UUID) field alongside the display key
(`number`/`id` string). `refineUUIDs` extracts the UUID. Update the `idOf` lambdas.

### C.3 POLICY_RULE seed for the demo objects

New Flyway migration (next free `V<n>__seed_demo_authz_policy_rules.sql`):
- section rows for the demo users (tim1 …): `(entity, null, null, 59, 5, firm)` etc.;
- per-row rules for a SUBSET of the stub UUIDs — so refine visibly filters (e.g. tim1 sees
  3 of 5 invoices with fine on) — this is what makes G4's behavioral check meaningful;
- reset path per `db/README.md` (V1 is BASELINE-only: clean → sqlplus@V1 → baseline → migrate).

### C.4 `add-domain.sh` compatibility

The scaffolder clones `domains/billing/`. After C, billing has no `DemoAuthz` and different
imports — run `./scripts/add-domain.sh zzz --no-deploy --no-hosts`, confirm the clone
compiles, then `./scripts/remove-domain.sh zzz --yes`. Patch the script's rename map if it
references `DemoAuthz`.

### C.5 Verification (gate G4)

- With `AUTHZ_FINE_ENABLED=true` + seeded rules: billing list shows exactly the seeded subset
  for tim1 (screenshot/e2e assert); a user without `59_5` gets 403 at `/auth/verify`
  (existing `api-authorization.spec.ts` extended).
- With fine off (default): behavior == baseline.
- `add-domain.sh` dry-run passes (C.4).

---

## Phase D — gwAdmin caller-side (half a day)

- Remove the `isGwAdmin` short-circuits from `PolicyRuleGate`; add `AuthClaims.isGwAdmin(auth)`.
- Controllers adopt master's convention explicitly:
  `if (!AuthClaims.isGwAdmin(auth)) gate.requireSection(...);`
  `items = AuthClaims.isGwAdmin(auth) ? items : gate.refineUUIDs(...);`
- Tests: gwAdmin cases move from gate tests to controller tests; add a REGRESSION test that
  the gate itself no longer bypasses (a gwAdmin user with no rules is denied by the raw gate).
- Verification (gate G5): unit green; e2e gwAdmin flow (superadmin user sees all rows with
  fine on) — extend `api-authorization.spec.ts`.

## Phase E — Docs & terminology sweep (half a day)

- Rewrite: `p1-auth-flow.md` (§1.7b/§2.2/§2.9 wire references), `CLAUDE.md` (Layout →
  bff-core/token-handler entries mention Tier23Gate/Tier-2/Tier-3), `bff-core/README.md`,
  `token-handler/application.yml` comments ("Tier-2 P1 permission gate"), the four domain
  `application.yml` comments, `sso-role-mapping.md` if it references tiers.
- Add the "master fidelity" note (accepted divergences: HTTP boundary; <=60s verify cache;
  no permissionOverride) — copy from alignment §10 + D6.
- Update the alignment doc Status → "implemented (see this doc)".
- Verification (gate G6): `grep -rniE "tier[ _-]?[123]" --include='*.{java,md,yml,ts,tsx}' .`
  → zero authz-related hits (allow unrelated e.g. CSS "tier" words if any — review manually).

---

## Final acceptance checklist (run after Phase E, all on one build)

| # | Check | How | Pass condition |
|---|---|---|---|
| 1 | Builds | all gradle modules + all images (`--no-cache` for bff-core consumers) | green |
| 2 | Unit | bff-core, authz-service, token-handler tests | green |
| 3 | e2e regression | full `e2e/` suite, fine-enabled **false** | == G0 baseline |
| 4 | e2e fine-grained | suite + new specs, fine-enabled **true** | capabilities in `/auth/me`; refine filters seeded subset; 403 without section; gwAdmin sees all |
| 5 | P1 decoupling | P1 not running (compose) / `toggle.sh legacy down` (k8s), fine on | login + `/api/**` + refine all work; `grep p1-authz` → nothing |
| 6 | Terminology | grep gate G6 | zero tier hits |
| 7 | Browser evidence | drive login → billing → filtered list → logout via Playwright/Chrome DevTools MCP; capture screenshots | full flow verified visually (per repo practice: 401/302 smoke checks are not enough) |
| 8 | K8s parity | `./k8s/up.sh` (or `--profile` incl. authz-service), re-run 3–5 against the cluster | same results in-cluster |
| 9 | Scaffolder | C.4 dry-run | clone compiles, remove clean |

## Rollback strategy

- Phases are separate commits/PRs in D2 order; each is independently revertible.
- The kill switch at every point is `AUTHZ_FINE_ENABLED=false` (the shipped default): all
  fine-grained paths go dormant and the demo behaves exactly as the pre-refactor baseline.
- A0 rollback = flip `AUTHZ_URL` back to the P1 base URL (`P1_AUTHZ_URL` semantics) and
  redeploy token-handler + BFFs — the P1 branch endpoints remain in the geowealth demo branch
  untouched until this plan is accepted, so the old path stays available during the window.
- Do not delete P1's `p1-authz-*.do` actions (geowealth repo) until final acceptance passes.

## Effort summary

| Phase | Size | Depends on |
|---|---|---|
| 0 baseline | 0.5d | — |
| A0 authz-service | 2–3d | 0 |
| A renames | 1d | A0 |
| B map split + SPA | 1–2d | A |
| C catalogs + seed | 1–2d | B |
| D gwAdmin | 0.5d | C |
| E docs | 0.5d | D |
| **Total** | **~7–9.5d** | sequential |
