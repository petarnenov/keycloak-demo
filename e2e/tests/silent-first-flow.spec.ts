import { test, expect, request as playwrightRequest } from '@playwright/test';
import { URLS } from '../fixtures/config.js';
import { deleteDemoKcUser, loginViaP1, recordNetwork } from '../fixtures/auth.js';

/**
 * Document § 4.2 — silent-first redirect SSO. The cold-start variant must
 * upgrade to interactive on `error=login_required`; the warm-start variant
 * must NOT round-trip through P1's SAML AuthnRequest path.
 */
test.describe('silent-first SSO redirect chain', () => {
  test.beforeAll(async () => {
    await deleteDemoKcUser();
  });

  test('cold-start visits SilentLoginController, gets login_required, upgrades to interactive', async ({ browser }) => {
    // Fresh browser context — no KC cookies, no P1 session.
    const context = await browser.newContext({ ignoreHTTPSErrors: true });
    const page = await context.newPage();
    const net = recordNetwork(page);

    // Drive a full login via the loginViaP1 helper so the page reaches a
    // signed-in state; we'll assert the network shape after.
    await loginViaP1(page, 'billing');

    // The silent endpoint must be visited and set its marker cookie.
    expect(net.urls.some((u) => u.includes('/oauth/login/silent'))).toBeTruthy();
    // The prompt=none authorize hop must be present.
    expect(
      net.urls.some(
        (u) => u.includes('/protocol/openid-connect/auth') && u.includes('prompt=none')
      )
    ).toBeTruthy();
    // KC must have replied error=login_required (cold-start has no SSO cookie).
    expect(
      net.urls.some((u) => u.includes('/oauth/callback/keycloak') && u.includes('error=login_required'))
    ).toBeTruthy();
    // /auth/login-failed must have fired — that's the cookie-driven upgrade hook.
    expect(net.urls.some((u) => u.includes('/auth/login-failed'))).toBeTruthy();
    // Followed by a non-silent /oauth/login/keycloak that brokers to P1.
    expect(
      net.urls.some(
        (u) =>
          u.includes('/oauth/login/keycloak') &&
          !u.includes('silent=1') &&
          !u.includes('/oauth/login/keycloak?silent=1')
      )
    ).toBeTruthy();

    await context.close();
  });

  test('KC authorize URL on silent attempt carries prompt=none + kc_idp_hint=p1', async ({ browser }) => {
    const context = await browser.newContext({ ignoreHTTPSErrors: true });
    const page = await context.newPage();
    const net = recordNetwork(page);

    await loginViaP1(page, 'billing');

    // Find the very first KC authorize request — that's the silent attempt.
    const authorize = net.urls.find(
      (u) =>
        u.startsWith(`GET ${URLS.kcBase}/realms/`) &&
        u.includes('/protocol/openid-connect/auth')
    );
    expect(authorize, 'silent KC authorize URL must be captured').toBeDefined();
    const url = new URL(authorize!.slice(authorize!.indexOf('https://')));

    expect(url.searchParams.get('prompt'), 'silent attempts must include prompt=none').toBe('none');
    expect(url.searchParams.get('kc_idp_hint'), 'IdpHintFilter must force kc_idp_hint=p1').toBe('p1');
    expect(url.searchParams.get('client_id'), 'multi-tenant Token Handler uses the shared client').toBe('demo-shared-client');
    expect(url.searchParams.get('response_type')).toBe('code');
    expect(url.searchParams.get('scope'), 'OIDC scope must include openid').toMatch(/\bopenid\b/);
    // PKCE — the BFF always supplies a code_challenge with S256.
    expect(url.searchParams.get('code_challenge_method')).toBe('S256');
    expect(url.searchParams.get('code_challenge')?.length ?? 0).toBeGreaterThan(20);

    await context.close();
  });

  test('non-silent /oauth/login/keycloak STILL gets kc_idp_hint=p1 (IdpHintFilter)', async () => {
    // No browser context needed — a direct GET shows what the BFF would
    // redirect the user to in any interactive login. Asserts the demo's
    // "no native KC users" invariant: nobody can ever reach the KC login
    // screen, because there's nothing to log into.
    const api = await playwrightRequest.newContext({ ignoreHTTPSErrors: true });
    try {
      const res = await api.get(`${URLS.domains.billing}/oauth/login/keycloak`, {
        maxRedirects: 0,
      });
      expect(res.status()).toBe(302);
      const location = res.headers()['location'];
      expect(location).toBeDefined();
      expect(location).toContain('kc_idp_hint=p1');
      expect(location).not.toContain('prompt=');
    } finally {
      await api.dispose();
    }
  });

  test('warm-start (KC SSO alive) completes WITHOUT a P1 round-trip', async ({ browser }) => {
    const context = await browser.newContext({ ignoreHTTPSErrors: true });

    // Warm up: log in on billing in this context, establishing the KC SSO cookie.
    const billingPage = await context.newPage();
    await loginViaP1(billingPage, 'billing');
    await billingPage.close();

    // Now hit trading in the same context. P1 must NOT be touched.
    const tradingPage = await context.newPage();
    const net = recordNetwork(tradingPage);
    await loginViaP1(tradingPage, 'trading');

    // Silent must be the entry — and there must be NO request hitting the
    // P1 SSO endpoint, because the KC SSO cookie should produce a code
    // immediately under prompt=none.
    expect(net.urls.some((u) => u.includes('/oauth/login/silent'))).toBeTruthy();
    const p1Touches = net.urls.filter((u) => u.startsWith(`GET ${URLS.p1Base}/saml/idp/sso.do`));
    expect(
      p1Touches,
      'P1 SAML must not be hit on warm-start cross-subdomain navigation'
    ).toHaveLength(0);

    // And there must NOT be an error=login_required callback.
    const loginRequired = net.urls.filter((u) => u.includes('error=login_required'));
    expect(loginRequired, 'warm-start must not see error=login_required').toHaveLength(0);

    await context.close();
  });
});
