import { Routes, Route, Navigate } from 'react-router-dom';
import { useAuth } from './auth/AuthProvider';
import { Layout } from './components/Layout';
import { PortfolioPage } from './pages/PortfolioPage';
import { OrdersPage } from './pages/OrdersPage';

export function App() {
  const auth = useAuth();

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
