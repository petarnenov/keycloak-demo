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

// BFF / Token Handler: the SPA carries no token — it calls the BFF with the
// httpOnly session cookie (`credentials: 'include'`). The BFF attaches the
// user's access token server-side. A 401 means the BFF session is gone (e.g.
// after a back-channel logout) → hand the browser to the BFF login route.
async function get<T>(path: string): Promise<T> {
  const res = await fetch(path, {
    credentials: 'include',
    headers: { Accept: 'application/json' }
  });
  if (res.status === 401 || res.status === 403) {
    window.location.assign('/oauth/login/keycloak');
    throw new Error(`${path} → ${res.status} (signed out)`);
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
