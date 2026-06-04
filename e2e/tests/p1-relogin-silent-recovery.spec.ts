import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { URLS, P1_CREDENTIALS } from '../fixtures/config.js';
import { FIRM, p1LoginState, sessionFirm, expectLoggedIn } from '../fixtures/p1.js';
import { clientSessionCount, logoutAllRealmSessions } from '../fixtures/kcadmin.js';

/**
 * Reproduces and guards the recovery scenario the user reported:
 *
 *   1. Log in to P1 as tim1 on the canonical host.
 *   2. Open a 2nd tab on c1wealth.localhost — silent SSO logs you in (firm 5).
 *   3. Log out everywhere.
 *   4. Re-log in to P1 as tim1 in the FIRST tab.
 *   5. Refresh the 2nd tab — expect silent SSO to succeed again (back to firm 5),
 *      NOT to stay on the credential form with `#login?silent_failed=1`.
 *
 * The two fixes this guards:
 *   - Gap 6's establish round-trip in appService.loginPassword() has no
 *     client-side guard — `SilentSsoAction.SESSION_KEY_ESTABLISH_DONE` on
 *     the P1 HttpSession is the single source of truth, and it resets when
 *     SLO invalidates the HttpSession. The earlier client-side
 *     `sessionStorage.kc_sso_established` flag was removed after a stale
 *     value silently suppressed the round-trip across re-logins.
 *   - The silent_failed loop guard in checkUserLoggedIn() honours a user
 *     reload (TYPE_RELOAD nav entry) as a retry signal and re-attempts silent
 *     SSO instead of going straight to the credential form.
 */

const C1_HOST = 'http://c1wealth.localhost:8888';

const P1_REQUEST_TIMEOUT = 30_000;

async function loginAsTim1OnP1(page: Page): Promise<void> {
  // P1's SilentSsoAction rate-limits the same IP to one probe / 2s. When this
  // function runs back-to-back (cold login → tab2 silent SSO → logout →
  // re-login), the previous silent-SSO bounce can still be inside the
  // window and the canonical host serves a plain `rate_limited` text body
  // instead of the React shell — the credential form never renders. A
  // short pause lets the rate-limit window expire before we drive the
  // canonical host again.
  await page.waitForTimeout(3_000);
  // React's cold-start silent-SSO probe may redirect immediately after
  // load and race page.goto's own navigation. Swallow the interrupted-
  // navigation error if it fires — we only need a P1 host loaded, and
  // the credential form is what we wait on below.
  try {
    await page.goto(URLS.p1Base, { waitUntil: 'commit' });
  } catch (e) {
    if (!String(e).includes('interrupted by another navigation')) throw e;
  }
  await page.locator('text="Sign in"').first().waitFor({ state: 'visible', timeout: 90_000 });
  await page.getByRole('textbox', { name: 'username' }).fill(P1_CREDENTIALS.username);
  await page.getByRole('textbox', { name: 'password' }).fill(P1_CREDENTIALS.password);
  await page.getByRole('button', { name: 'Login' }).click();
}

async function waitForKcSession(timeoutMs = 90_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if ((await clientSessionCount('p1-self-client')) > 0) return;
    await new Promise((r) => setTimeout(r, 1_000));
  }
  throw new Error('P1 credential login never established a Keycloak SSO session');
}

async function waitForNoKcSession(timeoutMs = 30_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if ((await clientSessionCount('p1-self-client')) === 0) return;
    await new Promise((r) => setTimeout(r, 1_000));
  }
  throw new Error('Logout never tore down the Keycloak SSO session');
}

test.describe('P1 re-login restores cross-host silent SSO', () => {
  test.setTimeout(300_000);

  test.beforeEach(async () => {
    await logoutAllRealmSessions();
  });

  test('logout + re-login re-establishes KC session so a 2nd tab silent-recovers on refresh', async ({ browser }) => {
    const context = await browser.newContext({ ignoreHTTPSErrors: true });
    try {
      // ── 1. Login tim1 on canonical host.
      const tab1 = await context.newPage();
      await loginAsTim1OnP1(tab1);
      await expectLoggedIn(tab1, URLS.p1Base);
      expect(await sessionFirm(tab1.request, URLS.p1Base), 'tim1 home firm').toBe(FIRM.geowealth);
      await waitForKcSession();

      // ── 2. Open c1wealth.localhost in a 2nd tab — silent SSO firm switch to firm 5.
      const tab2 = await context.newPage();
      await tab2.goto(C1_HOST);
      await expectLoggedIn(tab2, C1_HOST);
      expect(await sessionFirm(tab2.request, C1_HOST), 'tab2 firm context').toBe(FIRM.creativeOne);

      // ── 3. Logout — same sequence the React appService.logout() runs:
      //      drop the auth cookie copy, navigate to the global SLO. The
      //      establish guard is server-side only (SilentSsoAction's
      //      SESSION_KEY_ESTABLISH_DONE on the P1 HttpSession), so it
      //      goes away automatically when the SLO invalidates the
      //      HttpSession — no client-side cleanup needed.
      await tab1.evaluate(() => {
        window.location.href = '/saml/idp/initiate-slo.do';
      });
      await waitForNoKcSession();

      // ── 4. Re-login tim1.
      await loginAsTim1OnP1(tab1);
      await expectLoggedIn(tab1, URLS.p1Base);
      await waitForKcSession();

      // ── 5. Refresh tab2 (which is sitting at /#login?silent_failed=1 if the
      //      tab observed the logout fan-out, or at the previous firm-5 view
      //      if it hadn't loaded since). A user reload (TYPE_RELOAD) must
      //      bypass the silent_failed loop guard and re-attempt silent SSO.
      await tab2.reload();
      await expectLoggedIn(tab2, C1_HOST);
      expect(await sessionFirm(tab2.request, C1_HOST), 'tab2 silent-recovered firm').toBe(FIRM.creativeOne);

      // ── No bleed: tab 1 is still firm 1.
      expect(await p1LoginState(tab1.request, URLS.p1Base), 'tab1 still logged in').toBe('loggedUser');
      expect(await sessionFirm(tab1.request, URLS.p1Base), 'tab1 stays firm 1').toBe(FIRM.geowealth);
    } finally {
      await context.close();
    }
  });
});
