# WIP — SSO Migration (P1 ↔ keycloak-demo)

Snapshot for resuming work after `/clear`. The synthesis plan is in
[`SSO-MIGRATION-PLAN.md`](SSO-MIGRATION-PLAN.md); this file is only the
"what is done and what is next" view for the active branches.

## Active branches

| Repo | Branch | Latest SSO commit |
|---|---|---|
| `~/keycloak-demo`     | `petarnenov/geowealth-whitelabel-poc`      | `b8dd72f` Phase 3 done refresh |
| `~/geowealth`         | `team/petarnenov/keycloak-whitelabel-poc`  | `22cc161edf5` Phase 4 — real role mapping |

## Done

- **Phase 0 — synthesis.** `SSO-MIGRATION-PLAN.md` consolidates
  `p1-sso-architecture.md` + `p1-sso-integration-research.md` into a
  5-phase plan. Chose Solution 2 (SAML brokering, P1 as IdP).
- **Phase 1 — Keycloak SAML SP.** `keycloak/realm-export.json`
  registers `p1` identity provider on `demo-realm` with
  `firstBrokerLoginFlowAlias=first broker login`,
  `nameIDPolicyFormat=persistent`, and matching
  `identityProviderMappers` for `email`, `firstName`, `lastName`,
  `firmCd`, plus the role-attribute mapper trio. Cert is
  `PLACEHOLDER_REPLACED_WITH_P1_IDP_X509_AT_PHASE_2` — must be swapped
  for the real value once a P1 keystore is generated. Re-import
  applied to the running stack via `docker compose down -v && up`.
  Verified: `/realms/demo-realm/broker/p1/endpoint/descriptor` returns
  the SP metadata; login page renders the "p1" social button.
- **Phase 2 — P1 SAML IdP scaffold.** Seven Java classes under
  `~/geowealth/src/main/java/com/geowealth/saml/idp/`:
  `IdpKeyStore`, `KeycloakUserAttributes`,
  `KeycloakAttributeStatementMapper`, `KeycloakSamlResponseBuilder`,
  `IdpMetadataAction`, `IdpSsoAction`, `IdpSloAction`. Three Struts
  actions wired in `struts-tiles.xml`: `/saml/idp/metadata.do`,
  `/saml/idp/sso.do`, `/saml/idp/slo.do`. Classes compiled
  (`./gradlew compileJava -x test --offline` → `BUILD SUCCESSFUL`)
  and deployed to
  `~/tools/tomcat9/webapps/ROOT/WEB-INF/classes/com/geowealth/saml/idp/`.
  `sso-dev-keystore.sh` in this repo (uncommitted) generates the dev
  PKCS#12 keystore.

## Phase 2 finalize — DONE (2026-05-23)

All four steps from the original plan have landed:

1. **Dev keystore generated.** `/tmp/p1-idp-dev.p12` (alias `p1-idp`,
   RSA 2048, 365-day validity; CN=`p1-idp-dev`). Generator is
   `sso-dev-keystore.sh` in this repo.
2. **Tomcat env wired.** `~/tools/tomcat9/bin/setenv.sh` now exports
   `P1_IDP_KEYSTORE_PATH` / `_PASSWORD` / `_ENTITY`. Tomcat restarted
   and picked them up.
3. **Smoke tests pass.**
   - `GET /saml/idp/metadata.do` → 200,
     `application/samlmetadata+xml`, full `<EntityDescriptor>` with
     embedded `<X509Certificate>` (2349 bytes).
   - `GET /saml/idp/sso.do` (no session) → action returns the
     "Not logged into P1" text; gate logic works.
   - `GET /saml/idp/slo.do` → 200 `p1-slo-acknowledged`.
   - **Known follow-up:** Struts `httpheader` result type rewrites the
     status to 200 even when the action sets 401 inside `writeText()`.
     Functionally correct but cosmetic; cleanup is to switch to a
     `stream` result or set status via header earlier in the chain.
4. **Keycloak realm cert swapped.**
   - Live realm patched via admin API
     (`PUT /admin/realms/demo-realm/identity-provider/instances/p1`)
     with the real signing cert (HTTP 204; verify-read confirms 1132-char
     cert in place of placeholder).
   - `keycloak/realm-export.json` updated so fresh imports
     (`down -v && up`) get the same cert without re-patching.

### Struts namespace fix (not in original plan, caught by smoke test)

Struts2 was parsing action names containing slashes as namespace+name —
`saml/idp/metadata` became `namespace=/`, `actionName=metadata`. That
fell through to the default action, which redirected unauthenticated
callers to `/react/indexReact.do` (HTTP 302). Fix: move the three
actions into a new `samlIdpTiles` package with `namespace="/saml/idp"`
and bare `sso`/`metadata`/`slo` names. The original inline mapping in
`frontOfficeTiles` was replaced with a comment pointing at the new
package. See `~/geowealth/src/main/resources/struts-tiles.xml`.

## Phase 3 — DONE (2026-05-23)

