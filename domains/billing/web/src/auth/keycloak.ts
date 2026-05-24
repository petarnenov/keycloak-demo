import Keycloak from 'keycloak-js';

/**
 * Keycloak lives on its own auth.geowealth.int hostname behind nginx (TLS
 * terminator). /etc/hosts maps auth.geowealth.int → 127.0.0.1 so the
 * browser reaches the `auth` container's host port. Keycloak's own
 * KC_HOSTNAME env matches this URL, so token issuer claims and broker
 * callback URIs come out consistent.
 */
const KC_URL = (import.meta.env.VITE_KEYCLOAK_URL as string | undefined) ?? 'https://auth.geowealth.int:5180';
const KC_REALM = (import.meta.env.VITE_KEYCLOAK_REALM as string | undefined) ?? 'demo-realm';
const KC_CLIENT = (import.meta.env.VITE_KEYCLOAK_CLIENT_ID as string | undefined) ?? 'demo-billing-client';

export const keycloak = new Keycloak({
  url: KC_URL,
  realm: KC_REALM,
  clientId: KC_CLIENT
});
