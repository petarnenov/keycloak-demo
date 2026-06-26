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
  // No inline style — visual properties come from `.auth-spa__tip` in the
  // theme stylesheet so React/FTL stays presentation-consistent with the
  // P1 LoginTemplate1 palette.
  return (
    <div className="auth-spa__tip">
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
