import { test, expect } from '@playwright/test';
import { loginViaP1, fetchMeStatus } from '../fixtures/auth.js';
import { P1_HOSTS, silentSsoP1, p1LoginState } from '../fixtures/p1.js';
import { clientSessionCount, logoutAllRealmSessions } from '../fixtures/kcadmin.js';

/**
 * Global single-logout initiated from a whitelabel P1 host. Before the fix a
 * whitelabel logout only cleared that host's local P1 session; the KC SSO
 * session, the BFF session and the sibling localhost P1 tab all survived. Now
 * P1's SLO does a server-side RP-initiated logout (ending the KC session and
 * fanning OIDC back-channel logout out to the resource BFFs) and tears down
 * every sibling P1 session by kc_sub.
 *
 * The surfaces here (billing BFF + two P1 hosts) all share ONE KC SSO session:
 * billing logs in interactively, the P1 hosts silent-join it with prompt=none.
 * `beforeEach` force-logs-out the realm so "session count → 0" is unambiguous.
 */
test.describe('Global single-logout from a whitelabel host', () => {
  // One interactive login + two silent-SSO joins on a slow dev P1, then a
  // logout with back-channel fan-out polling — beyond the 90s default.
  test.setTimeout(240_000);

  test.beforeEach(async () => {
    await logoutAllRealmSessions();
  });

  test('logging out of the whitelabel firm signs the person out everywhere', async ({ page, context }) => {
    // One KC SSO session: billing logs in, the two P1 hosts silent-join it.
    await loginViaP1(page, 'billing');
    await silentSsoP1(page, P1_HOSTS.whitelabel);
    await silentSsoP1(page, P1_HOSTS.canonical);

    // Sanity: the shared session is live before logout.
    expect(await clientSessionCount('p1-client'), 'KC p1-self before').toBeGreaterThan(0);
    expect(await p1LoginState(page.request, P1_HOSTS.canonical)).toBe('loggedUser');

    // Sign out from the whitelabel host (what the SPA's logout button hits).
    await page.goto(`${P1_HOSTS.whitelabel}/oidc/logout.do`, { waitUntil: 'domcontentloaded' });

    // The KC SSO session and its client sessions are gone (RP-initiated logout).
    await expect.poll(() => clientSessionCount('p1-client'), { timeout: 20_000 }).toBe(0);
    await expect.poll(() => clientSessionCount('demo-shared-client'), { timeout: 20_000 }).toBe(0);

    // The resource BFF rejects the now-orphaned session (back-channel fan-out).
    await expect.poll(() => fetchMeStatus(context, 'billing'), { timeout: 20_000 }).toBe(401);

    // The sibling canonical-host P1 session is torn down too — directly by
    // kc_sub (the object-keyed registry), not only by the liveness fallback.
    await expect.poll(() => p1LoginState(page.request, P1_HOSTS.canonical), { timeout: 20_000 })
      .toBe('redirect');
  });
});
