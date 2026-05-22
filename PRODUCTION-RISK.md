# Production risk analysis — Keycloak white-labeling POC

Captures the answer to "what's the probability this approach breaks production?" — recorded on 2026-05-22 after the POC reached feature-complete state on both sides (Keycloak SPI on `petarnenov/geowealth-whitelabel-poc` in keycloak-demo, branding servlet on `team/petarnenov/keycloak-whitelabel-poc` in geowealth).

The exec summary: **very low immediate risk** of the in-flight commits breaking production, but the pattern has several operational gaps that must be addressed before it crosses the merge gate. Sections below break down both halves.

---

## TL;DR

| | Break probability if shipped as-is |
|---|---|
| keycloak-demo changes reach prod by accident | 0% — this is a demo repo, prod doesn't deploy from here |
| geowealth servlet merges to master **without** `POC_BRANDING_API_TOKEN` env var | <1% — filter is fail-closed, every `/branding-api/*` request returns 401 |
| geowealth servlet merges to master **with** `POC_BRANDING_API_TOKEN` env var | <1% to break, low/medium for unintended brand-data exposure |
| DB seed (`WHITELABEL_TBL` INSERTs) reaches prod | 0% — done by hand in dev container, not packaged as a migration |

The asymmetric exposure is intentional: the POC could in theory be merged without breaking anything (the filter rejects everything by default), but it would be a security/operability mistake to ship it without first closing the gaps in §"Production-readiness gaps" below.

---

## What CAN reach production from this session

| Change | Branch / path | Path to prod |
|---|---|---|
| `geowealth-keycloak/` Keycloak SPI (LoginFormsProvider, BrandingService, BrandRegistry, Brand, BrandCss, BrandingApiClient, FreeMarker templates) | `petarnenov/geowealth-whitelabel-poc` in **keycloak-demo** | None — keycloak-demo is a public-shareable demo repo, prod Keycloak doesn't pull from it |
| `BrandingApiServlet` (asset streaming + contact fields + assets map) | `team/petarnenov/keycloak-whitelabel-poc` in **geowealth** | Merge to master → next regular deploy |
| `BrandingApiAuthFilter` and `web.xml` filter mapping | Same | Same |
| `WHITELABEL_TBL` INSERTs for `cca` and `changepath` | Ran by hand via `sqlplus` against the local dev Oracle container | None — not a Flyway / Liquibase migration file, so no auto-apply on any other DB |
| `setenv.sh` token export | `~/tools/tomcat9/bin/setenv.sh` on the dev machine | None — local-only file |
| `docker-compose.yml` `POC_BRANDING_API_URL` env substitution | `petarnenov/geowealth-whitelabel-poc` in keycloak-demo | None — demo repo |

Only the geowealth-side servlet commit is on a path that could plausibly reach prod, and only by deliberate merge — there's no auto-deploy on the POC branch.

---

## Scenarios if the geowealth branch is merged to master

### Scenario A — `POC_BRANDING_API_TOKEN` is NOT set in prod env

What activates: a new servlet under `/branding-api/keycloak/whitelabel/*`, mapped before the Struts dispatcher. `BrandingApiAuthFilter.init()` logs one INFO line at Tomcat startup:

```
Branding API auth filter initialized (token length=N)
```

— except `N=0` when the env var is unset, in which case the filter actually logs:

```
POC_BRANDING_API_TOKEN env var not set; branding API will reject all requests
```

Every subsequent `/branding-api/*` request returns 401 with `{"error":"unauthorized"}`. No existing endpoint is touched. No DB writes happen. No login flow changes.

**Break probability: <1%.** The remaining sliver is an unrelated servlet-container regression triggered by adding the new filter/servlet — basically negligible given they follow the same pattern as `CheckPlatformStatusServlet` and `ResourceCenterServlet` already in production.

### Scenario B — `POC_BRANDING_API_TOKEN` IS set in prod env

Same activation as A, but the API now accepts requests with a matching bearer token. Anyone who knows both the URL pattern and the token can:

