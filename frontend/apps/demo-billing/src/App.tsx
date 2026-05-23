import { useAuth } from './auth/AuthProvider';

export function App() {
  const auth = useAuth();

  if (!auth.ready) {
    return (
      <div className="page">
        <div className="card">
          <p className="muted">Loading&hellip;</p>
        </div>
      </div>
    );
  }

  return (
    <div className="page">
      <div className="card">
        <header>
          <h1>Demo Billing</h1>
          <p className="muted">Under construction</p>
        </header>

        <section className="construction">
          <div className="construction-icon" aria-hidden="true">&#x1F6A7;</div>
          <p>This area is coming soon. For now it just confirms the SSO
            wiring works end-to-end.</p>
        </section>

        {auth.authenticated && (
          <section className="who">
            <p>Signed in as <strong>{auth.username}</strong></p>
            {auth.email && <p className="muted small">{auth.email}</p>}
            <button type="button" onClick={auth.logout}>Sign out</button>
          </section>
        )}
      </div>
    </div>
  );
}
