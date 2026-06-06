import type { ReactNode } from 'react';
import { NavLink } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';
import { AiAssistant } from './AiAssistant';

// Dashboard shell: a fixed sidebar with the two top-level destinations and
// the signed-in identity / sign-out at the foot, plus a content area that
// renders the active route's page.

const navLinkClass = ({ isActive }: { isActive: boolean }) =>
  isActive ? 'nav-link active' : 'nav-link';

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
          {/* Cross-subdomain SSO visualisation — see
              cross-subdomain-sso-implementation.md. personId stays the same
              when navigating to a sibling subdomain; tenantIdentity changes
              to the per-tenant alias. */}
          <div className="sso-claims">
            <p className="muted small">
              personId: <strong>{auth.personId ?? '—'}</strong>
            </p>
            <p className="muted small">
              active_tenant: <strong>{auth.activeTenant ?? '—'}</strong>
            </p>
            <p className="muted small">
              tenant_identity: <strong>{auth.tenantIdentity ?? '—'}</strong>
            </p>
          </div>
          <button type="button" className="signout" onClick={auth.logout}>
            Sign out
          </button>
        </div>
      </aside>

      <main className="main">{children}</main>

      {/* Floating RAG assistant — renders a FAB + chat panel over the whole app. */}
      <AiAssistant />
    </div>
  );
}
