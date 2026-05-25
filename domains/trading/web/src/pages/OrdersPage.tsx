import { useOrders } from '../queries';
import { isForbidden } from '../api';
import { money } from '../format';

export function OrdersPage() {
  const ordersQ = useOrders();
  const orders = ordersQ.data?.orders;

  return (
    <div>
      <header className="page-head">
        <h1>Orders</h1>
        <p className="muted">Recent order activity</p>
      </header>

      {isForbidden(ordersQ.error) ? (
        <p className="error">
          You don&rsquo;t have access to trading. Ask an administrator for the
          trading-viewer or trading-trader role.
        </p>
      ) : ordersQ.isError && (
        <p className="error">Failed to load: {(ordersQ.error as Error).message}</p>
      )}

      <section className="panel">
        <h2>Recent orders</h2>
        {ordersQ.isPending && <p className="muted">Loading&hellip;</p>}
        {orders && (
          <table>
            <thead>
              <tr>
                <th>ID</th><th>Placed</th><th>Symbol</th><th>Side</th>
                <th>Qty</th><th>Type</th><th>Limit</th><th>Status</th>
              </tr>
            </thead>
            <tbody>
              {orders.map((o) => (
                <tr key={o.id}>
                  <td>{o.id}</td>
                  <td>{o.placedOn}</td>
                  <td><strong>{o.symbol}</strong></td>
                  <td className={o.side === 'buy' ? 'pos' : 'neg'}>{o.side}</td>
                  <td>{o.quantity}</td>
                  <td>{o.type}</td>
                  <td>{o.limitPrice == null ? '—' : money(o.limitPrice, 'USD')}</td>
                  <td><span className={`pill pill-${o.status}`}>{o.status}</span></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}
