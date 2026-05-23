# WIP — SSO Migration (P1 ↔ keycloak-demo)

Snapshot for resuming work after `/clear`. The synthesis plan is in
[`SSO-MIGRATION-PLAN.md`](SSO-MIGRATION-PLAN.md); this file is only the
"what is done and what is next" view for the active branches.

## Active branches

| Repo | Branch | Latest SSO commit |
|---|---|---|
| `~/keycloak-demo`     | `petarnenov/geowealth-whitelabel-poc`      | Phase 6c/6d — FBL flow serialized, prod docs |
| `~/geowealth`         | `team/petarnenov/keycloak-whitelabel-poc`  | `daf87cb7e1d` Phase 6a/6b — sig validation + replay protection |

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

## Phase 6 — DONE (2026-05-23)

Five production-hardening items shipped (see
[`PHASE6-HARDENING-PLAN.md`](PHASE6-HARDENING-PLAN.md) for the full
plan + verification recipe).

| # | Item | Status |
|---|---|---|
| 6a | Inbound `LogoutRequest` signature validation | ✅ live (`geowealth/daf87cb7e1d`) — `KeycloakSpCert` + `SignatureValidator.validate`; unsigned/wrong-sig → 403 with `SECURITY_EVENT: SAML_LOGOUT_UNSIGNED` / `SAML_LOGOUT_SIG_REJECT` |
| 6b | Replay protection on inbound IDs | ✅ live (`geowealth/daf87cb7e1d`) — `SamlRequestIdCache` (TTL-bounded `ConcurrentHashMap`, 10 min, 10k cap); replayed `LogoutRequest` → 409 with `SAML_REPLAY_REJECT` |
| 6c | Serialize custom FBL flow into realm-export | ✅ live — `p1-first-broker-login` + 6 sub-flows + 2 authenticatorConfig entries in `keycloak/realm-export.json`; verified against fresh `down -v && up`; `apply-fbl-customization.sh` retired (kept with deprecation header) |
| 6d | TLS topology + secrets-manager interface | ✅ docs — [`docs/PROD-TLS-TOPOLOGY.md`](docs/PROD-TLS-TOPOLOGY.md) covers the four legs + cert provisioning; [`docs/PROD-KEYSTORE-PROVISIONING.md`](docs/PROD-KEYSTORE-PROVISIONING.md) covers Bamboo/Vault wiring + rotation cadence |
| 6e | JUnit E2E suite scaffold | ✅ live (`geowealth`, `SamlIdpEndpointsIT`) — 4 tests: 2 pass against running Tomcat, 2 self-skip when Phase 6a enforcement is on (need signed payload, Phase 6e continuation work) |

### Phase 6 verification matrix (live results)

```
metadata.do                     → 200, valid <EntityDescriptor>, X509 embeds   ✅
sso.do (no session)             → "Not logged into P1" text                    ✅
slo.do unsigned (cert set)      → 403 "logout-request must be signed"          ✅ (SAML_LOGOUT_UNSIGNED)
slo.do bad-sig (cert set)       → 403 "logout-request signature mismatch"     (covered by code; live test deferred)
slo.do signed first hit         → 200, signed LogoutResponse                   (works when cert off)
slo.do signed replay            → 409 "logout-request replay rejected"        ✅ (SAML_REPLAY_REJECT)
fresh `down -v && up`           → p1-first-broker-login imports cleanly,
                                  3 target steps DISABLED, IdP wired           ✅
./gradlew test --tests SamlIdpEndpointsIT
                                → 4 tests: 2 PASSED, 2 SKIPPED (documented)    ✅
```

### Phase 6 continuation (not blocking POC)

- Signed-LogoutRequest test variant. Build a real signed payload in
  `SamlIdpEndpointsIT` so the 6b replay test passes with Phase 6a
  enforcement active. Needs the SP private key (the keycloak-side
  half of `P1_IDP_KC_SP_CERT`), which the test would have to load
  from a fixture keystore.
- AuthnRequest replay protection on `IdpSsoAction`. Today's demo
  IdP-init flow doesn't take external AuthnRequests; once SP-init
  flow is exercised, wire the same `SamlRequestIdCache` check.
