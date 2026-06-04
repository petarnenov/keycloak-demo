import { test, expect, type Page } from '@playwright/test';
import { URLS, P1_CREDENTIALS } from '../fixtures/config.js';
import { logoutAllRealmSessions } from '../fixtures/kcadmin.js';

/**
 * The reported scenario:
 *   1. log into P1 as `johnastim5` (the firm-5 advisor linked to tim1 via
 *      ENTITY_TBL.LINKED_GW_USER → P-tim);
 *   2. open `billing.geowealth.int` in a 2nd tab — silent SSO should
 *      authenticate;
 *   3. `/auth/me` should surface `tenantIdentity` = `johnAstim5` (the
 *      *real* LDAP_UID of the firm-5 account, as found in ENTITY_TBL).
 *
 * Today it shows `tenantIdentity` = `tim5`, which is a P1 symbolic alias
 * that has no row in ENTITY_TBL — clearly wrong. This test pins the
 * expected behaviour: the BFF's view of the firm-5 identity must be a
 * username that actually exists.
 */

const P1_REQUEST_TIMEOUT = 30_000;

async function loginAsJohnastim5OnP1(page: Page): Promise<void> {
  await page.waitForTimeout(3_000);
  try {
    await page.goto(URLS.p1Base, { waitUntil: 'commit' });
  } catch (e) {
    if (!String(e).includes('interrupted by another navigation')) throw e;
  }
  await page.locator('text="Sign in"').first().waitFor({ state: 'visible', timeout: 90_000 });
  await page.getByRole('textbox', { name: 'username' }).fill('johnastim5');
  await page.getByRole('textbox', { name: 'password' }).fill(P1_CREDENTIALS.password);
  await page.getByRole('button', { name: 'Login' }).click();
  // The post-login establish round-trip lands back on `/`; wait for the
  // logged-in shell rather than a fixed timer.
  await page
    .locator('text="Welcome to Platform One"')
    .first()
    .waitFor({ state: 'visible', timeout: 90_000 })
    .catch(() => {});
}

test.describe('BFF /auth/me tenant identity for the firm-5 johnAstim5 account', () => {
  test.setTimeout(240_000);

  test.beforeEach(async () => {
    await logoutAllRealmSessions();
  });

  test('billing surfaces the real ENTITY_TBL.LDAP_UID for firm 5 (johnAstim5), not the symbolic tim5 alias', async ({ browser }) => {
    const context = await browser.newContext({ ignoreHTTPSErrors: true });
    try {
      // 1) Log into P1 as johnastim5 on the canonical host.
      const tab1 = await context.newPage();
      await loginAsJohnastim5OnP1(tab1);

      // 2) Open billing — the BFF's AuthProvider triggers /oauth/login/silent.
      const tab2 = await context.newPage();
      await tab2.goto('https://billing.geowealth.int:5184/', { waitUntil: 'load' });
      // Give the silent SSO chain time to complete and the SPA to settle.
      await tab2.waitForTimeout(8_000);

      // 3) Fetch /auth/me through the tab's context (cookies attached).
      const meResp = await tab2.request.get('https://billing.geowealth.int:5184/auth/me', {
        headers: { Accept: 'application/json' },
        timeout: P1_REQUEST_TIMEOUT,
      });
      expect(meResp.status(), '/auth/me must be authenticated').toBe(200);
      const me = await meResp.json();

      // Diagnostic dump — surfaces in CI when this fails again.
      console.log('billing /auth/me =', JSON.stringify(me, null, 2));

      // billing is configured as a TYPE_RESOURCE subdomain (`app.requirement.type=resource`)
      // — it serves any firm — so activeTenant is "resource" and tenantIdentity comes
      // from the login firm's membership entry (id_token `firmCd` claim).
      expect(me).toMatchObject({
        firmCd: '5',
        activeTenant: 'resource',
      });

      const memberships = (me.memberships ?? []) as string[];

      // The firm-5 entry in memberships MUST be the real ENTITY_TBL.LDAP_UID:
      // P-tim has one firm-5 account, `johnAstim5` (LINKED_GW_USER → tim1).
      // Today the membership emitted is `5:tim5` — there is no `tim5` row in
      // ENTITY_TBL, so it's a symbolic alias that breaks any downstream
      // username lookup.
      expect(memberships, 'memberships includes the firm-5 entry').toContain('5:johnAstim5');
      expect(memberships, 'memberships must NOT contain the non-existent tim5').not.toContain('5:tim5');
      expect(me.tenantIdentity, 'tenantIdentity for firm-5 login must be the real LDAP_UID')
        .toBe('johnAstim5');
    } finally {
      await context.close();
    }
  });
});
