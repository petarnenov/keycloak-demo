import { keycloak } from './auth/keycloak';

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
  await keycloak.updateToken(30).catch(() => {
    /* fall through and let fetch surface a 401 */
  });
  const res = await fetch(path, {
    headers: { Authorization: `Bearer ${keycloak.token ?? ''}` }
  });
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
