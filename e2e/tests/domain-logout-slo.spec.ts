import { test, expect } from '@playwright/test';
import { loginViaP1, fetchMeStatus } from '../fixtures/auth.js';
import { URLS } from '../fixtures/config.js';
import { P1_HOSTS, loginP1Interactive, p1LoginState } from '../fixtures/p1.js';
import {
  clientSessionCount,
  clientAttribute,
  logoutAllRealmSessions,
} from '../fixtures/kcadmin.js';

/**
 * Regression guard for the SLO bug where signing out of a BFF domain left P1
 * logged in.
 *
 * When logout starts on a domain (trading = `demo-shared-client`), Keycloak
 * ends the SSO session and fans the OIDC back-channel `logout_token` out to the
 * OTHER clients — including `p1-client` — which is what tears P1's server-side
 * session down. That leg was broken: `p1-client`'s `backchannel.logout.url`
 * omitted the Struts `.do` suffix, so KC POSTed to `/oidc/back-channel-logout`,
 * hit a 404 (P1's OIDC endpoints are `.do` actions; a bare path falls through
 * to the SPA catch-all), and P1's `HttpSession` survived.
 *
 * The suite missed it because every existing logout spec triggers SLO from a
 * whitelabel P1 host (`whitelabel-global-logout`) or a KC admin revoke
 * (`external-logout`) — never from a domain. And the whitelabel spec can't catch
 * it: there P1 IS the initiator, so KC skips its back-channel and the sibling
 * P1 tab is torn down by the slow `KcSessionProbe` liveness fallback, which a
 * 20s poll tolerates.
 */
test.describe('SLO from a BFF domain tears down P1', () => {
  test.beforeAll(async () => {
    await logoutAllRealmSessions();
  });

  test('p1-client back-channel URL targets the .do Struts action (deterministic contract)', async () => {
    // This assertion alone fails on the exact regression, with zero timing
    // dependence: KC's back-channel POST must reach P1's OIDC action, not the
    // SPA catch-all. struts.action.extension=do, so a bare path 404s.
    const url = await clientAttribute('p1-client', 'backchannel.logout.url');
    expect(url, 'p1-client must have a back-channel logout URL').toBeTruthy();
    expect(
      url!,
      'must target /oidc/back-channel-logout.do — a bare /oidc/back-channel-logout 404s'
    ).toContain('/oidc/back-channel-logout.do');
  });

  test('signing out of the trading domain logs the person out of P1 too', async ({ browser }) => {
    const context = await browser.newContext({ ignoreHTTPSErrors: true });
    const page = await context.newPage();

    // 1) KC SSO session on P1, then the trading domain silent-joins it.
    await loginP1Interactive(page, P1_HOSTS.canonical);
    await loginViaP1(page, 'trading');
    expect(await p1LoginState(page.request, P1_HOSTS.canonical), 'P1 live before logout').toBe(
      'loggedUser'
    );
    expect(
      await clientSessionCount('demo-shared-client'),
      'domain session live before logout'
    ).toBeGreaterThan(0);

    // 2) Sign out from the trading DOMAIN by clicking its real "Sign out"
    //    button — which submits a top-level form POST to /auth/logout
    //    (AuthProvider.postLogout). Two properties matter and neither is
    //    reproducible with an API request: the logout requires POST (CSRF
    //    defence) AND it must be a top-level navigation so the BROWSER FOLLOWS
    //    the 303 chain — token-handler 303 → KC end_session (id_token_hint) →
    //    KC ends the SSO session and fans the back-channel logout_token out to
    //    p1-client → P1 SLO splash. A non-following request POST stops at the
    //    303 and nothing is torn down; a GET navigation isn't accepted (POST-only).
    await page.getByRole('button', { name: 'Sign out' }).click().catch(() => {});

    // 3) KC SSO ended, and P1 is torn down via the back-channel to p1-client.
    await expect
      .poll(() => clientSessionCount('demo-shared-client'), { timeout: 20_000 })
      .toBe(0);
    await expect.poll(() => clientSessionCount('p1-client'), { timeout: 20_000 }).toBe(0);
    await expect
      .poll(() => p1LoginState(page.request, P1_HOSTS.canonical), { timeout: 20_000 })
      .toBe('redirect');
    expect(await fetchMeStatus(context, 'trading'), 'trading BFF session gone').toBe(401);

    await context.close();
  });
});
