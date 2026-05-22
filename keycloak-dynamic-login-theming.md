# Dynamic Login-Screen Theming in Keycloak — Full Report

A working reference for designing, implementing, and operating dynamic theme selection on the Keycloak login screen. Covers the industry-standard architectures, best practices, and the caveats that actually bite in production.

Targets **Keycloak 26.x** (PatternFly 5 `keycloak.v2` base, v1 themes removed). Most of the SPI surface predates 26 and is stable.

---

## 1. What "dynamic" actually means — disambiguation

"Dynamic theme switching" conflates several different problems, each with its own mechanism. Pick the one you're actually solving before you start.

| Scenario | Who picks the theme | When it's picked | Mechanism |
|---|---|---|---|
| Per-realm | Realm admin | Once, at config time | `loginTheme` on the realm |
| Per-client | Client admin | When an OIDC/SAML flow is initiated by that client | Client attribute `login_theme` |
| Per-tenant (multi-tenant in one realm) | Platform, based on context (subdomain / header / client) | Every request | `ThemeSelectorProvider` SPI |
| Per-locale | User / `Accept-Language` | Per request | `messages_<locale>.properties` + i18n switch |
| Light/dark toggle | End user | Client-side, in the browser | CSS variables + JS in the theme |
| Per A/B test / feature flag | Platform | Per request | `ThemeSelectorProvider` SPI |

The true "dynamic" answer on the server side is **`ThemeSelectorProvider`**. Everything else is just statically chosen configuration.

---

## 2. How Keycloak themes work (the substrate)

- **Theme types:** `login`, `account`, `admin`, `email`, `welcome`. Each type is independently implemented within a theme.
- **Parent inheritance:** `theme.properties` declares `parent=keycloak.v2`. Only the files you override live in the theme; the rest come from the parent.
- **Templating:** FreeMarker (`.ftl`). Access to `${url.resourcesPath}`, `${realm}`, `${client}`, `${msg("key")}`, etc.
- **i18n:** `messages_<locale>.properties` per theme type. Localization ≠ styling.
- **Static resources:** `resources/css/`, `resources/img/`, `resources/js/`. Served by Keycloak's static handler with a long cache header and a version-hashed URL.
- **Distribution:** A provider JAR dropped into `/opt/keycloak/providers/`. Declared through `META-INF/keycloak-themes.json`.
- **Keycloak 26 v2 base:** The PatternFly 5–based `keycloak.v2` is the default. The old v1 (`keycloak`) is removed — themes built for v1 do not work in 26 without a re-skin.

---

## 3. Built-in mechanisms for picking a theme

### 3.1 Per-realm theme
- Admin Console → Realm Settings → Themes → Login Theme.
- Admin API: `PUT /admin/realms/{realm}` with fields `loginTheme`, `accountTheme`, `adminTheme`, `emailTheme`.
- In `realm-export.json`: top-level `"loginTheme": "<name>"`.
- Hard fallback for everything in the realm unless a client overrides.

### 3.2 Per-client theme
- Client attribute `login_theme`. In a client JSON: `"attributes": { "login_theme": "app-a" }`.
- Applies only when the user initiates the auth flow from that specific client (OIDC `client_id=…`, SAML AuthnRequest, etc.).
- Does **not** apply when the user hits the realm account console or a generic login URL without client context.

### 3.3 ThemeSelectorProvider SPI — the real "dynamic" entry point

This is the official extension point for "decide the theme at request time":

```java
public interface ThemeSelectorProvider extends Provider {
    String getThemeName(Theme.Type type);
}
```

From the `KeycloakSession` injected into the factory you have access to:
- `session.getContext().getRealm()` — the realm
- `session.getContext().getClient()` — the current client (when the request is in the context of an OIDC/SAML flow)
- `session.getContext().getHttpRequest()` — headers, cookies, query params
- `session.getContext().getUri()` — URL, host
- `session.getContext().getAuthenticationSession()` — the auth session, if we're in a flow

Registered via `META-INF/services/org.keycloak.theme.ThemeSelectorProviderFactory`. Standard Keycloak SPI pattern (the same shape as User Storage SPI).

**This is the tool that solves ~90% of "dynamic theme" requirements** — multi-tenant branding, subdomain-based skinning, A/B testing, locale-driven overrides.

### 3.4 LoginFormsProvider customization
A more invasive route — replace the entire rendering pipeline. Rarely needed; almost anything you'd want can be done with `ThemeSelectorProvider` + a parametric theme.

---

## 4. Industry-standard architectures

### 4.1 Multi-tenant white-labeling in one realm — recommended pattern

**Approach A (naive, not recommended above ~10 tenants):** N themes as JARs, per-client `login_theme`.
- Pro: zero-code, everything is configuration.
- Con: every new brand = build + deploy + restart. Doesn't scale.

