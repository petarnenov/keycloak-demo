import { test, expect } from '@playwright/test';
import { URLS } from '../fixtures/config.js';
import { loginViaP1 } from '../fixtures/auth.js';
import { logoutAllRealmSessions } from '../fixtures/kcadmin.js';

/**
 * Per-user/per-firm whitelabel theming on the demo SPAs. The new BFF tier
 * surfaces a `wlcode` + brand display name on `/auth/me`, exposes a
 * `/auth/brand` JSON document (CSS variables + logo/favicon URLs + display
 * name), and the SPA's `BrandProvider` writes the CSS variables onto
 * `document.documentElement.style` so the Layout themes to the logged-in
 * user's firm. Mirrors P1's `App.updateWhiteLabel()` (App.js:295-417) +
 * `themeService` (themeService.js:23-37) contract.
 *
 * tim1 is firm 1 (`geowealth`), so this asserts the default-brand path end
 * to end: payload shape, applied CSS variable on the DOM, and the brand
 * display name rendered in the sidebar.
 */
test.describe('SPA whitelabel theming (per-session brand)', () => {
  test.setTimeout(180_000);

  test.beforeEach(async () => {
    await logoutAllRealmSessions();
  });

  test('billing: /auth/me + /auth/brand + DOM CSS vars + Layout brand', async ({ page }) => {
    const me = await loginViaP1(page, 'billing');

    // The wlcode + brandDisplayName the BFF surfaces come from whichever
    // BrandResolver backing source is active — P1's BrandingApiServlet when
    // POC_BRANDING_API_TOKEN is set, the inline app.brands.* fallback
    // otherwise. The contract is "non-empty + matches the rest of the
    // surface", not a specific brand identity.
    const meWithBrand = me as typeof me & { wlcode?: string; brandDisplayName?: string };
    expect(meWithBrand.wlcode, '/auth/me carries wlcode').toBeTruthy();
    expect(meWithBrand.brandDisplayName, '/auth/me carries brandDisplayName').toBeTruthy();

    // The brand endpoint returns the full shape: wlcode + display name +
    // logo/favicon URLs + the CSS-var map the SPA will apply on the DOM.
    const brandResp = await page.request.get(
      `${URLS.domains.billing}/auth/brand`,
      { headers: { Accept: 'application/json' } }
    );
    expect(brandResp.status(), '/auth/brand authenticated 200').toBe(200);
    const brand = (await brandResp.json()) as {
      wlcode: string;
      displayName: string;
      logoUrl: string | null;
      faviconUrl: string | null;
      cssVars: Record<string, string>;
    };
    // /auth/me and /auth/brand must agree on the active brand.
    expect(brand.wlcode).toBe(meWithBrand.wlcode);
    expect(brand.displayName).toBe(meWithBrand.brandDisplayName);
    // At least one css var must be present — this is the SPA contract.
    expect(Object.keys(brand.cssVars).length, 'cssVars not empty').toBeGreaterThan(0);
    // Every css-var key starts with `--` (resolver normalises both upstream
    // and inline-config inputs to that form).
    for (const k of Object.keys(brand.cssVars)) {
      expect(k.startsWith('--'), `var key ${k} starts with --`).toBe(true);
    }

    // The SPA's BrandProvider should have written at least one css-var entry
    // onto documentElement.style — exclusively the runtime path; the static
    // styles.css :root never sets these via inline style.
    const firstVar = Object.keys(brand.cssVars)[0];
    const firstVal = brand.cssVars[firstVar];
    await expect.poll(
      async () => page.evaluate(
        (key: string) => document.documentElement.style.getPropertyValue(key).trim(),
        firstVar
      ),
      { timeout: 10_000, message: `BrandProvider must apply ${firstVar}` }
    ).toBe(firstVal);

    // Layout renders the brand display name in the sidebar.
    await expect(page.locator('.sidebar .brand').first()).toContainText(brand.displayName);
    // Per data-attribute exposed by Layout so other CSS / debug can target it.
    await expect(page.locator('.app-shell').first()).toHaveAttribute('data-wlcode', brand.wlcode);
  });

  test('trading: same brand surface as billing for the same person', async ({ page }) => {
    const me = await loginViaP1(page, 'trading');

    const meWithBrand = me as typeof me & { wlcode?: string; brandDisplayName?: string };
    expect(meWithBrand.wlcode).toBeTruthy();
    expect(meWithBrand.brandDisplayName).toBeTruthy();

    const brandResp = await page.request.get(
      `${URLS.domains.trading}/auth/brand`,
      { headers: { Accept: 'application/json' } }
    );
    expect(brandResp.status()).toBe(200);
    const brand = (await brandResp.json()) as {
      wlcode: string; displayName: string; cssVars: Record<string, string>;
    };
    expect(brand.wlcode).toBe(meWithBrand.wlcode);
    expect(brand.displayName).toBe(meWithBrand.brandDisplayName);

    // Trading-side rendering: brand display name + the `Trading` suffix.
    await expect(page.locator('.sidebar .brand').first()).toContainText(brand.displayName);
    await expect(page.locator('.sidebar .brand').first()).toContainText('Trading');

    // SPA must apply at least one css var onto documentElement.
    const firstVar = Object.keys(brand.cssVars)[0];
    const firstVal = brand.cssVars[firstVar];
    await expect.poll(
      async () => page.evaluate(
        (key: string) => document.documentElement.style.getPropertyValue(key).trim(),
        firstVar
      ),
      { timeout: 10_000 }
    ).toBe(firstVal);
  });

  test('/auth/brand requires a session (401 when anonymous)', async ({ browser }) => {
    const ctx = await browser.newContext({ ignoreHTTPSErrors: true });
    try {
      const r = await ctx.request.get(`${URLS.domains.billing}/auth/brand`, {
        headers: { Accept: 'application/json' },
      });
      expect([401, 403]).toContain(r.status());
    } finally {
      await ctx.close();
    }
  });
});
