import Keycloak from 'keycloak-js';

/**
 * Keycloak is reachable on localhost:8898 even from trading.geowealth.int
 * because /etc/hosts only maps the trading hostname — localhost still
 * resolves to 127.0.0.1, and Keycloak's KC_* cookies are scoped by
 * Keycloak's own hostname, not the SPA's.
 */
const KC_URL = (import.meta.env.VITE_KEYCLOAK_URL as string | undefined) ?? 'http://localhost:8898';
const KC_REALM = (import.meta.env.VITE_KEYCLOAK_REALM as string | undefined) ?? 'demo-realm';
const KC_CLIENT = (import.meta.env.VITE_KEYCLOAK_CLIENT_ID as string | undefined) ?? 'demo-trading-client';

export const keycloak = new Keycloak({
  url: KC_URL,
  realm: KC_REALM,
  clientId: KC_CLIENT
});
