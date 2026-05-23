#!/usr/bin/env bash
# Generate a dev PKCS#12 keystore for the P1 SAML IdP signing key.
# Output goes to /tmp by default — override via $1.
#
# Production: replace this with secrets-manager-backed provisioning;
# the P1_IDP_KEYSTORE_PATH env var still points at the same shape.

set -euo pipefail

KEYSTORE="${1:-/tmp/p1-idp-dev.p12}"
PASSWORD="${P1_IDP_KEYSTORE_PASSWORD:-changeit}"
ALIAS="${P1_IDP_KEYSTORE_ENTITY:-p1-idp}"

if [ -e "$KEYSTORE" ]; then
  echo "Keystore already exists at $KEYSTORE — refusing to overwrite. Delete first if you want fresh keys." >&2
  exit 1
fi

keytool -genkeypair \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 2048 \
  -validity 365 \
  -storetype PKCS12 \
  -keystore "$KEYSTORE" \
  -storepass "$PASSWORD" \
  -dname "CN=p1-idp-dev,O=GeoWealth,L=Atlanta,ST=GA,C=US"

echo
echo "Generated $KEYSTORE"
echo "Add to ~/tools/tomcat9/bin/setenv.sh:"
echo "  export P1_IDP_KEYSTORE_PATH=\"$KEYSTORE\""
echo "  export P1_IDP_KEYSTORE_PASSWORD=\"$PASSWORD\""
echo "  export P1_IDP_KEYSTORE_ENTITY=\"$ALIAS\""
echo
echo "Public cert (base64 — paste into demo-realm Keycloak p1 IdP signingCertificate config):"
keytool -exportcert -alias "$ALIAS" -keystore "$KEYSTORE" -storepass "$PASSWORD" -rfc \
  | grep -v "^-----" | tr -d '\n'
echo
