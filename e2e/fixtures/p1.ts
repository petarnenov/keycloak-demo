import { request as playwrightRequest, type APIRequestContext, type Page } from '@playwright/test';
import { P1_PORT, P1_CREDENTIALS } from './config.js';

/**
 * Helpers for the P1 whitelabel hosts. Unlike the BFF domains (billing/trading)
 * these are served by the P1 monolith itself on a single port, distinguished
 * only by hostname. They back the whitelabel cross-host SSO, firm-switch,
 * firm-resolution and global-logout specs.
 *
 * `*.localhost` resolves to 127.0.0.1 with no `/etc/hosts` entry, so any
 * subdomain works as a distinct host for cookie/whitelabel purposes. The port
 * (`P1_PORT`) depends on how the stack is exposed — see `config.ts`.
 */
export const P1_HOSTS = {
  /** Canonical P1 host — resolves to firm 1 (GeoWealth). */
  canonical: `http://localhost:${P1_PORT}`,
  /** Loopback IP — a host that matches no firm; must fall to the default. */
  loopback: `http://127.0.0.1:${P1_PORT}`,
  /** Arbitrary unmapped subdomain — must also fall to the default firm. */
  neutral: `http://foo.localhost:${P1_PORT}`,
  /** CreativeOne whitelabel host — resolves to firm 5 (c1wealth). */
  whitelabel: `http://c1wealth.localhost:${P1_PORT}`,
} as const;

/** Firm codes the demo data assigns to the hosts above. */
export const FIRM = {
  geowealth: 1,
  creativeOne: 5,
} as const;

/**
 * Anonymous firm resolution for a host: what `identifyFirmByUrl` (via
 * `ReactJsonIndexAction.indexCommonAsJSON`) maps the host to with NO session.
 * Each call uses a throwaway request context so it never carries a JSESSIONID
 * from a previous host (which would return that session's cached firm).
 */
/** P1's `isUserLoggedIn.do` returns the full permission set for a logged-in
 *  user — a large payload that can exceed the suite's 10s actionTimeout on the
 *  slow dev box. Give every P1 data call generous headroom. */
const P1_REQUEST_TIMEOUT = 30_000;

export async function resolveFirmAnonymous(host: string): Promise<number> {
  const api = await playwrightRequest.newContext();
  try {
    const res = await api.get(`${host}/react/indexCommonAsJSON.do?reactRequest=true`, {
      timeout: P1_REQUEST_TIMEOUT,
    });
    if (!res.ok()) throw new Error(`indexCommonAsJSON on ${host}: ${res.status()}`);
    const body = (await res.json()) as { firmNameCd?: number };
    if (typeof body.firmNameCd !== 'number') {
      throw new Error(`no firmNameCd in indexCommonAsJSON for ${host}`);
    }
    return body.firmNameCd;
  } finally {
    await api.dispose();
  }
}

/**
 * P1's login state for the current cookie jar on a host: the `objectType` of
 * `react/isUserLoggedIn.do` — `"loggedUser"` when a P1 session is live,
 * `"redirect"` when it is not.
 */
export async function p1LoginState(reqCtx: APIRequestContext, host: string): Promise<string> {
  const res = await reqCtx.get(`${host}/react/isUserLoggedIn.do?reactRequest=true`, {
    timeout: P1_REQUEST_TIMEOUT,
  });
  const text = await res.text();
  try {
    const body = JSON.parse(text) as { objectType?: string };
    return body.objectType ?? '(none)';
  } catch {
    // Once KcSessionProbe tears the session down, a follow-up isUserLoggedIn on
    // the stale cookie can get P1's SPA/login HTML instead of the JSON envelope.
    // Not the logged-in JSON state → treat as logged out ('redirect').
    return 'redirect';
  }
}

/** Session firm context for the current cookie jar on a host (logged-in view). */
export async function sessionFirm(reqCtx: APIRequestContext, host: string): Promise<number> {
  const res = await reqCtx.get(`${host}/react/indexCommonAsJSON.do?reactRequest=true`, {
    timeout: P1_REQUEST_TIMEOUT,
  });
  const body = (await res.json()) as { firmNameCd?: number };
  return body.firmNameCd ?? -1;
}

/**
 * Drive P1's silent-SSO entry point on a host. Assumes a live KC SSO session
 * in the browser context (otherwise KC answers `login_required` and P1 lands
 * on the login form). Resolves once P1 reports the session is established on
 * THIS host, proving the cross-host redirect_uri kept the round-trip on-host.
 */
export async function silentSsoP1(page: Page, host: string): Promise<void> {
  // OIDC RP era: silent SSO is the same /oidc/login.do flow — KC answers
  // immediately when an SSO session already exists, and the callback
  // resolves the whitelabel firm switch.
  await page.goto(`${host}/oidc/login.do`, { waitUntil: 'domcontentloaded' });
  await expectLoggedIn(page, host);
}

/**
 * Establish a fresh P1 (`p1-client`) KC SSO session on a host by driving P1's
 * own OIDC RP login. Unlike {@link silentSsoP1} this handles the cold-start
 * case where Keycloak renders its geowealth login form (`#username`/`#password`,
 * User Storage SPI). Resolves once P1 reports `loggedUser` on the host.
 *
 * Used by the P1→domain and domain→P1 SLO specs, which must create the KC SSO
 * session on the P1 surface FIRST (not via a domain), to exercise the
 * BFF-domain ↔ P1 direction the rest of the suite doesn't.
 */
export async function loginP1Interactive(page: Page, host: string): Promise<void> {
  await page.goto(`${host}/oidc/login.do`, { waitUntil: 'domcontentloaded' });
  const kcUsername = page.locator('#username');
  // Cold: KC's form appears. Warm: P1 already has a session and bounces back to
  // its React app. Race both so the helper is idempotent.
  await Promise.race([
    kcUsername.waitFor({ state: 'visible', timeout: 90_000 }),
    page.waitForURL(/\/react\//, { timeout: 90_000 }).catch(() => {}),
  ]);
  if (await kcUsername.isVisible().catch(() => false)) {
    await kcUsername.fill(P1_CREDENTIALS.username);
    const password = page.locator('#password');
    await password.fill(P1_CREDENTIALS.password);
    // Enter for a clean native submit (see auth.ts loginViaP1 for why not click).
    await password.press('Enter');
  }
  await expectLoggedIn(page, host);
}

/** Poll `isUserLoggedIn` until P1 reports `loggedUser` on the host (or throw). */
export async function expectLoggedIn(page: Page, host: string, timeoutMs = 45_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  let last = '(none)';
  while (Date.now() < deadline) {
    last = await p1LoginState(page.request, host);
    if (last === 'loggedUser') return;
    await page.waitForTimeout(500);
  }
  throw new Error(`P1 never reported loggedUser on ${host} (last=${last})`);
}
