#!/usr/bin/env bash
# Apply the cross-subdomain SSO realm changes (personId, tenant_identity,
# active_tenant claims) to a running Keycloak via admin API. Realm import is
# IGNORE_EXISTING, so realm-export.json edits are only picked up by a fresh
# Postgres — this script lets you roll them out without nuking the volume.
#
# Idempotent: existing mappers with the same name are PUT-updated; missing
# ones are POSTed. Safe to re-run.
#
# Usage:
#   ./scripts/apply-cross-subdomain-sso.sh
#   KC_ADMIN_PASSWORD=… ./scripts/apply-cross-subdomain-sso.sh
#
# Matches the realm-export.json shape — see
# cross-subdomain-sso-implementation.md for the design rationale.

set -euo pipefail

KC_BASE="${KC_BASE:-https://auth.geowealth.int:5180}"
KC_REALM="${KC_REALM:-demo-realm}"
KC_IDP_ALIAS="${KC_IDP_ALIAS:-p1}"
KC_ADMIN_USER="${KC_ADMIN_USER:-admin}"
KC_ADMIN_PASSWORD="${KC_ADMIN_PASSWORD:-admin}"

step() { printf '\n==> %s\n' "$*"; }

step "1) Get a Keycloak admin token"
TOKEN="$(curl -sk -d "client_id=admin-cli&grant_type=password&username=${KC_ADMIN_USER}&password=${KC_ADMIN_PASSWORD}" \
  "${KC_BASE}/realms/master/protocol/openid-connect/token" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')"
echo "  token: ${#TOKEN} chars"

##############################################################################
# IdP-level SAML user-attribute mappers (P1 → KC user attributes).
##############################################################################

upsert_idp_mapper() {
  local name="$1" saml_attr="$2" user_attr="$3"
  local payload
  payload="$(python3 -c "
import json
print(json.dumps({
  'name': '$name',
  'identityProviderAlias': '$KC_IDP_ALIAS',
  'identityProviderMapper': 'saml-user-attribute-idp-mapper',
  'config': {
    'syncMode': 'INHERIT',
    'attribute.name': '$saml_attr',
    'user.attribute': '$user_attr'
  }
}))
")"
  # Try to find existing by name first.
  local existing_id
  existing_id="$(curl -sk -H "Authorization: Bearer $TOKEN" \
    "${KC_BASE}/admin/realms/${KC_REALM}/identity-provider/instances/${KC_IDP_ALIAS}/mappers" \
    | python3 -c "
import sys, json
ms = json.load(sys.stdin)
match = [m for m in ms if m.get('name') == '$name']
print(match[0]['id'] if match else '')
")"
  if [[ -n "$existing_id" ]]; then
    echo "  PUT  IdP mapper '$name' (id=$existing_id)"
    payload_with_id="$(python3 -c "
import json
d = json.loads('''$payload''')
d['id'] = '$existing_id'
print(json.dumps(d))
")"
    curl -sk -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
      --data-binary "$payload_with_id" \
      "${KC_BASE}/admin/realms/${KC_REALM}/identity-provider/instances/${KC_IDP_ALIAS}/mappers/${existing_id}" \
      > /dev/null
  else
    echo "  POST IdP mapper '$name'"
    curl -sk -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
      --data-binary "$payload" \
      "${KC_BASE}/admin/realms/${KC_REALM}/identity-provider/instances/${KC_IDP_ALIAS}/mappers" \
      -w '  HTTP %{http_code}\n' -o /dev/null
  fi
}

step "2) Upsert SAML→KC user-attribute mappers on IdP $KC_IDP_ALIAS"
upsert_idp_mapper "person-id-from-saml"            "personId"             "personId"
upsert_idp_mapper "tenant-identity-billing-from-saml" "tenantIdentity_billing" "tenantIdentity_billing"
upsert_idp_mapper "tenant-identity-trading-from-saml" "tenantIdentity_trading" "tenantIdentity_trading"
upsert_idp_mapper "tenant-identity-users-from-saml"   "tenantIdentity_users"   "tenantIdentity_users"
# Per-tenant role streams (multi-username case, see
# cross-subdomain-sso-multi-username-analysis.md § 3 Gap C). Stored as
# multi-valued KC user attributes; each subdomain's OIDC client reads its
# own slot via the per-client mapper added in step 3.
upsert_idp_mapper "roles-billing-from-saml"        "roles_billing"        "roles_billing"
upsert_idp_mapper "roles-trading-from-saml"        "roles_trading"        "roles_trading"
upsert_idp_mapper "roles-users-from-saml"          "roles_users"          "roles_users"

