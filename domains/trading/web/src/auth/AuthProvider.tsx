import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { keycloak } from './keycloak';

interface AuthContextValue {
  ready: boolean;
  authenticated: boolean;
  username: string | null;
  email: string | null;
  firmCd: string | null;
  roles: string[];
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

// Same singleton guard as the billing domain (and the historical MFE shell):
// keycloak-js init() must run exactly once per page load. React 18 StrictMode
// double-invokes effects, so we cache the init Promise.
let initPromise: Promise<boolean> | null = null;
function initOnce(): Promise<boolean> {
  if (!initPromise) {
    initPromise = keycloak.init({
      onLoad: 'check-sso',
      silentCheckSsoFallback: false,
      checkLoginIframe: false,
      pkceMethod: 'S256'
    });
  }
  return initPromise;
}

function tokenClaim<T = unknown>(key: string): T | null {
  const parsed = keycloak.tokenParsed as Record<string, unknown> | undefined;
  return (parsed?.[key] as T | undefined) ?? null;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [ready, setReady] = useState(false);
  const [authenticated, setAuthenticated] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const startLogin = () =>
      keycloak.login({ idpHint: 'p1', redirectUri: window.location.origin + '/' });

    initOnce()
      .then((ok) => {
        if (cancelled) return;
        if (!ok) {
          startLogin();
          return;
        }
        setAuthenticated(true);
        setReady(true);
      })
      .catch(() => {
        // State mismatch when ?code=... arrives from an auth flow that
        // keycloak-js did not initiate (e.g. the P1 sidebar used to
        // hand-build the OIDC authorize URL). Recover by starting a
        // fresh keycloak-js-driven login.
        if (cancelled) return;
        startLogin();
      });

    // Front-channel logout signal: a sibling SPA (billing, p1, etc)
    // signed out, KC fanned out a logout iframe to this origin's
    // `frontchannel-logout.html`, that page wiped storage and
    // broadcast on the "auth" channel. We need to leave the dashboard
    // — but NOT call keycloak.logout() (that would loop the SLO
    // chain). Reload reruns keycloak.init({ check-sso }); KC has no
    // session for us anymore, so init resolves false → startLogin().
    let channel: BroadcastChannel | null = null;
    try {
      channel = new BroadcastChannel('auth');
      channel.onmessage = (event) => {
        if (event?.data?.type !== 'logout' || cancelled) return;
        // Guard against destroying in-flight work. The browser fires
        // `beforeunload` only when there are unsaved changes the page
        // has flagged via `event.preventDefault()` — we replicate that
        // signal by checking for any element with a `data-dirty="true"`
        // attribute (forms can set it as the user types). If something
        // looks unsaved, prompt; otherwise reload silently.
        const dirty = document.querySelector('[data-dirty="true"]') !== null;
        if (dirty) {
          const ok = window.confirm(
            'You were signed out in another tab. Reload now and lose unsaved changes?'
          );
          if (!ok) return;
        }
        window.location.reload();
      };
    } catch {
      // BroadcastChannel unsupported (old browsers) — no front-channel
      // signal will reach this tab; refresh-token failure (~30 min)
      // will eventually drive the same outcome via init catch above.
    }

    return () => {
      cancelled = true;
      channel?.close();
    };
  }, []);

  const value: AuthContextValue = {
    ready,
    authenticated,
    username: authenticated ? tokenClaim<string>('preferred_username') : null,
    email: authenticated ? tokenClaim<string>('email') : null,
    firmCd: authenticated ? tokenClaim<string>('firmCd') : null,
    roles: authenticated ? tokenClaim<string[]>('roles') ?? [] : [],
    logout: () => keycloak.logout({ redirectUri: window.location.origin + '/' })
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
