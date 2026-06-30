import { Routes, Route, Navigate } from 'react-router-dom';
import { useAuth } from './auth/AuthProvider';
import { Layout } from './components/Layout';
import { OverviewPage } from './pages/OverviewPage';
import { InvoicesPage } from './pages/InvoicesPage';

export function App() {
  const auth = useAuth();

  // Login loop guard tripped — stop bouncing to the IdP and tell the user.
  if (auth.authError) {
    return (
      <div className="splash">
        <p className="error">Couldn&rsquo;t sign you in.</p>
        <p className="muted">Please reload the page, or contact your administrator if it persists.</p>
      </div>
    );
  }

  // AuthProvider drives redirect to login when there is no session; stay blank
  // until authenticated so we never flash a splash on the way to the IdP.
  if (!auth.authenticated) {
    return null;
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
