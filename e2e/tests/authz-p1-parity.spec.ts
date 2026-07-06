import { test, expect } from '@playwright/test';
import { URLS } from '../fixtures/config.js';
import { loginViaP1 } from '../fixtures/auth.js';
import { logoutAllRealmSessions } from '../fixtures/kcadmin.js';
import { groundTruthCapabilities } from '../fixtures/oracle.js';

/**
 * authz-service ↔ P1 PolicyRule sync — the differential parity harness from
 * docs/plans/2026-07-06-authz-service-p1-sync.md (gates P0 / P2).
 *
 * The two sides read the SAME Oracle: authz-service's `refine`/`canDo` READ the
 * pre-materialized `POLICY_RULE_TBL` that P1's PolicyRuleManager writes and also
 * reads, so those are parity-equivalent by construction. The ONE place
 * authz-service COMPUTES rather than reads a materialized row is the capability
 * map (`loadCreateExecutePermissions`). This spec pins that computation to the
 * DB ground truth — the exact ENTITY_ROLE × ROLE_PERMISSION × OBJECTTYPE_PERMISSION
 * join P1's AuthorizationManager uses — so both services necessarily agree.
 *
 * Mechanics (no user token minted, no realm change): the capability map surfaces
 * through the token-handler's authenticated `/auth/me` `permissions`; the ground
 * truth is read straight from the shared Oracle via kubectl. The spec skips when
 * the in-cluster DB isn't reachable or fine-grained authz is disabled.
 *
 * The demo schema has NO deny/override column on ROLE_PERMISSION_TBL (audited
 * 2026-07-06), so a plain JOIN is the correct capability computation — there is
 * no precedence subtlety to diverge on.
 */
const TIM1_ENTITY = '6459DFB4414B47DE9BFE9AC06205BD43';

test.describe('authz-service capability map == P1/Oracle ground truth', () => {
  test.setTimeout(180_000);

  test.beforeEach(async () => {
    await logoutAllRealmSessions();
  });

  test('billing /auth/me permissions equal the ENTITY_ROLE×ROLE_PERMISSION×OBJECTTYPE_PERMISSION join', async ({
    page,
  }) => {
    const truth = await groundTruthCapabilities(TIM1_ENTITY);
    test.skip(truth === null, 'in-cluster Oracle not reachable via kubectl — parity check skipped');

    await loginViaP1(page, 'billing');
    const meResp = await page.request.get(`${URLS.domains.billing}/auth/me`, {
      headers: { Accept: 'application/json' },
    });
    expect(meResp.status(), '/auth/me must be authenticated').toBe(200);
    const me = (await meResp.json()) as { permissions?: Record<string, boolean> };
    const authzKeys = new Set(Object.keys(me.permissions ?? {}));

    test.skip(
      authzKeys.size === 0,
      'AUTHZ_FINE_ENABLED != true — token-handler serves no capability map to compare'
    );

    // Sanity anchor: the BILLING_CENTER EXECUTE capability the billing tenant
    // gate enforces must be present in BOTH.
    expect(truth!.has('59_5'), 'ground truth holds 59_5').toBe(true);
    expect(authzKeys.has('59_5'), 'authz-service serves 59_5').toBe(true);

    // The whole computed map must equal the DB truth — this is the parity gate.
    const authzSorted = [...authzKeys].sort();
    const truthSorted = [...truth!].sort();
    const missingFromAuthz = truthSorted.filter((k) => !authzKeys.has(k));
    const extraInAuthz = authzSorted.filter((k) => !truth!.has(k));
    expect(
      { missingFromAuthz, extraInAuthz },
      'authz-service capability map must exactly match the Oracle ground truth'
    ).toEqual({ missingFromAuthz: [], extraInAuthz: [] });
  });
});
