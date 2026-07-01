import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import fs from 'node:fs';
import {
  forwardAuthPlugin,
  tokenHandlerProxyPlugin
} from '../../../scripts/vite-forward-auth-plugin.mjs';

const TOKEN_HANDLER_URL = process.env.TOKEN_HANDLER_URL ?? 'http://127.0.0.1:9080';
const BFF_URL = process.env.BFF_CUSTODIAN_URL ?? 'http://127.0.0.1:8087';
// Forward-auth (nginx-mirroring) dev mode is the DEFAULT for `npm run dev` —
// custodian needs the token-handler /auth/verify gate + /oauth proxy to log in.
// Opt out (plain BFF proxy, no auth) with DEV_FORWARD_AUTH=0.
const DEV_FORWARD_AUTH = process.env.DEV_FORWARD_AUTH !== '0';
// RAG AI-assistant backend (separate compose project, published on the host).
// The <ai-assistant> widget calls `/assistant/api/v1/*`; we proxy that to the
// backend's `/api/v1/*`, same-origin so there's no CORS or mixed-content block.
const ASSISTANT_URL = process.env.ASSISTANT_URL ?? 'http://localhost:8801';
// AI-assistant frontend container serves the widget bundle at /ai-assistant.js;
// we proxy it (instead of baking it in) so a widget change needs no custodian rebuild.
const ASSISTANT_FE_URL = process.env.ASSISTANT_FE_URL ?? 'http://localhost:8800';

// HTTPS is required because keycloak-js v26 uses Web Crypto API
// (crypto.subtle + crypto.randomUUID), which the browser only exposes in
// "secure contexts": HTTPS, or the loopback hostnames localhost / 127.0.0.1.
// custodian.geowealth.int resolves to 127.0.0.1 via /etc/hosts but the browser
// classifies secure context by hostname literal, not by resolved IP — so
// http://custodian.geowealth.int:5187 is NOT secure and crypto.subtle is
// undefined there. We serve HTTPS with an mkcert-issued cert (locally
// trusted, no browser warning) and the whole auth chain works.
// Default to the mkcert dev certs at the repo root so plain `npm run dev` gets
// HTTPS with no wrapper env. The existsSync guard keeps `vite build` (which loads
// this config in the Docker builder, where proxy/certs/ isn't present) safe —
// there the cert is absent → httpsConfig is undefined → no readFileSync crash.
const HTTPS_CERT = process.env.HTTPS_CERT_PATH || '../../../proxy/certs/custodian.geowealth.int.crt';
const HTTPS_KEY = process.env.HTTPS_KEY_PATH || '../../../proxy/certs/custodian.geowealth.int.key';

const httpsConfig = (fs.existsSync(HTTPS_CERT) && fs.existsSync(HTTPS_KEY))
  ? {
      cert: fs.readFileSync(HTTPS_CERT),
      key: fs.readFileSync(HTTPS_KEY)
    }
  : undefined;

// Bind the vanity hostname by default so Vite PRINTS
// https://custodian.geowealth.int:5187 (not https://localhost:5187 — clicking
// the localhost URL breaks auth: localhost is not a registered redirect_uri /
// token-handler tenant). Override with DEV_HOST=0.0.0.0 for network HMR.
const DEV_HOST = process.env.DEV_HOST || 'custodian.geowealth.int';

export default defineConfig({
  plugins: [
    react(),
    ...(DEV_FORWARD_AUTH
      ? [
          tokenHandlerProxyPlugin(TOKEN_HANDLER_URL),
          forwardAuthPlugin({
            apiPrefix: '/api/custodian',
            bffRewritePrefix: '/api',
            verifyBaseUrl: TOKEN_HANDLER_URL,
            bffBaseUrl: BFF_URL,
            forwardedHost: 'custodian.geowealth.int:5187'
          })
        ]
      : [])
  ],
  build: {
    target: 'esnext',
    minify: false
  },
  server: {
    port: 5187,
    host: DEV_HOST,
    strictPort: true,
    https: httpsConfig,
    allowedHosts: ['localhost', '127.0.0.1', 'custodian.geowealth.int'],
    proxy: DEV_FORWARD_AUTH ? {} : {
      '/api/custodian': {
        target: BFF_URL,
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api\/custodian/, '/api')
      },
      '/assistant': {
        target: ASSISTANT_URL,
        changeOrigin: true,
        // SSE chat stream — don't buffer the response.
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes) => {
            if ((proxyRes.headers['content-type'] ?? '').includes('text/event-stream')) {
              proxyRes.headers['cache-control'] = 'no-cache, no-transform';
            }
          });
        },
        rewrite: (path) => path.replace(/^\/assistant/, '')
      },
      '/ai-assistant/': {
        target: ASSISTANT_FE_URL,
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/ai-assistant/, '')
      }
    }
  },
  preview: {
    port: 5187,
    host: DEV_HOST,
    strictPort: true,
    https: httpsConfig,
    allowedHosts: ['localhost', '127.0.0.1', 'custodian.geowealth.int']
  }
});
