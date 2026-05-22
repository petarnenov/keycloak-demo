# GeoWealth ↔ Keycloak white-labeling sync — architecture and proposal

Production-ready architecture for keeping the Keycloak login screen visually consistent with GeoWealth's existing per-firm branding ("white-labeling"). Industry-standard practices only; no shortcuts that would break at 89+ tenants.

**Date:** 2026-05-21
**Related documents:**
- [`keycloak-dynamic-login-theming.md`](./keycloak-dynamic-login-theming.md) — generic Keycloak theming reference (this doc applies those patterns to GeoWealth)
- [`p1-sso-architecture.md`](./p1-sso-architecture.md) — the SSO bridge (SAML brokering, `firmCd` already in the SAML assertion)
- [`p1-sso-integration-research.md`](./p1-sso-integration-research.md) — solution selection
- [`CLAUDE.md`](./CLAUDE.md) — keycloak-demo survival notes

---

## 1. Executive summary

GeoWealth has a mature, actively-used white-labeling system: ~89 firms today, each with logos, color palettes, email templates, terms-of-use docs, and per-firm URLs. The source of truth is the GeoWealth Oracle database (`WHITELABEL_TBL` + `FIRM_TBL`) plus a sibling filesystem store (`etc/whitelabel/<code>/`). Resolution from request to firm already exists as `AuthorizationManagerTrait.identifyFirmByUrl()`.

