import type { ReactNode } from 'react';
import { NavLink } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';

// Dashboard shell: a fixed sidebar plus the active route. Single top-level
// destination for now — "Users & Access" — but the shell mirrors the other
// demo domains so adding sibling pages is just another <NavLink>.

const navLinkClass = ({ isActive }: { isActive: boolean }) =>
  isActive ? 'nav-link active' : 'nav-link';

export function Layout({ children }: { children: ReactNode }) {
  const auth = useAuth();

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">▤</span>
          <span>Demo Users</span>
        </div>

        <nav className="nav">
          <NavLink to="/" end className={navLinkClass}>
            Users &amp; Access
          </NavLink>
          <NavLink to="/persons" className={navLinkClass}>
            Persons
          </NavLink>
          <NavLink to="/firms" className={navLinkClass}>
            Firms
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
              cross-subdomain-sso-implementation.md. personId is identical to
              the value on billing/trading; tenantIdentity is this
              subdomain's per-tenant alias. */}
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
