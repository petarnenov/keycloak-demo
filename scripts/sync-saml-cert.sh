#!/usr/bin/env bash
#
# Sync the realm's view of the P1 SAML IdP signing certificate with the
# certificate P1 actually serves. The bug this prevents:
#
#   1. Tomcat's keystore is regenerated (rotation, dev machine reset,
#      restored from old backup).
#   2. Tomcat now signs SAML messages with a key whose public cert is
#      different from the one baked into `keycloak/realm-export.json`
#      and pushed to the live realm.
#   3. Every subsequent SAML Response (login) and LogoutRequest
#      (logout) fails KC signature validation with
#      `Logout request signature mismatch` / `invalid_signature`.
#
# This script is idempotent: if the realm already has the right cert,
# it exits 0 without touching anything. Run it after any keystore
# change, or wire it into `start.sh` post-up.
#
# Requirements: curl, python3 (used to extract X509Certificate from the
# IdP metadata XML — no XML libraries needed beyond stdlib).
#
# Override defaults via env:
#   KC_BASE_URL    https://auth.geowealth.int:5180
#   KC_REALM       demo-realm
#   KC_ADMIN_USER  admin
#   KC_ADMIN_PASS  admin
#   P1_METADATA_URL  http://localhost:8888/saml/idp/metadata.do
#   IDP_ALIAS      p1

set -euo pipefail

KC_BASE_URL="${KC_BASE_URL:-https://auth.geowealth.int:5180}"
KC_REALM="${KC_REALM:-demo-realm}"
P1_METADATA_URL="${P1_METADATA_URL:-http://localhost:8888/saml/idp/metadata.do}"
IDP_ALIAS="${IDP_ALIAS:-p1}"
BACKUP_DIR="${BACKUP_DIR:-/tmp/sync-saml-cert-backups}"

# KC_ADMIN_USER and KC_ADMIN_PASS must be set explicitly — refusing to
# default to admin/admin so a misconfigured cron-run can't leak the
# default credentials in audit logs or process listings. Source from a
# secret store in prod.
: "${KC_ADMIN_USER:?KC_ADMIN_USER not set — refusing to attempt admin/admin}"
: "${KC_ADMIN_PASS:?KC_ADMIN_PASS not set — refusing to attempt admin/admin}"

log() { echo "[$(date '+%H:%M:%S')] $*"; }
die() { echo "[FATAL] $*" >&2; exit 1; }

# --- 1. Get current cert from P1's metadata endpoint. ---------------
log "Fetching P1 SAML IdP metadata from $P1_METADATA_URL"
tmp_meta="$(mktemp)"
trap 'rm -f "$tmp_meta"' EXIT
curl -sf --max-time 10 "$P1_METADATA_URL" -o "$tmp_meta" \
    || die "P1 metadata endpoint unreachable — is Tomcat up?"

# Extract X509Certificate element, strip whitespace. Python's stdlib
# xml.etree is namespace-aware enough for this. We pass the metadata
# file path via env so stdin stays available to the heredoc'd script.
current_cert="$(METADATA_FILE="$tmp_meta" python3 <<'PY'
import os
import sys
import xml.etree.ElementTree as ET

ns = {
    'md': 'urn:oasis:names:tc:SAML:2.0:metadata',
    'ds': 'http://www.w3.org/2000/09/xmldsig#',
}
with open(os.environ['METADATA_FILE']) as fh:
    root = ET.fromstring(fh.read())
elem = root.find(
    'md:IDPSSODescriptor/'
    'md:KeyDescriptor[@use="signing"]/'
    'ds:KeyInfo/ds:X509Data/ds:X509Certificate',
    ns,
)
if elem is None or not (elem.text or '').strip():
    sys.exit('signing X509Certificate not found in metadata')
print(''.join((elem.text or '').split()))
PY
)" || die "Failed to parse P1 metadata"

if [ -z "$current_cert" ]; then
    die "Parsed cert is empty — refusing to patch realm with garbage"
