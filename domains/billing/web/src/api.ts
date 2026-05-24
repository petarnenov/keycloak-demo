import { keycloak } from './auth/keycloak';

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

async function get<T>(path: string): Promise<T> {
  await keycloak.updateToken(30).catch(() => {
    // If refresh fails, fall through — the fetch below will surface a 401
    // and the AuthProvider's check-sso loop handles re-login.
  });
  const res = await fetch(path, {
    headers: { Authorization: `Bearer ${keycloak.token ?? ''}` }
  });
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
