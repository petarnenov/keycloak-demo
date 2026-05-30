import type { ReactNode } from 'react';
import { NavLink } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';

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
              cross-subdomain-sso-implementation.md. personId is the same on
              billing/users; tenantIdentity is the per-tenant alias for this
              subdomain. */}
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
    </div>
  );
}