**Approach B (industry-standard):** One parametric theme + `ThemeSelectorProvider` + external tenant store.
1. One theme using CSS variables: `--primary`, `--logo-url`, `--font-family`, etc.
2. `ThemeSelectorProvider` always returns the same theme name, but **branches at the tenant-config level**.
3. Tenant configs (color palette, logo URL, copy strings) live in an **external store** — Postgres, Redis, a REST API, an S3 JSON file.
4. A custom `LoginFormsProvider` decorator or a `RealmResourceProvider` injects tenant-specific variables into the FreeMarker context.
5. CSS uses those variables at runtime.
6. Cached per-tenant with a TTL.

This is the architecture behind Auth0 Universal Login custom domains, Okta brands, and WSO2 Identity Server tenant branding.

### 4.2 Multi-tenant — realm per tenant
- Per-realm `loginTheme`, one theme JAR per realm.
- Heavier ops (more realms to manage), cleaner isolation.
- Fits <50 enterprise tenants.

### 4.3 Subdomain-based branding
- A reverse proxy (Nginx/Traefik/ALB) passes `X-Forwarded-Host` to Keycloak.
- `ThemeSelectorProvider` reads the header and resolves the tenant.
- **Critical caveat:** only trust the header if the reverse proxy sanitizes it (drops any client-supplied value and re-sets it). Otherwise → spoofing. Configure `--proxy-headers xforwarded` in Keycloak 26.

### 4.4 Light/dark toggle on the login screen itself
Server-side theme selection doesn't help here — the toggle must be client-side:
1. CSS variables in the theme for `:root` (light) and `[data-theme="dark"]` (dark).
2. `prefers-color-scheme` media query for OS default.
3. A small inline `<script>` early in `<head>` that reads cookie/localStorage and sets `data-theme` on `<html>` before CSS parsing → no FOUC.
4. Toggle button that writes to a cookie (`Secure`, `SameSite=Lax`, **not** HttpOnly — JS must read it).
5. Cookie persists across sessions.

### 4.5 Keycloakify — third-party but industry-adopted
- Build from React/Vue/Svelte → FreeMarker, deploy as a standard theme.
- Storybook for a visual dev loop.
- Type-safe FreeMarker context and a hydration layer.
- De facto standard for new "real UX" Keycloak themes since 2023.
- Con: an extra build step, learning curve, abstraction leaks when the underlying FreeMarker gets complex.

---

## 5. Best practices

### Theme hygiene
- **Never** edit `/opt/keycloak/themes/keycloak*`. Always ship a custom theme JAR.
- Declare `parent=keycloak.v2`; override only what you're differentiating.
- `theme.properties` should list `locales=`, `styles=`, `scripts=`, `import=` (for shared resources between themes).
- Localization: always via `messages_<locale>.properties`; never hard-code user-facing strings in `.ftl`.

### Dev workflow
- Enable dev mode: `--spi-theme-static-max-age=-1 --spi-theme-cache-themes=false --spi-theme-cache-templates=false`. Without it every edit needs a restart.
- Test in production mode (`kc.sh start`) too — cache + minification behave differently from `start-dev`.

### Security
- **CSP:** Keycloak 26 ships a strict CSP. Inline `<script>`/`<style>` will be blocked. Whitelist via realm Security Defenses or `--spi-content-security-policy-…`. Don't blanket-disable CSP.
- **Trusted headers:** never trust `X-Forwarded-*` without a properly configured proxy mode.
- **Theme injection:** if your `ThemeSelectorProvider` reads user-controlled input (a query param, etc.) and forwards it to the theme loader, validate against a whitelist — otherwise theme-name traversal.

### Accessibility
- Preserve the semantics of the base templates (aria, semantic HTML). Don't strip labels for "cleaner design."
- Test with axe-core, Lighthouse, NVDA/JAWS keyboard navigation.
- Contrast ≥ WCAG AA (4.5:1 for text); login forms are used by people with vision impairment, often on mobile.

### Performance
- Static resources in the JAR → served with a long `Cache-Control` and hash-suffixed URLs → CDN-friendly.
- Avoid runtime-templated CSS (string interpolation in `.ftl` for colors). Use CSS variables.
- First request after restart pays a FreeMarker compile cost (~100–300 ms first-hit per template). Not critical, but don't count it as your latency budget.

### Operationally
- Deploying a theme JAR requires `kc.sh build` (or autobuild on start). Hot-dropping into `providers/` without a build won't work in production mode.
- In a cluster: every node must have the JAR and must be `kc.sh build`-ed. Rolling deploy → mismatch → users temporarily see different themes on different nodes.
- Version themes through JAR version + a theme-name suffix (`brand-v2`) if you make a breaking change.

---

## 6. Caveats — in detail

