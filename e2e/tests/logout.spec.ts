import { test, expect } from '@playwright/test';
import { TENANT_SLUGS, URLS } from '../fixtures/config.js';
import { deleteDemoKcUser, loginViaP1, fetchMeStatus, logoutEverywhere } from '../fixtures/auth.js';

/**
 * Logout fan-out. The BFF's `/auth/logout` does:
 *   1. server-side OIDC end-session POST to KC — this triggers Keycloak's
 *      back-channel-logout fan-out to every BFF in the SSO session
 *      (per OIDC Back-Channel Logout 1.0);
 *   2. browser redirect to P1's IdP-initiated SLO.
 *
 * Prerequisite for step 1 to actually fire: every OIDC client in the realm
 * must have {@code frontchannelLogout=false}. With it set to true, Keycloak's
 * AuthenticationManager skips the back-channel POST entirely and logs
 * "Some clients have not been logged out…" — applied via
 * {@code scripts/apply-cross-subdomain-sso.sh}. The test asserts the fast
 * path: sibling BFFs return 401 within a couple of seconds of the logout
 * call, no waiting on a refresh-token revalidation floor.
 */
test.describe('logout fans out across all subdomains', () => {
  test.beforeAll(async () => {
    await deleteDemoKcUser();
  });

  test('signing out on billing invalidates trading via back-channel POST', async ({ browser }) => {
    const context = await browser.newContext({ ignoreHTTPSErrors: true });

    // Warm up all three subdomain sessions inside ONE context — they share
    // the realm SSO cookie, and each /auth/me materialises a BFF session.
    for (const slug of TENANT_SLUGS) {
      const page = await context.newPage();
      await loginViaP1(page, slug);
      await page.close();
    }

    // Sanity: all three /auth/me must be 200 right before we log out.
    for (const slug of TENANT_SLUGS) {
      expect(await fetchMeStatus(context, slug), `pre-logout ${slug}`).toBe(200);
    }

    // Sign out from billing. The KC end-session call back-channel-POSTs
    // every sibling BFF; the browser redirect lands the user on P1's SLO.
    const billingPage = await context.newPage();
    await logoutEverywhere(billingPage, 'billing');
    await billingPage.close();

    // After fan-out, /auth/me on every subdomain must be 401. Back-channel
    // POSTs are async on KC's side; a short poll covers normal scheduling
    // jitter.
    for (const slug of TENANT_SLUGS) {
      let status = 200;
      const deadline = Date.now() + 10_000;
      while (Date.now() < deadline) {
        status = await fetchMeStatus(context, slug);
        if (status === 401) break;
        await new Promise((r) => setTimeout(r, 250));
      }
      expect(status, `post-logout ${slug} (after back-channel fan-out)`).toBe(401);
    }

    await context.close();
  });
});
