# Keycloak white-labeling POC — hand-off

One-page synthesis of the proof-of-concept for stakeholders deciding whether the pattern goes to production. Pairs with [`PRODUCTION-RISK.md`](PRODUCTION-RISK.md) (break-probability analysis), [`PRODUCTION-HARDENING.md`](PRODUCTION-HARDENING.md) (gap-by-gap close-out), and [`TEST-SCENARIOS.md`](TEST-SCENARIOS.md) (full test matrix).

## What we set out to do

Render the Keycloak login page with **per-firm branding** — colors, logo, display name, favicon, support contact — driven by the **GeoWealth whitelabel database**. Single Keycloak realm serves all firms; whitelabel selection happens at login time based on the request's `Host` header. No per-firm realms, no static themes per firm, no operational change to how branding is administered (FE admin UI → `WHITELABEL_TBL` is still the source of truth).

## What worked

| Capability | Implementation | Validation |
|---|---|---|
| **Per-firm colors** (gradient, button, link) | `BrandingApiServlet` emits `cssVariables`; Keycloak SPI renders `:root { --... }` inline | 7 firms seeded, all visible side-by-side |
| **Per-firm display name** (header + tab title) | `${brand.displayName}` in FreeMarker template | Browser + JUnit assertions |
| **Per-firm logo + favicon** | Contract: `assets.{loginLogo,favicon}.url` references; SPI fetches binary, base64-inlines as data URI | Asset endpoint streams BLOBs from `WHITELABEL_TBL` |
| **Per-firm support email / phone / website** | Scalar fields on brand JSON; rendered as link strip below the login card | Regex-validated on SPI side as defense-in-depth |
| **Fail-open under GeoWealth outage** | Cache → API → registry-fallback chain in `BrandingService`; never blocks login | 5 failure-mode scenarios in `FAILURE-MODES.md`, all <5 s |
| **Fallback banner** | Amber "Branding fallback active" pill when SPI is serving from registry | Visible in screencaps + JUnit E2E |
| **Full URL→whitelabel resolution** | 1:1 with the existing Struts logic (`AuthorizationManager.identifyFirmByUrl`) — firm SYSTEM_BASE_URL → CLIENT_PORTAL_BASE_URL → advisor URLs → topSubDomain match → GeoWealth default | 43-test JUnit suite, full matrix passing |
| **Test infrastructure** | JUnit 5 integration tests (`gradle test`) + shell smoke (`test-whitelabel-scenarios.sh`) | 43/43 passing against the running stack |

## What didn't (and what we learned)

1. **Hibernate UUID columns silently brick on non-hex characters.** `WHITELABEL_TBL.WHITELABEL_ID` and `ENTITY_TBL.ENTITY_ID` are parsed via `IDConverter`; a single `V` or `W` in a seeded row throws `NumberFormatException` deep in the actor query and surfaces only as `ServiceException: null` — bricking pass 3/4 for the entire actor. Cost us about an hour to track down. Documented in `TEST-SCENARIOS.md` as the "Critical gotcha — ENTITY_ID must be 32 hex chars" section.

2. **`AuthorizationManager` Akka actor swallowed inner exceptions.** Mailer's `waitAndTakeResult` catch was `printStackTrace`-to-stderr only. The fix (route inner cause through log4j) is now in the geowealth tree; saved us when the second similar issue showed up.

3. **POC inline data URI shortcut got us moving but had to be undone for contract conformance.** First iteration of the logo/favicon path emitted base64 bytes directly in the brand JSON (single HTTP call). The OpenAPI contract pins `assets.loginLogo.url` → separate fetch. We switched once the architecture stabilized; the contract-aligned version costs one extra HTTP per cold-cache login.

4. **Resolution semantics are firm-centric, not whitelabel-centric.** A single firm can have multiple `WHITELABEL_TBL` rows, but `identifyFirmByUrl` returns one keyword per request based on **firm** matching (not WL row matching). Multi-whitelabel-per-firm requires either a sub-firm or an advisor binding. The POC code does not introduce a new resolution semantic — it stays 1:1 with the existing Struts logic.

