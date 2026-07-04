import { test, expect } from '@playwright/test';
import { URLS } from '../fixtures/config.js';
import { loginViaP1 } from '../fixtures/auth.js';
import { logoutAllRealmSessions } from '../fixtures/kcadmin.js';

/**
 * Phase B step 5a — the login-time capability map reaches the SPA, mirroring P1's
 * `LoggedUserJTO.permissions`. With fine-grained authz enabled, the
 * token-handler's `/auth/me` includes a `permissions` object keyed by
 * `<objectTypeCd>_<permissionCd>` (role-level, VIEW/CREATE/EXECUTE only) that the
 * authz-service serves from `/policy/capabilities`.
 *
 * tim1 holds BILLING_CENTER (59) EXECUTE (5) — the same capability the billing
 * tenant gate already enforces — so `permissions["59_5"]` must be present. This
 * is a client-side UI hint; the authoritative object decision stays server-side.
 *
 * Requires `AUTHZ_FINE_ENABLED=true` on the token-handler (the demo default is
 * off, in which case `permissions` is absent/empty and this spec is skipped).
 */
test.describe('PolicyRule capability map to the SPA (/auth/me permissions)', () => {
  test.setTimeout(180_000);

  test.beforeEach(async () => {
    await logoutAllRealmSessions();
  });

  test('billing /auth/me carries the <objType>_<perm> capability map with 59_5', async ({ page }) => {
    await loginViaP1(page, 'billing');

    const meResp = await page.request.get(`${URLS.domains.billing}/auth/me`, {
      headers: { Accept: 'application/json' },
    });
    expect(meResp.status(), '/auth/me must be authenticated').toBe(200);
    const me = (await meResp.json()) as { permissions?: Record<string, boolean> };
    console.log('billing /auth/me permissions =', JSON.stringify(me.permissions ?? null, null, 2));

    const permissions = me.permissions ?? {};
    test.skip(Object.keys(permissions).length === 0,
      'fine-grained authz disabled (AUTHZ_FINE_ENABLED != true) — no capability map to assert');

    // The role-level BILLING_CENTER EXECUTE capability, wire key "<objType>_<perm>".
    expect(permissions['59_5'], 'tim1 has BILLING_CENTER EXECUTE (59_5)').toBe(true);
    // MODIFY (2) is deliberately excluded from the capability map (VIEW/CREATE/EXECUTE only).
    for (const key of Object.keys(permissions)) {
      expect(key, 'capability map keys are <objectTypeCd>_<permissionCd>').toMatch(/^\d+_\d+$/);
      const perm = Number(key.split('_')[1]);
      expect([1, 3, 5], `permission ${perm} is VIEW/CREATE/EXECUTE only`).toContain(perm);
    }
  });
});
