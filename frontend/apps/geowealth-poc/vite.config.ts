import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    target: 'esnext',
    minify: false
  },
  server: {
    port: 5174,
    host: '0.0.0.0',
    strictPort: true,
    // Subdomain-routed POC: the browser-visible hostname encodes the firm,
    // so Vite must serve any *.localhost host rather than its default
    // localhost-only allowlist.
    allowedHosts: ['localhost', '127.0.0.1', '.localhost']
  }
});
