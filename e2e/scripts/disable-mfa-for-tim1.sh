#!/usr/bin/env bash
# One-time setup for the E2E suite.
#
# The Playwright suite (e2e/tests/*.spec.ts) drives a full P1 → KC → BFF login
# in a fresh browser context for every spec. P1 enforces email-OTP MFA on
# tim1, and there is no programmatic way for the suite to fetch the code from
# email, so MFA blocks automation by design. This script disables the
# MFA_REQUIRED_FLAG on tim1 in the local Oracle (FREEPDB1 on the geo-oracle
# container) — strictly a developer-machine accommodation, never run against
# anything but localhost.
#
# Usage:
#   ./e2e/scripts/disable-mfa-for-tim1.sh
#
# Re-enable later with:
#   ./e2e/scripts/disable-mfa-for-tim1.sh --restore
#
# Requires: `docker exec` access to the `geo-oracle` container; gp/gp123 creds
# (the standard local-DB profile per etc/hibernate-localhost.properties).

set -euo pipefail

MFA_VALUE=0
if [[ "${1:-}" == "--restore" ]]; then
  MFA_VALUE=1
fi

docker exec geo-oracle bash -c "echo \"
UPDATE ENTITY_TBL SET MFA_REQUIRED_FLAG=${MFA_VALUE} WHERE LDAP_UID='tim1';
COMMIT;
SELECT ENTITY_ID, LDAP_UID, MFA_REQUIRED_FLAG FROM ENTITY_TBL WHERE LDAP_UID='tim1';
EXIT
\" | sqlplus -s gp/gp123@FREEPDB1"

if [[ "$MFA_VALUE" == "0" ]]; then
  echo "MFA disabled for tim1. The E2E suite can now run unattended."
else
  echo "MFA restored for tim1."
fi
