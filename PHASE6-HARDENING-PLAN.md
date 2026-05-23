# Phase 6 — production hardening plan

Synthesizes the five items left open at the end of Phase 5
(committed in [`WIP-SSO.md`](WIP-SSO.md) under "Phase 5 continuation").
Lands as a sequenced plan + verification recipe so each item can be
landed independently and checked without manual archaeology.

**Date:** 2026-05-23
**Repos:**
- `keycloak-demo` branch `petarnenov/geowealth-whitelabel-poc`
- `geowealth` branch `team/petarnenov/keycloak-whitelabel-poc`

## Scope

Five items in priority order (highest user-visible risk first):

| # | Item | Effort | Where | Verifiable locally? |
|---|---|---|---|---|
| 6a | Inbound `LogoutRequest` signature validation | M | geowealth | ✓ (fake LogoutRequest + cert) |
| 6b | Replay protection on `AuthnRequest` / `LogoutRequest` IDs | M | geowealth | ✓ (replay same ID twice) |
| 6c | Serialize custom `p1-first-broker-login` flow into realm-export | M | keycloak-demo | ✓ (`down -v && up`, no script run) |
| 6d | TLS topology + secrets-manager keystore interface | L (doc) | both | ✗ (needs infra) |
| 6e | JUnit E2E suite (Phase 1 scaffold) | M | geowealth | ✓ (`./gradlew test`) |

Sequential dependencies: **none.** Each item ships independently.

---

## 6a — Inbound LogoutRequest signature validation

### Current state

`IdpSloAction.execute()` parses the inbound `<samlp:LogoutRequest>` via
`OpenSamlUtils` / `Unmarshaller`, extracts the request ID, then tears
down the P1 session unconditionally. No signature check. The
`wantAssertionsSigned=true` realm flag covers the Response side, not
the LogoutRequest direction.

### Risk

Without inbound validation, anyone who can reach
`POST /saml/idp/slo.do` and POST a well-formed (unsigned) LogoutRequest
can force-log-out any P1 session. Locally that's just the Tomcat
listener; in production behind a reverse proxy it could be exposed
to the internet.

### Implementation

1. Add `KeycloakSpCert.java` under `com.geowealth.saml.idp` — env-driven
   accessor for Keycloak's SP signing cert. Keycloak's realm-broker
   metadata is at
   `/realms/demo-realm/broker/p1/endpoint/descriptor` and contains the
   public cert it signs LogoutRequests with. Two pickup modes:

   - **Env var.** `P1_IDP_KC_SP_CERT` set to the base64 cert (no PEM
     armoring). Mirror the `IdpKeyStore` pattern. Default unset →
     `isReady() == false` → SLO action falls back to **rejecting all
     signed requests** but still accepts unsigned requests as Phase 5
     scaffold did.
   - **HTTP fetch** (Phase 6a part 2, optional). Fetch the SP
     descriptor at startup with a 30s timeout, parse the
     `<X509Certificate>`, cache forever. Failure → fall back to the
     env-var path.

   Start with env-var only; fetch is a continuation item.

2. In `IdpSloAction.execute()` after `parseLogoutRequest`:
   ```java
   if (req.getSignature() != null && KeycloakSpCert.getSole().isReady()) {
       try {
           SignatureValidator.validate(req.getSignature(), KeycloakSpCert.getSole().getCredential());
       } catch (SignatureException se) {
           LOG.warn("SECURITY_EVENT: SAML_LOGOUT_SIG_REJECT remote=... reason=" + se.getMessage());
           return writeText(res, 403, "logout-request signature mismatch");
       }
   } else if (req.getSignature() == null && KeycloakSpCert.getSole().isReady()) {
       LOG.warn("SECURITY_EVENT: SAML_LOGOUT_UNSIGNED remote=...");
       return writeText(res, 403, "logout-request must be signed");
   }
   // else: dev mode where SP cert is unset — accept (logs WARN once at startup)
   ```

