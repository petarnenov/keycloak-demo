# E2E coverage matrix

How the Playwright suite (`tests/*.spec.ts`) maps to the auth-flow scenarios
enumerated in [`../auth-flow-scenarios.md`](../auth-flow-scenarios.md) and to
the document `cross-subdomain-sso-keycloak (1).md` that drove the SSO model
in this repo. Read alongside [`../cross-subdomain-sso-implementation.md`](../cross-subdomain-sso-implementation.md)
and [`../cross-subdomain-sso-multi-username-analysis.md`](../cross-subdomain-sso-multi-username-analysis.md).

The legend: ✅ covered, 🟡 partial / implicit, ❌ gap, ⛔ out-of-scope for E2E
(time-based, infra-level, single-machine deployment quirk, or out of repo).

## Login scenarios (§ 1 of auth-flow-scenarios.md)

| # | Scenario | Status | Spec | Notes |
|---|---|---|---|---|
| L1 | Cold open of a domain SPA | ✅ | `silent-first-flow.cold-start`, `sso-claims.*` | Full P1 → KC → BFF chain |
| L2 | Cold open, KC SSO session already alive | ✅ | `silent-first-flow.warm-start` | Asserts NO P1 round-trip |
| L3 | Cold open of P1 directly (`localhost:8888`) | ⛔ | – | Not part of cross-subdomain SSO; P1's own login UI |
| L4 | P1 sidebar link to a domain | ⛔ | – | Sidebar lives in geowealth repo (not this repo) |
| L5 | Cold P1 visit, P1 session dead but KC alive | 🟡 | `silent-first-flow.warm-start` | Same KC-cookie-alive shape covers it functionally |
| L6 | Cold visit, both P1 and KC sessions dead | ✅ | `silent-first-flow.cold-start` | The default "first-ever" state |
| L7 | First-time login for a brand-new user (no KC fed-id) | ✅ | every spec via `deleteDemoKcUser()` in `beforeAll` | Forces first-broker-login + auto-link-by-email on every run |
| L8 | SPA-to-SPA cross-app navigation | ✅ | `sso-claims.personId-stable-across-subdomains` | The architectural heart of the doc |
| L9 | P1 direct login first, then visit domain | 🟡 | implicit in `silent-first-flow.warm-start` | The warm-start sequence uses an existing KC session, which is what L9 produces too |

## Logout scenarios (§ 2)

| # | Scenario | Status | Spec | Notes |
|---|---|---|---|---|
| O1 | Sign out from a domain SPA | ✅ | `logout.spec` | Asserts back-channel fan-out kills sibling BFFs |
| O2 | Logout from P1 (cascades to all BFFs) | ✅ | `whitelabel-global-logout.spec` | P1's IdP-initiated SLO now runs a server-side RP-initiated logout that ends the KC SSO session and fans OIDC back-channel logout out to the resource BFFs. The spec signs out via `/saml/idp/initiate-slo.do` on a whitelabel host and asserts KC sessions → 0, the resource BFF → 401, and the sibling P1 session torn down. (`logout.spec` still covers the BFF → P1 SLO direction.) |
| O3 | KC idle timeout (server-only) | ⛔ | – | Time-based; can't fast-forward in E2E |
| O4 | P1 session timeout (server-only) | ⛔ | – | Same |
| O5 | Browser restart | ⛔ | – | Cookie-store behaviour, not auth logic |
| O6 | KC admin force-logout (admin API) | ✅ | `external-logout.kc-admin-revoke` | Hits `/admin/realms/.../users/{id}/logout`; BFFs cascade |
| O7 | Server-side P1 admin invalidate | ⛔ | – | Same shape as O6 / O2 at the KC layer |

## Edge cases (§ 3)

| # | Scenario | Status | Notes |
|---|---|---|---|
| E1 | Stale `?code=…` URL after browser restart | ❌ | Possible to add; not currently observed as a real issue |
| E2 | KC reachable but JVM truststore doesn't trust mkcert | ⛔ | Deployment-time; covered by docker-compose certs setup |
| E3 | KC returns `code` but P1 can't resolve the user | ⛔ | Would need P1 fault injection |
| E4 | SAML signature mismatch (rotated cert) | ⛔ | `scripts/sso-dev-keystore.sh` is the recovery; not an auth-flow test |
| E5 | Multi-tab race on logout | ❌ | Worth adding eventually; medium priority |
| E6 | Incognito / different browser profile | ✅ | `silent-first-flow.cold-start` uses an isolated context |
| E7 | Cross-device | ⛔ | – |

## Cross-subdomain SSO document — architectural assertions

The list mirrors the section structure of `cross-subdomain-sso-keycloak (1).md`.

| § | Assertion | Status | Spec |
|---|---|---|---|
| § 2 | Keycloak subject = `person_id` (not the tenant username) | ✅ | `sso-claims.personId-stable-across-subdomains` |
| § 4.1 | KC → IdP brokering via SAML | ✅ | All login specs traverse it |
| § 4.2 | Silent re-auth, redirect-based (not iframe) | ✅ | `silent-first-flow.*` — the BFF returns 303 to KC, no iframe ever rendered |
| § 4.2 | KC authorize URL carries `prompt=none` and `kc_idp_hint=p1` | ✅ | `silent-first-flow.cold-start` asserts both query params on the request URL |
| § 4.3 | Approach A — one OIDC client per subdomain | ✅ | `sso-claims.*` asserts `active_tenant` differs per subdomain (= per client) |
| § 5 | Account linking inside the IdP (Option A) | ✅ | `personId-stability.*` confirms re-login same person |
| § 6 | `(person, tenant) → (alias, roles)` resolution | ✅ | `sso-claims.*` per-tenant alias + role correctness |
| § 7 | No tokens in the browser | ✅ | `token-handler.*` |
| § 7 | Logout single sign-out across all clients | ✅ | `logout.spec` + `external-logout.*` |
| § 7 | Token audience validation | 🟡 | The audience flows through the OIDC code exchange; not explicitly asserted with a forged token |
| § 7 | Modern V2 token exchange | ⛔ | The demo uses Approach A (audience-scoped tokens), not token exchange |

