# WIP — SSO Migration (P1 ↔ keycloak-demo)

Snapshot for resuming work after `/clear`. The synthesis plan is in
[`SSO-MIGRATION-PLAN.md`](SSO-MIGRATION-PLAN.md); this file is only the
"what is done and what is next" view for the active branches.

## Active branches

| Repo | Branch | Latest SSO commit |
|---|---|---|
| `~/keycloak-demo`     | `petarnenov/geowealth-whitelabel-poc`      | Phase 2 finalize — real cert in realm-export, WIP refreshed |
| `~/geowealth`         | `team/petarnenov/keycloak-whitelabel-poc`  | Phase 2 finalize — `/saml/idp/*` namespace fix |

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

## Then — Phase 3 (sidebar)

P1 sidebar entry "Demo MFE-BFF" linking to the keycloak shell at
`http://localhost:5173` (or the SP-init URL through Keycloak). Edit:
`WebContent/react/app/src/pages/PlatformOne/sidebar/_hooks/useIntegrationLinks.js`.
Plan section 3 in `SSO-MIGRATION-PLAN.md`.

## Then — Phase 4 / 5

- Phase 4: real role-mapping (P1 → realm roles), wire `IdpKeyStore`
  through Bamboo secrets-manager path, replace placeholder cert
  end-to-end.
- Phase 5: production hardening — TLS, dedicated key rotation,
  InResponseTo replay protection, front-channel SLO with signed
  LogoutResponse, audit-log routing to SECURITY_EVENT pipeline,
  E2E JUnit 5 suite mirroring the whitelabel one.

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

> Read `WIP-SSO.md` and `SSO-MIGRATION-PLAN.md`. Phase 1 + Phase 2
> are done — endpoints live, cert in place. Pick up at Phase 3:
> add the "Demo MFE-BFF" sidebar entry in P1 and verify the IdP-init
> flow ends in the keycloak-demo shell with a federated user.
