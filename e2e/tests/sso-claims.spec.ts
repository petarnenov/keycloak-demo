import { test, expect } from '@playwright/test';
import { PERSON, TENANT_SLUGS, URLS } from '../fixtures/config.js';
import { deleteDemoKcUser, loginViaP1, fetchMe } from '../fixtures/auth.js';

/**
 * Document § 3 — Keycloak subject = person_id. Same physical person across
 * tenants surfaces with the same `personId`, distinct per-tenant alias, and
 * distinct per-tenant role set. These tests pin the `(person, tenant) →
 * (alias, roles)` table from `PersonRegistry.java` to what the token
 * actually carries.
 */
test.describe('cross-subdomain SSO claims', () => {
  test.beforeAll(async () => {
    // Force a fresh first-broker-login so the existing KC user (if any)
    // doesn't poison the test with stale user attributes. See
    // `cross-subdomain-sso-implementation.md` § Multi-username support →
    // "One-time data migration".
    await deleteDemoKcUser();
  });

  test('billing emits the registry billing slot', async ({ page }) => {
    const me = await loginViaP1(page, 'billing');
    expect(me.personId).toBe(PERSON.personId);
    expect(me.activeTenant).toBe('billing');
    expect(me.tenantIdentity).toBe(PERSON.tenants.billing.identity);
    expect(me.email).toBe(PERSON.email);
    expect(me.roles).toEqual(expect.arrayContaining(PERSON.tenants.billing.roles));
    // Per-tenant scoping: the token must NOT carry another tenant's roles.
    // billing-admin yes, trading-trader no.
    expect(me.roles).not.toContain('trading-trader');
    expect(me.roles).not.toContain('users-viewer');
  });

  test('trading emits the registry trading slot', async ({ page }) => {
    const me = await loginViaP1(page, 'trading');
    expect(me.personId).toBe(PERSON.personId);
    expect(me.activeTenant).toBe('trading');
    expect(me.tenantIdentity).toBe(PERSON.tenants.trading.identity);
    expect(me.roles).toEqual(expect.arrayContaining(PERSON.tenants.trading.roles));
    expect(me.roles).not.toContain('billing-admin');
    expect(me.roles).not.toContain('users-viewer');
  });

  test('users emits the registry users slot', async ({ page }) => {
    const me = await loginViaP1(page, 'users');
    expect(me.personId).toBe(PERSON.personId);
    expect(me.activeTenant).toBe('users');
    expect(me.tenantIdentity).toBe(PERSON.tenants.users.identity);
    expect(me.roles).toEqual(expect.arrayContaining(PERSON.tenants.users.roles));
    expect(me.roles).not.toContain('billing-admin');
    expect(me.roles).not.toContain('trading-trader');
  });

  test('personId is stable across all three subdomains in one context', async ({ context }) => {
    // The architectural heart of the source document: ONE browser context
    // navigates all three subdomains, and the personId on each token must
    // be identical — that's what proves SSO is at the person level, not
    // per-tenant.
    const seen = new Map<string, { personId: string; tenantIdentity: string; roles: string[] }>();
    for (const slug of TENANT_SLUGS) {
      const page = await context.newPage();
      const me = await loginViaP1(page, slug);
      seen.set(slug, {
        personId: me.personId,
        tenantIdentity: me.tenantIdentity,
        roles: me.roles,
      });
      await page.close();
    }
    const personIds = new Set([...seen.values()].map((v) => v.personId));
    expect(personIds.size, 'personId must be identical across subdomains').toBe(1);
    expect([...personIds][0]).toBe(PERSON.personId);

    // Tenant identities must be DISTINCT — that's the per-(person, tenant)
    // alias the source document calls out.
    const tenantIdentities = new Set([...seen.values()].map((v) => v.tenantIdentity));
    expect(tenantIdentities.size, 'tenant_identity must vary per subdomain').toBe(3);

    // Spot-check each subdomain saw the right slot.
    for (const slug of TENANT_SLUGS) {
      expect(seen.get(slug)!.tenantIdentity).toBe(PERSON.tenants[slug].identity);
    }
  });
});
