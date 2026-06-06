import { useEffect, useRef, type DetailedHTMLProps, type HTMLAttributes } from 'react';

// Embeds the `<ai-assistant>` Web Component (the RAG assistant widget) into the
// billing SPA. The standalone IIFE bundle is loaded via a <script> tag in
// index.html and self-registers the custom element with its OWN React + styles
// inside an open Shadow DOM, so it can't clash with this app's React tree.
//
// `api-base-url` is a RELATIVE path: the widget calls `{api-base-url}/api/v1/*`,
// which resolves to `/assistant/api/v1/*` on this origin. nginx (container) and
// Vite (local `npm run dev`) both proxy `/assistant` → the AI-assistant backend.
// Same-origin, so no CORS and no mixed-content blocking on the HTTPS page.
//
// Auth: the backend runs with SECURITY_MODE=dev (no JWT required), so we do not
// wire a tokenProvider here. For a secured (oidc) backend, set
//   ref.current.tokenProvider = () => fetchAccessTokenSomehow()
// in the effect below.

// The widget self-registers asynchronously once the deferred bundle executes.
// React renders the tag regardless; the upgrade happens when the script loads.
declare global {
  // eslint-disable-next-line @typescript-eslint/no-namespace
  namespace JSX {
    interface IntrinsicElements {
      'ai-assistant': DetailedHTMLProps<
        HTMLAttributes<HTMLElement>,
        HTMLElement
      > & {
        'api-base-url'?: string;
        theme?: 'light' | 'dark' | 'auto';
      };
    }
  }
}

const ASSISTANT_API_BASE = '/assistant';

export function AiAssistant() {
  const ref = useRef<HTMLElement>(null);

  useEffect(() => {
    // Placeholder for future auth wiring. With SECURITY_MODE=dev the widget
    // works without a token; when the backend is switched to oidc, provide one:
    //   const el = ref.current as (HTMLElement & { tokenProvider?: () => unknown }) | null;
    //   if (el) el.tokenProvider = () => getAccessToken();
  }, []);

  return (
    <ai-assistant
      ref={ref}
      api-base-url={ASSISTANT_API_BASE}
      theme="auto"
    />
  );
}
