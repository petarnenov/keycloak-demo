# Whitelabel pipeline — still needed after Phase 13/14?

## Your hypothesis

> "При текущата имплементация не виждам нужда от вайлъбълинг
> препредаване, защото ползваме винаги и само логинът на оригиналния
> GeoWealth проект."

Concise restatement: after Phase 13 (`kc_idp_hint=p1` on shell click)
and Phase 14 (Redirect-binding eliminates broker splash), the user
never sees a Keycloak-rendered login screen — every login UI surface
is served by P1 at `:8888`. So the Keycloak-side whitelabel rendering
machinery (themes, branding API, BrandRegistry, theme-selector SPI)
has no audience and can be removed.

**Verdict: half-right.** Correct for `demo-realm` (the MFE shell at
`:5173`). Wrong as a blanket statement because **the repo has a
second realm — `geowealth-realm` — whose entire purpose is the
whitelabel POC**. They share the SPI but consume it for completely
different goals.

## Two realms, two stories

| Aspect | `demo-realm` (MFE shell @ :5173) | `geowealth-realm` (whitelabel POC @ :5174) |
|---|---|---|
| Frontend client | `mfe-shell-client` (OIDC, used by shell + 3 MFEs) | `geowealth-poc-client` (OIDC, single SPA) |
| Identity providers | P1 SAML broker (`alias=p1`) | none — local Keycloak auth |
| Login flow happy path | shell click → `kc_idp_hint=p1` → P1 → SAML resp → shell. **No Keycloak login UI ever shown.** | direct → Keycloak login form. **Keycloak login UI IS the demo.** |
| `loginTheme` | `mfe-shell` (static, P1 pixel-parity) | `geowealth-wl` (dynamic, brand-API-driven) |
| `geowealthBrandingProvider` attr | `true` | `true` |
| What it demonstrates | end-to-end SSO federation with P1 | per-tenant Keycloak login styling driven by host header → brand registry → CSS vars + logo + favicon + support contact |

The two realms intentionally show two unrelated capabilities. The
whitelabel pipeline is the *only* thing `geowealth-realm` does.

## What the whitelabel pipeline actually is

The `geowealth-keycloak/` provider jar ships three coupled SPIs +
one parametric theme:

| Component | Role | demo-realm consumes? | geowealth-realm consumes? |
|---|---|---|---|
| `GeoWealthThemeSelectorProvider` | Forces `geowealth-wl` theme on `LOGIN` requests for `geowealth-realm`; pass-through for everything else | **No** (pass-through returns `mfe-shell` from realm config) | **Yes** (hardcoded realm-name match swaps in `geowealth-wl`) |
| `GeoWealthLoginFormsProvider` | LoginFormsProvider override; on every login render, resolves host → brand and injects `brand` into FreeMarker scope. Opt-in via realm attr `geowealthBrandingProvider=true` | **Yes** — `mfe-shell` template *does* consume `${brand.displayName}`, `${brand.faviconDataUri}`, etc. (42 refs in `template.ftl`). The `<#if brand??>` guards keep it functional even with no brand. | **Yes** — `geowealth-wl` template consumes brand vars (37 refs in template + 3 in CSS) for full visual swap (CSS vars, logo, favicon, support email, phone, website) |
| `BrandingApiClient` + `BrandingService` + `BrandingCache` + `BrandRegistry` + `Brand` + `BrandDto` + `BrandCss` | Calls P1's `/branding-api/keycloak/whitelabel/{code}` + `/lookup?host=…`; caches 60s; falls back to in-memory `BrandRegistry` defaults (`cca`, `changepath`) on API failure | **Yes** (via LoginFormsProvider) — but only when Keycloak actually renders a login. After Phase 13/14 that's edge-case-only. | **Yes** — primary path; every login page render does a lookup. |
| `theme/geowealth-wl/` (template + CSS + messages) | The parametric theme: one set of files, many tenant looks via injected `brand.cssVariables` | **No** — `mfe-shell` is selected instead | **Yes** — the demo |

So when you say "we always use P1's GeoWealth login", you're describing
**only `demo-realm`**, and even there the GeoWealthLoginFormsProvider
+ branding API are still wired (just not visible in the happy path).

## Where Keycloak login *could* still render for `demo-realm`

After Phase 13/14, the happy-path SSO bypasses Keycloak's login UI.
But the form-rendering surface isn't completely gone — it surfaces
on:

| Trigger | Visible? |
|---|---|
| Shell click → `kc_idp_hint=p1` → P1 returns valid Response | No |
| Direct nav to `/realms/demo-realm/protocol/openid-connect/auth` *without* `kc_idp_hint` | **Yes** — local username/password form rendered |
| P1 SAML response signature invalid / cert mismatch / expired AuthnRequest | **Yes** — Keycloak error page |
| `/realms/demo-realm/account/` (account console) | **Yes** — account UI (uses `loginTheme` if no `accountTheme` set) |
| Logout confirmation (`/realms/demo-realm/protocol/openid-connect/logout` without `id_token_hint`) | **Yes** — "Do you want to log out?" page (you saw this 30 min ago) |
| First-broker-login flow exceptions if user-attribute mappers ever require manual review | **Yes** — currently disabled, but plausible regression |
| Session expired mid-flow + token refresh fails | **Yes** — re-auth prompt |
| Admin emergency access (operator hitting Keycloak directly when P1 is down) | **Yes** — needed for ops |

