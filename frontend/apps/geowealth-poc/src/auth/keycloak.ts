import Keycloak from 'keycloak-js';

/**
 * Keycloak base URL is derived from the browser's current hostname so the
 * Host header reaching Keycloak matches the firm subdomain the user opened
 * (e.g. browser at changepath.localhost:5174 → Keycloak at
 * http://changepath.localhost:8888). Phase 2's ThemeSelectorProvider reads
 * exactly that Host header to pick the firm's branding; aligning the two
 * here keeps the entire chain consistent without query-param hints.
 */
function keycloakBaseUrl(): string {
  if (typeof window === 'undefined') return 'http://localhost:8888';
  return `http://${window.location.hostname}:8888`;
}

export const keycloak = new Keycloak({
  url: keycloakBaseUrl(),
  realm: import.meta.env.VITE_KEYCLOAK_REALM ?? 'geowealth-realm',
  clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID ?? 'geowealth-poc-client'
});
