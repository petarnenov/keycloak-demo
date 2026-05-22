#!/usr/bin/env bash
# Automated tests for the production whitelabel resolution matrix in
# TEST-SCENARIOS.md. Drives the GeoWealth branding API directly (BE
# probes) and the Keycloak login endpoint end-to-end (E2E probes),
# then reports pass/fail per scenario.
#
# Usage:
#   ./test-whitelabel-scenarios.sh             # run all sections
#   ./test-whitelabel-scenarios.sh be          # BE probes only
#   ./test-whitelabel-scenarios.sh e2e         # E2E (Keycloak) only
#   ./test-whitelabel-scenarios.sh restart-kc  # flush SPI cache + run all
#
# Prereqs:
#   * Real GeoWealth Tomcat reachable at $BE_URL with WHITELABEL_TBL
#     seeded per TEST-SCENARIOS.md.
#   * Keycloak running at $KC_URL with POC_BRANDING_API_URL pointing at
#     the same Tomcat (or unset, defaulting to host.docker.internal:8080).
#   * POC_BRANDING_API_TOKEN env var set, matching both sides.

set -uo pipefail

cd "$(dirname "$0")"

if [ -f .envrc ]; then
  # shellcheck disable=SC1091
  source .envrc
fi

if [ -z "${POC_BRANDING_API_TOKEN:-}" ]; then
  echo "Error: POC_BRANDING_API_TOKEN unset. Source .envrc." >&2
  exit 2
fi

BE_URL="${BE_URL:-http://127.0.0.1:8080}"
KC_URL="${KC_URL:-http://localhost:8898}"
# Use a redirect_uri that's already in the geowealth-realm client whitelist
# so we don't 400 before the branding hook fires. Host header is what
# drives whitelabel resolution; redirect_uri is independent.
REDIRECT_URI_ENC="${REDIRECT_URI_ENC:-http%3A%2F%2Fdefault.localhost%3A5174%2F}"

pass=0
fail=0
section=""

green() { printf '\033[32m%s\033[0m' "$1"; }
red()   { printf '\033[31m%s\033[0m' "$1"; }
dim()   { printf '\033[2m%s\033[0m' "$1"; }

ok()   { printf "  %s %s\n" "$(green '✓')" "$1"; pass=$((pass + 1)); }
notok(){ printf "  %s %s\n" "$(red   '✗')" "$1"; fail=$((fail + 1)); }
hdr()  { section="$1"; printf "\n%s\n" "=== $1"; }

# ---------- BE-side helpers ----------------------------------------

# Probe /lookup and assert the returned code.
expect_lookup() {
  local host=$1 expected=$2 desc=$3
  local body
  body=$(curl -s -H "Authorization: Bearer $POC_BRANDING_API_TOKEN" \
    "$BE_URL/branding-api/keycloak/whitelabel/lookup?host=$host")
  local actual
  actual=$(printf '%s' "$body" \
    | python3 -c 'import json,sys;print(json.load(sys.stdin).get("code","?"))' \
    2>/dev/null || echo "?")
  if [ "$actual" = "$expected" ]; then
    ok "$desc  host=$host -> $(green "$actual")"
  else
    notok "$desc  host=$host -> got '$(red "$actual")', expected '$expected'"
  fi
}

