# Production hardening — gap-by-gap status

Tracking implementation of the 9 production-readiness gaps from `PRODUCTION-RISK.md`. Each row links the gap, what landed in code on this branch, and what's still outstanding before a real prod ship.

| Gap | Status | Where | What's left |
|---|---|---|---|
| #1 Token rotation | **Design only** | This doc § "Token rotation design" | Pick a secret manager + implement the refresh hook |
| #2 Rate limit on `/branding-api/*` | ✅ **Done** | `BrandingApiAuthFilter` per-IP rolling window via Caffeine (broader than just `/asset/*` — catches credential-stuffing too). Configurable via `POC_BRANDING_API_RATE_LIMIT_PER_MIN` (default 60). | Optional: per-token key (XFF-trusted) instead of per-IP |
| #3 BLOB size cap | ✅ **Done** | `BrandingApiServlet` `MAX_ASSET_BYTES` check before stream | None |
| #4 INFO-per-render log volume | ✅ **Done** | `BrandingService` — `cache_hit`/`api_hit` drops to DEBUG, fallback paths stay at WARN | None |
| #5 Cache invalidation hook | **Partial** | `BrandingService.invalidate(code)` programmatic API; `BrandingCache.clear()` + `removeWhereValueEquals()` | REST endpoint via `RealmResourceProvider` + token auth + webhook from geowealth admin |
| #6 Realm gate via attribute | ✅ **Done** | `GeoWealthLoginFormsProvider.isOptedIn(realm)` reads `geowealthBrandingProvider` realm attribute (legacy name match retained as fallback); realm export sets attribute | None |
| #7 Audit log on success | ✅ **Done** | `BrandingApiServlet.auditLog(...)` — one INFO line per call with path / code / remote / status / latency | Optional: shipped to dedicated audit pipeline (today: regular log4j) |
| #8 BrandRegistry placeholders feature flag | ✅ **Done** | `BrandRegistry.PLACEHOLDERS_ENABLED` env-gated; set `KC_SPI_LOGIN_FREEMARKER_GEOWEALTH_REGISTRY_PLACEHOLDERS=false` to strip the inline SVG fallback | None |
| #9 CSP tightening | ✅ **Done** | `geowealth-realm-export.json` `browserSecurityHeaders` — explicit `default-src 'self'`, `style-src 'self' 'unsafe-inline'`, `img-src 'self' data:`, `script-src 'self'`, plus the upstream defaults | None — `'unsafe-inline'` on style-src is irreducible while we ship inline `<style>` blocks; document in security review |

7 of 9 gaps closed in code. #1 deferred to vault picking (no in-code change makes sense without that decision); #5 partially closed (the SPI-side bits are ready, the REST surface needs a `RealmResourceProvider` jar registration cycle).

## #1 — Token rotation design

The current static-token approach (set `POC_BRANDING_API_TOKEN` once in `~/tools/tomcat9/bin/setenv.sh` on the GeoWealth side; same value in `keycloak-demo/.envrc` for the Keycloak SPI) is the canonical POC shape. To graduate to rotation:

### Goals

1. **Rotate the bearer token on a schedule** (recommended: 24 h).
2. **No restart required** to pick up a rotated value — both sides must hot-read.
3. **Overlap window** — old token stays accepted for N minutes after a rotation so in-flight requests don't fail.
4. **Token never logged** (already true; preserve).
5. **No secret-at-rest in tracked files** (already true; preserve).

### Architecture

```
   ┌────────────────────────────────────────────────────────────┐
   │  Secret manager (Vault / Doppler / AWS Secrets Manager)   │
   │  /poc-branding-api/current        ← active token           │
   │  /poc-branding-api/previous       ← prior token (overlap)  │
   │  /poc-branding-api/version        ← bumps on rotation      │
   └─────────────────┬───────────────────────┬──────────────────┘
                     │                       │
        Tomcat-side  ▼                       ▼  Keycloak SPI
   ┌──────────────────────────┐    ┌─────────────────────────────┐
   │ BrandingApiAuthFilter:   │    │ BrandingApiClient:          │
   │  - refresh thread @60s   │    │  - refresh thread @60s      │
   │  - cache current+prev    │    │  - cache current            │
   │  - constant-time compare │    │  - send `Bearer <current>`  │
   │    against EITHER token  │    │                             │
   └──────────────────────────┘    └─────────────────────────────┘
```

### Concrete changes

