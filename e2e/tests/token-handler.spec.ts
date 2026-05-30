import { test, expect } from '@playwright/test';
import { deleteDemoKcUser, loginViaP1 } from '../fixtures/auth.js';

/**
 * IETF "OAuth 2.0 for Browser-Based Apps" Token Handler model — the SPA
 * NEVER sees the OP tokens. The browser only carries the BFF's session
 * cookie (`BSESSION` / `TSESSION` / `USESSION`); access_token, id_token,
 * refresh_token live entirely server-side.
 */
test.describe('BFF / Token Handler — no tokens in the browser', () => {
  test.beforeAll(async () => {
    await deleteDemoKcUser();
  });

  test('only the BFF session cookie is observable to JS', async ({ page }) => {
    await loginViaP1(page, 'billing');

    // document.cookie is empty (BSESSION is httpOnly).
    const docCookies = await page.evaluate(() => document.cookie);
    expect(docCookies, 'no cookies visible to JS — BFF cookie must be httpOnly').toBe('');

    // The Playwright network layer sees the actual cookie. It must be the
    // session cookie, and it must not look like a JWT.
    const cookies = await page.context().cookies();
    const billingCookies = cookies.filter((c) => c.domain.includes('billing'));
    const session = billingCookies.find((c) => c.name === 'BSESSION');
    expect(session, 'BSESSION must be set').toBeDefined();
    expect(session!.httpOnly).toBe(true);
    expect(session!.secure).toBe(true);
    // A JWT has the shape `aaa.bbb.ccc`. A Micronaut session id is opaque.
    expect(session!.value.split('.').length).toBeLessThan(3);
  });

  test('no access_token / id_token leaks into storage', async ({ page }) => {
    await loginViaP1(page, 'billing');
    const storage = await page.evaluate(() => ({
      local: Object.fromEntries(
        Object.keys(localStorage).map((k) => [k, localStorage.getItem(k)])
      ),
      session: Object.fromEntries(
        Object.keys(sessionStorage).map((k) => [k, sessionStorage.getItem(k)])
      ),
    }));
    const flat = JSON.stringify(storage);
    expect(flat, 'no access_token leak').not.toMatch(/access_token/i);
    expect(flat, 'no id_token leak').not.toMatch(/id_token/i);
    expect(flat, 'no refresh_token leak').not.toMatch(/refresh_token/i);
    // A loose JWT shape check — three base64 segments separated by '.'.
    expect(flat, 'no JWT-shaped values in browser storage').not.toMatch(
      /eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\./
    );
  });
});
