import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import type { ShellProfile } from 'shell-api';
import { keycloak } from './keycloak';

interface AuthContextValue {
  ready: boolean;
  authenticated: boolean;
  username: string | null;
  email: string | null;
  roles: string[];
  login: () => void;
  logout: () => void;
  getToken: () => Promise<string | null>;
  loadProfile: () => Promise<ShellProfile | null>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

// keycloak-js init() must run exactly once for the lifetime of the page. React
// 18 StrictMode double-fires effects in dev, and the SPA can be re-mounted by
// router transitions, so we gate behind a module-level promise.
let initPromise: Promise<boolean> | null = null;
function initOnce(): Promise<boolean> {
  if (!initPromise) {
    initPromise = keycloak.init({
      onLoad: 'check-sso',
      silentCheckSsoRedirectUri: window.location.origin + '/silent-check-sso.html',
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

async function getFreshToken(): Promise<string | null> {
  if (!keycloak.authenticated) return null;
  try {
    await keycloak.updateToken(30);
  } catch {
    return null;
  }
  return keycloak.token ?? null;
}

async function loadProfile(): Promise<ShellProfile | null> {
  if (!keycloak.authenticated) return null;
  try {
    const profile = await keycloak.loadUserProfile();
    return {
      username: profile.username ?? '',
      email: profile.email ?? '',
      firstName: profile.firstName ?? '',
      lastName: profile.lastName ?? ''
    };
  } catch {
    return null;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [ready, setReady] = useState(false);
  const [authenticated, setAuthenticated] = useState(false);
  const queryClient = useQueryClient();

  useEffect(() => {
    let cancelled = false;
    initOnce()
      .then((ok) => {
        if (cancelled) return;
        setAuthenticated(ok);
        setReady(true);
      })
      .catch((err) => {
        console.error('Keycloak init error:', err);
        if (cancelled) return;
        setReady(true);
      });

    keycloak.onAuthSuccess = () => setAuthenticated(true);
    // Clearing the query cache on logout invalidates every MFE-visible query
    // (whoami, profile, BFF responses), so anything that re-renders against
    // an authenticated state automatically falls back to its anonymous branch
    // without per-MFE bookkeeping.
    keycloak.onAuthLogout = () => {
      setAuthenticated(false);
      queryClient.clear();
    };
    keycloak.onAuthRefreshError = () => {
      setAuthenticated(false);
      queryClient.clear();
    };

    return () => {
      cancelled = true;
      keycloak.onAuthSuccess = undefined;
      keycloak.onAuthLogout = undefined;
      keycloak.onAuthRefreshError = undefined;
    };
  }, [queryClient]);

  const value: AuthContextValue = {
    ready,
    authenticated,
    username: authenticated ? tokenClaim<string>('preferred_username') : null,
    email: authenticated ? tokenClaim<string>('email') : null,
    roles: authenticated ? keycloak.realmAccess?.roles ?? [] : [],
    // Land on the home page after auth; the Nav will show whichever MFE links
    // the user's roles unlock. The redirect matches the client's redirectUris
    // pattern in realm-export.json.
    //
    // idpHint='p1' forces Keycloak to skip its own login form and redirect
    // straight to the P1 SAML broker — demo-realm is P1-only auth, the local
    // username/password form would only ever be hit as an edge-case fallback.
    // Matches the SP-init kc_idp_hint=p1 pattern that the P1 sidebar entry
    // (Phase 8) uses; here we add it to the shell-initiated path too.
    login: () => keycloak.login({ redirectUri: window.location.origin + '/', idpHint: 'p1' }),
    logout: () => keycloak.logout(),
    getToken: getFreshToken,
    loadProfile
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