`Integrations → Demo MFE-BFF` sidebar entry added in
`useIntegrationLinks.js`. Renders as `<a target="_blank">` to
`/saml/idp/sso.do?RelayState=keycloak-demo` (absoluteUrl flag honored
by `BackOfficeLinks.js`). Webpack dev-server picked it up via HMR;
verified the URL is in the served bundle. Container-level gating
inherited from "Integrations" group (visible only to luIsFirmGEOWEALTH).

## Phase 4 — DONE (2026-05-23)

Real role mapping replaces the Phase 2 `List.of("user")` placeholder.
`IdpSsoAction.derivePocRoles(LoggedUser)` projects the active P1
session onto the demo realm's `client` / `user` / `admin` set:

- `client` — every authenticated P1 user.
- `user` — added when `LoggedUser.isAdvisor()` is true.
- `admin` — added when `LoggedUser.canLoggedUserAccessBackOffice()`
  returns true (back-office permission). The check is wrapped in
  try/catch so a permission-service failure logs and degrades to the
  lower-privilege set rather than 500-ing the SAML Response.

The realm role mappers (`saml-role-idp-mapper` × 3) in
`realm-export.json` consume the lowercase attribute values and bind
them to the matching realm roles — no Keycloak-side change needed
beyond the flow tweaks below.

**Silent first-broker-login.** Three `first broker login` flow steps
disabled (live, via admin API):

- `Review Profile` → DISABLED (no confirmation page)
- `Confirm link existing account` → DISABLED (auto-link by email)
- `Account verification options` → DISABLED (we trust the P1 email
  because `trustEmail=true` on the IdP config)

Combined effect: a P1 user whose email matches an existing realm
user federates silently; a new email auto-creates the realm user
silently. Both paths land in the keycloak-demo shell with no extra
clicks.

`updateProfileFirstLoginMode` flipped to `off` in
`keycloak/realm-export.json` so fresh imports get the
silent-link disposition. The three flow tweaks are not yet
serialized into `realm-export.json` (Keycloak's built-in flow is
default-recreated on every import), so the new script
`keycloak/apply-fbl-customization.sh` re-applies them via admin API
after a `down -v && up`. Run once after a wipe.

## Next — manual end-to-end smoke

With both stacks up (`./start.sh` + Tomcat + webpack-dev-server):

1. Log into P1 at `http://localhost:8888/` with a GeoWealth-firm user.
2. Open the Integrations submenu in the sidebar → click "Demo MFE-BFF".
3. New tab opens at `/saml/idp/sso.do?RelayState=keycloak-demo`.
4. P1 builds a signed SAML Response → auto-submit form POSTs to
   `http://localhost:8898/realms/demo-realm/broker/p1/endpoint`.
5. Keycloak validates the cert, runs first-broker-login (auto-link
   by email), mints a realm session, redirects to the keycloak-demo
   shell at `http://localhost:5173/` with the user already signed in.

Watch the logs while you click:
- `tail -f ~/tools/tomcat9/logs/catalina.out | grep SAML_ISSUED`
- `docker logs -f keycloak-demo-keycloak-1 | grep -iE 'broker|saml'`

## Then — Phase 5

Production hardening — picks up everything intentionally deferred
during the POC track:

- TLS everywhere (browser ↔ shell, shell ↔ Keycloak, browser ↔ P1,
  Keycloak ↔ P1 back-channel SLO)
- Dedicated IdP signing key with 60-day rotation; `IdpKeyStore` wired
  through Bamboo secrets-manager rather than env vars + `/tmp` PKCS#12
- InResponseTo replay protection — track AuthnRequest IDs in a
  short-lived cache (Caffeine?) and reject duplicates within the
  IssueInstant window
- Front-channel SLO with a proper signed `<samlp:LogoutResponse>`
  (today `IdpSloAction` returns a bare 200)
- Audit log routing — SAML_ISSUED events into the existing P1
  `SECURITY_EVENT` pipeline, including remote IP, target ACS, RelayState
- E2E JUnit 5 suite mirroring the whitelabel one: drives C1 (IdP-init)
  and C2 (SP-init) flows end-to-end against a real Keycloak + Tomcat
- Serialize the customized `first broker login` flow into
  `realm-export.json` proper so fresh installs don't need
  `apply-fbl-customization.sh`

## Files of interest

- `SSO-MIGRATION-PLAN.md` — full 5-phase plan, decision points
- `p1-sso-architecture.md` + `p1-sso-integration-research.md` — source
  analyses (Phase 0 inputs; do not re-synthesize)
- `keycloak/realm-export.json` — `identityProviders` + mappers
- `sso-dev-keystore.sh` — dev keystore generator (uncommitted)
- `~/geowealth/src/main/java/com/geowealth/saml/idp/*` — Phase 2 code
- `~/geowealth/src/main/resources/struts-tiles.xml` — action wiring

## Resume recipe for next session

```
/clear
```

Then prompt:

> Read `WIP-SSO.md` and `SSO-MIGRATION-PLAN.md`. Phases 1–4 done.
> Pick up at Phase 5 production hardening (TLS, key rotation,
> InResponseTo replay protection, signed LogoutResponse,
> SECURITY_EVENT audit routing, E2E JUnit suite, full flow
> serialization into realm-export). Or, before Phase 5, run the
> manual end-to-end smoke documented in this file to confirm
> Phases 1–4 land cleanly.
