import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { useAuth } from '../auth/AuthProvider';

// Demo-side analogue of P1's App.updateWhiteLabel (App.js:295-417) + themeService
// (themeService.js:23-37): after auth resolves, fetch /auth/brand for the
// session's wlcode and write the returned CSS variables onto
// document.documentElement so a Layout / styles.css that reads var(--accent)
// etc. instantly themes to the logged-in user's firm. Mirrors the contract
// the React app used against P1's /whitelabel/<code>/<code>_color_theme.json.

export interface Brand {
  wlcode: string;
  displayName: string | null;
  logoUrl: string | null;
  faviconUrl: string | null;
  cssVars: Record<string, string>;
}

interface BrandContextValue {
  ready: boolean;
  brand: Brand | null;
}

const BrandContext = createContext<BrandContextValue | null>(null);

async function fetchBrand(): Promise<Brand | null> {
  try {
    const res = await fetch('/auth/brand', {
      credentials: 'include',
      headers: { Accept: 'application/json' }
    });
    if (!res.ok) return null;
    return (await res.json()) as Brand;
  } catch {
    return null;
  }
}

function applyCssVars(vars: Record<string, string> | undefined): void {
  if (!vars) return;
  const root = document.documentElement;
  for (const [k, v] of Object.entries(vars)) {
    if (k.startsWith('--') && typeof v === 'string') {
      root.style.setProperty(k, v);
    }
  }
}

function applyFavicon(url: string | null): void {
  if (!url) return;
  let link = document.querySelector<HTMLLinkElement>('link[rel="icon"]');
  if (!link) {
    link = document.createElement('link');
    link.rel = 'icon';
    document.head.appendChild(link);
  }
  link.href = url;
}

export function BrandProvider({ children }: { children: ReactNode }) {
  const auth = useAuth();
  const [brand, setBrand] = useState<Brand | null>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let cancelled = false;
    if (!auth.authenticated) {
      // Pre-auth render uses the static :root palette from styles.css; no
      // /auth/brand call until we have a session.
      setReady(false);
      setBrand(null);
      return;
    }
    (async () => {
      const b = await fetchBrand();
      if (cancelled) return;
      if (b) {
        applyCssVars(b.cssVars);
        applyFavicon(b.faviconUrl);
        setBrand(b);
      }
      setReady(true);
    })();
    return () => { cancelled = true; };
  }, [auth.authenticated]);

  return (
    <BrandContext.Provider value={{ ready, brand }}>
      {children}
    </BrandContext.Provider>
  );
}

export function useBrand(): BrandContextValue {
  const ctx = useContext(BrandContext);
  if (!ctx) throw new Error('useBrand must be used within BrandProvider');
  return ctx;
}
