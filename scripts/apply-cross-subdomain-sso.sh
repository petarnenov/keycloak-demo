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

# Delete an IdP mapper by name if present (used to retire the old per-slug
# tenant-identity_<slug> / roles_<slug> mappers — the model is now subdomain-agnostic).
delete_idp_mapper() {
  local name="$1" existing_id
  existing_id="$(curl -sk -H "Authorization: Bearer $TOKEN" \
    "${KC_BASE}/admin/realms/${KC_REALM}/identity-provider/instances/${KC_IDP_ALIAS}/mappers" \
    | python3 -c "
import sys, json
ms = json.load(sys.stdin)
match = [m for m in ms if m.get('name') == '$name']
print(match[0]['id'] if match else '')
")"
  if [[ -n "$existing_id" ]]; then
    echo "  DEL  IdP mapper '$name' (id=$existing_id)"
    curl -sk -X DELETE -H "Authorization: Bearer $TOKEN" \
      "${KC_BASE}/admin/realms/${KC_REALM}/identity-provider/instances/${KC_IDP_ALIAS}/mappers/${existing_id}" \
      -w '  HTTP %{http_code}\n' -o /dev/null
  fi
}

step "2) Upsert subdomain-agnostic SAML→KC user-attribute mappers on IdP $KC_IDP_ALIAS"
upsert_idp_mapper "person-id-from-saml"   "personId"     "personId"
# memberships: multi-valued "<firmCd>:<ldapUid>" — the firms the person has an
# account in. Subdomain-agnostic; each BFF reads it to authorize access.
upsert_idp_mapper "memberships-from-saml" "memberships"  "memberships"
# login-roles: the login account's real ENTITY_ROLE_TBL role names (+ gwAdmin),
# surfaced as the `roles` claim for display + the gwAdmin Tier23Gate bypass.
upsert_idp_mapper "login-roles-from-saml" "roles"        "loginRoles"
# Retire the old per-slug mappers (model is no longer subdomain↔firm bound).
delete_idp_mapper "tenant-identity-billing-from-saml"
delete_idp_mapper "tenant-identity-trading-from-saml"
delete_idp_mapper "roles-billing-from-saml"
delete_idp_mapper "roles-trading-from-saml"

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

# Subdomain-agnostic: every client emits the same `memberships` claim (the
# firms the person has an account in). Multi-valued "<firmCd>:<ldapUid>".
memberships_payload() {
  python3 -c "
import json
print(json.dumps({
  'name': 'memberships-claim',
  'protocol': 'openid-connect',
  'protocolMapper': 'oidc-usermodel-attribute-mapper',
  'config': {
    'user.attribute': 'memberships',
    'claim.name': 'memberships',
    'jsonType.label': 'String',
    'multivalued': 'true',
    'userinfo.token.claim': 'true',
    'id.token.claim': 'true',
    'access.token.claim': 'true'
  }
}))
"
}

# The login account's real role names (+ gwAdmin), surfaced as the `roles`
# claim. Read from the loginRoles user attribute (populated in step 2).
login_roles_payload() {
  python3 -c "
import json
print(json.dumps({
  'name': 'roles-claim',
  'protocol': 'openid-connect',
  'protocolMapper': 'oidc-usermodel-attribute-mapper',
  'config': {
    'user.attribute': 'loginRoles',
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

# DELETE a client protocol mapper by name if present (retires the old
# per-subdomain tenant-identity-claim / active-tenant-claim / roles-tenant-scoped).
delete_client_mapper() {
  local client_pk="$1" name="$2" existing_id
  existing_id="$(curl -sk -H "Authorization: Bearer $TOKEN" \
    "${KC_BASE}/admin/realms/${KC_REALM}/clients/${client_pk}/protocol-mappers/models" \
    | python3 -c "
import sys, json
ms = json.load(sys.stdin)
match = [m for m in ms if m.get('name') == '$name']
print(match[0]['id'] if match else '')
")"
  if [[ -n "$existing_id" ]]; then
    echo "    DEL  client mapper '$name' (id=$existing_id)"
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
for slug in billing trading; do
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
for slug in billing trading; do
  client_id="demo-${slug}-client"
  pk="$(client_id_for "$client_id")"
  if [[ -z "$pk" ]]; then
    echo "  WARN: $client_id not found in realm $KC_REALM — skipping"
    continue
  fi
  echo "  client $client_id (id=$pk)"
  # Identical for every subdomain — Keycloak maps nothing to a subdomain.
  upsert_client_mapper "$pk" "person-id-claim"   "$(person_id_payload)"
  upsert_client_mapper "$pk" "memberships-claim" "$(memberships_payload)"
  upsert_client_mapper "$pk" "roles-claim"       "$(login_roles_payload)"
  # Retire the old per-subdomain mappers.
  delete_client_mapper "$pk" "tenant-identity-claim"
  delete_client_mapper "$pk" "active-tenant-claim"
  delete_client_mapper "$pk" "roles-tenant-scoped"
  delete_client_mapper "$pk" "realm-roles-flat"
done

step "5) Done. Re-login on any subdomain to mint a fresh token with the new claims."
echo "      You can inspect a token's claims via the BFF: curl -sk --cookie 'BSESSION=…' https://billing.geowealth.int:5184/auth/me"
