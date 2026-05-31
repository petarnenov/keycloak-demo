import { test, expect } from '@playwright/test';
import { P1_HOSTS, FIRM, resolveFirmAnonymous } from '../fixtures/p1.js';

/**
 * Guards the firm-resolution fix (AuthorizationManagerTrait.isMatchableBaseUrl
 * + the firm_tbl data cleanup). Several demo firms carried a degenerate
 * `system_base_url='/'`, which turned the `appURL.indexOf("/" + firmUrl)` host
 * probe into `indexOf("//")` — true for EVERY url — so every host resolved to
 * whichever mis-seeded firm matched first (CreativeOne, 1123). After the fix
 * only the whitelabel host maps to CreativeOne; everything else falls to the
 * GeoWealth default.
 *
 * Anonymous on purpose: this is pure host→firm resolution, no session involved.
 */
test.describe('Whitelabel firm resolution', () => {
  test('canonical, loopback and unmapped hosts all resolve to GeoWealth (not a whitelabel firm)', async () => {
    for (const host of [P1_HOSTS.canonical, P1_HOSTS.loopback, P1_HOSTS.neutral]) {
      expect(await resolveFirmAnonymous(host), `firm for ${host}`).toBe(FIRM.geowealth);
    }
  });

  test('the whitelabel host resolves to CreativeOne via its real base URL', async () => {
    expect(await resolveFirmAnonymous(P1_HOSTS.whitelabel)).toBe(FIRM.creativeOne);
  });
});
