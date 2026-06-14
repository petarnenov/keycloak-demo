import { test, expect } from '@playwright/test';
import { URLS } from '../fixtures/config.js';
import { loginViaP1 } from '../fixtures/auth.js';
import { logoutAllRealmSessions } from '../fixtures/kcadmin.js';

/**
 * The person scope crosses firms: tim1 (firm 1) is the SAME PERSON as the
 * firm-5 advisor `john` (ENTITY_TBL.LINKED_GW_USER → tim1, seeded in V18). When
 * tim1 logs into the resource subdomain (billing), `/auth/me` must surface that
 * cross-firm membership keyed by the **real ENTITY_TBL.LDAP_UID** of each firm
 * account — `1:tim1` and `5:john` — never a symbolic alias like `5:tim5` (there
 * is no `tim5` row in ENTITY_TBL, so an alias would break any downstream
 * username lookup).
 *
 * This is the BFF/token-claim half of the firm-5 person link; the browser-driven
 * firm-switch onto the whitelabel host (becoming `john` in firm 5) is covered by
 * `p1-login-newtab-john.spec.ts`.
 *
 * History: this spec previously logged in via a credential form as a firm-5 user
 * `johnastim5` and expected `tenantIdentity=johnAstim5`. That predated the V18
 * design where the firm-5 account is `john` and is reachable ONLY via the SSO
 * firm-switch (no password / no credential login). Rewritten to the supported
 * flow and the real seeded identity.
 */
test.describe('BFF /auth/me firm-5 membership identity', () => {
  test.setTimeout(180_000);

  test.beforeEach(async () => {
    await logoutAllRealmSessions();
  });

  test('billing surfaces the real ENTITY_TBL.LDAP_UID for firm 5 (john), not a symbolic alias', async ({ page }) => {
    await loginViaP1(page, 'billing');

    const meResp = await page.request.get(`${URLS.domains.billing}/auth/me`, {
      headers: { Accept: 'application/json' },
    });
    expect(meResp.status(), '/auth/me must be authenticated').toBe(200);
    const me = (await meResp.json()) as {
      firmCd: string;
      activeTenant: string;
      memberships?: string[];
    };
    console.log('billing /auth/me =', JSON.stringify(me, null, 2));

    // billing is a TYPE_RESOURCE subdomain (serves any firm), so the login firm
    // is tim1's home firm (1) and activeTenant is "resource".
    expect(me).toMatchObject({ firmCd: '1', activeTenant: 'resource' });

    const memberships = me.memberships ?? [];
    // The person's two firm accounts, each keyed by its REAL LDAP_UID.
    expect(memberships, 'memberships includes the firm-1 login account').toContain('1:tim1');
    expect(memberships, 'memberships includes the firm-5 linked account (real LDAP_UID)')
      .toContain('5:john');
    // A symbolic `tim<firmCd>` alias would have no ENTITY_TBL row — never emit it.
    expect(memberships, 'memberships must NOT contain a non-existent symbolic alias')
      .not.toContain('5:tim5');
  });
});