fi

log "P1 advertises signing cert: ${current_cert:0:40}…${current_cert: -10} (${#current_cert} chars)"

# --- 2. Authenticate against KC admin. ------------------------------
log "Authenticating against KC admin ($KC_BASE_URL)"
admin_token="$(curl -sf -k \
    --max-time 10 \
    -X POST "$KC_BASE_URL/realms/master/protocol/openid-connect/token" \
    -d "client_id=admin-cli&grant_type=password&username=$KC_ADMIN_USER&password=$KC_ADMIN_PASS" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')" \
    || die "Cannot mint admin token — is KC up at $KC_BASE_URL?"

# --- 3. Read current realm IdP config. ------------------------------
log "Reading current realm config for IdP '$IDP_ALIAS'"
tmp_cfg="$(mktemp)"
trap 'rm -f "$tmp_meta" "$tmp_cfg"' EXIT
curl -sf -k \
    --max-time 10 \
    -H "Authorization: Bearer $admin_token" \
    "$KC_BASE_URL/admin/realms/$KC_REALM/identity-provider/instances/$IDP_ALIAS" \
    -o "$tmp_cfg" \
    || die "Cannot read IdP config — alias '$IDP_ALIAS' may not exist"

realm_cert="$(CFG_FILE="$tmp_cfg" python3 <<'PY'
import json, os
with open(os.environ['CFG_FILE']) as fh:
    d = json.load(fh)
print(d.get("config", {}).get("signingCertificate", ""))
PY
)"

# --- 4. Compare. ----------------------------------------------------
if [ "$realm_cert" = "$current_cert" ]; then
    log "Realm already has the right cert — no change."
    exit 0
fi

log "Cert mismatch detected:"
log "  realm has: ${realm_cert:0:40}…${realm_cert: -10:10} (${#realm_cert} chars)"
log "  P1 emits:  ${current_cert:0:40}…${current_cert: -10:10} (${#current_cert} chars)"

# --- 5. Backup current config before mutating. ----------------------
mkdir -p "$BACKUP_DIR"
backup_file="$BACKUP_DIR/$(date '+%Y%m%d-%H%M%S')-$IDP_ALIAS.json"
cp "$tmp_cfg" "$backup_file"
log "Backed up current realm IdP config to $backup_file"

# --- 6. Patch the realm. --------------------------------------------
log "Updating realm IdP '$IDP_ALIAS' with new signing cert"
tmp_new="$(mktemp)"
trap 'rm -f "$tmp_meta" "$tmp_cfg" "$tmp_new"' EXIT
CFG_FILE="$tmp_cfg" NEW_CERT="$current_cert" OUT_FILE="$tmp_new" python3 <<'PY'
import json, os
with open(os.environ['CFG_FILE']) as fh:
    d = json.load(fh)
d.setdefault('config', {})['signingCertificate'] = os.environ['NEW_CERT']
with open(os.environ['OUT_FILE'], 'w') as fh:
    json.dump(d, fh)
PY

if ! curl -sf -k \
        --max-time 10 \
        -X PUT \
        -H "Authorization: Bearer $admin_token" \
        -H "Content-Type: application/json" \
        --data-binary "@$tmp_new" \
        "$KC_BASE_URL/admin/realms/$KC_REALM/identity-provider/instances/$IDP_ALIAS" \
        > /dev/null; then
    echo "[FATAL] PUT to update IdP config failed" >&2
    echo "  Restore with:" >&2
    echo "    curl -sk -X PUT -H 'Authorization: Bearer <admin-token>' -H 'Content-Type: application/json' \\" >&2
    echo "      --data-binary @$backup_file \\" >&2
    echo "      $KC_BASE_URL/admin/realms/$KC_REALM/identity-provider/instances/$IDP_ALIAS" >&2
    exit 1
fi

log "Realm updated. Verify on next login attempt."
log "Backup retained at $backup_file"
