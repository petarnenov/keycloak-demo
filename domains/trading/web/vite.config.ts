import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import fs from 'node:fs';

const BFF_URL = process.env.BFF_TRADING_URL ?? 'http://localhost:8085';

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
  plugins: [react()],
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
    proxy: {
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
