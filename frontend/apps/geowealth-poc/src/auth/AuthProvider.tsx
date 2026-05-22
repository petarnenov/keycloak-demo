import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { keycloak } from './keycloak';

interface AuthContextValue {
  ready: boolean;
  authenticated: boolean;
  username: string | null;
  email: string | null;
  roles: string[];
  login: () => void;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

// keycloak-js init() must run exactly once for the lifetime of the page. The
// pattern matches apps/shell/src/auth/AuthProvider.tsx — singleton init guarded
// by a module-level promise so React 18 StrictMode's double effect doesn't
// re-init.
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
        setAuthenticated(ok);
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
    username: tokenClaim<string>('preferred_username'),
    email: tokenClaim<string>('email'),
    roles: tokenClaim<string[]>('roles') ?? [],
    login: () => keycloak.login(),
    logout: () => keycloak.logout()
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
