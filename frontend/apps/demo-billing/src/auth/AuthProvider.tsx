import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { keycloak } from './keycloak';

interface AuthContextValue {
  ready: boolean;
  authenticated: boolean;
  username: string | null;
  email: string | null;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

// keycloak-js init() must run exactly once per page load. Same singleton
// pattern as apps/shell/src/auth/AuthProvider.tsx — guards against
// React 18 StrictMode's double-effect.
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
    initOnce()
      .then((ok) => {
        if (cancelled) return;
        if (!ok) {
          // Force SAML federation through P1 — demo-realm is P1-only
          // (Phase 13 of WIP-SSO.md). idpHint=p1 instructs Keycloak to
          // skip its own login form and go straight to the P1 broker.
          keycloak.login({
            idpHint: 'p1',
            redirectUri: window.location.origin + '/'
          });
          return;
        }
        setAuthenticated(true);
        setReady(true);
      })
      .catch(() => {
        if (cancelled) return;
        setReady(true);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const value: AuthContextValue = {
    ready,
    authenticated,
    username: authenticated ? tokenClaim<string>('preferred_username') : null,
    email: authenticated ? tokenClaim<string>('email') : null,
    logout: () => keycloak.logout({ redirectUri: window.location.origin + '/' })
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
