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
// httpOnly `BSESSION` cookie. So "who am I?" is a cookie-authenticated call to
// the BFF, and "log in" / "log out" are top-level navigations to BFF routes.
const LOGIN_URL = '/oauth/login/keycloak'; // BFF → KC authorize → (P1 via authenticateByDefault)
const LOGOUT_URL = '/logout';              // BFF RP-initiated logout → KC → P1 SLO

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

    // No session yet (first load) or session ended out-of-band → hand the
    // browser to the BFF login route, which 302s to Keycloak (and on to P1).
    const goLogin = () => window.location.assign(LOGIN_URL);

    const check = async (initial: boolean) => {
      const r = await fetchMe();
      if (cancelled) return;
      if (r.status === 'ok') {
        setMe(r.me);
        setReady(true);
      } else if (r.status === 'unauth') {
        // Definitive "no session". On first load → start login. While running →
        // this is the prompt-logout path: the BFF session was destroyed by KC's
        // back-channel logout (e.g. after a P1 / IdP logout), so leave for login.
        goLogin();
      } else if (initial) {
        // Transient error on first load — show the app shell as "redirecting";
        // a later poll will resolve. (Don't bounce to login on a network blip.)
        setReady(true);
      }
      // transient error while running → keep current state, retry next tick.
    };

    check(true);

    // Prompt session-end detection without keeping the federated KC session
    // alive: /auth/me only touches the BFF's own session cookie (no token
    // refresh against KC). After a back-channel logout the BFF session is gone →
    // 401 → we navigate to login. Re-check on focus and on a modest interval.
    const onFocus = () => check(false);
    window.addEventListener('focus', onFocus);
    const poll = setInterval(() => check(false), 30000);

    return () => {
      cancelled = true;
      window.removeEventListener('focus', onFocus);
      clearInterval(poll);
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
