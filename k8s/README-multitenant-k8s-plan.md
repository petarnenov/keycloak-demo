# Production-ready Token Handler for 20+ domains on Kubernetes

Sub-branch: `petarnenov/token-handler-multitenant-k8s` (off `petarnenov/bff-core-persons-registry`).

## Problem
The per-domain Token Handler (`token-handler-billing`, `token-handler-trading`)
is one image but one **instance per domain**. At 20+ domains that is 20 JVMs
(~250-400 MB baseline each) × HA replicas — wasteful, and per-domain isolation
buys little when all domains run the same shared auth code. Industry standard at
that scale: **one multi-tenant, host-aware Token Handler, replicated, scaling on
total traffic** (or push forward-auth to the ingress/mesh). This branch does the
former — the smallest step from today that keeps the custom P1 Tier-2 authz.

## Part A — Multi-tenant (host-aware) Token Handler  [code]
One Token Handler serves every domain; it resolves per-domain config from the
request `Host`:
- **OIDC client** — Micronaut multi-client (`micronaut.security.oauth2.clients.<name>`),
  one named client per domain (maps to its KC client). nginx rewrites the SPA's
  `/oauth/login/keycloak` → `/oauth/login/<domain>` by host, so the SPA is unchanged;
  the callback is `/oauth/callback/<domain>` (registered on that KC client).
- **Session cookie** — ONE name for all domains. Cookies are host-scoped (no
  `Domain` attribute), so `billing.geowealth.int` and `trading.geowealth.int` get
  distinct cookies even with the same name. No per-domain cookie needed.
- **Authorization (`/auth/verify`)** — `SubdomainRequirements` holds a map
  `host -> {type, firmCd | objectType, permission}`; `SubdomainAuthorizer` resolves
  the requirement by `Host`. Adding a domain = one config entry, not a new Deployment.
- The data BFFs are unchanged (already auth-unaware; forward-auth headers).

Verification (docker-compose): collapse `token-handler-billing` + `-trading` into a
single `token-handler` and route BOTH domains' `/(oauth|auth)/`, `/logout`, and the
`/api` `auth_request` to it. Full e2e green = one instance serving N domains.

## Part B — Production Kubernetes manifests  [k8s/]
Industry-standard, under `k8s/` (kustomize base + overlays):
- **token-handler** `Deployment` (≥2 replicas, anti-affinity, probes, resources,
  read-only rootfs) + **HPA** (scale on CPU/total traffic) + **Service** +
  **ConfigMap** (the per-host requirement map, mounted) + **Secret** (OIDC client
  secrets — placeholder; real = External Secrets/Vault) + `PodDisruptionBudget`.
- **data BFFs** (`bff-billing`, `bff-trading`) `Deployment` + `Service` — auth-unaware.
- **redis** `StatefulSet` + `Service` (shared session/sid store; managed/HA in prod).
- **Ingress** (nginx-ingress) per domain host with **external-auth** annotations
  (`nginx.ingress.kubernetes.io/auth-url` → the token-handler `/auth/verify`,
  `auth-response-headers: X-Auth-*`) — the K8s-native equivalent of the compose
  nginx `auth_request`. `/(oauth|auth)/` + `/logout` route to the token-handler;
  `/api` routes to the data BFF gated by external-auth.
- Keycloak is assumed external (managed / its own release); P1 is external (host).

Verification:
- `kubectl kustomize` of `base`, `overlays/dev`, `overlays/minikube-auth-tier` →
  manifests render (base = 18 resources). Server-side schema validation via
  `kubectl apply --dry-run=server -k k8s/overlays/dev` (needs a cluster — client
  dry-run can't validate without an API server's OpenAPI).
- minikube (if startable): deploy the **auth tier** (token-handler + redis +
  config) and confirm pods Ready + `/health` + HPA present. (Full login needs the
  external KC + P1, so only the tier is smoke-tested in-cluster.)

## Layout (this branch)
```
k8s/base/
  namespace.yaml  redis.yaml  token-handler-config.yaml  token-handler.yaml
  data-bff-config.yaml  bff-billing.yaml  bff-trading.yaml  ingress.yaml
  kustomization.yaml
k8s/overlays/dev/                 full stack, 1 replica each, local images
k8s/overlays/minikube-auth-tier/  token-handler + redis + config only (smoke)
```

## Verification — RESULTS (2026-06-13)
**Part A — one multi-tenant Token Handler serves both domains (docker-compose):**
- `token-handler-billing` + `-trading` collapsed into ONE `token-handler` using the
  shared `demo-shared-client` + one `GWSESSION` cookie (host-scoped) + the
  `app.tenants.*` host→requirement map. bff-core tests green (incl. new host-aware
  `SubdomainAuthorizer`/`SubdomainRequirements` cases). `/auth/me` made host-aware
  so each domain surfaces its own tenant facts.
- Full e2e through the SINGLE token-handler: **27 passed, 1 failed, 2 skipped**.
  The 1 failure is the pre-existing firm-5 `johnastim5` data flake (fails at P1
  login → 401, before any multi-tenant assertion; already failing on the parent
  branch). All auth/api/claims/logout/silent-first specs green — proof one instance
  fronts billing + trading with correct per-host authz (`/api/<name>` anon → 401;
  logged-in → data via `X-Auth-*`; `client_id=demo-shared-client` on authorize;
  back-channel + global logout cascade via the shared client).

**Part B — Kubernetes manifests:**
- `kubectl kustomize` renders base (18 resources), dev, and auth-tier overlays clean.
- `kubectl apply --dry-run=server -k k8s/overlays/dev` on a live API server (minikube
  v1.34) → all 18 resources schema-valid ("created (server dry run)").
- **minikube auth-tier smoke** (`overlays/minikube-auth-tier`: token-handler +
  redis + config/secret): deployed; `deployment "token-handler" successfully rolled
  out`, pod **1/1 Ready**, and:
  - `/health` → `{"status":"UP"}` (needed adding `micronaut-management` to bff-core
    so the probe endpoint exists — without it `/health` 404s);
  - the per-host tenant map is mounted from the ConfigMap at `/config/tenants.yml`
    (billing + trading) and merged via `MICRONAUT_CONFIG_FILES`;
  - `/auth/verify` with no session → `401` (forward-auth endpoint live, fails closed);
  - `HorizontalPodAutoscaler` + `PodDisruptionBudget` created (HPA TARGETS shows
    `<unknown>` only because minikube has no metrics-server — the object is valid).
  The data BFFs + Ingress need the external Keycloak + P1, so they are not smoke-
  tested in-cluster (only rendered + server-dry-run-validated).
- Tear down: `minikube delete` (or `kubectl delete ns geowealth-demo`).

## Scaling rule (documented)
| domains | shape |
|---|---|
| 1-3 | per-domain instances of the shared image (the previous branch) |
| ~5-10 | this: one multi-tenant Token Handler, replicated |
| 20+ | this, **or** forward-auth at the ingress/mesh (oauth2-proxy / Envoy ext_authz) + a small ext-authz for the P1 Tier-2 specifics |

## Out of scope (documented)
- Moving auth fully to oauth2-proxy/Pomerium/Envoy ext_authz (the "platform-layer"
  end state) — noted as the next step beyond a custom multi-tenant Token Handler.
- Distributed in-flight refresh lock; P1 (Tomcat) session clustering (B2-P1 remnant).
