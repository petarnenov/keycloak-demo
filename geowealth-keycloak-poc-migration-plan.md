# GeoWealth ↔ Keycloak white-labeling — POC migration & sync plan

A concrete, phased plan to deliver a proof-of-concept that demonstrates dynamic, per-firm Keycloak login branding driven by GeoWealth's existing white-labeling data. The architectural decisions are settled (see source documents); this plan turns them into work.

**Date:** 2026-05-22
**Source documents:**
- [`geowealth-keycloak-whitelabel-sync.md`](./geowealth-keycloak-whitelabel-sync.md) — target architecture
- [`keycloak-dynamic-login-theming.md`](./keycloak-dynamic-login-theming.md) — generic Keycloak theming reference
- [`p1-sso-architecture.md`](./p1-sso-architecture.md) — full SSO bridge (related, parallel track)
- [`p1-sso-integration-research.md`](./p1-sso-integration-research.md) — SSO solution selection
- [`CLAUDE.md`](./CLAUDE.md) — keycloak-demo survival notes

---

## 1. POC objectives & decisions on file

### 1.1 Goal

Demonstrate that the Keycloak login page can render with firm-specific branding (logo, colors, support contact, terms link) pulled live from GeoWealth, on a per-request basis, with sane caching and fail-open semantics. Scope is intentionally narrow: prove the **sync loop**, not the full SSO federation (the SSO work is tracked separately in `p1-sso-architecture.md`).

### 1.2 Decisions confirmed (top of session)

| # | Decision | Value |
|---|---|---|
| 1 | Firms in POC | **ChangePath + GeoWealth default** (2 brands) |
| 2 | Keycloak realm | **New `geowealth-realm`** (isolated from existing `demo-realm`) |
| 3 | Login entry point | **New minimal demo app** in keycloak-demo (`apps/geowealth-poc/`) |
| 4 | Tenant resolver | **Hostname-based** (subdomain, `/etc/hosts` entries) |

### 1.3 Decisions inherited from architecture doc

- One **parametric theme** `geowealth-wl`, not N theme JARs.
- **Branding source of truth = GeoWealth** (Oracle `WHITELABEL_TBL` + filesystem `etc/whitelabel/<code>/`). Keycloak never duplicates.
- **Pull, not push.** Caffeine cache in Keycloak, 60 s TTL.
- **Fail-open.** GeoWealth branding API unreachable → cached / default brand; login never blocks.
- **No JDBC into Oracle from Keycloak.** Always HTTP.

### 1.4 Topology for the POC

```
   Host (macOS)                                Docker network (keycloak-demo)
  ┌─────────────────────┐                     ┌──────────────────────────────────┐
  │ Browser              │                     │                                  │
  │  ↓                   │                     │  ┌────────────────────────────┐  │
  │ changepath.localhost │ ──────8888───────►  │  │ Keycloak (start-dev)        │  │
  │ default.localhost    │                     │  │  realm: geowealth-realm     │  │
  │ (5174)               │                     │  │  theme: geowealth-wl        │  │
  │  ↓                   │                     │  │  + ThemeSelectorProvider    │  │
  │ POC demo app         │                     │  │  + BrandingApiClient        │  │
  │ Vite, :5174           │                     │  └──────────┬─────────────────┘  │
  │                      │                     │             │                    │
  └──────┬───────────────┘                     │             │ host.docker.       │
         │                                     │             │ internal:8080      │
         │ unchanged                            │             │                    │
         │ 8080 (Tomcat)                       │             │                    │
         ▼                                     │             │                    │
  ┌─────────────────────┐                      │             │                    │
  │ GeoWealth Tomcat     │◄─────────────────────┘             │                    │
  │  branding-api/...    │                                    │                    │
  │  (new endpoints)     │                                    │                    │
  └──────────────────────┘                                    │                    │
                                                              │                    │
                                                              └────────────────────┘

  Existing keycloak-demo services (untouched by this POC):
  shell:5173, mfe-client/ops/admin:5181/5182/5183, bff-client/ops/admin:8081/8082/8083, user-service:8090
```

**Host boundaries that matter:**
- **Browser → Keycloak:** `http://changepath.localhost:8888` or `http://default.localhost:8888`. Browser sends the Host header; Keycloak's `ThemeSelectorProvider` reads it.
- **Browser → POC app:** `http://changepath.localhost:5174` or `http://default.localhost:5174`. The POC app derives the firm code from its own Host and uses it when constructing the OIDC `auth` URL.
- **Keycloak → GeoWealth:** `http://host.docker.internal:8080/branding-api/keycloak/...` (Docker Desktop / Podman macOS). Linux fallback: `--add-host=host.docker.internal:host-gateway` in compose. Podman: `host.containers.internal` (per `dev-bff.sh` precedent in this repo).
- **GeoWealth → Keycloak:** **none in this POC.** Branding sync is one-way pull. Full SSO (separate track) would need this.

### 1.5 Explicit non-goals for the POC

