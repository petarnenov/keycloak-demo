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

export const tradingApi = {
  portfolio: () => get<Portfolio>('/api/trading/portfolio'),
  positions: () => get<PositionsResponse>('/api/trading/positions'),
  orders:    () => get<OrdersResponse>('/api/trading/orders')
};
