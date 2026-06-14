import { test, expect } from '@playwright/test';
import { loginViaP1 } from '../fixtures/auth.js';
import { P1_HOSTS, FIRM, silentSsoP1, p1LoginState, sessionFirm } from '../fixtures/p1.js';

/**
 * Whitelabel cross-host silent SSO. P1's silent-SSO now derives its OIDC
 * redirect_uri per-request from the host, so a whitelabel host completes the
 * round-trip on itself instead of bouncing to the canonical host and
 * state-mismatching back to the login form. Each host's session keeps its own
 * firm context (no bleed).
 */
test.describe('Whitelabel cross-host SSO', () => {
  // P1 is slow (~7s/request on the dev box) and this drives a full login plus
  // two silent-SSO round-trips, so give it room beyond the 90s default.
  test.setTimeout(180_000);

  test('a whitelabel host establishes its own session and keeps firm context per host', async ({ page }) => {
    // Establish a KC SSO session through the normal billing login.
    await loginViaP1(page, 'billing');

    // Silent-SSO onto the whitelabel host — must land on c1wealth, not bounce
    // to localhost, and report a live P1 session there.
    await silentSsoP1(page, P1_HOSTS.whitelabel);
    expect(new URL(page.url()).host, 'landed on the whitelabel host').toBe(new URL(P1_HOSTS.whitelabel).host);
    expect(await p1LoginState(page.request, P1_HOSTS.whitelabel)).toBe('loggedUser');

    // And onto the canonical host off the same KC session.
    await silentSsoP1(page, P1_HOSTS.canonical);
    expect(await p1LoginState(page.request, P1_HOSTS.canonical)).toBe('loggedUser');

    // No bleed: the two live P1 sessions carry distinct firm context.
    expect(await sessionFirm(page.request, P1_HOSTS.whitelabel), 'c1wealth firm context')
      .toBe(FIRM.creativeOne);
    expect(await sessionFirm(page.request, P1_HOSTS.canonical), 'localhost firm context')
      .toBe(FIRM.geowealth);
  });
});
