// BFF / Token Handler: no token in the browser — calls carry the httpOnly
// session cookie; the BFF attaches the user's access token server-side.

export interface Portfolio {
  accountId: string;
  currency: string;
  marketValue: number;
  cashAvailable: number;
  dayPnl: number;
  dayPnlPct: number;
  ytdPnl: number;
  ytdPnlPct: number;
  asOf: string;
  source: string;
  username: string;
}

export interface Position {
  symbol: string;
  name: string;
  quantity: number;
  avgCost: number;
  lastPrice: number;
  marketValue: number;
  unrealized: number;
  changePct: number;
}

export interface PositionsResponse {
  positions: Position[];
  source: string;
  username: string;
}

export interface Order {
  id: string;
  symbol: string;
  side: 'buy' | 'sell';
  quantity: number;
  type: 'market' | 'limit';
  limitPrice: number | null;
  status: 'filled' | 'working' | 'cancelled';
  placedOn: string;
}

export interface OrdersResponse {
  orders: Order[];
  source: string;
  username: string;
}

const LOGIN_URL = '/oauth/login/keycloak';
// Step-up login URL: tells the BFF to forward `prompt=login` to Keycloak so
// KC re-authenticates the user. Used to clear a `linked_identity_acr` from a
// cross-domain swap before viewing sensitive endpoints (cross-domain-sso.md
// §4.4).
const STEP_UP_LOGIN_URL = '/oauth/login/keycloak?prompt=login';

// Thrown on 403: the BFF session is valid but the user's roles don't satisfy
// the endpoint. This is NOT a "signed out" state — bouncing a forbidden user
// back into the login flow just loops (login → callback → 403 → login …). So we
// surface it and the UI renders an access-denied state instead.
export class ForbiddenError extends Error {
  constructor(public readonly path: string) {
    super(`${path} → 403 (forbidden)`);
    this.name = 'ForbiddenError';
  }
}

export function isForbidden(err: unknown): err is ForbiddenError {
  return err instanceof ForbiddenError;
}

/**
 * RFC 9470 step-up signal: 401 + `WWW-Authenticate: Bearer
 * error="insufficient_user_authentication"`. See cross-domain-sso.md §4.4.
 */
export class StepUpRequiredError extends Error {
  constructor(public readonly path: string, public readonly acrRequired: string | null) {
    super(`${path} → 401 (step-up required; acr=${acrRequired ?? 'unknown'})`);
    this.name = 'StepUpRequiredError';
  }
}

export function isStepUpRequired(err: unknown): err is StepUpRequiredError {
  return err instanceof StepUpRequiredError;
}

export function startStepUpLogin(): void {
  window.location.assign(STEP_UP_LOGIN_URL);
}

// Loop guard for the reactive-401 → login redirect. A 401 means "no session",
// so we hand the browser to the BFF login route. But if we land back here and
// immediately get another 401, redirecting again creates an infinite
// login → callback → 401 → login storm. So redirect only if we haven't already
// tried within the window; otherwise give up and let the caller show an error.
// AuthProvider clears the marker on a successful /auth/me, so a later genuine
// logout can start login afresh.
const LOGIN_GUARD_KEY = 'bff:lastLoginRedirect';
const LOGIN_RETRY_WINDOW_MS = 10_000;

export function startLogin(): boolean {
  let last = 0;
  try { last = Number(sessionStorage.getItem(LOGIN_GUARD_KEY) ?? '0'); } catch { /* private mode */ }
  if (Date.now() - last < LOGIN_RETRY_WINDOW_MS) {
    return false; // bounced through login too recently → stop the storm
  }
  try { sessionStorage.setItem(LOGIN_GUARD_KEY, String(Date.now())); } catch { /* ignore */ }
  window.location.assign(LOGIN_URL);
  return true;
}

export function clearLoginGuard(): void {
  try { sessionStorage.removeItem(LOGIN_GUARD_KEY); } catch { /* ignore */ }
}

async function get<T>(path: string): Promise<T> {
  const res = await fetch(path, {
    credentials: 'include',
    headers: { Accept: 'application/json' }
  });
  // 403 = authenticated but not authorized → surface, never re-login.
  if (res.status === 403) {
    throw new ForbiddenError(path);
  }
  // 401 with `WWW-Authenticate: Bearer error="insufficient_user_authentication"`
  // (RFC 9470) is a step-up signal, not a sign-out. Surface as a distinct
  // error so the UI renders a "Re-authenticate" CTA.
  if (res.status === 401) {
    const wwwAuth = res.headers.get('WWW-Authenticate');
    if (wwwAuth && wwwAuth.includes('insufficient_user_authentication')) {
      const acrMatch = /acr_values="([^"]+)"/.exec(wwwAuth);
      throw new StepUpRequiredError(path, acrMatch ? acrMatch[1] : null);
    }
    // Genuine "no session" 401 → (re)start login once (loop-guarded).
    if (startLogin()) {
      throw new Error(`${path} → 401 (signed out, redirecting to login)`);
    }
    throw new Error(`${path} → 401 (login loop suppressed; please reload)`);
  }
  if (!res.ok) {
    throw new Error(`${path} → ${res.status}`);
  }
  return res.json() as Promise<T>;
}

export const tradingApi = {
  portfolio: () => get<Portfolio>('/api/trading/portfolio'),
  positions: () => get<PositionsResponse>('/api/trading/positions'),
  orders:    () => get<OrdersResponse>('/api/trading/orders')
};