The `mfe-shell` theme is the safety net for these. Brand injection
adds title, favicon, and `brand.displayName` to those edge-case
pages. Low value if you never see them; non-zero if you do.

## What it would cost to remove the pipeline entirely

**Scope 1 — drop only the dynamic branding from `demo-realm` (keep static theme)**

Cheapest. Flip `demo-realm.attributes.geowealthBrandingProvider = false`
in `realm-export.json` + admin API. `GeoWealthLoginFormsProvider`
short-circuits the host-lookup + brand fetch (early return), the
`<#if brand??>` guards in `mfe-shell/template.ftl` fall through to
the no-brand defaults. Result: edge-case Keycloak pages still
P1-styled (via the static `mfe-shell` CSS) but no per-tenant
favicon / title / displayName injection. **Net: ~0% UX loss for
demo-realm, 100% less work per login render.**

| Touched | Change |
|---|---|
| `keycloak/realm-export.json` | `attributes.geowealthBrandingProvider = "false"` |
| live `demo-realm` (admin API) | same |

`geowealth-realm` keeps the pipeline; nothing breaks there.

**Scope 2 — remove `geowealth-realm` + the entire whitelabel POC**

Aggressive. The whitelabel demonstration goes away. Files to
remove:

- `geowealth-keycloak/` (entire SPI module)
- `keycloak/geowealth-realm-export.json`
- `contracts/branding-api.openapi.yaml`
- `frontend/apps/geowealth-poc/` (POC app)
- Docker compose entries for `geowealth-poc` (port `:5174`)
- `test-whitelabel-scenarios.sh`
- `keycloak/themes/mfe-shell/` would lose its `<#if brand??>` guards (since `brand` will never be defined), but those guards already handle that branch — no breakage.
- `Dockerfile.keycloak` stage 1 loses the geowealth-keycloak jar build step
- Multiple docs (`geowealth-keycloak-poc-migration-plan.md`, `geowealth-keycloak-whitelabel-sync.md`, `keycloak-dynamic-login-theming.md`, plus mentions in `WIP-SSO.md` / `CLAUDE.md`)

This is a 4-figure line-count delete. Cleanest end-state, but only
sensible if the *product decision* has changed and whitelabel is no
longer part of the POC's scope.

**Scope 3 — keep everything (status quo)**

Cost is one extra REST call per Keycloak login render against P1's
`/branding-api/*`, cached 60s per host. After Phase 13/14 the
`demo-realm` happy path doesn't render any Keycloak page, so that
call effectively never fires in production traffic — only on the
edge cases listed above.

## Recommendation

**Scope 1 (drop the branding attr from `demo-realm`, keep
`geowealth-realm` intact).**

Reasoning:

1. Your observation is correct *for `demo-realm`*: the dynamic
   branding pipeline never adds visible value to the happy path
   anymore, because the happy path never renders a Keycloak login.
   So the work it does for `demo-realm` is wasted.
2. `geowealth-realm` is a separate, intentional demonstration of a
   completely different capability — per-tenant white-labeling of
   the Keycloak login. Removing it would mean removing the demo,
   not just removing dead code.
3. The `mfe-shell` static theme (the P1-pixel-parity assets) is
   independent of the dynamic branding pipeline. It will still
   render correctly for `demo-realm`'s edge cases (errors, account
   console, logout confirm) even with the branding attr off.
4. If you decide later that whitelabel POC is also out of scope,
   Scope 2 is a clean follow-up. Phase 13/14 didn't make Scope 2
   any *easier* or *harder*, so deferring it is free.

**Concrete change for Scope 1** (one-liner in `realm-export.json` +
one admin API PUT):

```json
// demo-realm attributes
"attributes": {
-  "geowealthBrandingProvider": "true"
+  "geowealthBrandingProvider": "false"
}
```

```bash
# Live realm
curl -X PUT "http://localhost:8898/admin/realms/demo-realm" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"attributes": {"geowealthBrandingProvider": "false"}}'
```

## TL;DR

| Question | Answer |
|---|---|
| Is the whitelabel rendering still needed for the shell login flow? | **No** — Phase 13/14 made it invisible in the happy path. |
| Can the whole whitelabel pipeline be deleted? | **Not without deleting `geowealth-realm`** — that realm is the whitelabel POC. |
| What can be safely turned off today? | `demo-realm.geowealthBrandingProvider` → `false`. Edge-case Keycloak pages still render correctly via the static `mfe-shell` theme. |
| Is anything urgent? | **No.** The pipeline doesn't fire in production traffic after Phase 13/14 — it's idle work, not broken work. |
