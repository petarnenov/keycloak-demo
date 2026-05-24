import { useEffect, useState } from 'react';
import { useAuth } from './auth/AuthProvider';
import { billingApi, type Summary, type Invoice, type UsageLine } from './api';

export function App() {
  const auth = useAuth();
  const [summary, setSummary] = useState<Summary | null>(null);
  const [invoices, setInvoices] = useState<Invoice[] | null>(null);
  const [usage, setUsage] = useState<UsageLine[] | null>(null);
  const [period, setPeriod] = useState<{ start: string; end: string } | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!auth.authenticated) return;
    let cancelled = false;
    Promise.all([billingApi.summary(), billingApi.invoices(), billingApi.usage()])
      .then(([s, i, u]) => {
        if (cancelled) return;
        setSummary(s);
        setInvoices(i.invoices);
        setUsage(u.lines);
        setPeriod({ start: u.periodStart, end: u.periodEnd });
      })
      .catch((e: Error) => {
        if (cancelled) return;
        setError(e.message);
      });
    return () => {
      cancelled = true;
    };
  }, [auth.authenticated]);

  if (!auth.ready) {
    return (
      <div className="page">
        <div className="card"><p className="muted">Loading&hellip;</p></div>
      </div>
    );
  }

  return (
    <div className="page">
      <div className="card wide">
        <header>
          <h1>Demo Billing</h1>
          <p className="muted">Account overview &amp; usage</p>
        </header>

        {error && <p className="error">Failed to load: {error}</p>}

        {summary && (
          <section className="summary">
            <div className="summary-row">
              <span className="label">Account</span>
              <span>{summary.accountId}</span>
            </div>
            <div className="summary-row">
              <span className="label">Plan</span>
              <span>{summary.plan} <span className="muted small">(renews {summary.planRenewsOn})</span></span>
            </div>
            <div className="summary-row">
              <span className="label">Current balance</span>
              <span>{money(summary.currentBalance, summary.currency)}</span>
            </div>
            <div className="summary-row">
              <span className="label">Next invoice</span>
              <span>{money(summary.nextInvoiceAmount, summary.currency)} on {summary.nextInvoiceDate}</span>
            </div>
            <div className="summary-row">
              <span className="label">Payment method</span>
              <span>{summary.paymentMethod.brand} &middot;&middot;&middot;&middot; {summary.paymentMethod.last4} ({summary.paymentMethod.expires})</span>
            </div>
          </section>
        )}

        {invoices && (
          <section className="block">
            <h2>Recent invoices</h2>
            <table>
              <thead>
                <tr><th>Number</th><th>Issued</th><th>Due</th><th>Amount</th><th>Status</th></tr>
              </thead>
              <tbody>
                {invoices.map((inv) => (
                  <tr key={inv.number}>
                    <td>{inv.number}</td>
                    <td>{inv.issuedOn}</td>
                    <td>{inv.dueOn}</td>
                    <td>{money(inv.amount, inv.currency)}</td>
                    <td><span className={`pill pill-${inv.status}`}>{inv.status}</span></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        )}

        {usage && period && (
          <section className="block">
            <h2>Usage <span className="muted small">({period.start} → {period.end})</span></h2>
            <ul className="usage-list">
              {usage.map((line) => {
                const pct = Math.min(100, Math.round((line.used / line.included) * 100));
                return (
                  <li key={line.metric}>
                    <div className="usage-head">
                      <span>{line.metric}</span>
                      <span className="muted small">{line.used.toLocaleString()} / {line.included.toLocaleString()} {line.unit}</span>
                    </div>
                    <div className="bar"><div className="bar-fill" style={{ width: `${pct}%` }} /></div>
                  </li>
                );
              })}
            </ul>
          </section>
        )}

        {auth.authenticated && (
          <section className="who">
            <p>Signed in as <strong>{auth.username}</strong></p>
            {auth.email && <p className="muted small">{auth.email}</p>}
            <p className="muted small">
              Firm: <strong>{auth.firmCd ?? '—'}</strong>
              {' · '}Roles: <strong>{auth.roles.length ? auth.roles.join(', ') : '—'}</strong>
            </p>
            <button type="button" onClick={auth.logout}>Sign out</button>
          </section>
        )}
      </div>
    </div>
  );
}

function money(amount: number, currency: string): string {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(amount);
}