The Keycloak login screen must visually match the firm-branded portal the user is logging into. Because the SSO flow (per `p1-sso-architecture.md`) eventually sends users through Keycloak, an un-themed Keycloak page would be a glaring break in the white-label experience and would also be an information leak (users see "GeoWealth" while logging into a firm-branded portal that doesn't mention GeoWealth).

**Recommended approach:** one parametric Keycloak theme + a `ThemeSelectorProvider` SPI + a new read-only branding endpoint on GeoWealth. GeoWealth stays the single source of truth; Keycloak pulls a small JSON snapshot per request (cached) and renders the login page with firm-specific CSS variables, logo, support contact, and terms link.

**Explicitly rejected approaches (and why):**
- ❌ One Keycloak theme JAR per firm. Doesn't scale beyond ~10 firms; every new firm = JAR build + cluster-wide redeploy.
- ❌ Per-client `login_theme` attribute on N OIDC clients. Same scaling problem, and it requires creating an OIDC client per firm, which conflicts with the recommended architecture in `p1-sso-architecture.md` (a single SAML/OIDC trust between Keycloak and the apps).
- ❌ Keycloak reads GeoWealth's Oracle DB directly via JDBC. Couples Keycloak deployment to GeoWealth schema; violates the bounded-context boundary; complicates network/security model.
- ❌ Mirror branding into Keycloak's own DB. Two sources of truth = drift; admins would have to know which UI to edit.

---

## 2. What we're syncing

The data Keycloak needs is a strict subset of `WHITELABEL_TBL` + the per-firm filesystem store. Everything below is *read-only* from Keycloak's perspective.

| Data | GeoWealth source | Keycloak use |
|---|---|---|
| Firm code (identifier) | `FIRM_TBL.FIRM_CD` (numeric), `WHITELABEL_TBL.CODE` (string, e.g. `changepath`) | Tenant key, theme variant selector |
| Display name | `WHITELABEL_TBL.NAME`, `whitelabel.properties#company.longname` | Page title, headings, copy |
| Primary color palette | `WHITELABEL_TBL.LINKS_COLOR`, `GRADIENT_START_COLOR`, `GRADIENT_END_COLOR`, `DESIGN_BODY_COLOR`, `TABLE_HEADER_COLOR`, `TABLE_ROW_ROLLOVER_COLOR` | CSS variables on `:root` |
| Extra colors (CSS variables) | `etc/whitelabel/<code>/<code>_color_theme.json` (already CSS-variable-shaped) | Inlined into `<style>` |
| Favicon | `WHITELABEL_TBL.FAVICON` (BLOB) | `<link rel="icon">` |
| Login logo | `WHITELABEL_TBL.LOGIN_LOGO_BIG` / `LOGIN_LOGO_SMALL` (BLOB) | Header image |
| Support contact | `WHITELABEL_TBL.SUPPORT_EMAIL`, `PHONE`, `ADDRESS`, `WEBSITE` + `whitelabel.properties` | "Need help?" footer |
| Terms of use | `WHITELABEL_TBL.TERMS_OF_USE_DOC` (BLOB) + `whitelabel.properties#company.ltc.url` | Link beneath the form |
| Email templates (subject + body) | `WHITELABEL_TBL.*_SUBJ_TEXT` + `etc/whitelabel/<code>/mail/*.html` | Keycloak email theme (password reset, account setup) |
| URLs (advisor / client portal) | `WHITELABEL_TBL.ADV_PORTAL_URL`, `CL_PORTAL_URL` | "Back to portal" link, post-logout redirect whitelist |

**Out of scope for the login screen** (still in GeoWealth, no Keycloak sync needed): proposal logos, report logos, BIRT master pages, advisor-level branding overrides for in-app pages.

---

## 3. Architecture overview

```
              ┌────────────────────────────────────────────────────────────┐
              │  Browser — https://changepath.demo.geowealth.com/login     │
              └─────┬──────────────────────────────────────────────────────┘
                    │ 1. GET /realms/geowealth/protocol/openid-connect/auth
                    │    Host: changepath.demo.geowealth.com
                    ▼
          ┌─────────────────────────────────────────────────────────────────────┐
          │  Keycloak — geowealth realm, parametric "geowealth-wl" theme         │
          │                                                                     │
          │  ┌──────────────────────────────┐                                   │
          │  │ ThemeSelectorProvider SPI    │ 2. extract tenant from request   │
          │  │  ├─ host header → code        │    priority: host > kc_tenant   │
          │  │  ├─ client attribute fallback│             > client > default   │
          │  │  └─ cache: Caffeine, 60s TTL │                                  │
          │  └──────────────┬───────────────┘                                   │
          │                 │ on cache miss                                     │
          │                 ▼                                                   │
          │  ┌──────────────────────────────┐                                   │
          │  │ GeoWealthBrandingClient      │ 3. GET branding-api/keycloak/   │
          │  │  HTTP, mTLS or token auth     │            whitelabel/<code>    │
          │  └──────────────┬───────────────┘                                   │
          │                 │                                                  │
          │  ┌──────────────▼───────────────┐                                  │
          │  │ FreeMarker context inject    │ 4. ${brand.primary},             │
          │  │  TemplateMethodModel          │    ${brand.logoUrl}, …          │
          │  └──────────────────────────────┘                                   │
          └────────────────────────────┬────────────────────────────────────────┘
                                       │
                                       │ 3. (over the trusted service network)
                                       ▼
              ┌─────────────────────────────────────────────────────────────────┐
              │  GeoWealth — new endpoint                                       │
              │   GET  /branding-api/keycloak/whitelabel/{code}                 │
              │   GET  /branding-api/keycloak/whitelabel/{code}/asset/{kind}    │
              │   POST /branding-api/keycloak/whitelabel/{code}/invalidate  ⇢   │
              │                                                                 │
              │  Reads WHITELABEL_TBL + etc/whitelabel/<code>/*.json            │
              │  Auth: short-lived service token (issued by Keycloak itself,    │
              │        or static long-lived token via secret manager)            │
              │  Writes nothing                                                  │
              └────────────┬───────────────────────────┬────────────────────────┘
                           │                           │
                           │ admin UI saves            │ pushes
                           │ Whitelabel form           │ invalidation
                           ▼                           ▼
              ┌───────────────────────┐    ┌──────────────────────────────┐
              │ WHITELABEL_TBL +      │    │ Keycloak invalidation hook   │
              │ etc/whitelabel/…      │    │ (custom RealmResourceProvider)│
              │ (existing source of   │    │ flushes Caffeine entry        │
              │ truth — unchanged)    │    └──────────────────────────────┘
              └───────────────────────┘
```

**Key properties:**

- **One Keycloak theme**, never one-per-firm.
- **GeoWealth remains the single source of truth.** Keycloak owns the *rendering*, never the *config*.
- **Pull, not push.** Keycloak pulls on demand; cache is short (60 s TTL) with optional invalidation webhook for instant updates from the admin UI.
- **Fail-open.** If GeoWealth branding API is unreachable, Keycloak serves the last-known-good cache; if there's no cache, it serves the default GeoWealth brand. Login is never blocked by a branding outage.
- **No shared DB.** Keycloak does not connect to the Oracle instance. The contract is a small JSON HTTP API.

---

## 4. Component-by-component design

### 4.1 GeoWealth side — new branding API

A new read-only HTTP module on the GeoWealth Tomcat app. Because GeoWealth is Struts 2, this is most naturally implemented either as:
- a Struts 2 action returning JSON (`<result type="json">`), or
- a thin Jakarta servlet mapped under `/branding-api/`.

Either works; servlet is preferable for clean separation from the user-facing Struts pipeline and so it doesn't interact with `BasicAction`'s session-based tenant inference (which would loop back on itself for service callers).

**Endpoints:**

```
GET  /branding-api/keycloak/whitelabel/{code}
GET  /branding-api/keycloak/whitelabel/{code}/asset/logo-login
GET  /branding-api/keycloak/whitelabel/{code}/asset/favicon
GET  /branding-api/keycloak/whitelabel/{code}/asset/terms
GET  /branding-api/keycloak/whitelabel/lookup?host={host}
POST /branding-api/keycloak/whitelabel/{code}/invalidate    (Keycloak ← GeoWealth, optional)
```

**`GET /whitelabel/{code}` response shape:**

```json
{
  "code": "changepath",
  "firmCd": 4,
  "displayName": "ChangePath",
  "supportEmail": "supportemail@changepath.com",
  "phone": "888.798.2360",
  "address": "11460 Tomahawk Creek Pkwy, Ste 200",
  "website": "http://www.changepath.com/",
  "advisorPortalUrl": "https://changepath.demo.geowealth.com",
  "clientPortalUrl": "https://client.changepath.demo.geowealth.com",
  "termsUrl": "/branding-api/keycloak/whitelabel/changepath/asset/terms",
  "assets": {
    "favicon":     { "url": "/branding-api/keycloak/whitelabel/changepath/asset/favicon",     "etag": "f0a1…" },
    "loginLogo":   { "url": "/branding-api/keycloak/whitelabel/changepath/asset/logo-login",  "etag": "9c12…" }
  },
  "cssVariables": {
    "--theme-body-background":   "#f5f5f5",
    "--theme-gradient-start":    "#0e4e79",
    "--theme-gradient-end":      "#b5dea4",
    "--theme-link-color":        "#155e8f",
    "--theme-table-header":      "#f1f1f2",
    "--theme-table-row-rollover":"#f5f5f5",
    "--theme-button-background": "#155e8f"
  },
  "version": "2026-05-21T09:14:22Z"
}
```

Notes:
- The shape mirrors the existing `<code>_color_theme.json` files almost 1:1 — minimal new server code, just a wrapper.
- Assets are URL-referenced, **not base64-inlined**, so Keycloak's static handler and the browser can cache them via HTTP `Cache-Control` + `ETag`.
- `version` is a logical timestamp used as a cache key suffix; bumping it forces re-fetch on the Keycloak side.

**`GET /asset/{kind}` response:**
- Streams the BLOB from `WHITELABEL_TBL` (favicon, login logo) or `etc/whitelabel/<code>/` (terms PDF).
- Strong `ETag` based on row mtime + BLOB hash; `Cache-Control: public, max-age=86400, immutable` when the version path component is included.
- `Content-Type` set correctly (PNG, SVG, application/pdf).

**Lookup endpoint:**
- `GET /whitelabel/lookup?host=changepath.demo.geowealth.com` returns `{ "code": "changepath" }`.
- Wraps the existing `AuthorizationManagerTrait.identifyFirmByUrl()` logic — the **single source of truth** for host→firm resolution.
- Keycloak uses this so we don't duplicate the per-advisor / subdomain matching algorithm.

**Authentication of these endpoints:**
- Service-to-service only. **Not exposed to end users.**
- Two options, pick one:
  1. **mTLS** between Keycloak nodes and GeoWealth (cleanest, requires PKI; assumes you already have a service-mesh / cert distribution mechanism).
  2. **Static bearer token** stored as a Keycloak secret + a corresponding constant on GeoWealth side, rotated quarterly. Simpler if there's no service mesh.
- Network: bind these endpoints to an internal listener (different connector / different ingress path) and firewall them off from the public internet. GeoWealth already has `localhost`-only admin endpoints; same pattern.

**Authorization model:**
- The branding API is *read-only* and exposes data that is already public on the firm's branded portal (logos, colors, support contact). Even so, gate by service token so a misconfigured proxy can't accidentally expose internal endpoints.
- The invalidation endpoint (Keycloak ← GeoWealth) needs its own token; should be different from the read token so a compromise of one doesn't enable the other.

### 4.2 Keycloak side — the parametric theme

A single theme JAR named `geowealth-wl` deployed under `/opt/keycloak/providers/`. Structure:

```
geowealth-wl-theme/
├── META-INF/keycloak-themes.json
└── theme/
    └── geowealth-wl/
        ├── login/
        │   ├── theme.properties               (parent=keycloak.v2)
        │   ├── template.ftl                   (overrides v2 base; injects brand vars)
        │   ├── login.ftl                      (overrides; uses ${brand.*})
        │   ├── login-update-password.ftl
        │   ├── resources/
        │   │   ├── css/
        │   │   │   └── geowealth-wl.css       (uses CSS variables, no hardcoded colors)
        │   │   └── img/
        │   │       └── default/               (GeoWealth default brand assets)
        │   └── messages/
        │       └── messages_en.properties     (English; per-tenant copy comes from API)
        └── email/
            ├── theme.properties
            ├── html/
            │   ├── password-reset.ftl
            │   └── executions-update-password.ftl
            └── text/
                └── …
```

**CSS strategy:**
- All colors expressed as CSS custom properties on `:root`, matching the GeoWealth naming convention (`--theme-link-color`, `--theme-gradient-start`, etc.).
- `template.ftl` writes an inline `<style>:root { … }</style>` block populated from `${brand.cssVariables}`, so the browser sees the final values immediately (no flash of unstyled content).
- Static CSS file holds layout, typography, spacing — never colors.
- Logo `<img>` tag uses `${brand.assets.loginLogo.url}`; favicon `<link>` uses `${brand.assets.favicon.url}`. Both URLs include a version suffix for cache busting.

**FreeMarker context:**
- A custom `TemplateMethodModel` (or a `Map<String,Object>` injected via a `LoginFormsProvider` wrapper) exposes `brand` under the template's variable scope.
- `brand` is a flat-ish DTO matching the JSON shape from §4.1.

### 4.3 Keycloak side — `ThemeSelectorProvider` SPI

This is the core dynamic piece. Lives in a new `keycloak-provider` module (or as a sibling to the existing User-Storage and Email-OTP providers in this repo).

**Resolution order** (first match wins):

1. **HTTP `X-Forwarded-Host` / `Host`** — call `/branding-api/keycloak/whitelabel/lookup?host=<host>` (with local cache, see §4.4). This is the primary mechanism, because GeoWealth's existing URL → firm resolution is the source of truth.
2. **Authentication session note `kc_tenant`** — for cases where an explicit tenant hint is needed (e.g. step-up auth, deeplinks).
3. **Client attribute `tenant_code`** — fallback if there's a per-client mapping (e.g. one OIDC client per region rather than per firm).
4. **Default** — return `"default"`, which resolves to GeoWealth Master (firm_cd=1).

**Why this order:** it mirrors GeoWealth's own `AuthorizationManagerTrait.identifyFirmByUrl()` priorities — host first, then context, then fallback — so the login screen brand will always match the destination portal brand.

**Skeleton:**

```java
public class GeoWealthThemeSelectorProvider implements ThemeSelectorProvider {

  private final KeycloakSession session;
  private final BrandingCache cache;
  private final BrandingApiClient client;

  @Override
  public String getThemeName(Theme.Type type) {
    // We always return one theme; the *content* changes per request via FreeMarker context.
    return type == Theme.Type.LOGIN || type == Theme.Type.EMAIL
        ? "geowealth-wl"
        : null; // delegates to the default selector for admin/account
  }

  // Side effect: stash the resolved tenant on the session so the FreeMarker
  // context injector can read it without re-doing the lookup.
  // (Done from a wrapping LoginFormsProvider, not from getThemeName itself —
  // because getThemeName is called from multiple sites and we want the lookup once.)
}
```

**Important:** `getThemeName()` returns the **same** theme name for every tenant. The selector's purpose here is not to multiplex between N themes — it's to make sure the parametric `geowealth-wl` theme is used and to side-effect the per-request tenant resolution into a place the template can read.

### 4.4 Keycloak side — branding client + cache

A thin HTTP client + Caffeine cache.

```java
class BrandingApiClient {
  private final HttpClient http;     // JDK HttpClient, virtual threads OK
  private final URI base;
  private final String serviceToken; // injected via SPI config
  Brand fetch(String code);          // GET /whitelabel/{code}
  String lookupHost(String host);    // GET /whitelabel/lookup?host=
}

class BrandingCache {
  // Caffeine, asyncLoading
  // maxSize = 256 (well above the ~89 known firms)
  // expireAfterWrite = 60s   (TTL — eventual consistency floor)
  // refreshAfterWrite = 30s  (async background refresh; serves stale during reload)
  CompletableFuture<Brand> get(String code);
  void invalidate(String code);
}
```

**Why these settings:**
- 60 s TTL = bounded staleness for normal updates (admin changes a color → visible within 60 s without any coordination).
- `refreshAfterWrite` means hot keys are reloaded in the background; users never wait on a network round trip.
- `asyncLoading` keeps `getThemeName` non-blocking.
- 256 entries × ~5 KB ≈ 1.3 MB per Keycloak node — negligible.

**Failure modes:**
| Failure | Behavior | Why it's safe |
|---|---|---|
| Branding API 500 / timeout, key in cache | Serve cached (possibly stale) value, log warning | Login never breaks because of branding |
| Branding API 500, key not in cache | Return `defaultBrand` constant baked into theme | Worst case = GeoWealth-branded login, still functional |
| Unknown firm code | Return `defaultBrand`, log INFO | Spoofed `?tenant=xyz` can't crash the page |
| Branding API returns malformed JSON | Treat as failure → above paths | Defensive parsing |

### 4.5 Keycloak side — invalidation webhook (optional, recommended)

For instant updates from the GeoWealth admin UI:

- Add a custom `RealmResourceProvider` exposing `POST /realms/geowealth/branding/invalidate` (authenticated with the second service token, distinct from the read token).
- Body: `{ "code": "changepath" }`.
- Effect: `cache.invalidate(code)` on the local node + best-effort fan-out to other Keycloak nodes (next §).
- On GeoWealth side: hook `WhitelabelDAO.save()` (or the equivalent Akka `SaveWhitelabelGroupMsg` handler) to fire the webhook after a successful commit.

**Cross-node invalidation in a Keycloak cluster:**
- Easiest production-ready route: rely on the Infinispan work cache (`keycloak.cache.work`) that Keycloak already runs for cluster-coordinated invalidation; expose a small wrapper that adds your branding key to it.
- Alternative: have GeoWealth call each Keycloak node individually (the admin UI knows the cluster topology via service discovery). Less elegant but no shared state.

**Why webhook is "optional":** the 60-s TTL is acceptable for branding changes — they're rare and non-urgent. Skip the webhook in v1 if it's a meaningful build cost.

---

## 5. Recommended URL & realm topology

This matters because the chosen topology determines how the `ThemeSelectorProvider` finds the tenant.

**Recommended: single realm, host-based tenant resolution.**

- **One Keycloak realm**: `geowealth` (matches the SSO architecture in `p1-sso-architecture.md`).
- **OIDC client(s):** one per backend application (P1, MFE shell, advisor portal, client portal). **Not one per firm.** The same client serves all firms; the tenant is derived from the host the user came through, not the client.
- **Hostnames:** each firm gets one or more host names that route to Keycloak (e.g. `auth.changepath.demo.geowealth.com` or just `changepath.demo.geowealth.com/auth/`).
- **Reverse proxy:** preserves `Host` (or sets `X-Forwarded-Host`), strips client-supplied values. Required for header-based theme selection to be safe.

**Why a single realm:** N realms = N realm configs, N sets of identity brokers, N copies of every SPI registration. Realms are an isolation boundary for *security configuration*, not for *branding*. Firms share the same security model; they differ only in look-and-feel.

**Why not per-firm OIDC clients:** the backend apps (P1, the MFE shell) don't know or care which firm a user belongs to at the OIDC layer — that's a runtime concern. Encoding firm in the client_id would require backends to maintain a list of N client IDs and pick the right one per request, which is the same complexity we're trying to eliminate.

---

## 6. Data flow examples

### 6.1 Cold-cache login at `changepath.demo.geowealth.com`

1. Browser → `GET https://changepath.demo.geowealth.com/realms/geowealth/protocol/openid-connect/auth?…`
2. Reverse proxy → Keycloak with `X-Forwarded-Host: changepath.demo.geowealth.com`.
3. Keycloak's auth endpoint → `LoginFormsProvider` → looks up theme via `ThemeSelectorProvider`.
4. `ThemeSelectorProvider`:
   - Reads `X-Forwarded-Host` → `changepath.demo.geowealth.com`.
   - Cache miss → `BrandingApiClient.lookupHost("changepath.demo.geowealth.com")` → `{ code: "changepath" }`.
   - Cache miss on `changepath` → `BrandingApiClient.fetch("changepath")` → full Brand JSON.
   - Caches both under their respective keys with 60 s TTL.
   - Stashes `Brand` on the auth session.
   - Returns theme name `geowealth-wl`.
5. FreeMarker renders `login.ftl`; `${brand.cssVariables}` writes the inline `<style>` block; logo `<img>` URL points at the branding API; favicon link likewise.
6. Browser receives a fully ChangePath-branded login page; logo and favicon are served from the GeoWealth branding API with `Cache-Control: public, max-age=86400`.

**Total cold-path latency:** ~2× HTTP round trips inside the trusted network (lookup + fetch), each ~5–20 ms. Subsequent loads in the same minute = zero network.

### 6.2 Admin changes ChangePath's primary color

1. Admin saves the form in GeoWealth back-office UI.
2. `WhitelabelDAO.save()` commits the change to `WHITELABEL_TBL`.
3. *(With webhook)* GeoWealth fires `POST /realms/geowealth/branding/invalidate { "code": "changepath" }`.
4. Keycloak invalidates the `changepath` cache entry on its node and broadcasts the invalidation through Infinispan work cache.
5. Next login on `changepath.demo.geowealth.com` repopulates the cache from the API → users see the new color immediately.

*(Without webhook)* — same flow, but step 3/4 are absent; users see the new color within 60 s.

### 6.3 GeoWealth branding API is down

1. Browser → Keycloak login page request.
2. `ThemeSelectorProvider` cache miss for `changepath` → `BrandingApiClient.fetch()` → timeout / 503.
3. Catch handler:
   - If a stale entry exists (e.g., still in-memory from before TTL expiry), serve that and log `WARN BrandingApi unreachable; serving stale`.
   - If no entry exists at all, return the hard-coded `defaultBrand` baked into the theme JAR (GeoWealth Master).
4. Login proceeds normally. Branding might degrade for new firms / cold caches; login flow never blocks.

---

## 7. Migration & rollout plan

Three phases, each independently shippable.

### Phase 1 — Read-only sync, TTL-only (minimum viable)

- Implement GeoWealth `/branding-api/keycloak/whitelabel/{code}` and `/lookup`.
- Implement Keycloak parametric theme + `ThemeSelectorProvider` + Caffeine cache.
- Roll out to a single canary firm (recommend GeoWealth Master itself first, then ChangePath).
- Monitor: cache hit ratio, API latency p99, theme render errors.
- Acceptance: 99% cache hit ratio in steady state; p99 < 50 ms on cache miss; zero theme render errors per 24 h.

### Phase 2 — Invalidation webhook

- Implement `POST /branding/invalidate` in Keycloak (custom `RealmResourceProvider`).
- Hook GeoWealth admin save path to fire the webhook.
- Service token rotation policy + secret distribution (Keycloak Vault or Kubernetes Secret).
- Acceptance: branding change visible end-to-end within 5 s p95.

### Phase 3 — Email theme + advanced assets

- Extend `geowealth-wl` theme with `email/` type using the per-firm email subject/body data already in `WHITELABEL_TBL` and `etc/whitelabel/<code>/mail/`.
- Migrate password reset / account setup mails from GeoWealth's existing Mailer to Keycloak's email pipeline (only if user accounts move to Keycloak as the system of record — otherwise leave email in GeoWealth).
- Optionally: move assets behind a CDN (CloudFront / Fastly) with `geowealth.com` origin; only the JSON config stays on the live API.

---

## 8. Security & compliance considerations

- **Service tokens:** the read token has access to brand metadata (non-sensitive on its face, but it's still internal data) — store in a secret manager, rotate quarterly. The invalidation token is more sensitive (can poison user experience by repeatedly invalidating) — rotate quarterly, alert on usage.
- **CSP:** Keycloak's default CSP must allow `img-src` from the GeoWealth branding API host (if it's a different origin than Keycloak). If logos are co-hosted (Keycloak proxies the asset endpoints), CSP can stay tight (`img-src 'self'`).
- **Open redirect on terms link:** the `termsUrl` comes from the API. Render it as a normal anchor with `rel="noopener noreferrer"`; don't paste raw URL into JS. Same for `clientPortalUrl` / `advisorPortalUrl` if displayed.
- **HTML / CSS injection:** all CSS variable values must be validated against a regex (`^#[0-9a-fA-F]{3,8}$` for colors, allow-list for keywords). A malicious value containing `;` could break out of the `style` attribute. Validate at the API boundary (GeoWealth) and again at the Keycloak template (defense in depth).
- **Logo upload constraints (already in GeoWealth):** confirm size and type limits at the API edge — Keycloak should refuse a logo over, say, 200 KB to keep the login page lean.
- **Audit logging:** every cache miss → log the lookup with firm code + decision (cache, default, fallback). Useful for catching spoofed `Host` headers.
- **`X-Forwarded-Host` trust:** **only** trust when behind the known reverse proxy. Configure Keycloak 26 with `--proxy-headers xforwarded`; configure the proxy to strip client-supplied `X-Forwarded-*` and set its own.
- **DDoS surface:** the branding API is read-only, returns small JSON, and is hit at most once per minute per firm per Keycloak node. Not a meaningful attack surface; standard WAF rate limits suffice.
- **PII:** none in the branding payload. Phone numbers and support emails are deliberately public-facing brand info, the same as on the firm portal's footer.

---

## 9. Trade-offs and explicitly rejected alternatives

### 9.1 Pull from GeoWealth (chosen) vs. push from GeoWealth to Keycloak

| Property | Pull (chosen) | Push |
|---|---|---|
| Source of truth | GeoWealth DB | Keycloak local store |
| Failure semantics | Stale cache acceptable | Out-of-sync risk if push fails |
| Operational complexity | One API in GeoWealth + cache in Keycloak | Push pipeline + reconciliation + drift detection |
| Time to first sync of new firm | Instant on first login (cache miss → fetch) | Lagged by push frequency |

Pull wins on every dimension except the (small) cost of always having GeoWealth available.

### 9.2 N themes (rejected) vs. parametric theme (chosen)

89 distinct theme JARs would mean: 89× Keycloak provider builds; 89× theme registrations; an in-house theme-build pipeline; cluster restarts on every new firm. Pure operational debt.

The parametric theme handles N=1, N=89, and N=10,000 identically.

### 9.3 Direct JDBC into Oracle (rejected)

Tempting because GeoWealth's Oracle is already a known store, but:
- Couples Keycloak deployment to a schema we don't control.
- Forces Keycloak's network into the database subnet.
- Schema changes in GeoWealth would silently break Keycloak.
- No clean API contract.

A REST endpoint is a stable, versionable contract.

### 9.4 Keycloakify (third-party theme framework)

Keycloakify is excellent for greenfield Keycloak themes built with React/Vue and Storybook, and would be on the table if the parametric theme grew complex enough to want a real component model. **For v1 not recommended**: the theme here is a small set of FreeMarker templates with CSS variables, and adding a build pipeline + framework adds more risk than it solves. Revisit if the theme starts to acquire >5 distinct page templates and complex state.

---

## 10. Implementation checklist

GeoWealth side (~3–5 days):
- [ ] New Struts action or servlet under `/branding-api/keycloak/` with the four endpoints in §4.1.
- [ ] Wire `lookup` to `AuthorizationManagerTrait.identifyFirmByUrl()` (re-use, do not duplicate).
- [ ] Service-token authentication filter, with rotation hook.
- [ ] `WhitelabelDAO.save()` post-commit hook firing the invalidation webhook (phase 2).
- [ ] Unit tests + an integration test that hits the endpoint with a real firm code from `WHITELABEL_TBL`.
- [ ] DDL for any new audit-log table if logging webhook firings.
- [ ] Documentation in `docs/branding-api.md` (response shape, auth, rotation).

Keycloak side (~5–8 days):
- [ ] New module in `keycloak-provider/` (or sibling) for the theme + SPI.
- [ ] `geowealth-wl` theme tree (login type initially; email type in Phase 3).
- [ ] `META-INF/services/org.keycloak.theme.ThemeSelectorProviderFactory` registration.
- [ ] `BrandingApiClient` (JDK HttpClient) + `BrandingCache` (Caffeine).
- [ ] FreeMarker context injection (TemplateMethodModel or wrapped LoginFormsProvider).
- [ ] CSP whitelisting for logo origin (if cross-origin).
- [ ] Color/value validation at the boundary (regex, length cap).
- [ ] Health probe: `GET /realms/geowealth/branding-health` reports last successful API call + cache state.
- [ ] Custom `RealmResourceProvider` for the invalidation webhook (Phase 2).
- [ ] Build into the existing `Dockerfile.keycloak` shadow-jar pipeline (matches the User-Storage + Email-OTP pattern in this repo).
- [ ] Integration tests against a Wiremock branding API.
- [ ] Load test: 200 RPS login starts, 99% cache hit; measure p99 latency.

Cross-cutting:
- [ ] Reverse proxy config: `--proxy-headers xforwarded` (Keycloak), trusted-proxy / `set_real_ip_from` on the proxy.
- [ ] Service-token rotation runbook.
- [ ] Operational dashboards: cache hit ratio, branding API latency, theme render errors.
- [ ] Decommission plan for the legacy P1 login page once Keycloak is canonical (track in `p1-sso-architecture.md`).

---

## 11. Open questions

These are decisions to make before starting Phase 1; they're not blockers but should be answered up front:

1. **Hostname pattern.** Are firms going to be `<firm>.geowealth.com` subdomains, or `<firm>.<env>.geowealth.com` per environment? The `ThemeSelectorProvider` lookup logic needs to know.
2. **Service-to-service auth mechanism.** mTLS or bearer token? Need PKI / secret infrastructure decision.
3. **Single realm or one realm per environment?** Recommendation: one realm per environment (`geowealth-dev`, `geowealth-prod`), all multi-tenant within. Not one realm per firm.
4. **Logo asset hosting.** Stream from GeoWealth API forever, or migrate to a CDN-fronted S3 bucket once the system is proven? Phase 3 decision.
5. **Email theme.** Are password-reset emails going to be sent from Keycloak or from GeoWealth's existing Mailer? Affects whether we ever build the email half of the theme.
6. **Per-advisor branding.** GeoWealth supports advisor-level whitelabel overrides (`NEmployeeDetail.getCpWhitelabelKeyword()`). Do those need to surface in Keycloak, or is firm-level branding sufficient at the login screen? Recommendation: firm-level only for v1; revisit if there's actual user demand.

---

## 12. References

- GeoWealth DDL: `DDL/old/2021-02-02_01_create_whitelabel_tbl.sql`, `DDL/old/2023-06-28_02_add_to_whitelabel_relationship.sql`, `DDL/old/authorisation.sql`.
- GeoWealth domain model: `src/main/java/com/geowealth/model/whitelabel/Whitelabel.java` (the canonical list of fields).
- GeoWealth URL → firm logic: `src/main/java/com/geowealth/agent/authorisation/AuthorizationManagerTrait.java` (`identifyFirmByUrl`).
- GeoWealth runtime property loader: `src/main/java/com/netfolio/whitelabel/WhiteLabeler.java`.
- Filesystem brand store: `etc/whitelabel/<code>/` (89+ firms).
- Keycloak SPI: `org.keycloak.theme.ThemeSelectorProvider`.
- Keycloak custom resource: `org.keycloak.services.resource.RealmResourceProvider`.
- This repo's existing SPI patterns: `keycloak-provider/` (User Storage SPI + Email-OTP authenticator) — same `shadowJar` pipeline used here.
