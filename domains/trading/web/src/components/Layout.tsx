import type { ReactNode } from 'react';
import { NavLink } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';

// Dashboard shell: a fixed sidebar with the two top-level destinations and
// the signed-in identity / sign-out at the foot, plus a content area that
// renders the active route's page.

const navLinkClass = ({ isActive }: { isActive: boolean }) =>
  isActive ? 'nav-link active' : 'nav-link';

// Cross-domain SSO link to the billing SPA — see billing/web/src/components/
// Layout.tsx for the design rationale. P1 swaps the identity for this
// physical person and brokers a billing-side session as the bound identity.
const P1_IDP_SSO_URL =
  (import.meta.env.VITE_P1_IDP_SSO_URL as string | undefined) ??
  'http://localhost:8888/saml/idp/sso.do';
const BILLING_HOME =
  (import.meta.env.VITE_BILLING_HOME as string | undefined) ??
  'https://billing.geowealth.int:5184/';

const SWITCH_TO_BILLING_HREF = (() => {
  const u = new URL(P1_IDP_SSO_URL);
  u.searchParams.set('targetClient', 'demo-billing-client');
  u.searchParams.set('RelayState', BILLING_HOME);
  return u.toString();
})();

export function Layout({ children }: { children: ReactNode }) {
  const auth = useAuth();

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">▦</span>
          <span>Demo Trading</span>
        </div>

        <nav className="nav">
          {/* `end` so "/" is only active on the exact path, not for /orders. */}
          <NavLink to="/" end className={navLinkClass}>
            Portfolio
          </NavLink>
          <NavLink to="/orders" className={navLinkClass}>
            Orders
          </NavLink>
          {/*
            Cross-domain SSO link — pre-flight hide: only rendered when
            /auth/me's linkedTargets list includes demo-billing-client
            (cross-domain-sso.md §8.6). See top-of-file note.
          */}
          {auth.linkedTargets.includes('demo-billing-client') && (
            <a href={SWITCH_TO_BILLING_HREF} className="nav-link">
              Switch to Billing →
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