- `GET /branding-api/keycloak/whitelabel/{code}` — read brand metadata (firm code, name, colors, support email, phone, website)
- `GET /branding-api/keycloak/whitelabel/lookup?host={host}` — resolve a hostname to a firm code (calls existing `AuthorizationManager.identifyFirmByUrl`)
- `GET /branding-api/keycloak/whitelabel/{code}/asset/{kind}` — stream the `LOGIN_LOGO_BIG` or `FAVICON` BLOB

What's exposed:
- Brand metadata is largely public (colors, display name) — same data the firm's marketing site already shows
- Contact fields (`firmSupportEmail`, `firmPhone`, `firmWebsite`) — typically public, but worth confirming per firm
- Logos / favicons — already public assets

**Break probability: <1%.** No existing endpoint is rerouted; no existing flow is touched. The legitimate concern is the **leak surface**: a stolen token gives an attacker full read-only enumeration of branding-relevant data. Low-impact alone, useful for OSINT in combination with other intel.

### Scenario C — branch merges with the dev DB seed accidentally included

This isn't actually possible without active intent. The INSERTs we ran today were one-off SQL executed against the dev Oracle container via `sqlplus`. They are not a tracked DB migration (no Flyway/Liquibase `V*.sql` file was added). For the same INSERTs to land in production, someone would have to copy them into a migration file and ship it. The risk of that happening unnoticed is essentially zero, and even if it did, it would simply add two more whitelabel rows (`cca`, `changepath`) — bad-data pollution rather than an outage.

---

## Things this approach CAN'T break

Sanity-check list of categories the POC does not touch:

- **No existing Struts actions** are modified, rerouted, or wrapped. The new servlet lives at a fresh URL prefix.
- **No existing endpoints** (HTTP, RPC, internal) have their behavior changed.
- **No DB schema changes.** No `ALTER TABLE`, no new columns, no index changes. Only writes are 2 hand-typed INSERTs in dev; nothing in any migration directory.
- **No Hibernate mapping changes.** `WhitelabelDAO`, `Whitelabel` model class — both untouched.
- **No login flow changes for non-POC realms.** The Keycloak SPI gates everything on `realm.getName().equals("geowealth-realm")`; any other realm goes through the unmodified upstream `FreeMarkerLoginFormsProvider`.
- **No Keycloak admin / API behavior change.** Admin endpoints, token issuance, OIDC flows — all unchanged. Only the rendered login HTML changes, and only for the geowealth realm.
- **No upstream-dependency upgrades.** No version bump of Keycloak, Tomcat, Oracle JDBC, Struts, Hibernate, Akka, or React. Only new code in the modules already in the build.
- **No Tomcat configuration changes** other than the new servlet/filter mapping (which is itself opt-in via web.xml entries).
- **No new outbound network calls** from Keycloak unless `POC_BRANDING_API_URL` env var is set. Default (unset) → fall through to localhost:8080, which would 401 against the new filter if also deployed → same fail-open path as if the API was down.

---

## Production-readiness gaps in the pattern

These would have to be closed before a serious prod ship. Listed in roughly descending priority.

