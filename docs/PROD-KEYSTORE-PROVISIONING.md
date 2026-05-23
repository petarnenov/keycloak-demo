# Production keystore provisioning — P1 SAML IdP signing key

Scope: how `P1_IDP_KEYSTORE_PATH` / `_PASSWORD` / `_ENTITY` reach the
Tomcat JVM in production without committing the keystore to source
control and with a working rotation pipeline.

## Interface contract (do not change)

`IdpKeyStore` reads three env vars at JVM startup:

| Env var | Meaning |
|---|---|
| `P1_IDP_KEYSTORE_PATH` | Absolute path to a PKCS#12 keystore file on the Tomcat container's filesystem |
| `P1_IDP_KEYSTORE_PASSWORD` | Keystore + entry password (same value) |
| `P1_IDP_KEYSTORE_ENTITY` | Alias of the key entry inside the keystore |

The same contract applies to `P1_IDP_KC_SP_CERT` (Phase 6a, the
Keycloak SP cert for inbound LogoutRequest validation) — a single
base64-encoded X509 cert string.

Code does not care where the values come from. The provisioning
pipeline is replaceable as long as it lands these three values in
the Tomcat process environment.

## Reference provisioning — Bamboo + Vault

```
        ┌─────────────────────┐         ┌───────────────────────┐
        │  Vault              │         │  Tomcat container     │
        │                     │         │                       │
        │  secret/p1-idp/     │         │  /etc/p1-idp/         │
        │    keystore.p12     │ ──────► │    keystore.p12       │
        │    password         │ ──────► │    env.sh             │
        │    entity-alias     │         │                       │
        │    kc-sp-cert       │         │  $TOMCAT/bin/setenv.sh│
        └─────────────────────┘         │    └─ sources env.sh  │
                                        └───────────────────────┘
```

### Bamboo deploy task (sketch)

```yaml
# infra/bamboo/p1-idp-keystore-deploy.yml
deploy:
  stage: secrets
  before_script:
    - vault login -method=approle role_id="$VAULT_ROLE_ID" secret_id="$VAULT_SECRET_ID"
  script:
    # 1. Pull keystore blob + metadata
    - vault kv get -field=keystore_p12_b64  secret/p1-idp/$ENV | base64 -d > /tmp/keystore.p12
    - PASSWORD=$(vault kv get -field=password    secret/p1-idp/$ENV)
    - ENTITY=$(vault kv get -field=entity-alias  secret/p1-idp/$ENV)
    - KC_CERT=$(vault kv get -field=kc-sp-cert   secret/p1-idp/$ENV)

    # 2. Push into the container (or onto the host if bare-metal)
    - ssh "$TOMCAT_HOST" "sudo mkdir -p /etc/p1-idp && sudo chmod 700 /etc/p1-idp"
    - scp /tmp/keystore.p12 "$TOMCAT_HOST:/etc/p1-idp/keystore.p12"
    - ssh "$TOMCAT_HOST" "sudo chmod 600 /etc/p1-idp/keystore.p12 && sudo chown tomcat: /etc/p1-idp/keystore.p12"

    # 3. Render env.sh atomically
    - |
      ssh "$TOMCAT_HOST" "sudo tee /etc/p1-idp/env.sh.new >/dev/null <<EOF
      export P1_IDP_KEYSTORE_PATH=/etc/p1-idp/keystore.p12
      export P1_IDP_KEYSTORE_PASSWORD=$PASSWORD
      export P1_IDP_KEYSTORE_ENTITY=$ENTITY
      export P1_IDP_KC_SP_CERT=$KC_CERT
      EOF
      sudo chmod 600 /etc/p1-idp/env.sh.new
      sudo mv /etc/p1-idp/env.sh.new /etc/p1-idp/env.sh"

    # 4. Restart Tomcat to pick up the new env
    - ssh "$TOMCAT_HOST" "sudo systemctl restart tomcat9"
```

`setenv.sh` sources `env.sh`:

```bash
# In ~/tools/tomcat9/bin/setenv.sh (production)
[ -f /etc/p1-idp/env.sh ] && . /etc/p1-idp/env.sh
```

Local dev keeps the existing inline `export` lines; the source-when-
present pattern means the same file works in both modes.

## Rotation cadence

| Item | Cadence | Path |
|---|---|---|
| `keystore.p12` | 60 days | Generate new keystore (`sso-dev-keystore.sh` shape), upload to Vault as new version, run the deploy task. |
| `P1_IDP_KC_SP_CERT` | Aligns with Keycloak signing-key rotation (Keycloak's own realm-key UX) | Re-fetch from `/realms/demo-realm/broker/p1/endpoint/descriptor`, upload to Vault, redeploy. |
| Realm-export.json `signingCertificate` | Same day as keystore rotation | PATCH `/admin/realms/demo-realm/identity-provider/instances/p1` with the new cert; commit the updated `realm-export.json` so fresh installs match. |

Rotation is a coordinated three-step:

1. Provision the new keystore via Bamboo (Tomcat starts signing with
   the new key on restart).
2. Update Keycloak's `signingCertificate` config so the realm validates
   the new key.
3. Commit the `realm-export.json` change so the rebuild matches the
   live realm.

Between steps 1 and 2 there is a window where Keycloak still trusts
the old cert; brief overlap is fine because the metadata endpoint
(`/saml/idp/metadata.do`) always advertises whichever key Tomcat
currently holds.

## Pre-deploy checklist

- [ ] PKCS#12 file generated with at least 2048-bit RSA, 1-year validity.
- [ ] Entry alias matches `P1_IDP_KEYSTORE_ENTITY` value in Vault.
- [ ] Password is unique per environment (no copy from dev keystore).
- [ ] Keycloak SP cert exported and uploaded to Vault under
      `kc-sp-cert` (same path).
- [ ] Bamboo deploy task tested in staging end-to-end.
- [ ] Rollback plan: re-upload the previous version of the keystore
      to Vault + redeploy.

## What this does NOT cover

- Hardware-backed key storage (HSM, KMS). The PKCS#12 file path is
  the simplest viable contract; if security review demands HSM,
  swap `IdpKeyStore` for an `IdpKmsSigner` backed by AWS KMS
  `SigningClient` and update the env-var contract accordingly.
- Hot rotation without restart. The current contract requires a
  Tomcat restart on key change; a watcher that reloads the keystore
  in-process is doable but out of scope.
