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
          {/*
            Linked identities — gw-admin only. The BFF endpoint is also
            @Secured("gwAdmin") and P1 re-checks gwAdminFlag, so a user who
            navigates here without the role gets 403 from the API anyway —
            hiding the nav link is purely UX.
          */}
          {auth.roles.includes('gwAdmin') && (
            <NavLink to="/linked-identities" className={navLinkClass}>
              Linked identities
            </NavLink>
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