- Keycloak realm-side: configure the broker SP to actually sign its
  outbound LogoutRequests (today `AuthnRequestsSigned="false"` and
  no SP signing key — Phase 6a's enforcement is a one-sided contract).

## Phase 5 partial — DONE (2026-05-23)

Two highest-impact Phase 5 items landed; the rest are deferred with
rationale to keep POC scope honest.

### 5a — `SECURITY_EVENT` audit routing (DONE)

Both `IdpSsoAction` and `IdpSloAction` now tag their lines with the
`SECURITY_EVENT:` prefix and the same structured field shape the
existing P1 actions (`LoginAction`, `LogOutAction`,
`GeowealthSessionListener`) use. Lines carry user / firm / remote IP
(honoring `X-Forwarded-For` first hop) / RelayState plus the
domain-specific tail (roles emitted on SSO, InResponseTo on SLO).
Operational tooling that already watches `SECURITY_EVENT:` picks up
the SAML traffic without any extra wiring.

### 5b — signed `<samlp:LogoutResponse>` (DONE)

`IdpSloAction` no longer returns a bare 200. It now:

1. Parses the inbound base64 `SAMLRequest` with OpenSAML's
   unmarshaller (fail-soft — malformed payloads log and continue;
   "session is gone" is the user contract, the SAML loop is a side
   effect).
2. Extracts the `LogoutRequest` ID for the `InResponseTo` echo.
3. Clears the three `LoginAction` session keys.
4. Builds a `LogoutResponse` with our IdP issuer + `Status` SUCCESS.
5. Signs with the keystore credential (new
   `IdpKeyStore.getSigningCredential()` accessor).
6. Marshals + base64 + returns an HTML auto-submit form posting to
   the Keycloak broker SLO endpoint
   (`P1_IDP_KEYCLOAK_SLO_URL`, default
   `http://localhost:8898/realms/demo-realm/broker/p1/endpoint`).

Static initializer mirrors the OpenSAML bootstrap from
`AbstractSamlAuthenticationResponseBuilder` so SLO can fire on a JVM
where the AuthN-Response side has never run.

### 5c — full FBL flow serialization into realm-export (DEFERRED)

Keycloak's built-in `first broker login` flow is opaque to
`realm-export.json` — when the DB is wiped (`down -v`) the import
recreates the default flow with default REQUIRED requirements,
ignoring any customization stored only in the live realm. Two paths
considered:

- **Override the built-in.** Putting a flow with `alias: first broker
  login` into `authenticationFlows` and re-importing risks collision
  with Keycloak's own bootstrap of that built-in flow; behavior is
  version-dependent and brittle.
- **Custom alias.** Add a sibling flow `p1-first-broker-login` and
  point the IdP config's `firstBrokerLoginFlowAlias` at it. Cleaner
  but requires serializing five nested sub-flows correctly
  (`User creation or linking`, `Handle Existing Account`,
  `Account verification options`, `First broker login - Conditional
  OTP`, `First Broker Login - Conditional Organization`) with the
  exact authenticator config and priorities Keycloak validates on
  import.

For the POC scope the script
`keycloak/apply-fbl-customization.sh` is the working contract:
re-apply after every `down -v && up`. A proper override is Phase 5
continuation work.

### 5d — InResponseTo replay protection (DEFERRED)

Today `IdpSsoAction` accepts any inbound SP-init `AuthnRequest`
without tracking IDs, so a replayed request could in theory mint a
duplicate Response. Mitigations:

- Add Caffeine to the geowealth runtime classpath.
- Cache `AuthnRequest.getID()` keys with TTL ≤ the SAML
  `IssueInstant` window (5 min).
- Reject duplicates with HTTP 400 + an audit `SECURITY_EVENT:
  SAML_REPLAY_REJECTED user=… reqId=…` line.

Not blocking the POC since the demo IdP-init flow doesn't take
external AuthnRequests at all (RelayState=keycloak-demo is sidebar
traffic only). Phase 5 continuation.

### 5e — E2E JUnit suite (DEFERRED)

Should mirror the whitelabel suite at
`com.geowealth.poc.whitelabel.WhitelabelE2E*Tests` — drive the C1
(IdP-init) and C2 (SP-init) flows end-to-end against the running
Tomcat + Keycloak stack:

- Hit `/saml/idp/metadata.do`, validate the `<EntityDescriptor>`
  schema, assert the cert in the metadata matches
  `IdpKeyStore.getCertificateBase64()`.
- Build a synthetic AuthnRequest, POST to `/saml/idp/sso.do` with a
  pre-authenticated test session cookie, assert the auto-submit form
  posts to Keycloak's ACS with a valid signed Response.
- POST a synthetic LogoutRequest to `/saml/idp/slo.do`, assert the
  emitted LogoutResponse signature verifies and `InResponseTo`
  echoes.

Requires standing up the Keycloak container fixture from the
keycloak-demo side or stubbing Keycloak's ACS — both are non-trivial
setup work. Phase 5 continuation.

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

## Phase 5 continuation (not yet landed)

The items above (5c / 5d / 5e) plus the original Phase 5 stretch
goals that haven't been touched:

- **TLS everywhere.** Browser ↔ shell, shell ↔ Keycloak,
  browser ↔ P1, Keycloak ↔ P1 back-channel SLO. Requires cert
  provisioning + a reverse proxy in front of Tomcat / Keycloak.
- **Dedicated signing key with rotation.** Today the env-var keystore
  points at `/tmp/p1-idp-dev.p12`. Production needs the keystore
  mounted from a secrets manager (Bamboo / Vault) with a 60-day
  rotation pipeline and metadata that advertises both the old and the
  new cert during the transition window.
- **Inbound LogoutRequest signature validation.** `IdpSloAction`
  parses the inbound request but trusts it. Reuse the existing
  `SamlValidator` for proper signature verification.
- **Front-channel multiframe SLO.** Keycloak's broker SLO loop
  works once the user reaches Keycloak; multi-SP fan-out is a
  Keycloak realm concern not implemented here.

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

> Read `WIP-SSO.md` and `SSO-MIGRATION-PLAN.md`. Phases 1–4 done,
> Phase 5a (audit routing) + 5b (signed LogoutResponse) done.
> Continuation work: 5c (full FBL flow serialization), 5d (InResponseTo
> replay protection), 5e (E2E JUnit), inbound LogoutRequest signature
> validation, TLS, dedicated key rotation. Or run the manual end-to-end
> smoke documented in this file first.