##############################################################################
# Per-client OIDC protocol mappers (KC user attributes → access/id-token claims).
##############################################################################

# Resolve a client's internal id from its clientId.
client_id_for() {
  curl -sk -H "Authorization: Bearer $TOKEN" \
    "${KC_BASE}/admin/realms/${KC_REALM}/clients?clientId=$1" \
    | python3 -c 'import sys, json; cs = json.load(sys.stdin); print(cs[0]["id"] if cs else "")'
}

upsert_client_mapper() {
  local client_pk="$1" name="$2" payload_json="$3"
  local existing_id
  existing_id="$(curl -sk -H "Authorization: Bearer $TOKEN" \
    "${KC_BASE}/admin/realms/${KC_REALM}/clients/${client_pk}/protocol-mappers/models" \
    | python3 -c "
import sys, json
ms = json.load(sys.stdin)
match = [m for m in ms if m.get('name') == '$name']
print(match[0]['id'] if match else '')
")"
  if [[ -n "$existing_id" ]]; then
    echo "    PUT  client mapper '$name' (id=$existing_id)"
    payload_with_id="$(python3 -c "
import json
d = json.loads('''$payload_json''')
d['id'] = '$existing_id'
print(json.dumps(d))
")"
    curl -sk -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
      --data-binary "$payload_with_id" \
      "${KC_BASE}/admin/realms/${KC_REALM}/clients/${client_pk}/protocol-mappers/models/${existing_id}" \
      > /dev/null
  else
    echo "    POST client mapper '$name'"
    curl -sk -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
      --data-binary "$payload_json" \
      "${KC_BASE}/admin/realms/${KC_REALM}/clients/${client_pk}/protocol-mappers/models" \
      -w '    HTTP %{http_code}\n' -o /dev/null
  fi
}

# Build the per-client payloads inline.
person_id_payload() {
  python3 -c "
import json
print(json.dumps({
  'name': 'person-id-claim',
  'protocol': 'openid-connect',
  'protocolMapper': 'oidc-usermodel-attribute-mapper',
  'config': {
    'user.attribute': 'personId',
    'claim.name': 'personId',
    'jsonType.label': 'String',
    'multivalued': 'false',
    'userinfo.token.claim': 'true',
    'id.token.claim': 'true',
    'access.token.claim': 'true'
  }
}))
"
}

tenant_identity_payload() {
  local tenant_slug="$1"
  python3 -c "
import json
print(json.dumps({
  'name': 'tenant-identity-claim',
  'protocol': 'openid-connect',
  'protocolMapper': 'oidc-usermodel-attribute-mapper',
  'config': {
    'user.attribute': 'tenantIdentity_$tenant_slug',
    'claim.name': 'tenant_identity',
    'jsonType.label': 'String',
    'multivalued': 'false',
    'userinfo.token.claim': 'true',
    'id.token.claim': 'true',
    'access.token.claim': 'true'
  }
}))
"
}

active_tenant_payload() {
  local tenant_slug="$1"
  python3 -c "
import json
print(json.dumps({
  'name': 'active-tenant-claim',
  'protocol': 'openid-connect',
  'protocolMapper': 'oidc-hardcoded-claim-mapper',
  'config': {
    'claim.name': 'active_tenant',
    'claim.value': '$tenant_slug',
    'jsonType.label': 'String',
    'userinfo.token.claim': 'true',
    'id.token.claim': 'true',
    'access.token.claim': 'true'
  }
}))
"
}

# Per-tenant roles claim: read the client's own roles_<tenant> user attribute
# (populated from the matching SAML attribute in step 2) and emit it as the
# multi-valued `roles` claim. Replaces the legacy realm-roles-flat mapper
# that exposed all realm roles to every client.
roles_tenant_scoped_payload() {
  local tenant_slug="$1"
  python3 -c "
import json
print(json.dumps({
  'name': 'roles-tenant-scoped',
  'protocol': 'openid-connect',
  'protocolMapper': 'oidc-usermodel-attribute-mapper',
  'config': {
    'user.attribute': 'roles_$tenant_slug',
    'claim.name': 'roles',
    'jsonType.label': 'String',
    'multivalued': 'true',
    'userinfo.token.claim': 'true',
    'id.token.claim': 'true',
    'access.token.claim': 'true'
  }
}))
"
}

