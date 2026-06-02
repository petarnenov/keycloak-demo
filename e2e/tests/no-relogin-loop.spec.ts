import { test, expect, request as playwrightRequest } from '@playwright/test';
import { URLS, TENANT_SLUGS, type TenantSlug } from '../fixtures/config.js';

/**
 * The "no re-login loop" invariant (commits a2f8c80, 1f471a5). Two failure
 * modes used to bounce a user to the IdP forever:
 *
 *   1. A 403 (authenticated but lacking the role) was treated like a 401 and
 *      re-started login: login → callback → 403 → login → … . The fix splits
 *      the two — `api.ts` throws a {@link ForbiddenError} on 403 and the page
 *      renders an access-denied panel, while a 401 (no session) restarts login.
 *   2. A 401 that login can't clear (KC session genuinely gone) re-redirected
 *      on every reload. The fix is a 10s sessionStorage loop guard in
 *      `startLogin()`: it redirects at most once, then `App` shows
 *      "Couldn't sign you in." instead of spinning.
 *
 * Historically a third path existed — the removed `users` domain proxied to P1
 * and a downstream 401 had to be translated to a 502 so the SPA wouldn't read
 * it as session-expiry. That code went away with the `users` domain, so it is
 * deliberately NOT asserted here; billing/trading make no such upstream call.
 *
 * The three tests below are the two halves that actually ship: the BFF emits
 * the right status (Part A), and the SPA reacts without looping (Parts B, C).
 * Parts B and C mock the BFF responses so the exact 401-vs-403 branch is
 * exercised deterministically, with no dependency on the demo's role seed
 * (which gives `tim1` every role, so a real 403 is unreachable end-to-end).
 */

/** A representative authenticated-only data endpoint per subdomain. */
const API_PROBE: Record<TenantSlug, string> = {
  billing: '/api/billing/summary',
  trading: '/api/trading/portfolio',
};

test.describe('no re-login loop — 401 vs 403 differentiation', () => {
  // PART A — the BFF contract the SPA keys on. An unauthenticated request must
  // answer 401 (the "start login" signal), never a 3xx redirect (which a
  // `fetch(credentials:'include')` could silently follow into a storm) and
  // never a 5xx. Cheap and reliable: no login, just the raw status.
  test('unauthenticated /auth/me and /api/* return 401 (not a redirect or 5xx) on every subdomain', async () => {
    const api = await playwrightRequest.newContext({ ignoreHTTPSErrors: true });
    try {
      for (const slug of TENANT_SLUGS) {
        const me = await api.get(`${URLS.domains[slug]}/auth/me`, { maxRedirects: 0 });
        expect(me.status(), `${slug} /auth/me unauthenticated`).toBe(401);

        const data = await api.get(`${URLS.domains[slug]}${API_PROBE[slug]}`, { maxRedirects: 0 });
        expect(data.status(), `${slug} ${API_PROBE[slug]} unauthenticated`).toBe(401);
      }
    } finally {
      await api.dispose();
    }
  });

  // PART B — the loop guard. Simulate a login that can never establish a
  // session: /auth/me is always 401 and the login route bounces straight back
  // to the SPA root. Before the guard this looped forever; now the SPA
  // redirects exactly once, then renders the "Couldn't sign you in." panel.
  test('the SPA stops the login storm after exactly one guarded redirect', async ({ page }) => {
    let silentHits = 0;
    const REDIRECT_CAP = 4; // backstop: if the guard were broken, don't hang the run

    // No session, ever.
    await page.route('**/auth/me', (route) =>
      route.fulfill({ status: 401, contentType: 'application/json', body: '{}' })
    );
    // The login route hands the browser back to the SPA root WITHOUT a session —
    // exactly the condition that used to produce an infinite
    // 401 → login → callback → 401 → … storm. (Use a tiny HTML page that
    // re-navigates, rather than a 302, so it works regardless of how the
    // Playwright route layer treats redirect responses.)
    await page.route('**/oauth/login/silent**', (route) => {
      silentHits += 1;
      const body =
        silentHits <= REDIRECT_CAP
          ? '<!doctype html><title>login</title><script>location.replace("/")</script>'
          : '<!doctype html><title>login</title>'; // backstop: stop bouncing
      route.fulfill({ status: 200, contentType: 'text/html', body });
    });

    await page.goto(URLS.domains.billing);

    // App's loop-guard branch. "sign you in" is unique to the error panel —
    // the transient "Redirecting to sign in…" splash says "sign in", not
    // "sign you in".
    await expect(page.locator('text=sign you in')).toBeVisible({ timeout: 20_000 });
    expect(silentHits, 'login redirect must fire at most once (loop guard)').toBe(1);
  });

  // PART C — 403 must never start a login. With a live session (/auth/me 200)
  // but every data call forbidden, the page renders access-denied and the SPA
  // never navigates to the login route. This is the half of the fix that keeps
  // a wrong-role user out of the login → 403 → login loop.
  test('a 403 on a data call shows access-denied and never redirects to login', async ({ page }) => {
    let loginAttempts = 0;
    page.on('request', (req) => {
      if (req.url().includes('/oauth/login/')) loginAttempts += 1;
    });

    // Authenticated: /auth/me succeeds so the dashboard mounts and clears the
    // loop guard. Shape mirrors MeResponse in fixtures/auth.ts.
    await page.route('**/auth/me', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          authenticated: true,
          username: 'tim1',
          email: 'tim.a@geo.com',
          firmCd: '1',
          personId: 'P-tim',
          tenantIdentity: 'tim1',
          activeTenant: 'billing',
          roles: ['client'],
        }),
      })
    );
    // …but every billing data call is forbidden (authenticated, wrong role).
    await page.route('**/api/billing/**', (route) =>
      route.fulfill({
        status: 403,
        contentType: 'application/json',
        body: '{"error":"forbidden"}',
      })
    );

    await page.goto(URLS.domains.billing);

    // OverviewPage's forbidden branch — proof the 403 surfaced as access-denied
    // rather than being retried into a login bounce.
    await expect(page.locator('text=have access to billing')).toBeVisible({ timeout: 20_000 });
    // The whole point of the fix: a forbidden user is NEVER sent to the IdP.
    expect(loginAttempts, 'a 403 must not start a login redirect').toBe(0);
  });
});
