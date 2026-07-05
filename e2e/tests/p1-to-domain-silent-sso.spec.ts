import { test, expect } from '@playwright/test';
import { URLS } from '../fixtures/config.js';
import { recordNetwork, fetchMe } from '../fixtures/auth.js';
import { P1_HOSTS, loginP1Interactive } from '../fixtures/p1.js';
import { logoutAllRealmSessions } from '../fixtures/kcadmin.js';

/**
 * Regression guard for the `kc_idp_hint=p1` login-splash bug.
 *
 * The exact scenario that broke: log into P1, then open a BFF domain
 * (billing/trading) in a NEW tab and expect a silent cross-app SSO. The suite
 * already covered domain→domain (silent-first `warm-start`) and P1→whitelabel
 * *P1-host* (`p1-login-newtab-john`), but NOT P1→BFF-domain — so nothing caught
 * the token-handler forcing `kc_idp_hint=p1` onto the silent `prompt=none`
 * probe. With no p1 SAML IdP in the realm that hint is at best a no-op and, if a
 * p1 IdP ever reappears (stale import / drift), it brokers the silent probe into
 * a dead SAML flow and the user lands on the KC login form instead of silently
 * signing in.
 *
 * Invariant: given a live KC SSO session established on P1, a fresh billing tab
 * must silent-join it — `/auth/me` 200, sidebar rendered, KC's login form never
 * shown — and the silent authorize hop must carry `prompt=none` and NO
 * `kc_idp_hint`.
 */
test.describe('P1 → BFF domain silent SSO', () => {
  test.beforeAll(async () => {
    // Known-zero KC baseline so the only SSO session is the one P1 creates here.
    await logoutAllRealmSessions();
  });

  test('P1 login then a new billing tab silently establishes the session with no kc_idp_hint', async ({
    browser,
  }) => {
    const context = await browser.newContext({ ignoreHTTPSErrors: true });

    // 1) Establish the KC SSO session via P1's OWN OIDC login (not a domain).
    const p1Page = await context.newPage();
    await loginP1Interactive(p1Page, P1_HOSTS.canonical);

    // 2) Open billing in a NEW tab in the SAME context — the user's exact move.
    const billingPage = await context.newPage();
    const net = recordNetwork(billingPage);
    await billingPage.goto(URLS.domains.billing, { waitUntil: 'domcontentloaded' });

    // 3) Silent SSO must WIN the race against the KC login form. If the
    //    kc_idp_hint splash regressed, #username would appear instead of the
    //    signed-in sidebar and this assertion fails.
    const loggedInMarker = billingPage.locator('text=personId:');
    const kcUsername = billingPage.locator('#username');
    await Promise.race([
      loggedInMarker.waitFor({ state: 'visible', timeout: 90_000 }),
      kcUsername.waitFor({ state: 'visible', timeout: 90_000 }),
    ]);
    expect(
      await kcUsername.isVisible().catch(() => false),
      'silent SSO must not fall back to the KC login form (kc_idp_hint splash)'
    ).toBe(false);
    await loggedInMarker.waitFor({ state: 'visible', timeout: 30_000 });

    const me = await fetchMe(billingPage, 'billing');
    expect(me.authenticated, 'billing silently authenticated from the P1 KC session').toBe(true);

    // 4) The silent authorize hop must be prompt=none with NO kc_idp_hint.
    const authorize = net.urls.find(
      (u) => u.includes('/protocol/openid-connect/auth') && u.includes('prompt=none')
    );
    expect(authorize, 'a silent prompt=none authorize hop must have fired').toBeDefined();
    const url = new URL(authorize!.slice(authorize!.indexOf('https://')));
    expect(url.searchParams.get('prompt'), 'silent join must use prompt=none').toBe('none');
    expect(
      url.searchParams.get('kc_idp_hint'),
      'no kc_idp_hint post-auth-extraction — the KC form is the intended login UI'
    ).toBeNull();

    await context.close();
  });
});