3. Add `P1_IDP_KC_SP_CERT` to the existing keystore env recipe in
   `~/tools/tomcat9/bin/setenv.sh` once we know the cert.

### Verification

```bash
# 1. Stand up the stack normally (Tomcat + Keycloak), don't set P1_IDP_KC_SP_CERT.
#    SLO with any payload still works (dev mode). Audit log shows WARN about cert unset.

# 2. Grab Keycloak's SP cert from its broker metadata:
curl -s http://localhost:8898/realms/demo-realm/broker/p1/endpoint/descriptor \
  | python3 -c "import sys,re; m=re.search(r'<ds:X509Certificate>([^<]+)', sys.stdin.read()); print(m.group(1).strip())"

# 3. Export it, restart Tomcat. Repeat the SLO smoke from Phase 5:
#    fake-LogoutRequest without a <ds:Signature> → 403 logout-request must be signed
#    fake-LogoutRequest signed by a WRONG key → 403 logout-request signature mismatch
#    a real Keycloak-issued LogoutRequest → 200 + signed LogoutResponse
```

---

## 6b — Replay protection

### Current state

Both `IdpSsoAction` (which would receive AuthnRequests in SP-init mode)
and `IdpSloAction` accept any inbound request ID. Replaying the same
LogoutRequest twice tears down the same session twice — idempotent in
practice but masks attack patterns; replaying an `AuthnRequest` could
in theory mint a duplicate Response with a different session index.

### Risk

Low for the demo (IdP-init only) but a real production concern once
SP-init traffic flows through `IdpSsoAction.execute()` for browsers
arriving via the Keycloak login screen.

### Implementation

1. Add Caffeine to `geowealth/build.gradle.kts`:
   ```kotlin
   implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
   ```
   (Already on the classpath for the white-label rate limiter; verify
   first, no need to bump.)

2. New `SamlRequestIdCache.java` under `com.geowealth.saml.idp`:
   - Caffeine cache, max size 10_000, TTL 10 min (matches the
     `IssueInstant` window the realm config tolerates).
   - `boolean rememberOrReject(String id)` — atomic put-if-absent;
     returns true on first insertion, false on replay.

3. In `IdpSloAction.execute()` after signature validation (6a):
   ```java
   String reqId = req.getID();
   if (reqId != null && !SamlRequestIdCache.getSole().rememberOrReject(reqId)) {
       LOG.warn("SECURITY_EVENT: SAML_REPLAY_REJECT kind=LogoutRequest id=" + reqId + " remote=" + clientRemoteAddr());
       return writeText(res, 409, "logout-request replay rejected");
   }
   ```

4. Same pattern in `IdpSsoAction.execute()` when an inbound
   `SAMLRequest` parameter is present (SP-init).

### Verification

```bash
# Replay test: post the same SAMLRequest twice.
FAKE_REQ=$(printf '<samlp:LogoutRequest ID="_replay-test-id" ...' | base64)
curl -i -X POST -d "SAMLRequest=$FAKE_REQ" http://localhost:8080/saml/idp/slo.do
# expect 200, signed LogoutResponse, audit: SAML_LOGOUT id=_replay-test-id

curl -i -X POST -d "SAMLRequest=$FAKE_REQ" http://localhost:8080/saml/idp/slo.do
# expect 409 logout-request replay rejected, audit: SAML_REPLAY_REJECT id=_replay-test-id
```

---

## 6c — Serialize custom FBL flow into realm-export.json

### Current state

`keycloak/apply-fbl-customization.sh` re-applies three DISABLED
requirements (`Review Profile`, `Confirm link existing account`,
`Account verification options`) on Keycloak's built-in
`first broker login` flow after every `down -v && up`. The
customization lives in the DB but isn't reproducible from
realm-export.json alone.

### Implementation

Create a **custom alias** flow `p1-first-broker-login` that doesn't
collide with the built-in:

