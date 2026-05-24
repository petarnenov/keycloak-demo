# Production topology — Keycloak SSO for domain apps

Reference architecture for taking this demo's domain stack to production
without rewriting it. The demo uses `localhost` ports and mkcert; production
swaps those for proper hostnames + edge-managed TLS + HA — same wire shape,
real infrastructure.

Audience: someone who knows the demo works and is sketching what changes
when this leaves a laptop.

## Axioms

These hold in any sensible production setup; everything else is detail.

1. **Keycloak must be publicly reachable.** Browsers are OIDC clients. They
   redirect to Keycloak's authorize endpoint, exchange codes, and refresh
   tokens. The instance lives somewhere with a real DNS name and a real cert.
2. **HTTPS everywhere, terminated at the edge.** Keycloak runs HTTP behind a
   TLS-terminating reverse proxy / load balancer. Quarkus' built-in TLS is
   fine but not the best terminator under load.
3. **Keycloak is stateful but the cluster is HA.** Multiple replicas share a
   Postgres database + Infinispan distributed cache. No single instance is
   a SPOF.
4. **Realm config is code.** No clicking in the admin UI in production.
   Declarative tooling (keycloak-config-cli, Terraform Keycloak provider)
   applies changes from a git-tracked source.
5. **Secrets never live in env vars in plaintext.** Vault / AWS Secrets
   Manager / GCP Secret Manager / k8s ExternalSecrets — pick one and use it
   for everything (DB password, admin password, SAML signing keys, client
   secrets, …).

## Topology

```
                       Internet
                          │
                  ┌───────▼───────┐  DDoS protection, WAF rules tuned for
                  │  Cloudflare   │  auth endpoints (credential stuffing
                  │  / Akamai     │  is the obvious target), bot detection.
                  └───────┬───────┘
                          │
                  ┌───────▼─────────┐  Managed TLS cert (ACM / Google-managed
                  │  ALB / GCLB /   │  / Azure Front Door cert), HTTP/2,
                  │  AzureFD        │  health probes against /health/ready.
                  └───────┬─────────┘
                          │
                  ┌───────▼────────────────┐  In-cluster reverse proxy.
                  │  Ingress controller    │  cert-manager renews internal
                  │  (ingress-nginx /      │  certs from Let's Encrypt or
                  │   Traefik / Istio)     │  internal CA.
                  └───────┬────────────────┘
                          │
                  ┌───────▼─────────────────────────────────────────┐
                  │  Keycloak StatefulSet (3+ replicas)             │
                  │  - quay.io/keycloak/keycloak:26.x               │
                  │  - KC_PROXY_HEADERS=xforwarded                  │
                  │  - KC_HOSTNAME=https://sso.acme.com             │
                  │  - KC_HTTP_ENABLED=true (TLS on edge)           │
                  │  - KC_HTTP_MANAGEMENT_PORT=9000 (separate       │
                  │    listener for liveness/readiness probes,      │
                  │    cluster-internal only)                       │
                  │  - Infinispan replicated cache (TCP/UDP between │
                  │    Pods, multicast or JGroups TCPPING)          │
                  └───────┬─────────────────────────────────────────┘
                          │
                  ┌───────▼─────────────────┐
                  │  Postgres (managed)     │  RDS / CloudSQL / AlloyDB
                  │  - multi-AZ             │  Backups: PITR enabled, copied
                  │  - read-replica         │  to secondary region nightly.
                  │  - encryption-at-rest   │  Connection pool: PgBouncer in
                  │  - automated backups    │  front (Keycloak opens many
                  │                         │  short-lived connections).
                  └─────────────────────────┘
```

