import { useSummary, useUsage } from '../queries';
import { isForbidden } from '../api';
import { money } from '../format';

export function OverviewPage() {
  const summaryQ = useSummary();
  const usageQ = useUsage();

  const summary = summaryQ.data;
  const usage = usageQ.data;

  const forbidden = isForbidden(summaryQ.error) || isForbidden(usageQ.error);

  return (
    <div>
      <header className="page-head">
        <h1>Overview</h1>
        <p className="muted">Account summary &amp; usage</p>
      </header>

      {forbidden ? (
        <p className="error">
          You don&rsquo;t have access to billing. Ask an administrator for the
          billing-viewer or billing-admin role.
        </p>
      ) : (summaryQ.isError || usageQ.isError) && (
        <p className="error">
          Failed to load:{' '}
          {(summaryQ.error as Error | null)?.message ??
            (usageQ.error as Error | null)?.message}
        </p>
      )}

      <section className="panel">
        <h2>Summary</h2>
        {summaryQ.isPending && <p className="muted">Loading&hellip;</p>}
        {summary && (
          <div className="summary">
            <div className="summary-row">
              <span className="label">Account</span>
              <span>{summary.accountId}</span>
            </div>
            <div className="summary-row">
              <span className="label">Plan</span>
              <span>
                {summary.plan}{' '}
                <span className="muted small">(renews {summary.planRenewsOn})</span>
              </span>
            </div>
            <div className="summary-row">
              <span className="label">Current balance</span>
              <span>{money(summary.currentBalance, summary.currency)}</span>
            </div>
            <div className="summary-row">
              <span className="label">Next invoice</span>
              <span>
                {money(summary.nextInvoiceAmount, summary.currency)} on {summary.nextInvoiceDate}
              </span>
            </div>
            <div className="summary-row">
              <span className="label">Payment method</span>
              <span>
                {summary.paymentMethod.brand} &middot;&middot;&middot;&middot;{' '}
                {summary.paymentMethod.last4} ({summary.paymentMethod.expires})
              </span>
            </div>
          </div>
        )}
      </section>

      <section className="panel">
        <h2>
          Usage{' '}
          {usage && (
            <span className="muted small">
              ({usage.periodStart} → {usage.periodEnd})
            </span>
          )}
        </h2>
        {usageQ.isPending && <p className="muted">Loading&hellip;</p>}
        {usage && (
          <ul className="usage-list">
            {usage.lines.map((line) => {
              const pct = Math.min(100, Math.round((line.used / line.included) * 100));
              return (
                <li key={line.metric}>
                  <div className="usage-head">
                    <span>{line.metric}</span>
                    <span className="muted small">
                      {line.used.toLocaleString()} / {line.included.toLocaleString()} {line.unit}
                    </span>
                  </div>
                  <div className="bar"><div className="bar-fill" style={{ width: `${pct}%` }} /></div>
                </li>
              );
            })}
          </ul>
        )}
      </section>
    </div>
  );
}
