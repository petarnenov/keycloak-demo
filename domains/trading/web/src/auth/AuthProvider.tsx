import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';

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

// BFF / Token Handler model (IETF "OAuth 2.0 for Browser-Based Apps"): this SPA
// holds NO tokens. The BFF runs the OIDC code flow server-side, keeps the
// access/refresh tokens in a server session, and hands the browser only an
// httpOnly `TSESSION` cookie. So "who am I?" is a cookie-authenticated call to
// the BFF, and "log in" / "log out" are top-level navigations to BFF routes.
const LOGIN_URL = '/oauth/login/keycloak'; // BFF → KC authorize → (P1 via authenticateByDefault)
const LOGOUT_URL = '/auth/logout';              // BFF RP-initiated logout → KC → P1 SLO

interface Me {
  username: string;
  email: string | null;
  firmCd: string | null;
  roles: string[];
}

type MeResult = { status: 'ok'; me: Me } | { status: 'unauth' } | { status: 'error' };

async function fetchMe(): Promise<MeResult> {
  try {
    const res = await fetch('/auth/me', {
      credentials: 'include',
      headers: { Accept: 'application/json' }
    });
    if (res.status === 200) {
      return { status: 'ok', me: (await res.json()) as Me };
    }
    if (res.status === 401 || res.status === 403) {
      return { status: 'unauth' };
    }
    return { status: 'error' };
  } catch {
    return { status: 'error' };
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [ready, setReady] = useState(false);
  const [me, setMe] = useState<Me | null>(null);

  useEffect(() => {
    let cancelled = false;

    // BFF / Token Handler: ask the BFF who we are over the session cookie, once.
    // No client polling and no keepalive — a logout that happens out-of-band
    // (KC back-channel logout after a P1 logout, or Sign out) destroys the BFF
    // session server-side; the SPA finds out reactively on its next API call,
    // where api.ts turns a 401 into a redirect to the login route.
    (async () => {
      const r = await fetchMe();
      if (cancelled) return;
      if (r.status === 'ok') {
        setMe(r.me);
        setReady(true);
      } else {
        // 'unauth' (no session) or a transient 'error' on first load → start the
        // BFF login flow; if a KC session already exists it re-SSOs silently and
        // lands back here.
        window.location.assign(LOGIN_URL);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, []);

  const value: AuthContextValue = {
    ready,
    authenticated: me !== null,
    username: me?.username ?? null,
    email: me?.email ?? null,
    firmCd: me?.firmCd ?? null,
    roles: me?.roles ?? [],
    logout: () => window.location.assign(LOGOUT_URL)
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
