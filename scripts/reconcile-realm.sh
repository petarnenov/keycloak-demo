#!/usr/bin/env bash
# Reconcile the demo-realm's environment-specific URLs from a per-env config file.
#
# Keycloak realm config is DATA in Postgres; `--import-realm` is IGNORE_EXISTING,
# so realm-export.json only seeds an empty DB and env vars never re-drive a running
# realm. This script is the env-driven path: it reads k8s/env/urls.<env>.env and
# PATCHes the live realm via the admin API — the `p1` SAML IdP endpoints and every
# active client's redirectUris / webOrigins / post-logout / back-channel URLs.
#
# Idempotent — safe to re-run; up.sh runs it after bring-up. Fixes BOTH a fresh
# install (whose seed carries only defaults) and any drift from hand-patching.
#
# Usage:
#   ./scripts/reconcile-realm.sh                      # defaults to urls.dev.env
#   ./scripts/reconcile-realm.sh k8s/env/urls.prod.env
#   KC_ADMIN_PASSWORD=… ./scripts/reconcile-realm.sh k8s/env/urls.qa.env

set -euo pipefail

ENV_FILE="${1:-${RECONCILE_ENV_FILE:-k8s/env/urls.dev.env}}"
[ -f "$ENV_FILE" ] || { echo "reconcile-realm: env file not found: $ENV_FILE" >&2; exit 1; }
# Parse KEY=VALUE lines and export them, so the inline python transforms can read
# them. NOT `source`: values are intentionally UNQUOTED (kustomize's
# configMapGenerator reads the same file) and some are space-separated lists,
# which `source` would try to execute. This split-on-first-= loop handles both.
while IFS='=' read -r _key _val; do
  [[ "$_key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue   # skip comments/blanks
  [[ -n "${!_key+x}" ]] && continue   # don't clobber a value already in the env
  export "$_key=$_val"
done < "$ENV_FILE"
# ↑ a caller may pre-set e.g. KC_ADMIN_BASE (up.sh reaches KC via an in-cluster
#   port-forward, not the browser-facing host in the file) — that override wins.

KC_ADMIN_USER="${KC_ADMIN_USER:-admin}"
KC_ADMIN_PASSWORD="${KC_ADMIN_PASSWORD:-admin}"
: "${KC_ADMIN_BASE:?missing in env file}" "${KC_REALM:?}" "${P1_BROWSER_BASE:?}" \
  "${P1_INCLUSTER_BASE:?}" "${SPA_HOSTS:?}" "${P1_CALLBACK_HOSTS:?}" "${TH_BACKCHANNEL_URL:?}"

step() { printf '\n==> %s\n' "$*"; }
api()  { curl -sk -H "Authorization: Bearer $TOKEN" "$@"; }

step "Reconciling realm '$KC_REALM' from $ENV_FILE (P1 browser base: $P1_BROWSER_BASE)"

TOKEN="$(curl -sk -d "client_id=admin-cli&grant_type=password&username=${KC_ADMIN_USER}&password=${KC_ADMIN_PASSWORD}" \
  "${KC_ADMIN_BASE}/realms/master/protocol/openid-connect/token" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')"
[ -n "$TOKEN" ] || { echo "reconcile-realm: failed to get admin token" >&2; exit 1; }

# --- Patch the `p1` SAML IdP browser-facing endpoints -----------------------
step "p1 IdP: singleSignOn/singleLogout -> ${P1_BROWSER_BASE}/saml/idp/{sso,slo}.do"
api "${KC_ADMIN_BASE}/admin/realms/${KC_REALM}/identity-provider/instances/p1" \
  | python3 -c '
import sys, json, os
idp = json.load(sys.stdin)
base = os.environ["P1_BROWSER_BASE"]
idp["config"]["singleSignOnServiceUrl"] = base + "/saml/idp/sso.do"
idp["config"]["singleLogoutServiceUrl"] = base + "/saml/idp/slo.do"
json.dump(idp, sys.stdout)' > /tmp/reconcile-p1-idp.json
api -X PUT -H "Content-Type: application/json" --data @/tmp/reconcile-p1-idp.json \
  "${KC_ADMIN_BASE}/admin/realms/${KC_REALM}/identity-provider/instances/p1" -o /dev/null -w "  PUT p1 idp -> %{http_code}\n"

# --- Helper: patch one client's URLs via a python transform on its full rep -
# $1 = clientId, stdin of the python = the client JSON, the transform mutates it.
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

# --- demo-shared-client: the ACTIVE multi-tenant login client ---------------
# redirectUris/webOrigins/post-logout from SPA_HOSTS; back-channel = token-handler.
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

# --- p1-self-client: P1 OIDC RP (silent re-auth) ----------------------------
# OIDC callbacks from P1_CALLBACK_HOSTS; back-channel = in-cluster P1 (KC -> P1).
step "p1-self-client: callback hosts = ${P1_CALLBACK_HOSTS}"
patch_client p1-self-client '
import sys, json, os
c = json.load(sys.stdin)
hosts = os.environ["P1_CALLBACK_HOSTS"].split()
c["redirectUris"] = [h + "/saml/idp/oidc-callback.do" for h in hosts]
c.setdefault("attributes", {})
c["attributes"]["post.logout.redirect.uris"] = "##".join(h + "/*" for h in hosts)
c["attributes"]["backchannel.logout.url"] = os.environ["P1_INCLUSTER_BASE"] + "/saml/idp/back-channel-logout.do"
json.dump(c, sys.stdout)'

# NB: demo-billing-client / demo-trading-client are VESTIGIAL (login runs through
# demo-shared-client — see CLAUDE.md), so they are intentionally not reconciled.

step "Realm reconcile complete."
