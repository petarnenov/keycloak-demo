import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { startLogin, clearLoginGuard } from '../api';

interface AuthContextValue {
  ready: boolean;
  authenticated: boolean;
  authError: boolean;
  username: string | null;
  email: string | null;
  firmCd: string | null;
  // Cross-subdomain SSO triple (cross-subdomain-sso-implementation.md):
  // personId is stable per physical person across subdomains, tenantIdentity
  // is the per-tenant alias the BFF received from KC, activeTenant is this
  // app's tenant slug ("custodian").
  personId: string | null;
  tenantIdentity: string | null;
  activeTenant: string | null;
  roles: string[];
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

// BFF / Token Handler model (IETF "OAuth 2.0 for Browser-Based Apps"): this SPA
// holds NO tokens. The BFF runs the OIDC code flow server-side, keeps the
// access/refresh tokens in a server session, and hands the browser only an
// httpOnly `GWSESSION` cookie. So "who am I?" is a cookie-authenticated call to
// the BFF, and "log in" / "log out" are top-level navigations to BFF routes.
const LOGOUT_URL = '/auth/logout';              // BFF RP-initiated logout → KC → P1 SLO

// Logout is state-changing (it ends the KC SSO session + every sibling BFF
// session), so the BFF requires POST as a CSRF defence: with the session cookie
// SameSite=Lax, the browser won't attach it to a cross-site POST, so a forged
// cross-site logout tears nothing down. Submit a real top-level form POST (not
// fetch) so the browser follows the BFF's 303 to P1's SLO as a same-origin
// navigation, carrying P1's JSESSIONID.
function postLogout(): void {
  const form = document.createElement('form');
  form.method = 'POST';
  form.action = LOGOUT_URL;
  document.body.appendChild(form);
  form.submit();
}

interface Me {
  username: string;
  email: string | null;
  firmCd: string | null;
  personId: string | null;
  tenantIdentity: string | null;
  activeTenant: string | null;
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
  const [authError, setAuthError] = useState(false);
  // Strict Mode double-mounts effects in dev; startLogin()'s sessionStorage loop
  // guard treats the second call as a failed round-trip and surfaces authError
  // before the browser leaves for /oauth/login/silent.
  const loginRedirectStarted = useRef(false);

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
        // Authenticated: reset the loop guard so a future genuine logout can
        // start login again.
        clearLoginGuard();
        setMe(r.me);
        setReady(true);
      } else {
        // 'unauth' (no session) or a transient 'error' on first load → start the
        // BFF login flow; if a KC session already exists it re-SSOs silently and
        // lands back here. startLogin() is loop-guarded: if we just came back
        // from login and still have no session, it returns false — stop instead
        // of bouncing to the IdP forever, and show an error.
        if (loginRedirectStarted.current) {
          // Strict Mode remount after the first effect already called startLogin().
          setReady(true);
          return;
        }
        loginRedirectStarted.current = true;
        const redirecting = startLogin();
        if (!redirecting) {
          setAuthError(true);
        }
        setReady(true);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, []);

  const value: AuthContextValue = {
    ready,
    authenticated: me !== null,
    authError,
    username: me?.username ?? null,
    email: me?.email ?? null,
    firmCd: me?.firmCd ?? null,
    personId: me?.personId ?? null,
    tenantIdentity: me?.tenantIdentity ?? null,
    activeTenant: me?.activeTenant ?? null,
    roles: me?.roles ?? [],
    logout: () => postLogout()
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
