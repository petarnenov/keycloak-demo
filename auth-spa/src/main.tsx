import React from 'react';
import { createRoot } from 'react-dom/client';

/**
 * Thin enhancement hook. The FTL template renders the working KC form
 * inside <main id="root">. We mount a small React tree as a SIBLING of the
 * form to add a contextual hint, not as a replacement — that way the
 * password POST still goes through KC's standard form-action and degrades
 * gracefully if JS is disabled.
 *
 * As the SPA grows it can take over the form rendering entirely (Phase 4+).
 */
const App: React.FC = () => {
  return (
    <div className="auth-spa__tip" style={{ fontSize: 12, color: '#64748b', marginTop: 12 }}>
      Secure sign-in powered by GeoWealth Identity.
    </div>
  );
};

const root = document.getElementById('root');
if (root) {
  const mount = document.createElement('div');
  mount.className = 'auth-spa__mount';
  root.appendChild(mount);
  createRoot(mount).render(<App />);
}
