#!/usr/bin/env bash
# Apply demo-realm "first broker login" customization for the P1 SAML IdP.
#
# The default Keycloak flow is REQUIRED on Review Profile, Confirm Link
# Existing Account, and Account Verification — which together break the
# silent SSO contract (the user sees confirmation prompts that defeat
# the point of brokered auth). We disable those three steps so that
# federated P1 logins resolve to the existing Keycloak user (or
# auto-create) without intermediate forms.
#
# Run this after every `down -v && up` (fresh import) until the
# customization is serialized into realm-export.json proper. See
# SSO-MIGRATION-PLAN.md § Phase 4 and WIP-SSO.md.

set -euo pipefail

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8898}"
REALM="${REALM:-demo-realm}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-admin}"

echo "Targeting $KEYCLOAK_URL realm=$REALM"

TOKEN=$(curl -s -X POST "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" \
  -d "client_id=admin-cli&grant_type=password&username=$ADMIN_USER&password=$ADMIN_PASS" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')

if [ -z "$TOKEN" ]; then
  echo "Failed to obtain admin token" >&2
  exit 1
fi

curl -s -H "Authorization: Bearer $TOKEN" \
  "$KEYCLOAK_URL/admin/realms/$REALM/authentication/flows/first%20broker%20login/executions" > /tmp/fbl-execs.json

TARGETS='Review Profile|Confirm link existing account|Account verification options'

python3 <<PY
import json, re, os, urllib.request
execs = json.load(open('/tmp/fbl-execs.json'))
targets = set("$TARGETS".split("|"))
to_disable = [x for x in execs if x.get('displayName') in targets]
print(f"to disable: {[x['displayName'] for x in to_disable]}")
for x in to_disable:
    x['requirement'] = 'DISABLED'
    with open(f"/tmp/fbl-{x['id']}.json","w") as f:
        json.dump(x, f)
PY

for f in /tmp/fbl-*.json; do
  [ "$f" = /tmp/fbl-execs.json ] && continue
  curl -s -o /dev/null -w "  %{http_code} $f\n" \
    -X PUT -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    --data @"$f" \
    "$KEYCLOAK_URL/admin/realms/$REALM/authentication/flows/first%20broker%20login/executions"
done

echo "--- verify ---"
curl -s -H "Authorization: Bearer $TOKEN" \
  "$KEYCLOAK_URL/admin/realms/$REALM/authentication/flows/first%20broker%20login/executions" \
  | python3 -c '
import json,sys
e = json.load(sys.stdin)
targets = {"Review Profile","Confirm link existing account","Account verification options"}
for x in e:
    if x.get("displayName") in targets:
        print(f"  {x[\"displayName\"]:35s} {x[\"requirement\"]}")
'