5. **Manual `/etc/hosts` setup matters for browser testing.** `*.geowealth.com` resolves to the real production IP unless overridden. Test recipes need to spell this out (`TEST-SCENARIOS.md` § "Manual browser test" does).

## Production gap

Of the 9 production-readiness items called out in `PRODUCTION-RISK.md`, **7 closed in code** during this POC, **2 deferred** to architecture decisions outside the SPI scope.

| | State | Owner |
|---|---|---|
| #1 Token rotation | Design only — `PRODUCTION-HARDENING.md § "Token rotation design"` | Security team picks vault (Vault / AWS SM / Doppler); ~half-day each side once picked |
| #2 Rate limit on `/branding-api/*` | ✅ Filter-level via Caffeine, env-tunable | — |
| #3 BLOB size cap | ✅ 2 MB default, env-tunable | — |
| #4 INFO log volume | ✅ DEBUG default, WARN on fallback | — |
| #5 Cache invalidation hook | Partial — programmatic API ready, REST endpoint needs `RealmResourceProvider` extension + admin webhook | Half-day SPI work + 2 h on the admin side |
| #6 Realm gate via attribute | ✅ `geowealthBrandingProvider` realm attribute; legacy name fallback | — |
| #7 Audit log | ✅ Structured INFO line per `/branding-api/*` call | Optional: ship to dedicated audit pipeline |
| #8 Registry placeholders | ✅ Feature-flagged via env var | — |
| #9 CSP tightening | ✅ Explicit `default-src`, `style-src`, `img-src data:`, `frame-ancestors`, etc. in realm export | `'unsafe-inline'` on `style-src` is irreducible while we ship inline `<style>` — document in security review |

Beyond the 9 hardening items, **end-to-end production readiness** also wants:

- **Real brand data seeded** in production `WHITELABEL_TBL` for every active firm — the POC's hardcoded `BrandRegistry` placeholders (which `KC_SPI_LOGIN_FREEMARKER_GEOWEALTH_REGISTRY_PLACEHOLDERS=false` can strip) should not be the prod safety net.
- **CDN-fronted assets**, not Tomcat-streamed BLOBs, for the logo/favicon path under real load.
- **GeoWealth-side audit log** symmetric to the SPI INFO line, so the request side is observable too.
- **CI integration** of `gradle test` + `test-whitelabel-scenarios.sh` against an ephemeral preview environment.
- **Demo recording** (60-90 s screencap of two firms logging in side-by-side, then the fallback path) — pending.

## Where the code lives

| | Branch | Repo |
|---|---|---|
| Keycloak SPI (provider jar, theme, tests, docs) | `petarnenov/geowealth-whitelabel-poc` | GitHub `petarnenov/keycloak-demo` |
| GeoWealth servlet (branding API, auth filter, dev profile) | `team/petarnenov/keycloak-whitelabel-poc` | GitLab `gwm1/geowealth` |
| OpenAPI contract | `contracts/branding-api.openapi.yaml` in the keycloak-demo repo | — |
| Database seed (dev only) | Documented in `TEST-SCENARIOS.md`; manual `INSERT`s for dev Oracle | — |

Both branches isolated from master / main throughout the POC — `PRODUCTION-RISK.md § "Scenarios if the geowealth branch is merged to master"` walks through the merge implications and rollback timing.

## TL;DR for an exec audience

The POC works end-to-end. The architecture is the right one — single realm + per-firm branding fetched at login time, 1:1 with how the rest of the app already picks a firm by URL. The code is ready to merge after a security review of the 7 closed hardening items and an architectural decision on token rotation (#1) plus optional cache invalidation (#5). Cost of production graduation is roughly **one engineer-week** total across both repos plus the vault decision; cost of NOT graduating is shipping a POC token that lives in dotfiles and cache invalidation that lags 60 s. Recommended next step: the security review + vault decision; everything else falls out from there.