# DELETE the legacy realm-roles-flat mapper from a client if present. The
# tenant-scoped mapper takes over emitting the `roles` claim — leaving both
# in place would shove every realm role into the token alongside the
# tenant-filtered set.
delete_legacy_realm_roles_mapper() {
  local client_pk="$1"
  local existing_id
  existing_id="$(curl -sk -H "Authorization: Bearer $TOKEN" \
    "${KC_BASE}/admin/realms/${KC_REALM}/clients/${client_pk}/protocol-mappers/models" \
    | python3 -c "
import sys, json
ms = json.load(sys.stdin)
match = [m for m in ms if m.get('name') == 'realm-roles-flat']
print(match[0]['id'] if match else '')
")"
  if [[ -n "$existing_id" ]]; then
    echo "    DEL  client mapper 'realm-roles-flat' (id=$existing_id)"
    curl -sk -X DELETE -H "Authorization: Bearer $TOKEN" \
      "${KC_BASE}/admin/realms/${KC_REALM}/clients/${client_pk}/protocol-mappers/models/${existing_id}" \
      -w '    HTTP %{http_code}\n' -o /dev/null
  fi
}

step "3) Disable front-channel logout + ensure backchannel.logout.url is set"
# Keycloak's AuthenticationManager skips the back-channel HTTP POST whenever
# `frontchannelLogout=true` — even if `backchannel.logout.url` is configured —
# and just records "client not logged out" in the warning. RP-initiated logout
# from the BFF is a server-side POST with no browser to render front-channel
# iframes, so the only path that actually invalidates sibling BFFs is
# back-channel. Flip the flag, re-assert the URL (PUT is a full replace, so
# the same call has to carry the existing attributes verbatim — we pipe the
# previous GET straight back through Python via stdin to avoid escaping
# corruption that an inline string interpolation would suffer).
for slug in billing trading users; do
  client_id="demo-${slug}-client"
  pk="$(client_id_for "$client_id")"
  if [[ -z "$pk" ]]; then
    continue
  fi
  export SLUG="$slug"
  curl -sk -H "Authorization: Bearer $TOKEN" \
    "${KC_BASE}/admin/realms/${KC_REALM}/clients/${pk}" \
    | python3 -c "
import json, os, sys
d = json.load(sys.stdin)
d.setdefault('attributes', {})
d['attributes']['backchannel.logout.url'] = 'http://bff-' + os.environ['SLUG'] + ':8080/backchannel-logout'
d['attributes']['backchannel.logout.session.required'] = 'true'
d['attributes']['backchannel.logout.revoke.offline.tokens'] = 'false'
d['frontchannelLogout'] = False
print(json.dumps(d))
" | curl -sk -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
        --data-binary @- \
        "${KC_BASE}/admin/realms/${KC_REALM}/clients/${pk}" \
        -w "  PUT ${client_id} (logout config) → HTTP %{http_code}\n" -o /dev/null
done
unset SLUG

step "4) Upsert per-client protocol mappers for each demo subdomain"
for slug in billing trading users; do
  client_id="demo-${slug}-client"
  pk="$(client_id_for "$client_id")"
  if [[ -z "$pk" ]]; then
    echo "  WARN: $client_id not found in realm $KC_REALM — skipping"
    continue
  fi
  echo "  client $client_id (id=$pk)"
  upsert_client_mapper "$pk" "person-id-claim"       "$(person_id_payload)"
  upsert_client_mapper "$pk" "tenant-identity-claim" "$(tenant_identity_payload "$slug")"
  upsert_client_mapper "$pk" "active-tenant-claim"   "$(active_tenant_payload "$slug")"
  upsert_client_mapper "$pk" "roles-tenant-scoped"   "$(roles_tenant_scoped_payload "$slug")"
  delete_legacy_realm_roles_mapper "$pk"
done

step "5) Done. Re-login on any subdomain to mint a fresh token with the new claims."
echo "      You can inspect a token's claims via the BFF: curl -sk --cookie 'BSESSION=…' https://billing.geowealth.int:5184/auth/me"
