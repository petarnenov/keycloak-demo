import { useEffect, useState } from 'react';
import { useAuth } from './auth/AuthProvider';
import { tradingApi, type Portfolio, type Position, type Order } from './api';

export function App() {
  const auth = useAuth();
  const [portfolio, setPortfolio] = useState<Portfolio | null>(null);
  const [positions, setPositions] = useState<Position[] | null>(null);
  const [orders, setOrders] = useState<Order[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!auth.authenticated) return;
    let cancelled = false;
    Promise.all([tradingApi.portfolio(), tradingApi.positions(), tradingApi.orders()])
      .then(([p, pos, ord]) => {
        if (cancelled) return;
        setPortfolio(p);
        setPositions(pos.positions);
        setOrders(ord.orders);
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
          <h1>Demo Trading</h1>
          <p className="muted">Portfolio &amp; orders</p>
        </header>

        {error && <p className="error">Failed to load: {error}</p>}

        {portfolio && (
          <section className="summary">
            <div className="summary-row">
              <span className="label">Account</span>
              <span>{portfolio.accountId}</span>
            </div>
            <div className="summary-row">
              <span className="label">Market value</span>
              <span>{money(portfolio.marketValue, portfolio.currency)}</span>
            </div>
            <div className="summary-row">
              <span className="label">Cash available</span>
              <span>{money(portfolio.cashAvailable, portfolio.currency)}</span>
            </div>
            <div className="summary-row">
              <span className="label">Day P/L</span>
              <span className={portfolio.dayPnl >= 0 ? 'pos' : 'neg'}>
                {money(portfolio.dayPnl, portfolio.currency)} ({pct(portfolio.dayPnlPct)})
              </span>
            </div>
            <div className="summary-row">
              <span className="label">YTD P/L</span>
              <span className={portfolio.ytdPnl >= 0 ? 'pos' : 'neg'}>
                {money(portfolio.ytdPnl, portfolio.currency)} ({pct(portfolio.ytdPnlPct)})
              </span>
            </div>
            <div className="summary-row">
              <span className="label">As of</span>
              <span className="muted small">{portfolio.asOf}</span>
            </div>
          </section>
        )}

        {positions && (
          <section className="block">
            <h2>Positions</h2>
            <table>
              <thead>
                <tr><th>Symbol</th><th>Name</th><th>Qty</th><th>Avg cost</th><th>Last</th><th>Market value</th><th>P/L</th></tr>
              </thead>
              <tbody>
                {positions.map((p) => (
                  <tr key={p.symbol}>
                    <td><strong>{p.symbol}</strong></td>
                    <td className="muted">{p.name}</td>
                    <td>{p.quantity}</td>
                    <td>{money(p.avgCost, 'USD')}</td>
                    <td>{money(p.lastPrice, 'USD')}</td>
                    <td>{money(p.marketValue, 'USD')}</td>
                    <td className={p.unrealized >= 0 ? 'pos' : 'neg'}>
                      {money(p.unrealized, 'USD')} ({pct(p.changePct)})
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        )}

        {orders && (
          <section className="block">
            <h2>Recent orders</h2>
            <table>
              <thead>
                <tr><th>ID</th><th>Placed</th><th>Symbol</th><th>Side</th><th>Qty</th><th>Type</th><th>Limit</th><th>Status</th></tr>
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

function pct(value: number): string {
  const sign = value >= 0 ? '+' : '';
  return `${sign}${value.toFixed(2)}%`;
}
