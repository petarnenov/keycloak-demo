import { Routes, Route, Navigate, useParams } from 'react-router-dom';
import { useAuth } from './auth/AuthProvider';
import { Layout } from './components/Layout';
import { UsersAndAccessPage } from './pages/UsersAndAccessPage';
import { PersonsPage } from './pages/PersonsPage';
import { FirmsPage } from './pages/FirmsPage';
import { useFirms } from './queries';

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
        <Route path="/" element={<UsersAndAccessRoute />} />
        <Route path="/users/:firmCd" element={<UsersAndAccessRoute />} />
        {/* Mirrors P1's path: /platformOne/firmAdmin/users/:firmCd? */}
        <Route path="/firmAdmin/users/:firmCd?" element={<UsersAndAccessRoute />} />
        <Route path="/persons" element={<PersonsPage />} />
        <Route path="/firms" element={<FirmsPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </Layout>
  );
}

function UsersAndAccessRoute() {
  const { firmCd } = useParams<{ firmCd?: string }>();
  const firmsQ = useFirms();

  // Lift the URL :firmCd if present; otherwise default to the caller's
  // own firmCd when the BFF knows it (non-gwAdmin users have one) — this
  // mirrors the redirect-to-own-firm effect in useUsersAndAccessData.
  const parsed = firmCd && !Number.isNaN(Number(firmCd)) ? Number(firmCd) : null;
  const effectiveFirmCd = parsed ?? firmsQ.data?.ownFirmCd ?? null;

  return <UsersAndAccessPage firmCd={effectiveFirmCd} firmsQuery={firmsQ} />;
}
