import { Routes, Route, Navigate } from 'react-router-dom';
import { useAuth } from './auth/AuthProvider';
import { Layout } from './components/Layout';
import { OverviewPage } from './pages/OverviewPage';
import { InvoicesPage } from './pages/InvoicesPage';

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

  // Post-swap-logout landing (cross-domain-sso.md §8.5). The user explicitly
  // signed out of this audience via the cross-domain identity swap; do not
  // auto-redirect them back through KC (would loop them in as the source
  // identity, often without roles for this audience). Render an explicit
  // sign-in CTA.
  if (!auth.authenticated && auth.signedOutAfterSwap) {
    return (
      <div className="splash">
        <p>You&rsquo;ve been signed out of this domain.</p>
        <p className="muted small">
          Your other domain session (if any) is unaffected — that&rsquo;s the SoD
          guarantee: each linked identity has its own session lifecycle.
        </p>
        <button type="button" className="signout" onClick={() => window.location.assign('/oauth/login/keycloak')}>
          Sign back in
        </button>
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
        <Route path="/" element={<OverviewPage />} />
        <Route path="/invoices" element={<InvoicesPage />} />
        {/* Unknown path → land back on the overview. */}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </Layout>
  );
}
