# WIP — SSO Migration (P1 ↔ keycloak-demo)

Snapshot for resuming work after `/clear`. The synthesis plan is in
[`SSO-MIGRATION-PLAN.md`](SSO-MIGRATION-PLAN.md); this file is only the
"what is done and what is next" view for the active branches.

## Active branches

| Repo | Branch | Latest SSO commit |
|---|---|---|
| `~/keycloak-demo`     | `petarnenov/geowealth-whitelabel-poc`      | `5a176d5` SSO Phase 1: register P1 SAML IdP on demo-realm |
| `~/geowealth`         | `team/petarnenov/keycloak-whitelabel-poc`  | `efebf820ae3` GEO-99999 SSO Phase 2: P1 SAML IdP scaffold |

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

## Next — Phase 2 finalize (cert + smoke test)

1. **Generate dev keystore.** From `~/keycloak-demo`:
   ```bash
   ./sso-dev-keystore.sh /tmp/p1-idp-dev.p12
   ```
   Capture the base64 cert it prints.
2. **Set Tomcat env.** Append to `~/tools/tomcat9/bin/setenv.sh`:
   ```bash
   export P1_IDP_KEYSTORE_PATH="/tmp/p1-idp-dev.p12"
   export P1_IDP_KEYSTORE_PASSWORD="changeit"
   export P1_IDP_KEYSTORE_ENTITY="p1-idp"
   ```
   Restart Tomcat: `~/tools/tomcat9/bin/shutdown.sh && sleep 3 && ~/tools/tomcat9/bin/startup.sh`.
3. **Smoke-test endpoints.**
   - `curl -i http://localhost:8080/saml/idp/metadata.do` → 200,
     `application/samlmetadata+xml`, embedded `<X509Certificate>`.
   - `curl -i http://localhost:8080/saml/idp/sso.do` →
     401 (no P1 session) or 503 (keystore not loaded).
   - With an active P1 session in browser, hit
     `http://localhost:8080/saml/idp/sso.do?RelayState=demo` →
     auto-submit HTML form POSTing to keycloak broker ACS.
4. **Swap Keycloak placeholder cert.** Either:
   - patch `keycloak/realm-export.json` and `docker compose down -v && up`, **or**
   - PATCH `/admin/realms/demo-realm/identity-provider/instances/p1`
     with the real `signingCertificate` value (preserves sessions).

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

> Read `WIP-SSO.md` and `SSO-MIGRATION-PLAN.md`. We finished Phase 1
> + Phase 2 scaffold. Pick up at Phase 2 finalize: generate the dev
> keystore, export `P1_IDP_*` env in Tomcat, smoke-test the three
> `/saml/idp/*.do` endpoints, swap the Keycloak realm placeholder
> cert, then move to Phase 3 sidebar entry.
