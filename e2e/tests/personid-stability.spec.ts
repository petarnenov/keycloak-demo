import { test, expect } from '@playwright/test';
import { PERSON, URLS } from '../fixtures/config.js';
import { deleteDemoKcUser, loginViaP1, fetchMeStatus } from '../fixtures/auth.js';

/**
 * Document § 2 / § 5 — Keycloak's broker-link table must join by the
 * <em>person</em> identifier, not by the per-login user. Re-logging in (after
 * an explicit sign-out, or after a fresh first-broker-login) must produce
 * the same {@code personId} every time. This is the architectural
 * pre-condition for the multi-username scenario from
 * {@link ../../cross-subdomain-sso-multi-username-analysis.md}.
 */
test.describe('personId stability across re-logins', () => {
  test.beforeAll(async () => {
    await deleteDemoKcUser();
  });

  test('login → logout → login produces the same personId', async ({ browser }) => {
    test.setTimeout(120_000);

    // First login — fresh KC user via first-broker-login.
    const ctxA = await browser.newContext({ ignoreHTTPSErrors: true });
    const pageA = await ctxA.newPage();
    const meA = await loginViaP1(pageA, 'billing');
    expect(meA.personId).toBe(PERSON.personId);
    // Sign out everywhere — KC SSO session must end so the next login is a
    // real re-login, not silent re-use.
    await pageA.goto(`${URLS.domains.billing}/auth/logout`, { waitUntil: 'commit' });
    await pageA.waitForLoadState('domcontentloaded', { timeout: 15_000 }).catch(() => {});
    // Give back-channel logout a beat to propagate.
    for (let i = 0; i < 20; i++) {
      const s = await fetchMeStatus(ctxA, 'billing');
      if (s === 401) break;
      await new Promise((r) => setTimeout(r, 250));
    }
    await ctxA.close();

    // Second login — fresh context so no KC SSO cookie carries over.
    const ctxB = await browser.newContext({ ignoreHTTPSErrors: true });
    const pageB = await ctxB.newPage();
    const meB = await loginViaP1(pageB, 'billing');
    expect(meB.personId, 'personId must be stable across logins for the same person').toBe(meA.personId);
    await ctxB.close();
  });

  test('personId stays stable even after the KC user is deleted (broker-link recreate)', async ({ browser }) => {
    test.setTimeout(120_000);

    // Fresh login → first-broker-login creates KC user.
    const ctxA = await browser.newContext({ ignoreHTTPSErrors: true });
    const pageA = await ctxA.newPage();
    const meA = await loginViaP1(pageA, 'billing');
    expect(meA.personId).toBe(PERSON.personId);
    await ctxA.close();

    // Wipe the KC user out-of-band, then log in again. The new KC user
    // will have a different KC `sub` (random UUID) but the same
    // `personId` claim — that's the source of truth in this model.
    await deleteDemoKcUser();

    const ctxB = await browser.newContext({ ignoreHTTPSErrors: true });
    const pageB = await ctxB.newPage();
    const meB = await loginViaP1(pageB, 'billing');
    expect(meB.personId).toBe(meA.personId);
    await ctxB.close();
  });
});
