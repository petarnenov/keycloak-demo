import { test, expect } from '@playwright/test';
import { URLS } from '../fixtures/config.js';
import { loginViaP1 } from '../fixtures/auth.js';
import { p1LoginState, silentSsoP1 } from '../fixtures/p1.js';
import { logoutAllRealmSessions, clientSessionCount } from '../fixtures/kcadmin.js';

/**
 * Bug report: log into a domain (billing) → open a NEW tab to the P1 host
 * (localhost:8080) → P1 silent-SSOs the person in via the existing KC session →
 * click "Sign out" on P1 → expected to land on the P1 login form, but the SPA
 * auto-relogs the person right back in instead.
 *
 * P1's "Sign out" button hands the browser to `/saml/idp/initiate-slo.do`
 * (see `WebContent/react/app/src/app/_services/appService.js` `logout()`),
 * which is supposed to:
 *   - end the Keycloak SSO session (so any subsequent silent-SSO probe gets
 *     `error=login_required` and the SPA falls through to the credential form),
 *   - tear down every P1 session for that `kc_sub` on this host,
 *   - redirect the browser to `/#login`.
 *
 * The bug: after the redirect, the SPA at `/#login` fires its silent-SSO probe
 * (`/saml/idp/silent-sso.do?return_to=/`) which still finds a live KC SSO
 * (because the SAML LogoutRequest fallback path in `IdpInitiateSloAction`
 * doesn't end the KC SSO session, only `rpInitiatedLogout()` does), so the
 * probe succeeds and the SPA is dropped back onto the authenticated home
 * page — visually the user is back where they started, not on the login form.
 *
 * What this test pins:
 *   1) `/react/isUserLoggedIn.do` on the P1 host returns `redirect` (not
 *      `loggedUser`) right after `/saml/idp/initiate-slo.do` completes — proves
 *      the P1 HttpSession is invalidated.
 *   2) `p1-client` has ZERO Keycloak sessions after logout — proves the
 *      KC SSO session was actually ended (not just the local P1 session). If
 *      this fails, the SAML fallback path was used and KC SSO survived.
 *   3) A fresh silent-SSO probe AFTER logout returns the user to the login
 *      form, not to a re-established session. This is the user-visible
 *      symptom: opening any page on P1 after sign-out lands on `/#login`,
 *      not on the dashboard.
 */
test.describe('P1 logout (after cross-host silent SSO) lands on login form, not a silent relogin', () => {
  test.setTimeout(240_000); // two full logins + a logout round-trip + a probe

  test.beforeEach(async () => {
    // Start from zero KC realm sessions so post-logout assertions are unambiguous.
    await logoutAllRealmSessions();
  });

  test('logout from P1 (entered via silent SSO from billing) → P1 stays signed out', async ({ browser }) => {
    const context = await browser.newContext({ ignoreHTTPSErrors: true });
    try {
      // ── Tab 1: log into billing. Mints KC SSO + the billing BFF session.
      const billingTab = await context.newPage();
      const me = await loginViaP1(billingTab, 'billing');
      expect(me.authenticated, 'billing is logged in').toBe(true);

      // ── Tab 2: open P1. Silent SSO rides the KC session minted by billing,
      // never landing on the credential form on this host.
      const p1Tab = await context.newPage();
      await silentSsoP1(p1Tab, URLS.p1Base);
      expect(await p1LoginState(p1Tab.request, URLS.p1Base), 'P1 silent-SSO succeeded').toBe('loggedUser');

      // Sanity: KC has a live p1-self session for this person.
      expect(await clientSessionCount('p1-client'), 'p1-self has a live KC session').toBeGreaterThan(0);

      // ── Sign out. Drive the same code path the SPA's logout button uses.
      // waitUntil:'load' follows the full SAML / OIDC SLO round-trip to its
      // final destination — should be `/#login?silent_failed=1` after the fix.
      await p1Tab.goto(`${URLS.p1Base}/oidc/logout.do`, { waitUntil: 'load' });

      // (1) P1 HttpSession is gone.
      expect(await p1LoginState(p1Tab.request, URLS.p1Base), 'P1 session ended after logout')
        .toBe('redirect');

      // (2) The KC SSO session for p1-self is GONE — i.e. the logout reached
      // KC, not just the local P1 session. If this is non-zero, the SAML
      // LogoutRequest fallback was used and KC SSO survived; the next silent
      // probe will silently re-log the user in.
      expect(await clientSessionCount('p1-client'), 'KC SSO for p1-self ended after P1 sign-out')
        .toBe(0);

      // (3) The user-visible symptom: re-driving the silent-SSO probe now
      // must NOT re-establish a session. With the bug, KC still has the SSO
      // and `oidc/login.do` would succeed silently. With the fix, the KC
      // session is gone so the probe lands on the KC credential form, not
      // a silent re-auth — `/react/isUserLoggedIn.do` still reports the P1
      // session is `redirect`-only because we never completed the new flow.
      await p1Tab.goto(`${URLS.p1Base}/oidc/login.do`, { waitUntil: 'commit' }).catch(() => {});
      const after = await p1LoginState(p1Tab.request, URLS.p1Base);
      expect(after, `silent-SSO probe after sign-out must NOT re-establish a session (got: ${after})`)
        .toBe('redirect');
    } finally {
      await context.close();
    }
  });
});
