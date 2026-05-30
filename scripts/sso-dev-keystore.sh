#!/usr/bin/env bash
# Regenerate the P1 IdP SAML signing keystore AND push the new public cert to
# the live Keycloak `p1` IdP config in one shot. The canonical recovery for the
# `SAML emission failed` error caused by a missing /tmp/p1-idp-dev.p12 (macOS
# wipes /tmp at reboot) — see CLAUDE.md "P1 SAML federation flow" + the
# `IdpKeyStore` Javadoc in the geowealth repo.
#
# Usage:
#   ./scripts/sso-dev-keystore.sh                 # use defaults below
#   KS_PATH=/some/where ./scripts/sso-dev-keystore.sh
#
# What it does:
#   1. Generates a fresh PKCS#12 keystore at $KS_PATH (alias $KS_ALIAS,
#      password $KS_PASSWORD, RSA 2048, validity $KS_VALIDITY_DAYS).
#   2. Extracts the public X.509 cert as base64 DER (no PEM armoring).
#   3. PATCHes the live realm's `p1` IdP `signingCertificate` via the admin API
#      so Keycloak verifies P1's SAML Responses with the new public key.
#   4. Backs up the previous IdP config to /tmp/kc-p1-idp.before-rotation.json
#      so you can roll back with a single PUT if something goes wrong.
#
# It does NOT restart Tomcat. P1's `AbstractSamlAuthenticationResponseBuilder`
# reads the keystore from disk per-request, so the next SAML emission picks up
# the new file automatically. Tomcat's `P1_IDP_KEYSTORE_PATH` env var must
# already point at $KS_PATH.

set -euo pipefail

KS_PATH="${KS_PATH:-/tmp/p1-idp-dev.p12}"
KS_ALIAS="${KS_ALIAS:-p1-idp}"
KS_PASSWORD="${KS_PASSWORD:-changeit}"
KS_VALIDITY_DAYS="${KS_VALIDITY_DAYS:-1825}"
KS_DNAME="${KS_DNAME:-CN=p1-idp-dev,O=GeoWealth,C=US}"

KC_BASE="${KC_BASE:-https://auth.geowealth.int:5180}"
KC_REALM="${KC_REALM:-demo-realm}"
KC_IDP_ALIAS="${KC_IDP_ALIAS:-p1}"
KC_ADMIN_USER="${KC_ADMIN_USER:-admin}"
KC_ADMIN_PASSWORD="${KC_ADMIN_PASSWORD:-admin}"

step() { printf '\n==> %s\n' "$*"; }

step "1) Generate PKCS#12 keystore at $KS_PATH (alias=$KS_ALIAS, validity=${KS_VALIDITY_DAYS}d)"
rm -f "$KS_PATH"
keytool -genkeypair -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 \
  -validity "$KS_VALIDITY_DAYS" -storetype PKCS12 \
  -keystore "$KS_PATH" -storepass "$KS_PASSWORD" -keypass "$KS_PASSWORD" \
  -dname "$KS_DNAME"
ls -la "$KS_PATH"

step "2) Export the public cert (DER) → base64"
CERT_DER="$(mktemp)"
keytool -exportcert -alias "$KS_ALIAS" -keystore "$KS_PATH" -storepass "$KS_PASSWORD" -file "$CERT_DER" > /dev/null
CERT_B64="$(openssl x509 -inform DER -in "$CERT_DER" -outform DER | base64 | tr -d '\n')"
rm -f "$CERT_DER"
echo "  cert b64 length: ${#CERT_B64}"

step "3) Get a Keycloak admin token"
TOKEN="$(curl -sk -d "client_id=admin-cli&grant_type=password&username=${KC_ADMIN_USER}&password=${KC_ADMIN_PASSWORD}" \
  "${KC_BASE}/realms/master/protocol/openid-connect/token" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')"
echo "  token: ${#TOKEN} chars"

step "4) Backup current p1 IdP config → /tmp/kc-p1-idp.before-rotation.json"
curl -sk -H "Authorization: Bearer $TOKEN" \
  "${KC_BASE}/admin/realms/${KC_REALM}/identity-provider/instances/${KC_IDP_ALIAS}" \
  > /tmp/kc-p1-idp.before-rotation.json
echo "  backup: $(wc -c < /tmp/kc-p1-idp.before-rotation.json) bytes"

step "5) PATCH signingCertificate on the live realm"
PAYLOAD="$(mktemp)"
CERT_B64="$CERT_B64" python3 -c "
import json, os
d = json.load(open('/tmp/kc-p1-idp.before-rotation.json'))
d['config']['signingCertificate'] = os.environ['CERT_B64']
open('$PAYLOAD','w').write(json.dumps(d))
"
HTTP="$(curl -sk -X PUT \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data-binary "@$PAYLOAD" \
  -w '%{http_code}' -o /dev/null \
  "${KC_BASE}/admin/realms/${KC_REALM}/identity-provider/instances/${KC_IDP_ALIAS}")"
rm -f "$PAYLOAD"
echo "  PUT HTTP $HTTP"
[ "$HTTP" = "204" ] || { echo "ERROR: PATCH failed"; exit 1; }

step "6) Verify Keycloak sees the new cert"
curl -sk -H "Authorization: Bearer $TOKEN" \
  "${KC_BASE}/admin/realms/${KC_REALM}/identity-provider/instances/${KC_IDP_ALIAS}" \
  | python3 -c "
import sys, json, hashlib, base64
v = json.load(sys.stdin)['config']['signingCertificate']
print('  KC live cert sha256[:32] =', hashlib.sha256(base64.b64decode(v)).hexdigest()[:32])
"

cat <<EOF

==> Done. The next P1→Demo* login should succeed end-to-end.
    If it does not, you can roll back the realm config with:

      TOKEN=\$(curl -sk -d "client_id=admin-cli&grant_type=password&username=${KC_ADMIN_USER}&password=${KC_ADMIN_PASSWORD}" \\
        "${KC_BASE}/realms/master/protocol/openid-connect/token" \\
        | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')
      curl -sk -X PUT -H "Authorization: Bearer \$TOKEN" -H 'Content-Type: application/json' \\
        --data-binary @/tmp/kc-p1-idp.before-rotation.json \\
        "${KC_BASE}/admin/realms/${KC_REALM}/identity-provider/instances/${KC_IDP_ALIAS}"

    Tomcat env vars expected:
      P1_IDP_KEYSTORE_PATH=${KS_PATH}
      P1_IDP_KEYSTORE_PASSWORD=${KS_PASSWORD}
      P1_IDP_KEYSTORE_ENTITY=${KS_ALIAS}
EOF
