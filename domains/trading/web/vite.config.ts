import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import fs from 'node:fs';
import {
  forwardAuthPlugin,
  tokenHandlerProxyPlugin
} from '../../../scripts/vite-forward-auth-plugin.mjs';

const TOKEN_HANDLER_URL = process.env.TOKEN_HANDLER_URL ?? 'http://127.0.0.1:9080';
const BFF_URL = process.env.BFF_TRADING_URL ?? 'http://127.0.0.1:8085';
const DEV_FORWARD_AUTH = process.env.DEV_FORWARD_AUTH === '1';

// Vite preview must serve HTTPS for trading.geowealth.int. See
// domains/billing/web/vite.config.ts for the full rationale — same story
// (keycloak-js v26 + browser secure-context rules).
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
    host: '0.0.0.0',
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
    host: '0.0.0.0',
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
