import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Build output lands in the Keycloak theme's resources/js dir so the static
// bundle is served by KC at /resources/<hash>/login/geowealth/js/auth-spa.js.
// One IIFE bundle, no code splitting (theme assets are loaded individually,
// not via dynamic import).
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: 'theme/login/resources/js',
    emptyOutDir: false,
    cssCodeSplit: false,
    lib: {
      entry: 'src/main.tsx',
      name: 'AuthSpa',
      formats: ['iife'],
      fileName: () => 'auth-spa.js',
    },
    rollupOptions: {
      // React + ReactDOM are bundled; KC theme doesn't expose a global React.
    },
  },
});
