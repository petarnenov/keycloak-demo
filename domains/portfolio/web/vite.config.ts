import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import fs from 'node:fs';
import {
  forwardAuthPlugin,
  tokenHandlerProxyPlugin
} from '../../../scripts/vite-forward-auth-plugin.mjs';

const TOKEN_HANDLER_URL = process.env.TOKEN_HANDLER_URL ?? 'http://127.0.0.1:9080';
const BFF_URL = process.env.BFF_PORTFOLIO_URL ?? 'http://127.0.0.1:8086';
const DEV_FORWARD_AUTH = process.env.DEV_FORWARD_AUTH === '1';
// RAG AI-assistant backend (separate compose project, published on the host).
// The <ai-assistant> widget calls `/assistant/api/v1/*`; we proxy that to the
// backend's `/api/v1/*`, same-origin so there's no CORS or mixed-content block.
const ASSISTANT_URL = process.env.ASSISTANT_URL ?? 'http://localhost:8801';
// AI-assistant frontend container serves the widget bundle at /ai-assistant.js;
// we proxy it (instead of baking it in) so a widget change needs no portfolio rebuild.
const ASSISTANT_FE_URL = process.env.ASSISTANT_FE_URL ?? 'http://localhost:8800';

// HTTPS is required because keycloak-js v26 uses Web Crypto API
// (crypto.subtle + crypto.randomUUID), which the browser only exposes in
// "secure contexts": HTTPS, or the loopback hostnames localhost / 127.0.0.1.
// portfolio.geowealth.int resolves to 127.0.0.1 via /etc/hosts but the browser
// classifies secure context by hostname literal, not by resolved IP — so
// http://portfolio.geowealth.int:5186 is NOT secure and crypto.subtle is
// undefined there. We serve HTTPS with an mkcert-issued cert (locally
// trusted, no browser warning) and the whole auth chain works.
const HTTPS_CERT = process.env.HTTPS_CERT_PATH;
const HTTPS_KEY = process.env.HTTPS_KEY_PATH;

const httpsConfig = (HTTPS_CERT && HTTPS_KEY)
  ? {
      cert: fs.readFileSync(HTTPS_CERT),
      key: fs.readFileSync(HTTPS_KEY)
    }
  : undefined;

export default defineConfig({
  plugins: [
    react(),
    ...(DEV_FORWARD_AUTH
      ? [
          tokenHandlerProxyPlugin(TOKEN_HANDLER_URL),
          forwardAuthPlugin({
            apiPrefix: '/api/portfolio',
            bffRewritePrefix: '/api',
            verifyBaseUrl: TOKEN_HANDLER_URL,
            bffBaseUrl: BFF_URL,
            forwardedHost: 'portfolio.geowealth.int:5186'
          })
        ]
      : [])
  ],
  build: {
    target: 'esnext',
    minify: false
  },
  server: {
    port: 5186,
    host: '0.0.0.0',
    strictPort: true,
    https: httpsConfig,
    allowedHosts: ['localhost', '127.0.0.1', 'portfolio.geowealth.int'],
    proxy: DEV_FORWARD_AUTH ? {} : {
      '/api/portfolio': {
        target: BFF_URL,
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api\/portfolio/, '/api')
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
    port: 5186,
    host: '0.0.0.0',
    strictPort: true,
    https: httpsConfig,
    allowedHosts: ['localhost', '127.0.0.1', 'portfolio.geowealth.int']
  }
});