- ❌ Federating user identity from GeoWealth into Keycloak (that's the `p1-sso-architecture.md` track). The POC uses the existing keycloak-demo demo users.
- ❌ Email theme (Phase 3 of the architecture). Only the login theme is in scope.
- ❌ Invalidation webhook (Phase 2). 60 s TTL is acceptable for the POC.
- ❌ CDN-fronted assets. BLOBs streamed straight from GeoWealth.
- ❌ Per-advisor branding overrides (firm-level only).
- ❌ Production-grade service-token rotation / mTLS. Static bearer token via env var is fine for POC; the rotation hooks land in Phase 2.
- ❌ Touching existing `demo-realm`, MFE shell, or BFFs — they keep working unmodified.

---

## 2. Branching strategy

Two branches, one per repo. Both off the agreed parents.

| Repo | Branch | Parent | Purpose |
|---|---|---|---|
| `keycloak-demo` | `petarnenov/geowealth-whitelabel-poc` | `petarnenov/onboarding-mfe-monorepo` (current) | Theme, ThemeSelectorProvider, branding client, POC demo app, new realm export |
| `geowealth` | `petarnenov/keycloak-whitelabel-poc` | `master` | Branding API endpoint, service-token plumbing, code-analysis-driven decisions |

**Conventions:**
- One commit per logical step in the phase plan below; matches existing commit style (plain prose, no emoji, `Co-Authored-By: Claude` trailer).
- Each commit must leave the POC in a runnable state — no half-cut intermediate states.
- All artifacts that land on disk are in English (per `CLAUDE.md`).
- No force-push, no rebase of shared commits.
- PRs gated on the acceptance criteria in §6.

**Cross-repo coordination:**
- Each commit message references the sibling commit if there's a coupling (e.g. "matches geowealth commit abc1234").
- Cross-cutting changes (e.g. JSON schema of the branding response) require committing on both sides before any single side merges.

---

## 3. Phase plan

Five phases. Each is independently shippable: at the end of each phase the POC works end-to-end at that phase's scope, even if subsequent phases aren't done yet.

| Phase | What ships | Duration estimate | Independently demoable? |
|---|---|---|---|
| **0** | Repo prep, branches, scaffolding, environment validation | 0.5 day | No (foundation) |
| **1** | Static branded login for one firm (no API yet — colors hard-coded in theme) | 1 day | Yes — proves the theme JAR + realm wiring |
| **2** | Dynamic branding via GeoWealth API (read-only, TTL cache, hostname resolver) | 2–3 days | Yes — **the main POC milestone** |
| **3** | Add second firm (default GeoWealth), demonstrate switching | 0.5 day | Yes — shows the sync loop end-to-end |
| **4** | Hardening: failure modes, value validation, telemetry, documentation | 1 day | Yes — POC ready to hand off |

Total: **~5–6 working days** on the Keycloak side, **~3–4 working days** on the GeoWealth side (largely parallel from Phase 2 onward).

---

## 4. Phase 0 — Repo prep & environment validation

### 4.1 keycloak-demo side

- [ ] **Create branch:** `git checkout -b petarnenov/geowealth-whitelabel-poc` from `petarnenov/onboarding-mfe-monorepo`.
- [ ] **Add `/etc/hosts` entries** to local dev machine (document in `geowealth-poc/README.md`):
  ```
  127.0.0.1 changepath.localhost
  127.0.0.1 default.localhost
  ```
  Modern macOS resolves `*.localhost` to 127.0.0.1 automatically, but explicit entries avoid surprises and match what other devs will need.
- [ ] **Validate Keycloak host header tolerance.** In dev mode (`start-dev`, already in `docker-compose.yml:56`), Keycloak accepts arbitrary `Host` and uses the browser-visible host for redirects. Confirm with:
  ```bash
  curl -sI -H 'Host: changepath.localhost:8888' http://localhost:8888/realms/master/protocol/openid-connect/auth | head -5
  ```
  Expect 200/302. If 400 (hostname strict), add `KC_HOSTNAME_STRICT: "false"` to the keycloak service env in docker-compose.
- [ ] **Add a new compose service `geowealth-poc`** at port `5174`, mirroring the existing `shell` service pattern (bind-mount `./frontend`, `npm install`, `npm run -w geowealth-poc dev`). Do **not** modify existing services.
- [ ] **Skeleton the new workspace** at `frontend/apps/geowealth-poc/` (Vite + React + keycloak-js, the minimum needed). The package mirrors `apps/shell/` at ~10% the size — one route, one "Sign in" button.

### 4.2 geowealth side

- [ ] **Create branch:** `git checkout -b petarnenov/keycloak-whitelabel-poc` from `master`.
- [ ] **Confirm local Tomcat 8080 access.** `curl http://localhost:8080/` (or wherever GeoWealth root is). Verify the existing branded portal renders for at least the default firm.
- [ ] **Confirm Keycloak container can reach the host.** From the host:
  ```bash
  podman compose -f /Users/petarpetrov/keycloak-demo/docker-compose.yml exec keycloak \
    curl -sI http://host.docker.internal:8080/ | head -3
  ```
  (Linux variant if needed: add `--add-host=host.docker.internal:host-gateway` to the keycloak service.) Expect 200/302/404 from Tomcat (not "could not resolve host").
- [ ] **Identify the right module to add the branding API to.** A code-analysis task — see §7.1.

### 4.3 Cross-cutting

- [ ] **Agree on a service-token value** for POC (e.g., `POC_BRANDING_API_TOKEN=<random-32-bytes-base64>`). Distribute via `.envrc` on both sides (gitignored). Bake into Keycloak as an env var on the keycloak service; bake into GeoWealth as a `geowealth.json` / `geowealth.yaml` override.
- [ ] **Pin the JSON contract** in a single committed file: `keycloak-demo/contracts/branding-api.openapi.yaml`. Both sides regenerate / reference this. Mirrors how the user-service ↔ Keycloak SPI contract works in this repo (`user-api/openapi.yaml`, see `CLAUDE.md`).

**Phase 0 exit criteria:** branches exist; both apps run independently; browser can reach `changepath.localhost:8888` and see (un-branded) Keycloak; Keycloak container can reach `host.docker.internal:8080`.

---

## 5. Phase 1 — Static branded login (no API yet)

Goal: prove the theme + realm wiring with hard-coded values. Eliminates "is it the theme or is it the API?" confusion in later phases.

### 5.1 keycloak-demo side

- [ ] **Create new realm export `keycloak/geowealth-realm-export.json`**:
  - Realm ID `geowealth-realm`, display name `GeoWealth`.
  - One OIDC client `geowealth-poc-client` (public, PKCE-only, redirect URIs `http://*.localhost:5174/*` and `http://localhost:5174/*`).
  - `loginTheme: "geowealth-wl"`.
  - Internationalization enabled, supported locales `en` only for POC.
  - **Do not** include any user federation SPI in this realm yet; create users locally for the POC (e.g. `poc-user` / `123`). The user-store SPI work belongs to the `p1-sso-architecture.md` track.
  - Realm import is `IGNORE_EXISTING` by default (per `CLAUDE.md` warning) — note in commit message.
- [ ] **Update docker-compose**: add `keycloak/geowealth-realm-export.json` to the Keycloak service's volume mount (alongside the existing `realm-export.json`). Keycloak's `--import-realm` will import both on a fresh DB.
- [ ] **Scaffold the theme JAR.** New module structure (sibling of existing `keycloak-provider/`):
  ```
  geowealth-keycloak/
  ├── build.gradle.kts                 (shadowJar pipeline like keycloak-provider/)
  └── src/main/resources/
      └── theme/
          └── geowealth-wl/
              └── login/
                  ├── theme.properties            (parent=keycloak.v2)
                  ├── template.ftl                  (overrides v2; inline <style> block)
                  ├── login.ftl                     (uses brand vars)
                  ├── resources/
                  │   ├── css/geowealth-wl.css      (CSS variables, no hardcoded colors)
                  │   └── img/changepath-logo.svg   (HARDCODED for Phase 1)
                  └── messages/messages_en.properties
  ```
- [ ] **Wire the theme JAR into `Dockerfile.keycloak`** stage 2 (it copies provider JARs to `/opt/keycloak/providers/`). Mirror the existing `keycloak-provider` build step.
- [ ] **Hard-code ChangePath colors** into `template.ftl`'s inline `<style>` block for Phase 1. Pull from the actual ChangePath color theme:
  ```css
  --theme-link-color: #155e8f;
  --theme-gradient-start: #0e4e79;
  --theme-gradient-end: #b5dea4;
  --theme-body-background: #f5f5f5;
  ```
  (These are the real values from `etc/whitelabel/changepath/changepath_color_theme.json` in geowealth.)
- [ ] **Build & smoke test.** `podman compose build keycloak && podman compose up -d --force-recreate keycloak`. Open `http://changepath.localhost:8888/realms/geowealth-realm/protocol/openid-connect/auth?client_id=geowealth-poc-client&response_type=code&redirect_uri=http://changepath.localhost:5174/&scope=openid` and visually verify the ChangePath colors appear.

### 5.2 POC demo app (still keycloak-demo side)

- [ ] **Implement `apps/geowealth-poc/src/App.tsx`** (~30 lines):
  - Reads `window.location.hostname`, parses subdomain → derives firm code (`changepath`, `default`).
  - Displays "You are signing in to ChangePath" (or "GeoWealth").
  - "Sign in" button → triggers keycloak-js `login()` against `http://${hostname}:8888/realms/geowealth-realm`. The Keycloak base URL uses **the same hostname as the browser** so the Host header propagates.
  - On login success → display username + roles, plus a "Logout" button. Nothing else.
- [ ] **Auth provider**: a stripped-down version of `apps/shell/src/auth/AuthProvider.tsx` — same singleton pattern, no MFE federation, no TanStack Query.
- [ ] **Smoke test.** `curl -I http://changepath.localhost:5174/` returns 200 from Vite dev server. Click through full login → return → confirm `iss` on the access token = `http://changepath.localhost:8888/realms/geowealth-realm`.

### 5.3 geowealth side

Nothing in Phase 1.

**Phase 1 exit criteria:** Visiting `http://changepath.localhost:5174/` and clicking "Sign in" shows a ChangePath-colored Keycloak login. Login succeeds. ChangePath logo (hard-coded from JAR) is visible. **No GeoWealth API call yet.**

---

## 6. Phase 2 — Dynamic branding via GeoWealth API (the main POC milestone)

This is the phase that demonstrates the actual sync. Both repos contribute in parallel.

### 6.1 geowealth side (parallel with 6.2)

**Pre-work — code analysis (these decisions follow from §7.1, do not skip):**
- Determine the correct module / package for the new endpoint (see §7.1).
- Determine the existing service-token / API-key pattern in geowealth (if any) and reuse — see §7.2.
- Determine whether the BLOB lookups should go through Hibernate (`WhitelabelDAO`) or JDBC directly — see §7.3.

**Implementation:**
- [ ] **New servlet or Struts action** at path `/branding-api/keycloak/` — exact location depends on §7.1 analysis. Whichever module is chosen, follow that module's existing dependency-injection and logging conventions.
- [ ] **Endpoint 1: `GET /branding-api/keycloak/whitelabel/{code}`**:
  - Auth: bearer token, compared in constant time against the configured POC token.
  - Reads `WHITELABEL_TBL` row by `CODE`. Reads `etc/whitelabel/<code>/<code>_color_theme.json` if present (graceful if missing).
  - Composes the JSON in the shape from `geowealth-keycloak-whitelabel-sync.md` §4.1.
  - **Validates every color value** against `^#[0-9a-fA-F]{3,8}$` before serializing — defense in depth against a poisoned DB row (see security §8 of source doc).
  - Sets `Cache-Control: no-store` (caching is Keycloak's responsibility, not HTTP layer).
- [ ] **Endpoint 2: `GET /branding-api/keycloak/whitelabel/{code}/asset/{kind}`** where `kind ∈ {favicon, logo-login, terms}`:
  - Streams the BLOB from `WHITELABEL_TBL` (favicon / logo) or filesystem (terms PDF).
  - Strong `ETag` based on `LAST_UPDATE_DATE` + BLOB SHA-256.
  - `Cache-Control: public, max-age=86400, immutable` when the URL contains a versioned path segment; `max-age=300` otherwise.
  - Correct `Content-Type` (sniff or read from filename / DB column).
  - Honor `If-None-Match` → 304.
- [ ] **Endpoint 3: `GET /branding-api/keycloak/whitelabel/lookup?host=<host>`**:
  - Calls into the **existing** `AuthorizationManagerTrait.identifyFirmByUrl()` — do not reimplement the matching algorithm. (See `geowealth-keycloak-whitelabel-sync.md` §1, "URL → firm resolution is well-defined".)
  - Returns `{"code": "changepath"}` or `{"code": "default"}`.
- [ ] **Negative-path testing**: unknown firm → 404; invalid token → 401; missing token → 401; malformed host → 400; service unavailable (DB down) → 503, not 500.
- [ ] **Log every request** at INFO with: firm code, decision path, latency. Useful for the cache hit ratio analysis in Phase 4.
- [ ] **Make endpoints reachable from the Keycloak container.** Bind Tomcat to `0.0.0.0:8080` (not just `127.0.0.1:8080`) so `host.docker.internal:8080` resolves. Confirm with the §4.2 curl from Phase 0.

### 6.2 keycloak-demo side (parallel with 6.1)

- [ ] **Replace hard-coded values in `template.ftl`** with FreeMarker variables (`${brand.cssVariables['--theme-link-color']}`, etc.). Logo URL becomes `${brand.assets.loginLogo.url}`. Defensive escaping: use `?html` on every brand string in the template.
- [ ] **Add a new SPI module** under the new `geowealth-keycloak/` jar (or as a second SPI in `keycloak-provider/` — match whatever has lower friction with the existing `shadowJar` setup):
  - `META-INF/services/org.keycloak.theme.ThemeSelectorProviderFactory` registration.
  - `GeoWealthThemeSelectorProvider` implements `ThemeSelectorProvider`. For `Theme.Type.LOGIN`, always returns `"geowealth-wl"`; for other types, returns `null` (delegates).
- [ ] **`BrandingApiClient`** — thin JDK `HttpClient`:
  - Base URL injected via SPI config (`spi-theme-selector-geowealth-base-url=http://host.docker.internal:8080`).
  - Service token injected via SPI config (`spi-theme-selector-geowealth-token`). Read from env var at provider-factory init time.
  - 2 s connect timeout, 3 s request timeout. Aggressive — login latency must not balloon when GeoWealth is slow.
  - Methods: `fetch(code)`, `lookupHost(host)`. Both return `Optional<...>` — never throw to callers.
- [ ] **`BrandingCache`** — Caffeine:
  - Two caches: `host → code` (small, hot) and `code → Brand` (small, ~5 KB × 2 entries in POC).
  - `expireAfterWrite=60s`, `refreshAfterWrite=30s` on the second cache.
  - Async loading via Caffeine's `AsyncLoadingCache` so `getThemeName` is non-blocking.
- [ ] **FreeMarker context injection.** Two viable approaches; pick the simpler:
  - **A. Wrap `LoginFormsProvider`** with a custom factory that, after the underlying provider runs, sets a `brand` attribute on the resulting `Response`. Requires extending Keycloak's default factory.
  - **B. Custom `TemplateMethodModel`** registered globally via theme initialization. Cleaner; preferred if it works for static templates without extra hooks.
  - Decide during implementation; both have working Keycloak examples in the wild.
- [ ] **Tenant resolution order** (mirrors §1.2 of the architecture doc):
  1. `X-Forwarded-Host` → strip port → `lookupHost(host)`.
  2. `Host` → same.
  3. Auth-session note `kc_tenant` (for testability and step-up flows).
  4. Default → `"default"`.
- [ ] **Wire the SPI config** into `docker-compose.yml` Keycloak service env:
  ```yaml
  KC_SPI_THEME_SELECTOR_PROVIDER: geowealth
  KC_SPI_THEME_SELECTOR_GEOWEALTH_BASE_URL: http://host.docker.internal:8080
  KC_SPI_THEME_SELECTOR_GEOWEALTH_TOKEN: ${POC_BRANDING_API_TOKEN}
  ```
- [ ] **CSP**: keep Keycloak's default CSP. If the logo `<img>` URL is on a different origin from Keycloak (e.g., `http://host.docker.internal:8080/...` vs the browser-side `http://changepath.localhost:8888/...`), proxy the asset URL through Keycloak. Either:
  - Use Keycloak's `theme-resources` mechanism (heavy — defeats dynamism), **OR**
  - Add a tiny `RealmResourceProvider` at `/realms/geowealth-realm/branding-assets/{code}/{kind}` that proxies the upstream BLOB. **Recommended** — preserves dev-mode CSP defaults.

### 6.3 Integration test

- [ ] **End-to-end smoke**: Browser → `http://changepath.localhost:5174/` → "Sign in" → Keycloak login renders with values from the live GeoWealth response → log in → return to POC app.
- [ ] **Network proof**: tail GeoWealth Tomcat logs and Keycloak logs concurrently during a fresh login → see the `GET /branding-api/keycloak/whitelabel/changepath` call in GeoWealth, see `cache miss → fetch` in Keycloak.
- [ ] **Cache proof**: second login within 60 s shows no GeoWealth API call (cache hit).
- [ ] **Cross-tenant proof**: kill the browser session, go to `http://default.localhost:5174/`, "Sign in" → see GeoWealth default brand. Then back to `changepath.localhost:5174` → see ChangePath brand again, no FOUC.

**Phase 2 exit criteria:** all four bullets in §6.3 pass. Per-request firm resolution works; cache works; the loop is closed.

---

## 7. GeoWealth-side code-analysis tasks

> **Update (2026-05-22):** All six investigations are complete. Findings, evidence, and per-question decisions live in [`~/geowealth/keycloak-poc-findings.md`](file://~/geowealth/keycloak-poc-findings.md) on the `petarnenov/keycloak-whitelabel-poc` branch. Summary of resolved direction:
>
> | Q | Decision | Source |
> |---|---|---|
> | Q1 | Dedicated Jakarta servlet at `/branding-api/*`, registered in `web.xml` before the Struts filter. Reference impl: `CheckPlatformStatusServlet`. | findings §Q1 |
> | Q2 | Custom `BrandingApiAuthFilter` with static bearer token, constant-time compare. No prior pattern in geowealth. | findings §Q2 |
> | Q3 | `WhitelabelDAO.getWhitelabelGroupByCode(code)` wrapped in `HibernateSessionFactory.beginHibernateTransaction()` block. Pattern from `AuthorizationManagerTrait`. BLOBs eagerly loaded. | findings §Q3 |
> | Q4 | DB colors authoritative (6 columns in `WHITELABEL_TBL`). Filesystem `{code}-colors.json` optional override if present; **the previously-referenced `<code>_color_theme.json` does not exist in the current dev tree** — DB-only is sufficient for the POC. | findings §Q4 |
> | Q5 | Default firm code = `"cca"` (`WebConstants.GEOWEALTH_KEYWORD`, firm_cd=1). `AuthorizationManager.identifyFirmByUrl()` already returns it on unmatched hosts. | findings §Q5 |
> | Q6 | Assume DB BLOBs populated; null-check → 404 for missing assets; Keycloak theme JAR ships default fallback. **Needs live SQL verification — see "Live-environment items" below.** | findings §Q6 |
>
> **Live-environment items the user must run** before Phase 2 implementation lands (cannot be determined from code alone):
> 1. `SELECT CODE, LENGTH(LOGIN_LOGO_BIG), LENGTH(FAVICON), LENGTH(TERMS_OF_USE_DOC) FROM WHITELABEL_TBL WHERE CODE IN ('changepath', 'cca');` — confirm BLOBs populated.
> 2. `SELECT CODE FROM FIRM_TBL WHERE FIRM_CD = 1;` — confirm case of "cca".
> 3. Check whether `etc/whitelabel/changepath/changepath-colors.json` (or similar) exists in the actual dev/prod filesystem — the dev tree we analyzed had none.
>
> The original "things to investigate" subsections below are preserved for archive context, since they describe *why* each decision was framed.

These are the decisions that the user said should be made *after* thorough code analysis, not asked upfront. Each task is a small focused investigation, ideally executed at the very start of Phase 2.

### 7.1 Where to add the branding API endpoint

**Question:** Add it to the main Struts 2 module, or as a sibling servlet?

**Why it matters:** Struts 2 actions inherit the `BasicAction` session-resolving pipeline, which infers the firm from the request URL or session. The branding API is *itself* what some callers will use to determine the firm — circular dependency. Also, GeoWealth's session-based auth is the wrong fit for a service-to-service endpoint.

**To investigate:**
- Is there already a "service / API" module separate from the user-facing Struts pipeline?
- Look for `/api/*` mappings in `WebContent/WEB-INF/web.xml` or `struts.xml` — is anything already pure JSON / non-session?
- Are there examples of `@WebServlet` annotations or filter chains that bypass Struts entirely?

**Decision criterion:** pick the location with the **smallest blast radius** on existing functionality and the **clearest auth boundary** (service token, not session cookie).

**Default if no clean separation exists:** new Jakarta servlet under `WebContent/WEB-INF/` with a dedicated security filter, mapped at `/branding-api/*`, completely bypassing the Struts dispatcher.

### 7.2 Service-token / API-key pattern in geowealth

**Question:** Does geowealth already have a pattern for service-to-service authentication that we should reuse?

**To investigate:**
- Search for `Bearer`, `X-API-Key`, `Authorization` header handling in existing servlets/filters.
- Look at how the integration with docusign / 55IP / iCapital (mentioned in `p1-sso-architecture.md`) authenticates outbound calls — incoming calls might mirror that.
- Check `conf/`, `dev_etc/`, `geowealth.json`, `geowealth.yaml` for any existing token storage.

**Decision criterion:** reuse if a pattern exists and is current; otherwise add a minimal `BrandingApiAuthFilter` that compares the header in constant time and short-circuits anything else (no session creation, no audit-log side effects).

**Production-readiness note:** the POC token is static and bound to `.envrc`. Production must rotate (secret manager). The filter must not log the token value, even partially.

### 7.3 Whitelabel data access: Hibernate (`WhitelabelDAO`) or JDBC?

**Question:** Hit the existing `WhitelabelDAO`, or query Oracle directly?

**To investigate:**
- Is `WhitelabelDAO.read(code)` (or equivalent) callable from a non-Struts context, or does it require an active Hibernate session bound to the Struts request lifecycle?
- What is the cost (in lines of code) of obtaining a `SessionFactory` outside of Struts?
- Does the DAO already lazy-load BLOBs, or fetch them eagerly? (Important for the asset endpoint — we don't want to load all BLOBs to return color metadata.)

**Decision criterion:** **strongly prefer the DAO** if it can be invoked cleanly. Bypassing the DAO duplicates schema knowledge in two places and breaks the production architecture's "single source of truth" principle. Only fall back to JDBC if the DAO is irretrievably coupled to Struts.

**Production-readiness note:** the JDBC path, if needed, must use the same connection pool as the rest of the app — not a parallel one. Two pools double the connection footprint and lead to deadlocks under load.

### 7.4 Filesystem CSS theme JSON: read at runtime or migrate to DB?

**Question:** Some color variables only exist in `etc/whitelabel/<code>/<code>_color_theme.json`, not in `WHITELABEL_TBL`. Do we read the file at API time, or migrate the variables to the DB?

**To investigate:**
- For ChangePath specifically: what is in `changepath_color_theme.json` vs what is in `WHITELABEL_TBL`?
- Is there an existing pattern of files-supplementing-DB-rows that we can mirror?
- Operationally, what is the deployment story for these files in GeoWealth prod (NFS mount? baked into WAR? per-pod local disk?) — that determines whether read-at-API-time is robust.

**Decision criterion:** for POC, **read at API time** (simplest, no schema migration). The file location is `${NETFOLIO_HOME_LOCATION}/whitelabel/<code>/...` already — just read it. Document a migration as a future cleanup. Production may want both, with file as override.

**Production-readiness note:** the API must handle file-missing gracefully (use DB-only colors); must validate file contents (JSON parse error ≠ 500); must not follow symlinks out of the whitelabel directory (path traversal defense).

### 7.5 How does GeoWealth currently identify "default" branding?

**Question:** What firm code should the POC's `default.localhost` map to?

**To investigate:**
- `FIRM_TBL`: which row is the GeoWealth Master firm? (Architecture doc says firm_cd=1 — confirm.)
- `WHITELABEL_TBL.CODE` for that row: is it `"default"`, `"geowealth"`, or something else?
- How does the existing code handle a request that doesn't match any firm? (Per architecture doc §3, fallback is firm_cd=1.)

**Decision criterion:** use whatever string is already in the DB as the "default" code. Don't invent a new identifier. POC config: `default.localhost:5174` → look up firm via `lookup?host=default.localhost` → returns the existing default code → fetch that brand.

### 7.6 Are the BLOBs in `WHITELABEL_TBL` actually populated for ChangePath?

**Question:** Confirm the data we plan to render actually exists for the chosen firm.

**To investigate:**
- `SELECT LENGTH(LOGIN_LOGO_BIG), LENGTH(FAVICON), LINKS_COLOR, GRADIENT_START_COLOR FROM WHITELABEL_TBL WHERE CODE = 'changepath';`
- If any column is null, document the fallback the POC will use (logo from `etc/whitelabel/changepath/imgs/`, colors from the JSON file).

**Decision criterion:** the POC must render *something* for ChangePath even if DB BLOBs are empty. Filesystem fallback is acceptable; serving a blank page is not.

---

## 8. Phase 3 — Add second firm (default GeoWealth)

Brief — all foundation work is done.

- [ ] **/etc/hosts** already has `default.localhost`.
- [ ] **GeoWealth API**: should already serve `"default"` (or whatever the actual master code is, per §7.5) without code changes. Spot-check with `curl -H 'Authorization: Bearer …' http://localhost:8080/branding-api/keycloak/whitelabel/default`.
- [ ] **Keycloak side**: zero changes. The parametric theme + dynamic lookup handles N firms identically.
- [ ] **Demo script** committed to `apps/geowealth-poc/README.md`: open one tab to `http://changepath.localhost:5174`, one to `http://default.localhost:5174`, click through both, take screenshots.

**Phase 3 exit criteria:** Both branded logins render side-by-side. Cache populated for both. Visible color/logo difference.

---

## 9. Phase 4 — Hardening, telemetry, documentation

The POC is functionally complete after Phase 3; Phase 4 makes it *demoable* to someone who didn't build it.

### 9.1 Failure-mode coverage

- [ ] **GeoWealth down**: stop Tomcat → fresh login → expect cached or default brand (not 500). Add a banner to the POC app: "branding fallback active" when this state is detected.
- [ ] **Unknown firm**: hit `unknown.localhost:5174` → expect default brand, no errors.
- [ ] **Malformed brand JSON** (manually break the API response): expect default brand + ERROR log.
- [ ] **Slow API** (add `Thread.sleep(5000)` in the endpoint): expect timeout → stale or default brand, login still loads in <5 s.

### 9.2 Telemetry

- [ ] **Keycloak logs**: every branding decision logged at INFO with: requested host, resolved code, cache state (hit/miss/refresh/fallback), latency.
- [ ] **GeoWealth logs**: every branding-api request logged at INFO with: firm code, source IP, decision, latency.
- [ ] **One-page operational dashboard** (markdown is fine for POC): `apps/geowealth-poc/OPERATIONS.md` listing key log greps, expected latency p99, normal cache hit ratio.

### 9.3 Security checks

- [ ] **CSP**: load both branded logins, open browser devtools → zero CSP violations.
- [ ] **Token leak audit**: grep both repos for the literal POC token value → it must appear only in `.envrc` files (gitignored) and in `docker-compose.yml` via env reference, never inline.
- [ ] **Path traversal**: `curl 'http://localhost:8080/branding-api/keycloak/whitelabel/../../etc/passwd'` → 400 / 404, not file content.
- [ ] **Color injection**: temporarily set a color value in the DB to `red; } body { background: url(evil); }` and confirm the validator rejects it at the API edge (and the Keycloak template would reject it again as defense in depth).

### 9.4 Documentation

- [ ] **POC README** at `apps/geowealth-poc/README.md`: how to start, what to expect, the four `/etc/hosts` entries, screenshots, link back to this plan.
- [ ] **`CLAUDE.md` update** in keycloak-demo: a short paragraph in the "Applying changes" table for the new theme JAR + SPI, mirroring the existing entries.
- [ ] **GeoWealth-side README** (this is up to the geowealth repo's conventions): explain the new endpoints, the auth pattern, the env-var for the token.
- [ ] **Hand-off doc**: one page summarizing what worked, what didn't, and the production gap (Phase 5+).

### 9.5 Demo flow

- [ ] **Recorded short demo**: 60–90 s screen recording showing both firms logging in. Useful for stakeholder review and for the eventual production pitch.

**Phase 4 exit criteria:** any of the failure modes in §9.1 reproduce cleanly; the demo script in `apps/geowealth-poc/README.md` runs end-to-end without surprises by someone other than the author.

---

## 10. Production-readiness gap (what the POC explicitly does NOT do)

A POC by definition cuts scope. This section lists what's deliberately deferred — both as a hand-off doc for production planning and as a warning to anyone tempted to ship the POC as-is.

| Gap | POC choice | Production requirement |
|---|---|---|
| Service-token rotation | Static, gitignored | Secret manager / vault, rotated quarterly, alert on usage |
| Service-to-service auth | Bearer token | mTLS or signed JWTs (depending on infra) |
| GeoWealth → Keycloak invalidation webhook | Not implemented; rely on 60 s TTL | Implemented, with cluster-wide fan-out via Infinispan work cache |
| Asset hosting | Streamed from GeoWealth Tomcat | CDN-fronted (CloudFront / Fastly) with GeoWealth origin |
| OIDC client model | One public client, multiple redirect URIs | Per-application clients, confidential where applicable |
| Realm topology | Single `geowealth-realm` | Probably the same; revisit if regulatory boundaries require split |
| Cluster behavior | Single Keycloak node | Multi-node Keycloak cluster with shared Infinispan work cache |
| Audit log | Console logs only | Structured logs to SIEM, correlation IDs across both apps |
| Color/value injection defense | Regex validator at API + template `?html` | Same + Content Security Policy hardening + automated property fuzz tests |
| Locale support | English only | Per-firm locale picker, `messages_<locale>.properties` per firm if needed |
| Email theme | Out of scope | Phase 3 of the architecture doc |
| Per-advisor branding | Firm-level only | Optional; investigate after user demand |
| Federated identity (P1 SSO) | Out of scope; uses POC realm users | Tracked separately in `p1-sso-architecture.md` |
| Failover when GeoWealth DB is down | DB error → API 503 → Keycloak falls back to cache/default | Same, plus circuit breaker + healthcheck-driven traffic shedding |
| Monitoring | Log greps | Prometheus metrics, alerting on cache-miss ratio, API error rate |
| Load testing | Manual smoke | k6 / Gatling load profile: 200 RPS login starts, 99% cache hit, p99 < 50 ms |

---

## 11. Risk register

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | Keycloak in start-dev mode rejects hostnames it doesn't expect | Low | High (POC blocked) | Phase 0 check explicitly tests this; fallback is `KC_HOSTNAME_STRICT: "false"` |
| R2 | `host.docker.internal` doesn't resolve on Linux | Medium | High | Add `extra_hosts: ["host.docker.internal:host-gateway"]` to the keycloak service; documented in Phase 0 |
| R3 | GeoWealth `WhitelabelDAO` only callable from Struts context | Medium | Medium | §7.3 analysis upfront; JDBC fallback documented |
| R4 | Existing ChangePath BLOBs are empty in current dev DB | Medium | Low | §7.6 confirms data; filesystem fallback covers gap |
| R5 | CSP blocks cross-origin asset loading | Medium | Medium | §6.2 plans a proxy endpoint inside Keycloak, eliminating the cross-origin |
| R6 | Theme cache holds stale brand after admin edit | High | Low (POC) | 60 s TTL; webhook is Phase 2 of the architecture; documented as a known limitation |
| R7 | Issuer URL changes per hostname → tokens hard to validate | Medium | Medium | Document acceptable issuer regex in POC app's token validator; alternative: pin `KC_HOSTNAME_URL` |
| R8 | Color value injection bypasses validator | Low | Medium | Defense in depth: validate at API + escape in template |
| R9 | Branding API leaks PII via misconfigured public exposure | Low | High | Internal endpoint; bind to internal listener; firewall in prod; token check |
| R10 | Existing keycloak-demo realm or shell breaks during POC | Low | High | New realm, new app, new SPI; zero changes to existing services — guaranteed by branch diff review |

---

## 12. Open questions to resolve during implementation

These are non-blocking for starting Phase 0 but should be answered before Phase 2 lands.

1. **Issuer URL stability** — do we let Keycloak issue tokens with `iss` matching the browser hostname (`http://changepath.localhost:8888/...`), or do we pin `KC_HOSTNAME_URL` to a canonical value? Affects token validation in the POC app. Recommendation: pin to `http://localhost:8888` and let the SPI read the original Host from `X-Forwarded-Host` / `Host` for theme selection only.
2. **Webhook invalidation** — defer to Phase 2 of the architecture doc, or include lightweight invalidation in this POC? Recommendation: defer.
3. **Email theme** — out of scope for this POC, confirmed. Tracked for future phase.
4. **GeoWealth admin UI** — does the POC need any change to the admin UI ("Login preview" button)? Recommendation: no — out of scope.

---

## 13. Acceptance criteria for the POC as a whole

A single demo run that satisfies all of:

- ✅ Visiting `http://changepath.localhost:5174/` renders a ChangePath-themed Keycloak login at `changepath.localhost:8888` with the actual ChangePath colors and logo from the live GeoWealth DB.
- ✅ Visiting `http://default.localhost:5174/` renders the default GeoWealth-themed login.
- ✅ The first login per firm per minute triggers exactly one `GET /branding-api/keycloak/whitelabel/...` call in GeoWealth's access log. Subsequent logins in the same minute trigger zero calls.
- ✅ Stopping the GeoWealth Tomcat does not break the login flow — cached or default brand is served instead.
- ✅ Both apps continue to function correctly side-by-side with the existing `keycloak-demo` services (shell, MFEs, BFFs, user-service).
- ✅ Branch diffs show zero changes to existing realms, themes, MFE shell, BFFs, or user-service.
- ✅ All code committed in English, plus one Bulgarian translation if the hand-off doc warrants it (per repo precedent with `p1-sso-*.bg.md`).
- ✅ A 60–90 s screen recording demonstrating the above.

---

## 14. Rollback plan

If at any phase the POC needs to be abandoned or rolled back without affecting the rest of the work:

1. **Both branches are isolated.** Neither merges to `master` / `main` automatically; the existing keycloak-demo branches and the geowealth master are untouched.
2. **No shared resources** — new realm, new theme, new SPI, new endpoint. Drop the branches and the world is unchanged.
3. **Local state cleanup**: `podman compose down -v` in keycloak-demo (wipes the realm import); remove the `/etc/hosts` entries; remove `.envrc` POC overrides.
4. **Document the lessons learned** — even an abandoned POC produces real information about the integration cost.

---

## 15. Implementation start checklist

Before opening a single line of code:

- [ ] Both branches created (§2).
- [ ] `/etc/hosts` entries added on dev machine (§4.1).
- [ ] POC service token generated and shared via `.envrc` on both sides (§4.3).
- [ ] §7.1 — branding-API location decision made.
- [ ] §7.2 — service-token pattern decision made.
- [ ] §7.5 — confirmed which firm code = GeoWealth default.
- [ ] §7.6 — confirmed ChangePath data is present (or filesystem fallback is wired).
- [ ] One Phase 0 hour blocked on the calendar for the macOS / Docker / Keycloak hostname plumbing — this is where most weird failures land, and front-loading it saves rework later.

Once those are green, Phase 1 can start.
