import type { ReactNode } from 'react';
import { NavLink } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';

// Dashboard shell: a fixed sidebar with the two top-level destinations and
// the signed-in identity / sign-out at the foot, plus a content area that
// renders the active route's page.

const navLinkClass = ({ isActive }: { isActive: boolean }) =>
  isActive ? 'nav-link active' : 'nav-link';

// Cross-domain SSO link to the trading SPA (cross-domain-sso.md §3): P1
// receives ?targetClient=<oidc-client-id>, resolves the linked identity for
// the current physical person, emits a SAML Response for that identity, and
// posts to KC's client-scoped broker endpoint. RelayState is the trading
// SPA URL KC will redirect to after the broker session is established;
// it must be on the OIDC client's redirect_uris list.
const P1_IDP_SSO_URL =
  (import.meta.env.VITE_P1_IDP_SSO_URL as string | undefined) ??
  'http://localhost:8888/saml/idp/sso.do';
const TRADING_HOME =
  (import.meta.env.VITE_TRADING_HOME as string | undefined) ??
  'https://trading.geowealth.int:5185/';

const SWITCH_TO_TRADING_HREF = (() => {
  const u = new URL(P1_IDP_SSO_URL);
  u.searchParams.set('targetClient', 'demo-trading-client');
  u.searchParams.set('RelayState', TRADING_HOME);
  return u.toString();
})();

export function Layout({ children }: { children: ReactNode }) {
  const auth = useAuth();

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">▦</span>
          <span>Demo Billing</span>
        </div>

        <nav className="nav">
          {/* `end` so "/" is only active on the exact path, not for /invoices. */}
          <NavLink to="/" end className={navLinkClass}>
            Overview
          </NavLink>
          <NavLink to="/invoices" className={navLinkClass}>
            Invoices
          </NavLink>
          {/*
            Cross-domain SSO link: silent linked-identity swap to the trading
            domain. Top-level navigation (not router push) — we leave the
            billing app entirely. Pre-flight hide: only rendered when /auth/me's
            linkedTargets list says this user has an active binding to
            demo-trading-client (cross-domain-sso.md §8.6). Without the hide,
            clicking would land on P1's "no linked identity provisioned" 403,
            which is correct behaviour but a worse UX than just not showing
            the link in the first place.
          */}
          {auth.linkedTargets.includes('demo-trading-client') && (
            <a href={SWITCH_TO_TRADING_HREF} className="nav-link">
              Switch to Trading →
            </a>
          )}
        </nav>

        <div className="sidebar-foot">
          <p className="who-name">{auth.username ?? '—'}</p>
          {auth.email && <p className="muted small">{auth.email}</p>}
          <p className="muted small">
            Firm: <strong>{auth.firmCd ?? '—'}</strong>
          </p>
          <p className="muted small">
            Roles: <strong>{auth.roles.length ? auth.roles.join(', ') : '—'}</strong>
          </p>
          <button type="button" className="signout" onClick={auth.logout}>
            Sign out
          </button>
        </div>
      </aside>

      <main className="main">{children}</main>
    </div>
  );
}
