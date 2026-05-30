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
| O2 | Logout from P1 (cascades to all BFFs) | 🟡 | `logout.spec` (BFF → P1 SLO redirect) | The BFF `/auth/logout` flow already drives P1's `/saml/idp/initiate-slo.do` as its second hop and is covered. A standalone P1-SLO-only test exists scaffolded but `.skip`-ped in `external-logout.spec` — driving P1's JS-auto-submit SAML form from Playwright is brittle and adds no coverage beyond the KC-admin-revoke test below |
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
| 401 vs 403 differentiation prevents login loop | 🟡 | The P1 sidebar → Users SPA loop bug (downstream P1 401 mistreated as session-expiry) is covered by `no-relogin-loop.spec` — the fix translates upstream 401 to 502 in the BFF. Pure-BFF 401 vs 403 isn't asserted because the demo dataset gives `tim1` enough roles to never trigger 403 |
| `kc_idp_hint=p1` forced on every authorize redirect | ✅ | `silent-first-flow.*` asserts it appears |

## What's intentionally out of scope

- **Time-based scenarios** (O3, O4) — can't fast-forward 30 min / 7 h in CI.
- **P1 UI** (L3, L4) — lives in the geowealth repo; not part of this demo.
- **Production deployment concerns** (E2, certificate rotation, KC_HOSTNAME mismatch) — handled at infrastructure level.
- **Cross-device / cross-browser** (E7) — out of single-machine E2E reach.

## Running the suite

See [`README.md`](README.md). All scenarios marked ✅ above run as part of
`npm test`; gaps marked ❌ are tracked here so the next contributor can pick
them up without re-reading the design docs from scratch.
