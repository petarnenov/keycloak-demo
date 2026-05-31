import { test, expect } from '@playwright/test';
import { TENANT_SLUGS } from '../fixtures/config.js';
import { deleteDemoKcUser, loginViaP1 } from '../fixtures/auth.js';

/**
 * Document § 3 — Keycloak subject = person_id. The same physical person is the
 * same `personId` on every subdomain. Asserted as an invariant of the
 * architecture (personId is stable across subdomains and every token carries a
 * coherent claim set), not against a hard-coded seed identity — so the suite
 * holds against whatever person the backing DB carries and survives the
 * resource-subdomain model where billing/trading share an active_tenant.
 */
test.describe('cross-subdomain SSO claims', () => {
  test.beforeAll(async () => {
    // Force a fresh first-broker-login so a stale KC user can't poison the run.
    await deleteDemoKcUser();
  });

  test('billing emits a complete claim set', async ({ page }) => {
    const me = await loginViaP1(page, 'billing');
    expect(me.personId, 'personId present').toBeTruthy();
    expect(me.email, 'email present').toBeTruthy();
    expect(me.tenantIdentity, 'tenant identity present').toBeTruthy();
    expect(me.activeTenant, 'active_tenant present').toBeTruthy();
    expect(Array.isArray(me.roles) && me.roles.length, 'a non-empty role set').toBeTruthy();
  });

  test('trading emits a complete claim set', async ({ page }) => {
    const me = await loginViaP1(page, 'trading');
    expect(me.personId, 'personId present').toBeTruthy();
    expect(me.tenantIdentity, 'tenant identity present').toBeTruthy();
    expect(me.activeTenant, 'active_tenant present').toBeTruthy();
    expect(Array.isArray(me.roles) && me.roles.length, 'a non-empty role set').toBeTruthy();
  });

  test('personId is identical across every subdomain in one context', async ({ context }) => {
    // The architectural heart of the source document: ONE browser context
    // visits every subdomain and the personId on each token is identical —
    // that is what proves SSO is at the person level, not per-tenant.
    const personIds = new Set<string>();
    for (const slug of TENANT_SLUGS) {
      const page = await context.newPage();
      const me = await loginViaP1(page, slug);
      expect(me.personId, `personId on ${slug}`).toBeTruthy();
      personIds.add(me.personId);
      await page.close();
    }
    expect(personIds.size, 'personId must be identical across subdomains').toBe(1);
  });
});
