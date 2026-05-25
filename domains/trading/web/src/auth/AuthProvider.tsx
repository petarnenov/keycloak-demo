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
    let sessionPoll: ReturnType<typeof setInterval> | undefined;
    const startLogin = () =>
      keycloak.login({ idpHint: 'p1', redirectUri: window.location.origin + '/' });

    // Leave the dashboard after a logout that happened outside this tab.
    // Reloading reruns keycloak.init({ check-sso }); KC has no session for us
    // anymore, so init resolves false → startLogin(). We do NOT call
    // keycloak.logout() (that would loop the SLO chain). Forms / editors set
    // `data-dirty="true"` when they hold unsaved input; prompt before
    // discarding it, otherwise reload silently.
    const reloadForLogout = () => {
      if (cancelled) return;
      const dirty = document.querySelector('[data-dirty="true"]') !== null;
      if (dirty && !window.confirm(
        'You were signed out. Reload now and lose unsaved changes?'
      )) {
        return;
      }
      window.location.reload();
    };

    // Detect a Keycloak session that ended *outside* this tab — most importantly
    // a P1 (IdP) logout. That terminates the KC session but cannot front-channel
    // back to an already-open SPA tab: KC has to POST its SAML LogoutResponse to
    // P1 instead of rendering the per-client logout iframes, so KC logs "Some
    // clients have not been logged out" and our frontchannel-logout.html never
    // runs. checkLoginIframe would surface this too, but it leans on third-party
    // cookies that modern browsers block. Instead we force a refresh-token
    // round-trip on a timer; once the KC session is gone the refresh is rejected
    // and we converge to the front-channel-logout outcome — tell sibling tabs on
    // this origin, then reload into a fresh sign-in.
    const SESSION_POLL_MS = 20000;
    const onSessionLost = () => {
      if (cancelled) return;
      try {
        const ch = new BroadcastChannel('auth');
        ch.postMessage({ type: 'logout', source: 'session-poll' });
        ch.close();
      } catch {
        // No BroadcastChannel — the reload below still self-recovers this tab.
      }
      reloadForLogout();
    };

    initOnce()
      .then((ok) => {
        if (cancelled) return;
        if (!ok) {
          startLogin();
          return;
        }
        setAuthenticated(true);
        setReady(true);
        // minValidity past the access-token lifetime forces a real refresh on
        // every tick, so a server-side session kill surfaces within one poll.
        sessionPoll = setInterval(() => {
          keycloak.updateToken(Number.MAX_SAFE_INTEGER).catch(onSessionLost);
        }, SESSION_POLL_MS);
      })
      .catch(() => {
        // State mismatch when ?code=... arrives from an auth flow that
        // keycloak-js did not initiate (e.g. the P1 sidebar used to
        // hand-build the OIDC authorize URL). Recover by starting a
        // fresh keycloak-js-driven login.
        if (cancelled) return;
        startLogin();
      });

    // Front-channel logout signal: a sibling SPA (billing, p1, etc) signed out,
    // KC fanned out a logout iframe to this origin's `frontchannel-logout.html`,
    // that page wiped storage and broadcast "logout" on the "auth" channel.
    // (Same channel the session poll above uses.)
    let channel: BroadcastChannel | null = null;
    try {
      channel = new BroadcastChannel('auth');
      channel.onmessage = (event) => {
        if (event?.data?.type !== 'logout' || cancelled) return;
        reloadForLogout();
      };
    } catch {
      // BroadcastChannel unsupported (old browsers) — the session poll still
      // drives the same outcome on its next refresh round-trip.
    }

    return () => {
      cancelled = true;
      if (sessionPoll) clearInterval(sessionPoll);
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