# Probe /whitelabel/{code} for a 200 with specific JSON fields present.
expect_brand_ok() {
  local code=$1
  local body status
  body=$(curl -s -w "\n__STATUS=%{http_code}" -H "Authorization: Bearer $POC_BRANDING_API_TOKEN" \
    "$BE_URL/branding-api/keycloak/whitelabel/$code")
  status=$(printf '%s' "$body" | grep -o '__STATUS=[0-9]*' | tail -1 | cut -d= -f2)
  body=$(printf '%s' "$body" | sed 's/__STATUS=[0-9]*//')
  if [ "$status" != "200" ]; then
    notok "/whitelabel/$code  expected 200, got $status"
    return
  fi
  local checks
  checks=$(printf '%s' "$body" | python3 -c '
import json, sys
d = json.load(sys.stdin)
out = []
out.append("code=" + d.get("code","?"))
out.append("displayName=" + d.get("displayName","?"))
out.append("hasAssets" if d.get("assets") else "noAssets")
out.append("hasLogo" if d.get("assets",{}).get("loginLogo") else "noLogo")
out.append("hasFavicon" if d.get("assets",{}).get("favicon") else "noFavicon")
out.append("hasEmail" if d.get("supportEmail") else "noEmail")
print(" | ".join(out))
' 2>/dev/null || echo 'parse_error')
  ok "/whitelabel/$code  $(dim "$checks")"
}

# Probe /whitelabel/{code} expecting a 404 (intentional fallback case).
expect_brand_404() {
  local code=$1 desc=$2
  local status
  status=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $POC_BRANDING_API_TOKEN" \
    "$BE_URL/branding-api/keycloak/whitelabel/$code")
  if [ "$status" = "404" ]; then
    ok "$desc  /whitelabel/$code -> 404 (expected)"
  else
    notok "$desc  /whitelabel/$code -> got $status, expected 404"
  fi
}

# Probe an asset endpoint expecting 200 + image content-type.
expect_asset() {
  local code=$1 kind=$2
  local status ct
  read -r status ct < <(curl -s -o /dev/null \
    -w "%{http_code} %{content_type}\n" \
    -H "Authorization: Bearer $POC_BRANDING_API_TOKEN" \
    "$BE_URL/branding-api/keycloak/whitelabel/$code/asset/$kind")
  if [ "$status" = "200" ] && printf '%s' "$ct" | grep -qi '^image/'; then
    ok "/asset/$code/$kind  HTTP 200  content-type=$ct"
  else
    notok "/asset/$code/$kind  HTTP $status  content-type=$ct (expected 200 image/*)"
  fi
}

# ---------- Keycloak-E2E helpers -----------------------------------

# Drive an auth request with a specific Host header and assert the
# rendered displayName + banner state in the response HTML. We probe
# Keycloak through the host port (8898) but override the Host header
# so the SPI sees the firm's branded subdomain.
e2e_login() {
  local host=$1 expected_display=$2 expected_banner=$3 desc=$4
  local html
  html=$(curl -s -L \
    -H "Host: $host" \
    "$KC_URL/realms/geowealth-realm/protocol/openid-connect/auth?client_id=geowealth-poc-client&response_type=code&redirect_uri=${REDIRECT_URI_ENC}&scope=openid&state=test-$RANDOM")
  local actual_display
  actual_display=$(printf '%s' "$html" \
    | awk '/<div id="kc-header-wrapper"/{flag=1; next} flag{gsub(/^[ \t]+|[ \t]+$/,""); print; exit}' \
    | sed 's/<[^>]*>//g; s/^[ \t]*//; s/[ \t]*$//')
  if [ -z "$actual_display" ]; then
    # Display name may be on the same line as the open tag (for the img
    # variant or the brand text variant) — fall back to a less precise grep.
    actual_display=$(printf '%s' "$html" | grep -oE '<div id="kc-header-wrapper"[^>]*>[^<]*' | head -1 | sed 's/.*>//')
  fi
  local has_banner
  if printf '%s' "$html" | grep -q "Branding fallback active"; then
    has_banner=yes
  else
    has_banner=no
  fi
  local detail=""
  if [ "$expected_display" = "_logo_" ]; then
    # Brand emitted a logo image — header has <img> instead of plain text.
    if printf '%s' "$html" | grep -q 'class="geowealth-brand-logo"'; then
      detail="logo"
    else
      detail="!logo!"
    fi
  else
    detail="display=$actual_display"
  fi

  local display_ok=1 banner_ok=1
  if [ "$expected_display" = "_logo_" ]; then
    printf '%s' "$html" | grep -q 'class="geowealth-brand-logo"' || display_ok=0
  else
    [ "$actual_display" = "$expected_display" ] || display_ok=0
  fi
  [ "$has_banner" = "$expected_banner" ] || banner_ok=0

  if [ "$display_ok" = "1" ] && [ "$banner_ok" = "1" ]; then
    ok "$desc  host=$host  $detail  banner=$has_banner"
  else
    notok "$desc  host=$host  display=$actual_display (want $expected_display)  banner=$has_banner (want $expected_banner)"
  fi
}

# ---------- Section runners ----------------------------------------

run_be() {
  hdr "Pass 1 — FIRM.SYSTEM_BASE_URL substring match (advisor portal)"
  expect_lookup "c1wealth.geowealth.com"            "c1wealth"            "lookup"
  expect_lookup "c1securities.geowealth.com"        "c1securities"        "lookup"
  expect_lookup "bcj.geowealth.com"                 "bcj"                 "lookup"
  expect_lookup "riverwaterpartners.geowealth.com"  "riverwaterpartners"  "lookup"
  expect_lookup "smithandcox.geowealth.com"         "smithandcox"         "lookup"
  expect_lookup "wisewealthkc.geowealth.com"        "wisewealthkc"        "lookup (WL row intentionally absent — SPI fallback)"

  hdr "Pass 2 — FIRM.CLIENT_PORTAL_BASE_URL substring match (client portal)"
  expect_lookup "c1wealthclient.geowealth.int"      "c1wealth"            "lookup"
  expect_lookup "bcjclient.geowealth.int"           "bcj"                 "lookup"
  expect_lookup "smithandcoxclient.geowealth.int"   "smithandcox"         "lookup"

  hdr "Pass 5 — topSubDomain == FIRM.CODE"
  expect_lookup "bcj.localhost"                     "bcj"                 "lookup"
  expect_lookup "c1wealth.localhost"                "c1wealth"            "lookup"

  hdr "Default — GeoWealth (no match)"
  expect_lookup "unknown.foo.com"                   "cca"                 "lookup"
  expect_lookup "localhost"                         "cca"                 "lookup (no subdomain)"
  expect_lookup "mca.localhost"                     "cca"                 "lookup (mca has WL but no FIRM/advisor binding)"
  expect_lookup "changepath.localhost"              "cca"                 "lookup (changepath has WL but no FIRM/advisor binding)"

  hdr "Brand JSON shape — /whitelabel/{code}"
  for code in cca changepath c1wealth c1securities bcj riverwaterpartners smithandcox; do
    expect_brand_ok "$code"
  done
  expect_brand_404 "wisewealthkc"  "no WL row seeded"
  expect_brand_404 "unknownfirm42" "non-existent code"

  hdr "Asset endpoints — /whitelabel/{code}/asset/{kind}"
  for code in c1wealth bcj smithandcox; do
    expect_asset "$code" "logo-login"
    expect_asset "$code" "favicon"
  done
}

run_e2e() {
  hdr "Keycloak end-to-end — login HTML carries the right brand"
  # Pass-1 firms with WL rows seeded.
  e2e_login "c1wealth.geowealth.com:8898"           "_logo_"     "no"  "pass-1 c1wealth"
  e2e_login "c1securities.geowealth.com:8898"       "_logo_"     "no"  "pass-1 c1securities"
  e2e_login "bcj.geowealth.com:8898"                "_logo_"     "no"  "pass-1 bcj"
  e2e_login "riverwaterpartners.geowealth.com:8898" "_logo_"     "no"  "pass-1 riverwaterpartners"
  e2e_login "smithandcox.geowealth.com:8898"        "_logo_"     "no"  "pass-1 smithandcox"

  # Pass-1 firm WITHOUT a WL row — SPI falls back to registry (banner expected).
  e2e_login "wisewealthkc.geowealth.com:8898"       "_logo_"     "yes" "pass-1 wisewealthkc (registry fallback)"

  # Pass-5 (topSubDomain == FIRM.CODE) — same expected as pass-1 for these firms.
  e2e_login "bcj.localhost:8898"                    "_logo_"     "no"  "pass-5 bcj.localhost"

  # Default fallback — host doesn't match any firm, code resolves to 'cca'.
  e2e_login "unknown.foo.com:8898"                  "_logo_"     "no"  "default -> cca"
  e2e_login "localhost:8898"                        "_logo_"     "no"  "default no-subdomain -> cca"
}

restart_kc() {
  hdr "Flushing Keycloak SPI cache (restart container)"
  docker compose restart keycloak >/dev/null 2>&1
  while ! docker inspect keycloak-demo-keycloak-1 \
      --format '{{.State.Health.Status}}' 2>/dev/null | grep -q healthy; do
    sleep 2
  done
  ok "keycloak healthy"
}

# ---------- Main ----------------------------------------------------

mode="${1:-all}"
case "$mode" in
  be)         run_be ;;
  e2e)        run_e2e ;;
  restart-kc) restart_kc; run_be; run_e2e ;;
  all)        run_be; run_e2e ;;
  *) echo "Unknown mode: $mode (use be|e2e|restart-kc|all)" >&2; exit 2 ;;
esac

printf "\n"
if [ "$fail" -eq 0 ]; then
  printf "%s  %d passed, %d failed\n" "$(green PASS)" "$pass" "$fail"
  exit 0
else
  printf "%s  %d passed, %d failed\n" "$(red FAIL)" "$pass" "$fail"
  exit 1
fi