1. Pull the full flow tree from the live realm:
   ```bash
   ./keycloak/dump-fbl-flows.sh > /tmp/p1-fbl-flows.json
   ```
   New helper script (one-off) that walks `first broker login` +
   sub-flows + executions and serializes to a single JSON array
   matching Keycloak's `authenticationFlows` schema.

2. Hand-edit the dump to:
   - Prefix every alias with `p1-` (`p1-first-broker-login`,
     `p1-fbl-user-creation-or-linking`, `p1-fbl-handle-existing`,
     etc.) so the import doesn't collide with Keycloak's own bootstrap
     of the built-ins.
   - Set the three target executions' `requirement` to `DISABLED`.

3. Merge into `keycloak/realm-export.json` under
   `authenticationFlows`.

4. Update the p1 IdP config in `realm-export.json`:
   ```json
   "firstBrokerLoginFlowAlias": "p1-first-broker-login"
   ```

5. Patch the live realm via admin API to use the new flow (so the
   running stack tracks the JSON).

6. Retire `keycloak/apply-fbl-customization.sh` — leave the file with
   a top-of-file note pointing to the realm-export approach, keep it
   in tree for one cycle as fallback, then delete in a follow-up.

### Verification

```bash
# 1. Wipe DB and re-import.
./start.sh  # or docker compose down -v && up

# 2. Confirm the custom flow exists.
TOKEN=$(./scripts/kc-admin-token.sh)
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8898/admin/realms/demo-realm/authentication/flows \
  | python3 -c 'import json,sys; print([f["alias"] for f in json.load(sys.stdin) if f["alias"].startswith("p1-")])'
# expect: ['p1-first-broker-login', 'p1-fbl-user-creation-or-linking', ...]

# 3. Confirm IdP is wired to the custom flow.
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8898/admin/realms/demo-realm/identity-provider/instances/p1 \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["firstBrokerLoginFlowAlias"])'
# expect: p1-first-broker-login

# 4. Run E2E smoke (manual P1 login → sidebar Demo MFE-BFF):
# Should land in keycloak-demo shell without any confirmation prompts.
# If the flow custom-alias is wrong, the prompts come back.
```

---

## 6d — TLS topology + secrets-manager interface

Both items need infra that isn't available in the dev box; ship as a
design + interface contract instead so ops can implement.

### TLS topology

Three legs need TLS:

| Leg | Today | Production |
|---|---|---|
| Browser ↔ React shell (`:5173`) | HTTP | HTTPS via reverse proxy in front of Vite/nginx |
| Browser ↔ Keycloak (`:8898`) | HTTP | HTTPS via Keycloak's own HTTPS port + reverse proxy |
| Browser ↔ P1 (`:8080`) | HTTP | HTTPS via Tomcat connector or reverse proxy in front |
| Keycloak ↔ P1 back-channel (broker SLO) | HTTP | HTTPS; Keycloak must trust the P1 cert chain |

Action: write `docs/PROD-TLS-TOPOLOGY.md` describing the listener + cert
strategy. No code change.

### Secrets-manager keystore mounting

Today: `IdpKeyStore` reads `P1_IDP_KEYSTORE_PATH` etc. from env vars
and loads a PKCS#12 file off the filesystem.

