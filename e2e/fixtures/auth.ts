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

  // Login is a DIRECT authentication against Keycloak's own login form (the
  // `geowealth` theme), delegated to the User Storage SPI → user-service — no
  // SAML, no P1 login screen (docs/solution-architect/v2/03-login-flows.md).
  // Match Keycloak's stable form ids rather than P1's old React SAML page.
  const loggedInMarker = page.locator('text=personId:');
  const kcUsername = page.locator('#username');

  await Promise.race([
    loggedInMarker.waitFor({ state: 'visible', timeout: 90_000 }),
    kcUsername.waitFor({ state: 'visible', timeout: 90_000 }),
  ]);

  if (await kcUsername.isVisible().catch(() => false)) {
    await kcUsername.fill(P1_CREDENTIALS.username);
    await page.locator('#password').fill(P1_CREDENTIALS.password);
    await page.locator('#kc-login').click();
    await loggedInMarker.waitFor({ state: 'visible', timeout: 90_000 });
  }

  return fetchMe(page, tenant);
}

/**
 * Hit the BFF's `/auth/me` from the current browser context. Returns the
 * full claim shape. Throws on non-200 — callers expecting a 401 should use
 * {@link fetchMeStatus} instead.
 */
export async function fetchMe(page: Page, tenant: TenantSlug): Promise<MeResponse> {
  // 30s (not the 10s default actionTimeout): right after a fresh login the
  // forward-auth /auth/verify + a possible token refresh can be slow on a
  // loaded dev stack, and a warm-up timeout here fails the whole test.
  const response = await page.request.get(`${URLS.domains[tenant]}/auth/me`, { timeout: 30_000 });
  expect(response.status(), `/auth/me on ${tenant}`).toBe(200);
  return (await response.json()) as MeResponse;
}

/** Status-only variant for negative tests. */
export async function fetchMeStatus(context: BrowserContext, tenant: TenantSlug): Promise<number> {
  const response = await context.request.get(`${URLS.domains[tenant]}/auth/me`);
  return response.status();
}

/**
 * Drive the BFF logout and wait until KC's SSO cookie is gone. Logout is
 * POST-only (CSRF defence, M1), so this mirrors the SPA's "Sign out": a same-site
 * form POST from the tenant origin, which carries the SameSite=Lax session cookie.
 * Best-effort: when the redirect chain takes the browser to P1's login screen the
 * SLO completed; otherwise we still poll up to a few seconds.
 */
export async function logoutEverywhere(page: Page, tenant: TenantSlug): Promise<void> {
  const target = URLS.domains[tenant];
  // POST directly to the BFF's POST-only logout (M1) with the context's session
  // cookie. We deliberately do NOT load the SPA first: a fresh SPA load fires its
  // own /auth/me, which — with the session in a shared store and >1 replica (B2) —
  // can be in flight on another replica when logout deletes the session and then
  // re-persist it (test-only race; the real SPA never calls /auth/me during a
  // logout click). The POST runs the BFF's KC end-session, which back-channel-POSTs
  // the sibling BFFs, so the fan-out the spec asserts still happens.
  await page.request.post(`${target}/auth/logout`, { maxRedirects: 0 }).catch(() => {});
}

/**
 * Historically deleted the brokered KC user in `beforeAll` to force a fresh
 * first-broker-login. Now a deliberate no-op: on the current backing DB the
 * credential-form → SAML → first-broker-login re-link does NOT drive reliably
 * from Playwright (Keycloak stalls on a required-action page), which breaks the
 * very login the specs depend on. The specs assert architectural invariants
 * against the persistent brokered user, so a pristine user isn't needed.
 *
 * Kept as a stable seam (rather than ripped out of seven `beforeAll`s) so the
 * day the demo DB seeds a clean, programmatically-resettable user this can
 * become a real reset again in one place.
 */
export async function deleteDemoKcUser(): Promise<void> {
  // intentionally empty — see doc comment
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
