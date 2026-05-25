export interface Summary {
  accountId: string;
  plan: string;
  planRenewsOn: string;
  currency: string;
  currentBalance: number;
  nextInvoiceAmount: number;
  nextInvoiceDate: string;
  paymentMethod: { brand: string; last4: string; expires: string };
  source: string;
  username: string;
}

export interface Invoice {
  number: string;
  issuedOn: string;
  dueOn: string;
  amount: number;
  currency: string;
  status: 'paid' | 'open' | 'overdue';
}

export interface InvoiceList {
  invoices: Invoice[];
  source: string;
  username: string;
}

export interface UsageLine {
  metric: string;
  included: number;
  used: number;
  unit: string;
}

export interface UsageReport {
  periodStart: string;
  periodEnd: string;
  lines: UsageLine[];
  source: string;
  username: string;
}

const LOGIN_URL = '/oauth/login/keycloak';

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

// BFF / Token Handler: the SPA carries no token — it calls the BFF with the
// httpOnly session cookie (`credentials: 'include'`). The BFF attaches the
// user's access token server-side.
async function get<T>(path: string): Promise<T> {
  const res = await fetch(path, {
    credentials: 'include',
    headers: { Accept: 'application/json' }
  });
  // 403 = authenticated but not authorized → surface, never re-login.
  if (res.status === 403) {
    throw new ForbiddenError(path);
  }
  // 401 = no/expired BFF session → (re)start login once (loop-guarded).
  if (res.status === 401) {
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

export const billingApi = {
  summary:  () => get<Summary>('/api/billing/summary'),
  invoices: () => get<InvoiceList>('/api/billing/invoices'),
  usage:    () => get<UsageReport>('/api/billing/usage')
};