For SAML federation (this demo's flow to P1):

```
  Browser ──→ sso.acme.com/realms/.../auth?kc_idp_hint=p1
              ──→ p1.acme.com/saml/idp/sso.do
                  ──→ Browser ──→ sso.acme.com/.../broker/p1/endpoint
                       (back-channel call from Keycloak to P1 only for
                        artifact resolution or backchannel SLO; with
                        front-channel HTTP-POST binding no such call is
                        needed)
```

Both `sso.acme.com` and `p1.acme.com` need:
- Valid TLS certs (no self-signed, no mkcert)
- SAML signing certificates trusted by the peer (mutual exchange of
  metadata.xml or signingCertificate baked into the IdP config)
- Clock sync (NTP) — SAML assertions have `NotBefore` / `NotOnOrAfter`
  windows; >5min skew between Keycloak and P1 breaks the flow

## Network exposure (the original question)

| Component | Public hostname | Public port | Private only |
|---|---|---|---|
| Domain SPA #1 | `billing.acme.com` | 443 | — |
| Domain SPA #2 | `trading.acme.com` | 443 | — |
| Domain SPA #N | `<name>.acme.com` | 443 | — |
| Keycloak | `sso.acme.com` | 443 | — |
| P1 SAML IdP | `p1.acme.com` | 443 | — |
| Domain BFFs | — | — | internal cluster network only |
| Postgres | — | — | private subnet, no public IP |
| Infinispan | — | — | pod-to-pod inside the cluster |

The BFFs never face the public internet. Browsers reach them by hitting the
domain SPA's edge (nginx / ingress), which proxies `/api/<domain>/*` to the
internal BFF service. Same shape as the demo, just at scale.

## What changes per `apply` between environments

| Thing | Dev | Stage | Prod |
|---|---|---|---|
| Keycloak hostname | `auth.geowealth.int` (mkcert) | `sso.stage.acme.com` (LE cert) | `sso.acme.com` (managed cert) |
| Domain hostnames | `*.geowealth.int` (mkcert) | `*.stage.acme.com` | `*.acme.com` |
| Postgres | container (volume) | managed (small instance) | managed (multi-AZ, PITR) |
| Keycloak replicas | 1 | 1–2 | 3+ |
| Admin password | `admin/admin` | rotated, in Vault | rotated, in Vault |
| Realm import | `--import-realm` of JSON | keycloak-config-cli apply | keycloak-config-cli apply |
| TLS | mkcert | Let's Encrypt | enterprise CA / managed |
| `/admin/*` access | open | VPN | VPN + IP allowlist + WAF rule |
| Brute-force protection | off | on | on + alerting |
| OTP / MFA | optional | required for admin role | required for all privileged roles |

Everything above the table line (the wire shape, the SAML flow, the OIDC
client configs, the BFF JWT validation) stays the same.

## Operational concerns

### Realm-config as code

- **Source of truth**: `realm-export.json` in git, declarative form (no
  random component IDs, no timestamps, no client secrets — those come from
  the secret store at apply time).
- **Tooling**: pick one, don't mix.
  - [keycloak-config-cli](https://github.com/adorsys/keycloak-config-cli) —
    runs as a job, reads JSON/YAML, applies idempotently. Native to the
    Keycloak realm-export shape.
  - [Terraform Keycloak provider](https://github.com/mrparkers/terraform-provider-keycloak)
    — declarative, integrates with the rest of your IaC. Higher friction for
    flow customization (custom authenticators).
- **Workflow**: PR → CI applies to dev → manual approve → stage → smoke →
  manual approve → prod. Never `kcadm.sh` against prod from a laptop.

### Secrets

- `KEYCLOAK_ADMIN_PASSWORD`, `KC_DB_PASSWORD`, OIDC `client.secret` for any
  confidential client, SAML signing-key passphrase — all in Vault.
- Rotation policy:
  - Client secrets: 90 days, automated through Vault dynamic secrets if the
    app supports it, otherwise scheduled rotation via keycloak-config-cli.
  - SAML signing keys: yearly, with an overlap period (Keycloak supports
    multiple active keys via `KeyProvider` components; rotate the active
    one and let SAML metadata advertise both during the transition).
  - DB password: 60 days, coordinated with Keycloak rolling restart.

### Observability

- **Metrics**: Keycloak 26 ships `/metrics` (Prometheus-compatible) on the
  management port. Track `keycloakhealth_uptime`, login success/failure
  rate, average authorize-endpoint latency, JVM heap, GC pauses.
- **Logs**: JSON to stdout (`KC_LOG_LEVEL=INFO`, structured format),
  forwarded by the cluster log agent (Fluent Bit / Vector / Datadog Agent)
  to ELK / Loki / Splunk / Datadog. Mask `Authorization` headers and JWT
  payloads.
- **Tracing**: OpenTelemetry SDK enabled (`KC_TRACING_ENABLED=true` in
  Keycloak 26+). Propagate `traceparent` from the edge → ingress → Keycloak
  → BFF so a single login is one trace.
- **Audit**: separate sink, immutable. Keycloak's event listener SPI
  publishes login / consent / admin-action events; subscribe with a custom
  listener that writes to an immutable store (S3 Object Lock, Kafka with
  log compaction off, etc.).
- **Alerting**: page on login error rate > X%, average authorize latency >
  Y ms, JVM heap > 80%, Postgres connection pool exhaustion, Infinispan
  split-brain.

### Backup & DR

- **Postgres**: PITR enabled, daily snapshots replicated to secondary
  region. Test restore quarterly (just restoring a snapshot proves the
  blob is valid; quarterly = test that the Keycloak instance comes up
  against the restored DB).
- **Realm config**: lives in git, applied via keycloak-config-cli — no
  separate backup needed.
- **SAML / OIDC signing keys**: backed up out-of-band (HSM / KMS export
  with restricted access). Loss of the active key means every issued
  token is invalidated and every federated identity stops working until
  re-bootstrap; treat key material like crown jewels.
- **Failover**: blue/green Keycloak deployment in a second region with
  Postgres logical replication. DNS-based failover (Route 53 / Cloud DNS
  health-checked records) when the primary region is unhealthy. RPO/RTO
  driven by Postgres replication lag and DNS TTL.

### Authentication & authorization posture

- **PKCE required** for all public OIDC clients (SPAs). Set
  `pkce.code.challenge.method=S256` on the client. (The demo dropped this
  to keep the static P1 sidebar links working — production routes through
  keycloak-js which always sends PKCE.)
- **Redirect URIs**: exact-match, no wildcards.
- **Token lifetimes**:
  - Access token: 5–15 min.
  - Refresh token: 24h–30d depending on application risk profile; rotate
    on use (`refresh_token` policy = rotate, not reuse).
  - SSO session idle: 30 min; max: 8h.
- **Brute-force detection**: realm-level, enabled.
  `permanentLockout=false`, temp lockout window 5–15 min, threshold 5
  failed logins.
- **WebAuthn / TOTP**: required for any role that touches admin endpoints.
  Conditional MFA on user-facing apps based on IP risk / device risk.
- **Headers from the edge**:
  - `Strict-Transport-Security: max-age=63072000; includeSubDomains; preload`
  - `Content-Security-Policy` (strict, scoped per SPA)
  - `X-Content-Type-Options: nosniff`
  - `Referrer-Policy: strict-origin-when-cross-origin`
  - `Permissions-Policy` (lock down camera/mic/geo if unused)

## Compliance hooks

The shape of what's needed depends on the business, but common items:

- **GDPR**: user data export endpoint (custom REST resource on Keycloak,
  reading user attributes + federated identities + sessions), user
  deletion endpoint (Keycloak admin API + cascading deletion of related
  records in your apps).
- **SOC 2 / ISO 27001**: audit log immutability evidence, access reviews
  (admin-role assignments reviewed quarterly with sign-off), key rotation
  evidence (Vault metadata is your friend), backup-restore test logs.
- **PCI / HIPAA**: stricter session controls, dedicated environment for
  scoped data. Keycloak doesn't directly touch the regulated data; the
  business apps do. But Keycloak's audit trail is what proves who
  authenticated when.

## What this demo gets right (and where the gap to production is)

Right:
- The wire shape (browser → SPA → SAML → Keycloak → OIDC code → BFF JWT
  validation) is identical.
- The role of `realm-export.json` as the seed file is identical.
- The split of public-facing SPAs vs. internal-only BFFs is identical.
- The mkcert + custom hostname pattern teaches the secure-context
  requirements that production HTTPS imposes.

Gaps (intentional, for demo simplicity):
- Single Keycloak instance, no HA.
- Postgres in a container with a docker volume, not managed.
- mkcert certs, not edge-managed TLS.
- `admin/admin` and `--import-realm`, not Vault + keycloak-config-cli.
- No WAF / DDoS protection (running on a laptop, irrelevant).
- No metrics / logs / tracing.
- No backup beyond `docker compose down` not using `-v`.

The demo's next-best step toward this topology is to give Keycloak its
own dedicated hostname behind nginx — `auth.geowealth.int` instead of
`localhost:8898`. That's covered by the changes in commit history (look
for "auth domain"); see `CLAUDE.md` for the runtime details.
