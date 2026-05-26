#!/usr/bin/env bash
#
# Strip Keycloak's auto-added defaults from `default-roles-demo-realm`.
# Keycloak adds `offline_access` and `uma_authorization` as composites of
# the realm's default-role on first realm creation, regardless of what
# `realm-export.json` declares. They then leak into every federated
# user and surface in the OIDC `roles` claim via the custom
# `realm-roles-flat` mapper on demo-billing-client / demo-trading-client.
#
# Run after `docker compose up` on a fresh DB volume. Idempotent: a
# second run is a no-op (HTTP 204 either way, since the composites are
# already absent).
#
# Requires KC_ADMIN_USER / KC_ADMIN_PASS env vars — refusing to default
# to admin/admin so a misconfigured run can't leak credentials in
# process listings.

set -euo pipefail

KC_BASE_URL="${KC_BASE_URL:-https://auth.geowealth.int:5180}"
KC_REALM="${KC_REALM:-demo-realm}"
DEFAULT_ROLE="${DEFAULT_ROLE:-default-roles-${KC_REALM}}"

: "${KC_ADMIN_USER:?KC_ADMIN_USER not set}"
: "${KC_ADMIN_PASS:?KC_ADMIN_PASS not set}"

log() { echo "[$(date '+%H:%M:%S')] $*"; }
die() { echo "[FATAL] $*" >&2; exit 1; }

log "Authenticating against KC admin ($KC_BASE_URL)"
token="$(curl -sf -k --max-time 10 \
    -X POST "$KC_BASE_URL/realms/master/protocol/openid-connect/token" \
    -d "client_id=admin-cli&grant_type=password&username=$KC_ADMIN_USER&password=$KC_ADMIN_PASS" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')" \
    || die "Cannot mint admin token — is KC up at $KC_BASE_URL?"

log "Reading current composites of $DEFAULT_ROLE"
composites="$(curl -sf -k --max-time 10 \
    -H "Authorization: Bearer $token" \
    "$KC_BASE_URL/admin/realms/$KC_REALM/roles/$DEFAULT_ROLE/composites")" \
    || die "Cannot read composites of $DEFAULT_ROLE in $KC_REALM"

# Filter to the two realm roles we want gone. Pass an array of {id,name,...}
# matching exactly what Keycloak emitted, so the DELETE matches.
payload="$(echo "$composites" | python3 -c '
import json, sys
all_comp = json.load(sys.stdin)
targets = {"offline_access", "uma_authorization"}
to_remove = [c for c in all_comp if not c["clientRole"] and c["name"] in targets]
print(json.dumps(to_remove))
')"

count="$(echo "$payload" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')"

if [ "$count" = "0" ]; then
    log "Already trimmed — no offline_access / uma_authorization in composite. No-op."
    exit 0
fi

log "Removing $count composite(s) from $DEFAULT_ROLE"
http_code="$(curl -sk -o /dev/null -w '%{http_code}' --max-time 10 \
    -X DELETE \
    -H "Authorization: Bearer $token" \
    -H "Content-Type: application/json" \
    --data "$payload" \
    "$KC_BASE_URL/admin/realms/$KC_REALM/roles/$DEFAULT_ROLE/composites")"

if [ "$http_code" != "204" ]; then
    die "DELETE composites returned HTTP $http_code (expected 204)"
fi

log "Done. Verify with: kcadm get roles/$DEFAULT_ROLE/composites -r $KC_REALM"
