#!/usr/bin/env bash
# Reconcile the demo-realm's environment-specific URLs from a per-env config file.
#
# Keycloak realm config is DATA in Postgres; `--import-realm` is IGNORE_EXISTING,
# so realm-export.json only seeds an empty DB and env vars never re-drive a running
# realm. This script is the env-driven path: it reads k8s/env/urls.<env>.env and
# PATCHes the live realm via the admin API — demo-shared-client and p1-client
# redirect URIs / web origins / post-logout / back-channel URLs.
#
# Auth extraction note: P1 is no longer a SAML IdP. The realm has no `p1`
# identityProvider and no p1-self-client. P1 is a regular OIDC RP via
# `p1-client`; user-service (User Storage SPI) replaces the SAML federation.
#
# Idempotent — safe to re-run; up.sh runs it after bring-up.
#
# Usage:
#   ./scripts/reconcile-realm.sh                      # defaults to urls.dev.env
#   ./scripts/reconcile-realm.sh k8s/env/urls.prod.env
#   KC_ADMIN_PASSWORD=… ./scripts/reconcile-realm.sh k8s/env/urls.qa.env

set -euo pipefail

ENV_FILE="${1:-${RECONCILE_ENV_FILE:-k8s/env/urls.dev.env}}"
[ -f "$ENV_FILE" ] || { echo "reconcile-realm: env file not found: $ENV_FILE" >&2; exit 1; }
while IFS='=' read -r _key _val; do
  [[ "$_key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
  [[ -n "${!_key+x}" ]] && continue
  export "$_key=$_val"
done < "$ENV_FILE"

KC_ADMIN_USER="${KC_ADMIN_USER:-admin}"
KC_ADMIN_PASSWORD="${KC_ADMIN_PASSWORD:-admin}"
: "${KC_ADMIN_BASE:?missing in env file}" "${KC_REALM:?}" \
  "${SPA_HOSTS:?}" "${P1_CALLBACK_HOSTS:?}" "${TH_BACKCHANNEL_URL:?}"

step() { printf '\n==> %s\n' "$*"; }
api()  { curl -sk -H "Authorization: Bearer $TOKEN" "$@"; }

step "Reconciling realm '$KC_REALM' from $ENV_FILE"

TOKEN="$(curl -sk -d "client_id=admin-cli&grant_type=password&username=${KC_ADMIN_USER}&password=${KC_ADMIN_PASSWORD}" \
  "${KC_ADMIN_BASE}/realms/master/protocol/openid-connect/token" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')"
[ -n "$TOKEN" ] || { echo "reconcile-realm: failed to get admin token" >&2; exit 1; }

patch_client() {
  local client_id="$1" transform="$2"
  local uuid
  uuid="$(api "${KC_ADMIN_BASE}/admin/realms/${KC_REALM}/clients?clientId=${client_id}" \
    | python3 -c 'import sys,json; a=json.load(sys.stdin); print(a[0]["id"] if a else "")')"
  if [ -z "$uuid" ]; then echo "  (client $client_id absent — skipping)"; return 0; fi
  api "${KC_ADMIN_BASE}/admin/realms/${KC_REALM}/clients/${uuid}" \
    | python3 -c "$transform" > "/tmp/reconcile-${client_id}.json"
  api -X PUT -H "Content-Type: application/json" --data "@/tmp/reconcile-${client_id}.json" \
    "${KC_ADMIN_BASE}/admin/realms/${KC_REALM}/clients/${uuid}" \
    -o /dev/null -w "  PUT ${client_id} -> %{http_code}\n"
}

# --- demo-shared-client: the multi-tenant SPA login client ------------------
step "demo-shared-client: SPA hosts = ${SPA_HOSTS}"
patch_client demo-shared-client '
import sys, json, os
c = json.load(sys.stdin)
hosts = os.environ["SPA_HOSTS"].split()
redirects = []
for h in hosts:
    redirects += [h, h + "/", h + "/*"]
c["redirectUris"] = redirects
c["webOrigins"] = hosts
c.setdefault("attributes", {})
c["attributes"]["post.logout.redirect.uris"] = "##".join(h + "/*" for h in hosts)
c["attributes"]["backchannel.logout.url"] = os.environ["TH_BACKCHANNEL_URL"]
json.dump(c, sys.stdout)'

# --- p1-client: P1 as a regular OIDC RP -------------------------------------
# Callback at /oidc/callback (the new OidcCallbackServlet, NOT the old SAML path).
# Back-channel: KC -> P1 in-cluster reaches the OidcBackChannelLogoutServlet.
step "p1-client: P1 hosts = ${P1_CALLBACK_HOSTS}"
patch_client p1-client '
import sys, json, os
c = json.load(sys.stdin)
hosts = os.environ["P1_CALLBACK_HOSTS"].split()
c["redirectUris"] = [h + "/oidc/callback" for h in hosts]
c["webOrigins"] = hosts
c.setdefault("attributes", {})
c["attributes"]["post.logout.redirect.uris"] = "##".join(h + "/*" for h in hosts)
# In-cluster back-channel target: prefer P1_INCLUSTER_BASE if set, else use
# the in-cluster Service DNS the realm-export carries (p1-tomcat:8080).
incluster = os.environ.get("P1_INCLUSTER_BASE", "http://p1-tomcat.geowealth-demo.svc.cluster.local:8080")
c["attributes"]["backchannel.logout.url"] = incluster + "/oidc/back-channel-logout"
json.dump(c, sys.stdout)'

# Vestigial demo-billing-client / demo-trading-client intentionally not reconciled.

step "Realm reconcile complete."