## Multi-username case (`cross-subdomain-sso-multi-username-analysis.md`)

| Gap | Status | Spec |
|---|---|---|
| Person-stable `personId` (Gap A) | ✅ | `sso-claims.personId-stable-across-subdomains` + `personId-stability.*` |
| Per-(person, tenant) alias (Gap B) | ✅ | `sso-claims.*` (`tim1` / `tim5` / `tim10` per slot) |
| Per-(person, tenant) roles (Gap C) | ✅ | `sso-claims.*` asserts no role bleed between slots |
| Scenario B (different emails per username) | ⛔ | Would need three real P1 users with distinct emails |

## BFF / Token Handler invariants

| Invariant | Status | Spec |
|---|---|---|
| No OP tokens reach the SPA | ✅ | `token-handler.spec` |
| Session cookie is httpOnly + Secure + SameSite=Lax | ✅ | same |
| `/api/<domain>/*` is gated by per-tenant role | ✅ | `api-authorization.spec` |
| `/api/<domain>/*` returns the expected shape under the right role | ✅ | same |
| 401 vs 403 differentiation prevents login loop | ✅ | `no-relogin-loop.spec` — (A) unauthenticated `/auth/me` + `/api/*` return 401 (not a 3xx/5xx) on both subdomains; (B) when login can't establish a session the SPA redirects exactly once then shows "Couldn't sign you in." (the 10s loop guard); (C) a mocked 403 on a data call renders access-denied and never navigates to the login route. The 403 branch is driven via route mocking because the demo dataset gives `tim1` every role, so a real end-to-end 403 is unreachable. The old downstream-401→502 translation (the removed `users` domain's P1 proxy) is intentionally not asserted — that code path no longer exists; billing/trading make no such upstream call |
| `kc_idp_hint=p1` forced on every authorize redirect | ✅ | `silent-first-flow.*` asserts it appears |

## Whitelabel cross-host SSO (P1 hosts)

P1 serves whitelabel hosts (e.g. `c1wealth.localhost:8888` → CreativeOne)
off the same port, distinguished by hostname. The silent-SSO redirect_uri is
host-dynamic so a whitelabel host keeps its round-trip on-host, with a real
firm switch and a true global logout. These specs drive P1 hosts directly
(not the BFF domains).

| Assertion | Status | Spec |
|---|---|---|
| Host → firm resolution is per-host (no degenerate `system_base_url='/'` hijack) | ✅ | `whitelabel-firm-resolution.spec` — localhost / 127.0.0.1 / unmapped → GeoWealth(1); c1wealth → CreativeOne(1123), anonymous |
| Whitelabel host completes silent SSO on-host (host-dynamic redirect_uri) | ✅ | `whitelabel-cross-host-sso.spec` — lands on c1wealth, `loggedUser` there |
| Per-host firm context, no bleed | ✅ | `whitelabel-cross-host-sso.spec` — c1wealth session = 1123 while localhost session = 1 |
| Logout from a whitelabel host is a true single-logout | ✅ | `whitelabel-global-logout.spec` — KC sessions → 0, resource BFF → 401 |
| Sibling P1 session (canonical host) torn down on logout | ✅ | `whitelabel-global-logout.spec` — kc_sub object-index kill; the `KcSessionProbe` liveness check is the fallback |

## What's intentionally out of scope

- **Time-based scenarios** (O3, O4) — can't fast-forward 30 min / 7 h in CI.
- **P1 UI** (L3, L4) — lives in the geowealth repo; not part of this demo.
- **Production deployment concerns** (E2, certificate rotation, KC_HOSTNAME mismatch) — handled at infrastructure level.
- **Cross-device / cross-browser** (E7) — out of single-machine E2E reach.

## Running the suite

See [`README.md`](README.md). All scenarios marked ✅ above run as part of
`npm test`; gaps marked ❌ are tracked here so the next contributor can pick
them up without re-reading the design docs from scratch.

### Slow dev stack — run in batches, not one big serial pass

Every spec passes in isolation (or small batches), but the **full serial
`npm test` is flaky on the local dev P1**: a single P1 login takes ~45–90s on
this box, the suite drives ~30 of them, and the P1/Keycloak/Oracle stack
degrades under that sustained load until late logins blow their timeouts
(observed 18/22 then 10/22 green across two identical back-to-back runs). This
is an infrastructure constraint, not a test-logic bug.

Practical guidance until the login cost is removed:

- Run by area, e.g. `npx playwright test whitelabel-`, `npx playwright test sso-claims token-handler`, etc. Each batch goes green.
- Restart the P1 Tomcat (and ideally Keycloak) before a full pass so the stack starts cold.
- The asserts are written as **invariants** (personId is stable / consistent, claim set is complete, active sessions reach zero) and admin operations **discover the user dynamically** — so they hold against whatever person the backing DB seeds, with no hard-coded identity to drift.

The proper fix for a reliable single-pass run is to stop re-logging-in per
spec: a Playwright `storageState` captured once in a global setup and reused by
the read-only assertion specs (the cold-start / logout specs that genuinely
need a fresh session opt out). That cuts the login count by more than half and
takes the stack out of the critical path. Not yet done.