Production swap: the **interface stays the same** (env vars + a
PKCS#12 file at the path) but the *provisioning* changes:

1. Bamboo deploy task mounts the keystore from Vault/AWS Secrets
   Manager into `/etc/p1-idp/keystore.p12`.
2. `setenv.sh` sources the env from a sourceable secret-vendor file
   (`/etc/p1-idp/env.sh`).
3. Rotation = re-run the Bamboo deploy with a new keystore version +
   `tomcat restart` + patch the realm-export cert via admin API.

Action: write `docs/PROD-KEYSTORE-PROVISIONING.md` describing this.
Add a placeholder Bamboo job spec (YAML or `.yaml.j2`) under
`infra/bamboo/p1-idp-keystore-deploy.yml`. No live infra integration.

### Verification

Documentation review — no automated check.

---

## 6e — JUnit E2E suite (Phase 1 scaffold)

### Current state

No automated tests on the SAML IdP side. The whitelabel track ships
`com.geowealth.poc.whitelabel.*` JUnit 5 tests that hit Tomcat over
HTTP; we mirror that pattern.

### Implementation

`geowealth/src/test/java/com/geowealth/saml/idp/SamlIdpEndpointsIT.java`:

```java
@TestInstance(Lifecycle.PER_CLASS)
class SamlIdpEndpointsIT {

    @Test
    void metadataIsValidEntityDescriptorWithMatchingCert() throws Exception {
        // GET http://localhost:8080/saml/idp/metadata.do
        // Assert: 200, content-type application/samlmetadata+xml
        // Parse the XML, assert <md:EntityDescriptor> root
        // Assert: the X509Certificate matches IdpKeyStore.getCertificateBase64()
    }

    @Test
    void sloEmitsSignedLogoutResponseWithInResponseTo() throws Exception {
        // Build a syntactic <samlp:LogoutRequest> with ID="_test-...", base64-encode
        // POST to /saml/idp/slo.do
        // Assert: 200, HTML body contains <form action="..." method="post">
        // Decode the SAMLResponse base64
        // Parse as LogoutResponse, assert InResponseTo matches ID, Status=SUCCESS
        // Assert: signature verifies against IdpKeyStore.getCertificate()
    }

    @Test
    void sloRejectsReplayedRequest() throws Exception {
        // Wired in after 6b lands. POST same SAMLRequest twice, assert second is 409.
    }
}
```

### Verification

```bash
cd ~/geowealth && ./gradlew test --tests SamlIdpEndpointsIT
# expect: BUILD SUCCESSFUL, 2-3 tests passing
```

The suite assumes Tomcat is running on `localhost:8080` with the
P1_IDP_* env vars set. Same precondition as the whitelabel suite.

---

## Sequencing

Recommended order:

1. **6a** (sig validation) — highest user-visible risk, smallest blast.
2. **6b** (replay) — same code paths, builds on 6a.
3. **6c** (flow serialization) — independent, can ship in parallel.
4. **6e** (test scaffold) — once 6a+6b land, codifies regressions.
5. **6d** (TLS + secrets docs) — ships independently as documentation.

## Verification matrix

| Phase | Local smoke | Audit log line to expect | Pass condition |
|---|---|---|---|
| 6a unsigned | `curl -X POST /saml/idp/slo.do -d 'SAMLRequest=...'` | `SAML_LOGOUT_UNSIGNED` | HTTP 403 |
| 6a bad sig | post LogoutRequest signed with wrong key | `SAML_LOGOUT_SIG_REJECT` | HTTP 403 |
| 6a good sig | real Keycloak LogoutRequest | `SAML_LOGOUT user=...` | HTTP 200, valid Response |
| 6b first hit | unique SAMLRequest ID | `SAML_LOGOUT` | HTTP 200 |
| 6b replay | same ID second time | `SAML_REPLAY_REJECT` | HTTP 409 |
| 6c fresh import | `down -v && up` only | (any successful broker login) | Demo MFE-BFF lands in shell without prompts, no script run |
| 6e | `./gradlew test --tests SamlIdpEndpointsIT` | (none) | BUILD SUCCESSFUL |

## What 6d cannot verify locally

The TLS + secrets-manager work needs:
- A real Bamboo (or analogous) deploy pipeline that can mount secret
  files into the Tomcat container.
- A reverse proxy (nginx / Caddy / cloud LB) terminating HTTPS in
  front of all three listeners (P1 Tomcat, Keycloak, shell).
- DNS + cert provisioning for the listener hostnames.

Verification on those is operational — review the docs as part of
the production rollout runbook.
