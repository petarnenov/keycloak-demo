import { useInvoices } from '../queries';
import { isForbidden } from '../api';
import { money } from '../format';

export function InvoicesPage() {
  const invoicesQ = useInvoices();
  const invoices = invoicesQ.data?.invoices;

  return (
    <div>
      <header className="page-head">
        <h1>Invoices</h1>
        <p className="muted">Recent custodian history</p>
      </header>

      {isForbidden(invoicesQ.error) ? (
        <p className="error">
          You don&rsquo;t have access to custodian. Ask an administrator for the
          custodian-viewer or custodian-admin role.
        </p>
      ) : invoicesQ.isError && (
        <p className="error">Failed to load: {(invoicesQ.error as Error).message}</p>
      )}

      <section className="panel">
        <h2>Recent invoices</h2>
        {invoicesQ.isPending && <p className="muted">Loading&hellip;</p>}
        {invoices && (
          <table>
            <thead>
              <tr>
                <th>Number</th><th>Issued</th><th>Due</th><th>Amount</th><th>Status</th>
              </tr>
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
        )}
      </section>
    </div>
  );
}
