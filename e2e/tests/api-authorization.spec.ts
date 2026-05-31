import { test, expect } from '@playwright/test';
import { URLS, PERSON } from '../fixtures/config.js';
import { deleteDemoKcUser, loginViaP1 } from '../fixtures/auth.js';

/**
 * BFF Tier-1 role gate. The {@code @Secured} list on each controller is the
 * coarse "can this user reach this domain" check; the per-tenant role
 * registry hands {@code P-tim} the right capability per subdomain
 * ({@code billing-admin} / {@code trading-trader}),
 * so all of these endpoints should answer 200.
 *
 * These specs also confirm that the BFF actually forwards the request and
 * returns domain data — not just that the gate lets it through — which is
 * the only end-to-end assertion that the full BFF / Token Handler chain
 * (cookie → session → access token → controller) works.
 */
test.describe('BFF /api authorization', () => {
  test.beforeAll(async () => {
    await deleteDemoKcUser();
  });

  test('billing — billing-admin can read summary, invoices, usage', async ({ page }) => {
    await loginViaP1(page, 'billing');

    const summary = await page.request.get(`${URLS.domains.billing}/api/billing/summary`);
    expect(summary.status()).toBe(200);
    const summaryJson = (await summary.json()) as Record<string, unknown>;
    expect(summaryJson).toHaveProperty('accountId');
    expect(summaryJson).toHaveProperty('plan');

    const invoices = await page.request.get(`${URLS.domains.billing}/api/billing/invoices`);
    expect(invoices.status()).toBe(200);
    const invoicesJson = (await invoices.json()) as { invoices: unknown[] };
    expect(Array.isArray(invoicesJson.invoices)).toBe(true);

    const usage = await page.request.get(`${URLS.domains.billing}/api/billing/usage`);
    expect(usage.status()).toBe(200);
  });

  test('trading — trading-trader can read portfolio, positions, orders', async ({ page }) => {
    await loginViaP1(page, 'trading');

    const portfolio = await page.request.get(`${URLS.domains.trading}/api/trading/portfolio`);
    expect(portfolio.status()).toBe(200);

    const positions = await page.request.get(`${URLS.domains.trading}/api/trading/positions`);
    expect(positions.status()).toBe(200);

    const orders = await page.request.get(`${URLS.domains.trading}/api/trading/orders`);
    expect(orders.status()).toBe(200);
  });

  test('firmCd is propagated to every BFF response', async ({ page }) => {
    // PERSON.tenants is keyed by slug; firmCd is realm-level and shared.
    for (const slug of ['billing', 'trading'] as const) {
      const _ = PERSON.tenants[slug]; // tie test to the registry intent
      await loginViaP1(page, slug);
      const me = await page.request.get(`${URLS.domains[slug]}/auth/me`);
      expect(me.status()).toBe(200);
      const meJson = (await me.json()) as { firmCd: string };
      // firmCd is the tenancy boundary; it must always be present and
      // non-empty so any downstream service can scope its queries.
      expect(meJson.firmCd).toBeTruthy();
      expect(meJson.firmCd).toBe('1');
    }
  });
});