### 6.1 Build-time
- Theme JARs must be in `providers/` at `kc.sh build` time, not only at `start`. Auto-build mode covers this but is slower on start.
- A new theme requires a full image rebuild if you bake themes into the image (e.g. via a `Dockerfile.keycloak` stage), not just a container restart.

### 6.2 Caching — a double-edged sword
- In prod, Keycloak caches templates and static resources aggressively.
- For a user-facing change without a restart: bump the `?v=` query string on resources, or version-bump the theme name.
- Realm theme override is not cached per request — but the **compiled FreeMarker** is, so if `ThemeSelectorProvider` returns different themes, each one has its own cache (fine).

### 6.3 ThemeSelectorProvider — gotchas
- Runs **per request**, including AJAX from the login page (`/login-actions/authenticate`). Must be **fast** — no synchronous REST in the hot path. Cache the tenant lookup.
- On error: falls back to the default theme. Log the error, otherwise debugging is a nightmare.
- On the initial GET `/auth`, the user is **not** yet authenticated → `session.getContext().getAuthenticationSession()` may be null or partial. Do not rely on user identity here.
- Returning a non-existent theme name → fallback + error in `keycloak.log`. Test the unknown-tenant path explicitly.

### 6.4 Per-client theme — its limits
- Applies only in client-initiated flows. Directly hitting `/realms/<r>/account` or `/realms/<r>/protocol/openid-connect/auth` without `client_id` → falls back to the realm theme.
- The account console (v2 SPA) has its own theme type — `loginTheme` does not theme the account UI.
- Direct-grant (`grant_type=password`) has no UI at all → no theme is applied. (Practical note: scripts that exercise the realm via direct-grant won't surface theme issues.)

### 6.5 Admin console theme
- Supported, but **strongly not recommended** to customize for end users. The admin UI is tightly coupled to specific templates — removing a template breaks admin functionality.
- Industry recommendation: leave the admin console default, lock it behind network/VPN.

### 6.6 Email theme — often forgotten
- Separate from the login theme. `emailTheme` on the realm + email type in the JSON.
- Plain-text and HTML variants; both must be themed.
- Resend / SMTP doesn't care — Keycloak renders with FreeMarker and submits the full body.

### 6.7 i18n interaction
- The locale picker on the login page is built into `keycloak.v2` if `internationalizationEnabled: true` and `supportedLocales` are set on the realm.
- Locale cookie: `KEYCLOAK_LOCALE`. Persists across sessions.
- Theme resources may be locale-specific: `resources/img/logo_bg.png` vs `resources/img/logo_en.png` — branch in FreeMarker on `${locale}`.

### 6.8 CSP and external assets
- The default CSP blocks external scripts/styles.
- If you load a tenant logo from S3/CDN → whitelist in realm Security Defenses → Content-Security-Policy → add `img-src 'self' https://your-cdn.example.com`.
- Don't disable CSP entirely — overlay-phishing attacks against login pages (notably 2021) are real.

### 6.9 Reverse proxy
- For header-based theme selection: set `--proxy-headers xforwarded` (or `forwarded` for RFC7239) in Keycloak 26.
- The reverse proxy **must** re-set `X-Forwarded-Host` (not just append), otherwise it will forward client-supplied values.
- Locally: `localhost:8898` won't trigger subdomain logic — you need `*.localhost` or `/etc/hosts` entries to test.

### 6.10 Hot reload across a cluster
- It doesn't exist painlessly. Rolling restart with a health-checked drain is the standard.
- Sticky sessions reduce the risk of a user seeing two different themes across nodes during a deploy.

### 6.11 Migration: v1 → v2 (Keycloak 26)
- The old `keycloak` (v1) theme is removed in 26. Old custom themes with `parent=keycloak` → fall back and break.
- v2 is built on PatternFly 5; class names, DOM structure, CSS variables — all different.
- Account console v1 is removed — a new account theme must extend `keycloak.v2` account.

### 6.12 Resource versioning
- Static resources get a hash in the URL (`/resources/<version>/login/<theme>/<file>`). Don't hard-code paths — always go through `${url.resourcesPath}`.
- When changing the theme name (Keycloak picks a new resources hash), the browser cache is invalidated automatically. When **replacing files in the same theme**, old URLs may still sit in browser caches until the TTL expires.

### 6.13 Production — easy to miss
- `kc.sh start` (production) requires `--hostname` to be set. Without it, login URLs may be wrong; the theme renders but redirects fight you.
- The health endpoint (`/health`) doesn't go through theme rendering — it isn't a test of the theme.

---

## 7. Concrete recommendation for "industry-standard dynamic theme switching"

The architecture I'd recommend for a new project:

1. **One parametric theme** as a JAR in `/opt/keycloak/providers/`. CSS over variables: `--primary`, `--surface`, `--on-primary`, `--logo-url`, `--font-family`, `--radius`. No hard-coded colors.

2. **A `ThemeSelectorProviderFactory`** implementation that:
   - Reads tenant ID in this priority: client attribute `tenant_id` → host header (subdomain) → query param `?tenant=` (preview only).
   - Looks up in an in-process cache (Caffeine, 60 s TTL).
   - On cache miss → REST call to a tenant service.
   - Always returns the parametric theme's name — but **records** the tenant ID in `session.setAttribute("tenantId", id)` for later use.

3. **A tenant-config provider** (a separate `RealmResourceProvider` or a `TemplateMethodModel`) that injects a JSON blob of tenant-specific variables into the FreeMarker context. The template writes them as `<style>:root { --primary: ${tenant.primary}; ... }</style>`.

4. **Static assets:**
   - Small N tenants (~50): logos baked into the theme JAR under `resources/img/tenants/<id>.svg`.
   - Large scale: external CDN with CSP whitelist; tenant config carries the URL.

5. **Caching strategy:**
   - Theme files cached by Keycloak (default prod behavior, don't touch).
   - Tenant config cached in-memory with a TTL.
   - Invalidation endpoint: `POST /realms/.../tenant-cache/invalidate?tenantId=…` (custom realm resource).

6. **Fallback strategy:**
   - Unknown tenant → default theme config (the base brand).
   - Tenant service down → use last-known cached config; if never cached → default.
   - Never break login. Always deliver some UI.

7. **Test matrix:**
   - `GET /realms/<r>/protocol/openid-connect/auth?client_id=<c>` per tenant → validate CSS variables in the HTML.
   - Per-locale × per-tenant cross product (at least one non-English locale × one tenant).
   - Unknown-tenant fallback.
   - CSP: zero violations in the browser devtools console.
   - Lighthouse a11y > 95.
   - Load: p99 < 100 ms for `GET` of the login page on a cache hit.

---

## 8. Applied to this repo (`keycloak-demo`)

Current state observed:

- `keycloak/realm-export.json` has `"loginTheme": "mfe-shell"` (line 6).
- Legacy per-client overrides exist: `app-a`, `app-b`, `app-c` carry a `"login_theme"` client attribute. Per `CLAUDE.md`, those clients are disabled in favor of `mfe-shell-client`, but the theme attributes remain as a documented example.
- `keycloak-provider/src/main/resources/theme-resources/templates/` contains `email-otp.ftl` — but there is **no** full `mfe-shell` theme tree there. The realm's `loginTheme: "mfe-shell"` therefore most likely silently falls back to the default unless that theme is provisioned elsewhere.

If you want to add real dynamic theme switching here:

1. **Quick (config-only, ~1 h):** Create 2–3 themes as JARs and edit the per-client `login_theme` on `mfe-shell-client` (or set it dynamically through admin API). Still static; not "dynamic" in the strict sense.

2. **Properly (`ThemeSelectorProvider`, ~1–2 days):** Add a third SPI to `keycloak-provider/` (it already ships the User Storage SPI and the Email-OTP authenticator — same pattern):
   - `META-INF/services/org.keycloak.theme.ThemeSelectorProviderFactory`
   - An implementation class that reads something from request context (subdomain, or a test query param), resolves it against `user-service` (the REST infrastructure already exists) or a static map, and returns a theme name.
   - Builds through the existing `shadowJar` pipeline.
   - Hot test: `podman compose build keycloak && podman compose up -d --force-recreate keycloak`.

3. **For demo purposes (light/dark toggle):** Add a single `mfe-shell` theme under `theme-resources/` (structure `theme-resources/themes/mfe-shell/login/`) with CSS variables + a `<script>` toggle. No new SPI needed — purely a theme deliverable.

Specific caveats that will bite in this repo:
- `kc.sh build` runs in `Dockerfile.keycloak` stage 2 — a new theme needs a full `podman compose build keycloak`, not just a restart (matches the table in `CLAUDE.md`).
- `realm-export.json` is imported with `IGNORE_EXISTING` (see `CLAUDE.md`) → for live realm configs you need an admin API patch or `down -v`.
- Theme cache in prod mode: when debugging, use `start-dev` or the `--spi-theme-cache-*=false` flags.
- CSP in Keycloak 26: the demo OTP template (`email-otp.ftl`) will struggle if you add inline JS. Whitelist explicitly.

---

## 9. Canonical references

- Keycloak Server Developer Guide → Themes chapter (latest)
- Keycloak SPI Javadoc: `org.keycloak.theme.ThemeSelectorProvider`
- Keycloak 26 Migration Guide (for v1 → v2 changes)
- Keycloakify docs — https://www.keycloakify.dev — de facto industry standard for new custom themes
- PatternFly 5 design tokens (for compatibility with the `keycloak.v2` base)
- OWASP ASVS V14 (Configuration) — for CSP best practices that also apply to login pages
