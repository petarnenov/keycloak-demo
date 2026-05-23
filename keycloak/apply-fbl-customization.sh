#!/usr/bin/env bash
# DEPRECATED as of SSO Phase 6c (2026-05-23).
#
# The silent first-broker-login behavior this script applied at runtime
# is now baked into keycloak/realm-export.json as the custom flow
# "p1-first-broker-login" (with its six nested sub-flows + two
# authenticatorConfig entries), and the p1 IdP config's
# firstBrokerLoginFlowAlias points at it. A fresh `down -v && up`
# imports the realm-export and the custom flow comes up correctly with
# Review Profile / Confirm Link / Account Verification = DISABLED,
# with no runtime patching needed.
#
# Kept in tree for one release cycle as a fallback in case the import
# path regresses. To verify the import is healthy:
#
#   curl -s -H "Authorization: Bearer $TOKEN" \
#     http://localhost:8898/admin/realms/demo-realm/authentication/flows/p1-first-broker-login/executions \
#     | python3 -c 'import json,sys; e=json.load(sys.stdin); print(*[(x["displayName"],x["requirement"]) for x in e if x["displayName"] in ("Review Profile","Confirm link existing account","Account verification options")],sep="\n")'
#
# Expect all three to read DISABLED. If they read REQUIRED, run this
# script to repair without re-importing the realm.
#
# Background (kept verbatim from the original docstring): The default
# Keycloak first-broker-login flow is REQUIRED on Review Profile,
# Confirm Link Existing Account, and Account Verification  which
# together break the silent SSO contract.

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
