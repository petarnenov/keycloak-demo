import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import fs from 'node:fs';
import {
  forwardAuthPlugin,
  tokenHandlerProxyPlugin
} from '../../../scripts/vite-forward-auth-plugin.mjs';

const TOKEN_HANDLER_URL = process.env.TOKEN_HANDLER_URL ?? 'http://127.0.0.1:9080';
const BFF_URL = process.env.BFF_TRADING_URL ?? 'http://127.0.0.1:8085';
// Forward-auth (nginx-mirroring) dev mode is the DEFAULT for `npm run dev` —
// trading needs the token-handler /auth/verify gate + /oauth proxy to log in.
// Opt out (plain BFF proxy, no auth) with DEV_FORWARD_AUTH=0.
const DEV_FORWARD_AUTH = process.env.DEV_FORWARD_AUTH !== '0';

// Vite preview must serve HTTPS for trading.geowealth.int. See
// domains/billing/web/vite.config.ts for the full rationale — same story
// (keycloak-js v26 + browser secure-context rules).
// Default to the mkcert dev certs at the repo root so plain `npm run dev` gets
// HTTPS with no wrapper env. The existsSync guard keeps `vite build` (which loads
// this config in the Docker builder, where proxy/certs/ isn't present) safe —
// there the cert is absent → httpsConfig is undefined → no readFileSync crash.
const HTTPS_CERT = process.env.HTTPS_CERT_PATH || '../../../proxy/certs/trading.geowealth.int.crt';
const HTTPS_KEY = process.env.HTTPS_KEY_PATH || '../../../proxy/certs/trading.geowealth.int.key';

const httpsConfig = (fs.existsSync(HTTPS_CERT) && fs.existsSync(HTTPS_KEY))
  ? {
      cert: fs.readFileSync(HTTPS_CERT),
      key: fs.readFileSync(HTTPS_KEY)
    }
  : undefined;

// Bind the vanity hostname by default so Vite PRINTS
// https://trading.geowealth.int:5185 (not https://localhost:5185 — clicking
// the localhost URL breaks auth: localhost is not a registered redirect_uri /
// token-handler tenant). Override with DEV_HOST=0.0.0.0 for network HMR.
const DEV_HOST = process.env.DEV_HOST || 'trading.geowealth.int';

export default defineConfig({
  plugins: [
    react(),
    ...(DEV_FORWARD_AUTH
      ? [
          tokenHandlerProxyPlugin(TOKEN_HANDLER_URL),
          forwardAuthPlugin({
            apiPrefix: '/api/trading',
            bffRewritePrefix: '/api',
            verifyBaseUrl: TOKEN_HANDLER_URL,
            bffBaseUrl: BFF_URL,
            forwardedHost: 'trading.geowealth.int:5185'
          })
        ]
      : [])
  ],
  build: {
    target: 'esnext',
    minify: false
  },
  server: {
    port: 5185,
    host: DEV_HOST,
    strictPort: true,
    https: httpsConfig,
    allowedHosts: ['localhost', '127.0.0.1', 'trading.geowealth.int'],
    proxy: DEV_FORWARD_AUTH ? {} : {
      '/api/trading': {
        target: BFF_URL,
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api\/trading/, '/api')
      }
    }
  },
  preview: {
    port: 5185,
    host: DEV_HOST,
    strictPort: true,
    https: httpsConfig,
    allowedHosts: ['localhost', '127.0.0.1', 'trading.geowealth.int'],
    proxy: {
      '/api/trading': {
        target: BFF_URL,
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api\/trading/, '/api')
      }
    }
  }
});
