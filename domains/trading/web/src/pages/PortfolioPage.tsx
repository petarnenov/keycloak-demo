import { usePortfolio, usePositions } from '../queries';
import { money, pct } from '../format';

export function PortfolioPage() {
  const portfolioQ = usePortfolio();
  const positionsQ = usePositions();

  const portfolio = portfolioQ.data;
  const positions = positionsQ.data?.positions;

  return (
    <div>
      <header className="page-head">
        <h1>Portfolio</h1>
        <p className="muted">Account summary &amp; open positions</p>
      </header>

      {(portfolioQ.isError || positionsQ.isError) && (
        <p className="error">
          Failed to load:{' '}
          {(portfolioQ.error as Error | null)?.message ??
            (positionsQ.error as Error | null)?.message}
        </p>
      )}

      <section className="panel">
        <h2>Summary</h2>
        {portfolioQ.isPending && <p className="muted">Loading&hellip;</p>}
        {portfolio && (
          <div className="summary">
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
          </div>
        )}
      </section>

      <section className="panel">
        <h2>Positions</h2>
        {positionsQ.isPending && <p className="muted">Loading&hellip;</p>}
        {positions && (
          <table>
            <thead>
              <tr>
                <th>Symbol</th><th>Name</th><th>Qty</th><th>Avg cost</th>
                <th>Last</th><th>Market value</th><th>P/L</th>
              </tr>
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
        )}
      </section>
    </div>
  );
}