**Geowealth side** (`BrandingApiAuthFilter`):
- Replace the single `expectedTokenBytes` field with `{currentTokenBytes, previousTokenBytes, expiresAt}`.
- Replace the `init()`-time `System.getenv()` read with a refresh thread polling `${secretManagerEndpoint}/poc-branding-api/current` (and `/previous`) every 60 s.
- `doFilter()` accepts a request if `MessageDigest.isEqual(presented, current)` OR `MessageDigest.isEqual(presented, previous)`.
- Boot-time read keeps the fail-closed semantics: if the secret manager is unreachable at startup, every request → 401 (existing behavior).

**Keycloak SPI side** (`BrandingApiClient`):
- Replace the `bearerToken` constructor argument with a `Supplier<String>` that resolves via the same secret manager.
- A refresh thread polls the manager every 60 s and updates an `AtomicReference<String>` the client reads on each request.
- The SPI init reads the secret manager URL + credentials from existing `KC_SPI_LOGIN_FREEMARKER_GEOWEALTH_*` env vars; rotation happens silently from there.

### What to pick for the secret manager

In rough order of preference for GeoWealth's existing footprint:
- **HashiCorp Vault** — if the org already runs one. Native Java SDK, role-based access, audit log built in.
- **AWS Secrets Manager** — if deployed on AWS. SDK is heavier but rotation hooks are first-class.
- **Doppler / 1Password Secrets Automation** — if no native vault. Cheaper to onboard; weaker audit story.

The actual choice belongs with the GeoWealth security team; the code shape above doesn't change based on which vault is picked. Adding the implementation is ~half a day on each side once the manager is settled.

## #5 — Cache invalidation REST endpoint

What's already in place on the SPI side:

- `BrandingService.invalidate(code)` — drops the brand cache entry for `code` and clears every host→code mapping that pointed at it. `invalidate(null)` clears the whole cache (admin reset use case).
- `BrandingService.stats()` — exposes `(hostCacheSize, brandCacheSize)` so an admin endpoint can answer `GET /branding-cache/stats`.
- `BrandingCache.clear()` and `removeWhereValueEquals(value)` — the two primitives `invalidate()` builds on.

What's outstanding:

1. **`BrandingCacheResourceProvider` + `BrandingCacheResourceProviderFactory`** Keycloak realm-resource SPI classes. They register a REST endpoint at `/realms/{realm}/branding-cache/{action}` and dispatch to `BrandingService.invalidate(code)` / `stats()`.
2. **`META-INF/services/org.keycloak.services.resource.RealmResourceProviderFactory`** entry that points at the factory above so Keycloak discovers the new endpoint on startup.
3. **Auth on the endpoint** — bearer token compared in constant time against the same `POC_BRANDING_API_TOKEN` the geowealth side uses to authenticate to Keycloak. Rejects everything else with 401.
4. **Geowealth admin hook** — on every successful WHITELABEL_TBL update / asset upload, POST `{keycloakBase}/realms/geowealth-realm/branding-cache/invalidate?code={code}` with the bearer token. Fail-open: if Keycloak is unreachable, log + carry on; the 60 s TTL still bounds the staleness.

Estimated effort: half a day for the SPI side (Keycloak boilerplate is fiddly), ~2 h for the admin hook. Test-able by curl-ing the new endpoint and asserting the next render shows the updated brand without waiting 60 s.

## Touchpoints

Code changes for #2–#9 live in:
- `geowealth/src/main/java/com/geowealth/keycloak/branding/BrandingApiServlet.java` (#2, #3, #7)
- `keycloak-demo/geowealth-keycloak/src/main/java/com/geowealth/keycloak/forms/GeoWealthLoginFormsProvider.java` (#6)
- `keycloak-demo/geowealth-keycloak/src/main/java/com/geowealth/keycloak/branding/BrandingRegistry.java` (#8)
- `keycloak-demo/geowealth-keycloak/src/main/java/com/geowealth/keycloak/branding/BrandingService.java` (#4, #5 partial)
- `keycloak-demo/geowealth-keycloak/src/main/java/com/geowealth/keycloak/branding/BrandingCache.java` (#5 partial)
- `keycloak-demo/keycloak/geowealth-realm-export.json` (#6 realm attribute, #9 CSP)

Env vars introduced:
- `POC_BRANDING_API_MAX_ASSET_BYTES` (default 2 MB) — gap #3
- `POC_BRANDING_API_RATE_LIMIT_PER_MIN` (default 60) — gap #2
- `KC_SPI_LOGIN_FREEMARKER_GEOWEALTH_REGISTRY_PLACEHOLDERS` (default `true`) — gap #8

None of these are required to be set; defaults preserve current behavior.
