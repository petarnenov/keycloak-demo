/**
 * Shared environment configuration for the SSO E2E suite. Centralised here so
 * that "what URLs are the demo on?" is a single edit when ports or hosts
 * change. See `keycloak-demo/docker-compose.yml` for the source of truth.
 *
 * P1 is reached on a host port that depends on how the stack is exposed:
 *   - docker compose: P1 publishes 8888 directly.
 *   - Kubernetes (minikube): `kubectl port-forward svc/p1-tomcat 8080:8080`.
 * Default to the K8s port (8080); override with `P1_PORT` for compose (8888).
 */
export const P1_PORT = process.env.P1_PORT ?? '8080';

export const URLS = {
  /** P1 monolith — the SAML IdP that fronts every demo login. */
  p1Base: `http://localhost:${P1_PORT}`,
  /** Keycloak's public reverse-proxied origin (TLS by `auth` nginx). */
  kcBase: 'https://auth.geowealth.int:5180',
  realm: 'demo-realm',
  domains: {
    billing: 'https://billing.geowealth.int:5184',
    trading: 'https://trading.geowealth.int:5185',
  },
} as const;

/** P1 local dev credentials — also in MEMORY.md (reference_p1_local_credentials). */
export const P1_CREDENTIALS = {
  username: 'tim1',
  // eslint-disable-next-line no-secrets/no-secrets -- demo seed, not real
  password: 'c0w&ch1k3n',
} as const;

/**
 * What the demo PersonRegistry returns for `tim1`'s person scope. The
 * cross-subdomain expectations below are derived from
 * `geowealth/.../PersonRegistry.java#PERSON_TENANT_ALIASES/ROLES` — keep them
 * in sync when you change the registry. The asserts below pin both halves of
 * the document's `(person, tenant) → (alias, roles)` contract.
 */
export const PERSON = {
  personId: 'P-tim',
  email: 'tim.a@geo.com',
  tenants: {
    billing: {
      identity: 'tim1',
      roles: ['client', 'advisor', 'billing-admin'],
    },
    trading: {
      identity: 'tim5',
      roles: ['client', 'advisor', 'trading-trader'],
    },
  },
} as const;

export type TenantSlug = keyof typeof URLS.domains;

export const TENANT_SLUGS: readonly TenantSlug[] = ['billing', 'trading'] as const;