| # | Gap | Why it matters | Mitigation direction |
|---|---|---|---|
| 1 | **Static bearer token, no rotation** | A leaked token grants permanent access; rotating today requires a Tomcat restart since the filter reads env on init | Move to secret manager (Vault / Doppler / equivalent), scheduled rotation, dynamic re-read; or switch to mTLS / signed JWT |
| 2 | **No rate limiting on `/asset/{kind}`** | BLOB streaming under heavy load could saturate Tomcat I/O | Reverse-proxy rate limit by IP/token, or Servlet filter with token bucket |
| 3 | **No BLOB size cap** | A 50 MB logo in `WHITELABEL_TBL` becomes a 50 MB response; multiple concurrent fetches → OOM | Read `LENGTH(blob)` first, refuse > N MB at the SQL edge; alternatively cap the read in the servlet stream and 413 over the cap |
| 4 | **One INFO log per login render** | High-traffic Keycloak can blow log volume budget on `Branding: host=X code=Y ...` lines | Drop to DEBUG by default; or structured-log with sampling; or alert thresholds rather than full event log |
| 5 | **No cache invalidation hook** | Brand data changes in DB → up to 60 s lag in the Keycloak login | Webhook from geowealth → Keycloak `/branding-cache/invalidate?code=...`; or shorter TTL where stale data is unacceptable |
| 6 | **Realm gate is string-compare on `"geowealth-realm"`** | A future realm rename / re-keying silently disables the SPI | Use realm attribute (`brandingProvider=geowealth`) instead of name equality |
| 7 | **No audit log on successful calls** | Can't answer "who fetched which brand when?" for compliance / forensics | Structured access log on each `/branding-api/*` 2xx, persisted to the existing audit pipeline |
| 8 | **`BrandRegistry` ships hardcoded placeholders into the JAR** | Production Keycloak shouldn't ever serve a hardcoded `LOGO_CHANGEPATH` SVG — the registry exists so the demo works without GW, but in prod the only legitimate fallback is "render the default firm if API is down" | Strip `BrandRegistry` to the GW default firm only, or remove entirely and accept slightly uglier degraded UX |
| 9 | **No browser CSP tightening** | Inline `<style>` and inline data: image URIs currently work because Keycloak's default CSP doesn't restrict `style-src` / `img-src` | Tighten via realm `browserSecurityHeaders` once the prod reverse proxy is in place; document `'unsafe-inline'` as the irreducible inline-style requirement |
| 10 | **Token comparison is already constant-time** (`MessageDigest.isEqual`) ✓ | — | No action; already correct |

Items 1–3 are blockers; 4–6 are strongly recommended; 7–9 are polish.

§10 of `geowealth-keycloak-poc-migration-plan.md` and §"What the geowealth-side audit must add" in `FAILURE-MODES.md` track the same set of concerns from a different angle. This file deliberately overlaps so an operator can find the answer regardless of which doc they open first.

---

## Rollback estimates

### keycloak-demo
- N/A in production. `git revert` the relevant commits locally if the demo needs to roll back to a prior visual state.

### geowealth side, after a hypothetical prod deploy

| What | Action | Time |
|---|---|---|
| Disable the `/branding-api/*` endpoint cluster-wide | `git revert ca55daceff6` (+ prior auth filter / web.xml commits if any), rebuild + deploy | ~5–15 min depending on deploy pipeline |
| Disable just the endpoint without revert | Unset `POC_BRANDING_API_TOKEN` env var → restart Tomcat. Filter then 401s every request, effectively offline | ~2–5 min |
| Remove the seed data accidentally written to prod | `DELETE FROM WHITELABEL_TBL WHERE CODE IN ('cca','changepath')` — but only if those codes weren't already in use prod-side first; otherwise targeted UPDATE to restore the original values | Operator-judgement minutes |

The "unset env var" rollback is cleanest for short-term incident response — no redeploy, immediate effect.

---

## Recommendation

1. Keep both branches isolated from `master` / `main` until production-readiness gaps #1–#3 are closed (token rotation, rate limit, BLOB size cap).
2. Run a focused security review against this list before any merge — the failure-mode walkthrough in `FAILURE-MODES.md` and the security-check audit in `SECURITY-CHECKS.md` are starting templates.
3. Production `WHITELABEL_TBL` data should be populated through the existing admin flow, not via a copy of this session's INSERT script. The script is dev-only by construction.
4. The production token must come from a secret manager, not from `setenv.sh`. The `setenv.sh` line we added today exists only to make the local Tomcat match the Keycloak SPI's bearer token; in prod it should never appear.

With those controls in place, the pattern is functionally correct and pre-tested against four §9.1 failure modes plus one observed real-BE behavior (§5 in `FAILURE-MODES.md`). Without them, the pattern is a working POC but not an operational system.
