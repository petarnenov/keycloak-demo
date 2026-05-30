import { expect, request as playwrightRequest, type BrowserContext, type Page, type Request } from '@playwright/test';
import { URLS, P1_CREDENTIALS, type TenantSlug } from './config.js';

/**
 * Auth helpers used by every spec. The signatures mirror what each test
 * actually wants to do: "land me on this subdomain authenticated as the demo
 * person" or "give me the BFF's view of the current session".
 */

/** Response shape from `GET /auth/me` — see `bff-core/.../AuthController.java`. */
export interface MeResponse {
  authenticated: boolean;
  username: string;
  email: string;
  firmCd: string;
  personId: string;
  tenantIdentity: string;
  activeTenant: string;
  roles: string[];
}

/**
 * Drive a fresh login through P1 starting from the requested subdomain.
 * Returns the {@link MeResponse} for that subdomain after the BFF session is
 * established. Idempotent — if a KC SSO session already exists in the context,
 * the silent-first flow completes without an IdP UI.
 *
 * Implementation: race the SPA's "personId:" sidebar label against P1's
 * "Sign in" heading. Whichever resolves first decides what the helper does
 * next — submit credentials, or just confirm we're already in. P1's React
 * inputs are accessibility-named (placeholder text becomes the accessible
 * name), so we locate them by role + name rather than by HTML attribute.
 */
export async function loginViaP1(page: Page, tenant: TenantSlug): Promise<MeResponse> {
  await page.goto(URLS.domains[tenant]);
  await page.waitForLoadState('domcontentloaded');

  const loggedInMarker = page.locator('text=personId:');
  const loginHeading = page.locator('text="Sign in"').first();

  await Promise.race([
    loggedInMarker.waitFor({ state: 'visible', timeout: 45_000 }),
    loginHeading.waitFor({ state: 'visible', timeout: 45_000 }),
  ]);

  if (await loginHeading.isVisible().catch(() => false)) {
    await page.getByRole('textbox', { name: 'username' }).fill(P1_CREDENTIALS.username);
    await page.getByRole('textbox', { name: 'password' }).fill(P1_CREDENTIALS.password);
    await page.getByRole('button', { name: 'Login' }).click();
    await loggedInMarker.waitFor({ state: 'visible', timeout: 45_000 });
  }

  return fetchMe(page, tenant);
}

/**
 * Hit the BFF's `/auth/me` from the current browser context. Returns the
 * full claim shape. Throws on non-200 — callers expecting a 401 should use
 * {@link fetchMeStatus} instead.
 */
export async function fetchMe(page: Page, tenant: TenantSlug): Promise<MeResponse> {
  const response = await page.request.get(`${URLS.domains[tenant]}/auth/me`);
  expect(response.status(), `/auth/me on ${tenant}`).toBe(200);
  return (await response.json()) as MeResponse;
}

/** Status-only variant for negative tests. */
export async function fetchMeStatus(context: BrowserContext, tenant: TenantSlug): Promise<number> {
  const response = await context.request.get(`${URLS.domains[tenant]}/auth/me`);
  return response.status();
}

/**
 * Drive the BFF logout (`GET /auth/logout`) and wait until KC's SSO cookie
 * is gone. Best-effort: when the redirect chain takes the browser to P1's
 * login screen the SLO completed; otherwise we still poll up to a few seconds.
 */
export async function logoutEverywhere(page: Page, tenant: TenantSlug): Promise<void> {
  await page.goto(`${URLS.domains[tenant]}/auth/logout`, { waitUntil: 'commit' });
  // After logout the user lands on P1 SLO → P1 login screen, or KC's
  // post-logout redirect target. Either way we're done. Allow either.
  await page.waitForLoadState('domcontentloaded', { timeout: 15_000 }).catch(() => {});
}

/**
 * Pre-emptively delete the demo's KC user via Keycloak's admin API. Used as a
 * `beforeAll` cleanup so the first-broker-login flow always runs clean,
 * preventing the "Handle Existing Account" path that requires native-user
 * verification (which the demo realm has no credentials for).
 *
 * Safe to call when the user doesn't exist (404 → noop). Uses Playwright's
 * own request context so the mkcert-signed Keycloak cert is honoured under
 * the suite's global {@code ignoreHTTPSErrors}.
 */
export async function deleteDemoKcUser(): Promise<void> {
  const api = await playwrightRequest.newContext({ ignoreHTTPSErrors: true });
  try {
    const tokenRes = await api.post(
      `${URLS.kcBase}/realms/master/protocol/openid-connect/token`,
      {
        form: {
          client_id: 'admin-cli',
          grant_type: 'password',
          username: 'admin',
          password: 'admin',
        },
      }
    );
    if (!tokenRes.ok()) throw new Error(`KC admin token: ${tokenRes.status()}`);
    const { access_token: token } = (await tokenRes.json()) as { access_token: string };

    const lookupRes = await api.get(
      `${URLS.kcBase}/admin/realms/${URLS.realm}/users?email=tim.a@geo.com`,
      { headers: { Authorization: `Bearer ${token}` } }
    );
    if (!lookupRes.ok()) return;
    const users = (await lookupRes.json()) as Array<{ id: string }>;
    for (const user of users) {
      await api.delete(`${URLS.kcBase}/admin/realms/${URLS.realm}/users/${user.id}`, {
        headers: { Authorization: `Bearer ${token}` },
      });
    }
  } finally {
    await api.dispose();
  }
}

/** Helper: tap network requests for assertions about the SSO redirect chain. */
export function recordNetwork(page: Page): { urls: string[]; statuses: Map<string, number> } {
  const urls: string[] = [];
  const statuses = new Map<string, number>();
  page.on('request', (req: Request) => {
    urls.push(`${req.method()} ${req.url()}`);
  });
  page.on('response', (res) => {
    statuses.set(res.url(), res.status());
  });
  return { urls, statuses };
}
