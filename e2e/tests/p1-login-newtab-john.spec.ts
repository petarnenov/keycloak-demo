import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { URLS, P1_CREDENTIALS, P1_PORT } from '../fixtures/config.js';
import { FIRM, p1LoginState, sessionFirm, expectLoggedIn } from '../fixtures/p1.js';
import { clientSessionCount, logoutAllRealmSessions } from '../fixtures/kcadmin.js';

/**
 * The reported scenario, 1:1:
 *
 *   "I'm logged into P1 as tim1. I open a new tab to john.localhost:8888 and
 *    expect to be silently logged in as john in firm 5, because that whitelabel
 *    host points to firm 5 and john is the same person as tim1."
 *
 * It used to land on the credential form. This proves the whole chain now works
 * end-to-end:
 *   - a plain P1 credential login mints a Keycloak SSO session (post-login
 *     establish round-trip),
 *   - opening the firm-5 whitelabel host in a SECOND tab auto-triggers silent
 *     SSO (no manual step),
 *   - john.localhost resolves to firm 5 (advisor whitelabel base URL),
 *   - the person-link (john.LINKED_GW_USER → tim1) drives the firm switch, so
 *     the new tab is established AS john in firm 5 — not tim1, not a login form.
 *   - the two tabs keep distinct per-host identities (tim1/firm1, john/firm5).
 *
 * Backing demo data: keycloak-demo/db/migration/V18__seed_firm5_john_link_whitelabel.sql.
 */

/** Firm-5 whitelabel host. `*.localhost` resolves to 127.0.0.1 with no hosts entry. */
const JOHN_HOST = `http://john.localhost:${P1_PORT}`;
/** john's P1 entity id — the firm-5 account of tim1's person (see V18). */
const JOHN_ENTITY_ID = '019E8978F9AE7676915751838956A526';

/** P1 data calls are slow on the dev box; give them headroom (mirrors p1.ts). */
const P1_REQUEST_TIMEOUT = 30_000;

/** The logged-in user's P1 entity UUID for the current cookie jar on a host. */
async function sessionUserUuid(reqCtx: APIRequestContext, host: string): Promise<string> {
  const res = await reqCtx.get(`${host}/react/isUserLoggedIn.do?reactRequest=true`, {
    timeout: P1_REQUEST_TIMEOUT,
  });
  const body = (await res.json()) as { geoUUID?: string; id?: string };
  return (body.geoUUID ?? body.id ?? '(none)').toUpperCase();
}

/** Drive the KC login form for tim1. P1 is an OIDC RP now — visiting
 *  {@code localhost:8080} bounces the browser to KC's login page (on
 *  {@code auth.geowealth.int:5180}); fill it and KC will issue a code back to
 *  P1's {@code /oidc/callback.do}. */
async function loginAsTim1OnP1(page: Page): Promise<void> {
  await page.goto(URLS.p1Base);
  await page.waitForURL(/auth\.geowealth\.int.*\/protocol\/openid-connect\/auth/, { timeout: 90_000 });
  await page.locator('input[name="username"]').fill(P1_CREDENTIALS.username);
  await page.locator('input[name="password"]').fill(P1_CREDENTIALS.password);
  await page.locator('input[type="submit"], button[type="submit"]').first().click();
  // Wait for the post-callback redirect back to the P1 React shell.
  await page.waitForURL(new RegExp(`^${URLS.p1Base.replace(/[/.]/g, '\\$&')}`), { timeout: 90_000 });
}

/** Poll Keycloak's admin API until the p1-client has a live SSO session. */
async function waitForKcSession(timeoutMs = 90_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  let count = 0;
  while (Date.now() < deadline) {
    count = await clientSessionCount('p1-client');
    if (count > 0) return;
    await new Promise((r) => setTimeout(r, 1_000));
  }
  throw new Error('P1 credential login never established a Keycloak SSO session');
}

test.describe('P1 login → open john.localhost in a new tab → silent login as john (firm 5)', () => {
  // Credential login + post-login KC establish + a second-tab silent-SSO
  // round-trip, all against the slow P1 dev box — well beyond the 90s default.
  test.setTimeout(240_000);

  // Start from zero KC sessions so "login mints a session" is unambiguous.
  test.beforeEach(async () => {
    await logoutAllRealmSessions();
  });

  test('a new tab on the firm-5 whitelabel host is silently established as john', async ({ browser }) => {
    const context = await browser.newContext({ ignoreHTTPSErrors: true });
    try {
      // ── Tab 1: log into P1 as tim1 (credential form on the canonical host).
      const tab1 = await context.newPage();
      await loginAsTim1OnP1(tab1);

      // P1 session is live on the canonical host, in tim1's home firm (1).
      await expectLoggedIn(tab1, URLS.p1Base);
      expect(await sessionFirm(tab1.request, URLS.p1Base), 'tim1 home firm').toBe(FIRM.geowealth);

      // The credential login also minted a Keycloak SSO session (the
      // post-login establish round-trip). Gate the second tab on it so the
      // silent-SSO probe there has a session to ride.
      await waitForKcSession();

      // ── New tab: open the firm-5 whitelabel host. No P1 session on this host,
      // so the SPA auto-triggers silent SSO; it completes against the KC
      // session and the firm switch re-establishes us AS john in firm 5.
      const tab2 = await context.newPage();
      await tab2.goto(JOHN_HOST);
      await expectLoggedIn(tab2, JOHN_HOST); // never lands on the credential form

      expect(await sessionFirm(tab2.request, JOHN_HOST), 'new tab firm context').toBe(FIRM.creativeOne);
      expect(await sessionUserUuid(tab2.request, JOHN_HOST), 'new tab is logged in AS john')
        .toBe(JOHN_ENTITY_ID);

      // ── No bleed: tab 1 is still tim1 in firm 1.
      expect(await p1LoginState(tab1.request, URLS.p1Base), 'tab1 still logged in').toBe('loggedUser');
      expect(await sessionFirm(tab1.request, URLS.p1Base), 'tab1 stays firm 1').toBe(FIRM.geowealth);
    } finally {
      await context.close();
    }
  });
});
