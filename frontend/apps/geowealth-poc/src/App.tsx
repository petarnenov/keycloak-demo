import { useAuth } from './auth/AuthProvider';
import { firmFromHostname, firmDisplayName } from './firm';

export function App() {
  const auth = useAuth();
  const firmCode = firmFromHostname(window.location.hostname);
  const firmName = firmDisplayName(firmCode);

  if (!auth.ready) {
    return (
      <div className="page">
        <div className="card">
          <p className="muted">Loading…</p>
        </div>
      </div>
    );
  }

  return (
    <div className="page">
      <div className="card">
        <header>
          <h1>Sign in to {firmName}</h1>
          <p className="muted">Firm code: <code>{firmCode}</code></p>
        </header>

        {auth.authenticated ? (
          <section>
            <p>Signed in as <strong>{auth.username}</strong> ({auth.email}).</p>
            {auth.roles.length > 0 && (
              <p className="muted">Roles: {auth.roles.join(', ')}</p>
            )}
            <button type="button" onClick={auth.logout}>Sign out</button>
          </section>
        ) : (
          <section>
            <p className="muted">
              Click below to sign in. You'll be redirected to a Keycloak login
              page branded for <strong>{firmName}</strong>.
            </p>
            <button type="button" onClick={auth.login}>Sign in</button>
          </section>
        )}

        <footer>
          <p className="muted small">
            POC user: <code>poc-user</code> / <code>123</code>
          </p>
        </footer>
      </div>
    </div>
  );
}
