import { request as playwrightRequest } from '@playwright/test';
import { URLS } from './config.js';

/**
 * Thin Keycloak admin-API helpers for asserting SSO-session state directly at
 * the realm, rather than inferring it from SPA behaviour. Used by the global
 * logout spec to prove the KC SSO session and every dependent client session
 * are actually gone, not just visually logged out.
 */

const CLIENTS = ['p1-self-client', 'demo-billing-client', 'demo-trading-client'] as const;
export type DemoClient = (typeof CLIENTS)[number];

async function adminToken(): Promise<string> {
  const api = await playwrightRequest.newContext({ ignoreHTTPSErrors: true });
  try {
    const res = await api.post(`${URLS.kcBase}/realms/master/protocol/openid-connect/token`, {
      form: { client_id: 'admin-cli', grant_type: 'password', username: 'admin', password: 'admin' },
    });
    if (!res.ok()) throw new Error(`KC admin token: ${res.status()}`);
    return ((await res.json()) as { access_token: string }).access_token;
  } finally {
    await api.dispose();
  }
}

/** Active user-session count for a single OIDC client in demo-realm. */
export async function clientSessionCount(client: DemoClient): Promise<number> {
  const token = await adminToken();
  const api = await playwrightRequest.newContext({ ignoreHTTPSErrors: true });
  try {
    const lookup = await api.get(
      `${URLS.kcBase}/admin/realms/${URLS.realm}/clients?clientId=${client}`,
      { headers: { Authorization: `Bearer ${token}` } }
    );
    const clients = (await lookup.json()) as Array<{ id: string }>;
    if (clients.length === 0) return 0;
    const sessions = await api.get(
      `${URLS.kcBase}/admin/realms/${URLS.realm}/clients/${clients[0].id}/user-sessions?max=50`,
      { headers: { Authorization: `Bearer ${token}` } }
    );
    return ((await sessions.json()) as unknown[]).length;
  } finally {
    await api.dispose();
  }
}

/**
 * Force-logout every session in demo-realm. Used in `beforeEach` so a global
 * logout test starts from a known-zero baseline — the browser context
 * otherwise accumulates several KC SSO sessions across separate interactive
 * logins, which makes "session count → 0" assertions flaky.
 */
export async function logoutAllRealmSessions(): Promise<void> {
  const token = await adminToken();
  const api = await playwrightRequest.newContext({ ignoreHTTPSErrors: true });
  try {
    await api.post(`${URLS.kcBase}/admin/realms/${URLS.realm}/logout-all`, {
      headers: { Authorization: `Bearer ${token}` },
    });
  } finally {
    await api.dispose();
  }
}

/** Total active sessions across all three demo clients. */
export async function totalDemoSessions(): Promise<Record<DemoClient, number>> {
  const entries = await Promise.all(
    CLIENTS.map(async (c) => [c, await clientSessionCount(c)] as const)
  );
  return Object.fromEntries(entries) as Record<DemoClient, number>;
}
