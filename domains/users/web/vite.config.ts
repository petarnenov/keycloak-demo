import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import fs from 'node:fs';

const BFF_URL = process.env.BFF_USERS_URL ?? 'http://localhost:8086';

// Vite preview must serve HTTPS for users.geowealth.int. See
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

// BFF / Token Handler proxies: /api/users → the domain BFF (rewriting the
// /api/users prefix to /api); /api/linked-identities passes through to the
// BFF's LinkedIdentityAdminController which lives at the literal path
// /api/linked-identities (no rewrite needed); /oauth, /auth, /logout pass
// through unchanged (BFF's OIDC + session endpoints). /backchannel-logout is
// server-to-server (KC → bff-users) and never hits the SPA preview.
const apiProxy = {
  '/api/users': {
    target: BFF_URL,
    changeOrigin: true,
    rewrite: (path: string) => path.replace(/^\/api\/users/, '/api')
  },
  '/api/linked-identities': { target: BFF_URL, changeOrigin: true },
  '/oauth':  { target: BFF_URL, changeOrigin: true },
  '/auth':   { target: BFF_URL, changeOrigin: true },
  '/logout': { target: BFF_URL, changeOrigin: true }
};

export default defineConfig({
  plugins: [react()],
  build: {
    target: 'esnext',
    minify: false
  },
  server: {
    port: 5186,
    host: '0.0.0.0',
    strictPort: true,
    https: httpsConfig,
    allowedHosts: ['localhost', '127.0.0.1', 'users.geowealth.int'],
    proxy: apiProxy
  },
  preview: {
    port: 5186,
    host: '0.0.0.0',
    strictPort: true,
    https: httpsConfig,
    allowedHosts: ['localhost', '127.0.0.1', 'users.geowealth.int'],
    proxy: apiProxy
  }
});
