import { Routes, Route, Navigate } from 'react-router-dom';
import { useAuth } from './auth/AuthProvider';
import { Layout } from './components/Layout';
import { PortfolioPage } from './pages/PortfolioPage';
import { OrdersPage } from './pages/OrdersPage';

export function App() {
  const auth = useAuth();

  // Logout in progress (set by postLogout, cleared once /auth/me succeeds
  // again). The browser is mid-navigation through /auth/logout → KC → P1 SLO
  // → re-login; rendering a splash here just flashes "Redirecting…" between
  // hops. Stay blank until we're authenticated again.
  if (auth.loggingOut && !auth.authenticated) {
    return null;
  }

  // BFF /auth/me in flight: nothing to route yet.
  if (!auth.ready) {
    return (
      <div className="splash">
        <p className="muted">Loading&hellip;</p>
      </div>
    );
  }

  // Login was attempted but we still have no session (loop guard tripped) —
  // stop bouncing to the IdP and tell the user, instead of an endless spinner.
  if (auth.authError) {
    return (
      <div className="splash">
        <p className="error">Couldn&rsquo;t sign you in.</p>
        <p className="muted">Please reload the page, or contact your administrator if it persists.</p>
      </div>
    );
  }

  // AuthProvider drives the redirect to P1 when there is no session; this
  // is the brief window before the browser leaves for the IdP.
  if (!auth.authenticated) {
    return (
      <div className="splash">
        <p className="muted">Redirecting to sign in&hellip;</p>
      </div>
    );
  }

  return (
    <Layout>
      <Routes>
        <Route path="/" element={<PortfolioPage />} />
        <Route path="/orders" element={<OrdersPage />} />
        {/* Unknown path → land back on the portfolio. */}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </Layout>
  );
}
