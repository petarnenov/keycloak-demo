# Plan: decompose `bff-core` — eliminate the shared library for a repo-per-domain world

Date: 2026-07-06
Status: analysis complete; ready to scope (no code changed yet)
Basis: the repo-per-domain direction (each domain ships as its own repo with `web/` +
`bff/`). Answers: **can we have NO `bff-core`?** — yes, and it is the cleaner target.

Suggested branch: `petarnenov/bff-core-decomposition`.

## Key finding — `bff-core` is two asymmetric slices, not one shared library

Measured 2026-07-06:

| Slice | Classes | ~Lines | Runtime consumer |
|---|---|---|---|
| **Auth / session / logout** | AuthController, TokenRefreshFilter, SilentLoginController, BackchannelLogoutController, RotatingSessionLoginHandler, SidSessionRegistry, LogoutTokenValidator, KeycloakAuthenticationMapper, ForwardAuthController, SubdomainAuthorizer/Requirement(s)/Filter, `Bff` main, Brand*, IdpHintFilter | **~1570+** | **token-handler ONLY** |
| **Data-BFF slice** | HeaderIdentity (62), AuthClaims (56), PolicyRuleGate (93), PolicyRuleClient (143) | **~354** | the domain data BFFs |

Facts the plan relies on:

- The domain data BFFs import **exactly three** `demo.bff.core` types: `HeaderIdentity`,
  `AuthClaims`, `PolicyRuleGate` (which pulls `PolicyRuleClient`). The slice is
  self-contained: `PolicyRuleGate` → `PolicyRuleClient` (an HTTP client to authz-service);
  `HeaderIdentity`/`AuthClaims` parse the `X-Auth-*` forward-auth headers.
- The auth beans are **not conditional** (`@Controller("/auth")`, `@Filter("/api/**")`, …).
  A data BFF bundles `bff-core`, runs `demo.bff.core.Bff`, and **instantiates the whole
  auth stack — but it is behaviourally dormant**: nginx forward-auth routes all `/auth` +
  the `/api` `auth_request` to the token-handler, and the data BFF's `/api/**` is
  `isAnonymous()`. So each data BFF today carries ~1570 lines of auth code it never runs.
- Build wiring is already a registry coordinate: `implementation("demo.bff:bff-core:1.0.0")`,
  substituted from sibling source via `includeBuild` today. Both the token-handler and the
  data BFFs set `mainClass = demo.bff.core.Bff`.

**Conclusion:** the auth slice has exactly ONE real consumer (the token-handler), so it is
not "shared" at all; the data BFFs need only a thin slice whose substance is two HTTP
contracts (`X-Auth-*` headers + authz-service `/policy/*`). A fat shared library is
unnecessary — integration should be by contract, not by a bundled Java jar.

## 0. Decisions to lock

| # | Decision | Recommended choice | Rationale |
|---|---|---|---|
| B1 | Auth slice home | **Fold into the token-handler repo** as its own source | One runtime consumer; it stops being a library. |
| B2 | Data-BFF slice delivery | **Thin published client SDK** `demo.bff:domain-sdk` (HeaderIdentity + AuthClaims + PolicyRuleClient/Gate), OR inline-copy per repo | Keeps compile-time safety for the `X-Auth-*` + `/policy/*` contracts without the fat lib. Inline is fine for ≤ a few domains; SDK scales to many teams. |
| B3 | Contract ownership | **`X-Auth-*` header set + authz-service `/policy/*` OpenAPI** are the integration contracts, enforced by contract tests | Convention → compiler/contract-test, not a shared jar. |
| B4 | `mainClass` for data BFFs | **Own `Application` main per domain** (not `demo.bff.core.Bff`) | A data BFF is a plain data service; it must not boot the auth stack. |
| B5 | Migration order | **token-handler self-containment first, then per-domain** | De-risks: the active auth path moves cleanly, domains follow mechanically. |

## Phase 0 — Baseline

1. Branch; record a green baseline (`bff-core` unit tests + full `e2e/` suite in K8s).
2. Snapshot the two contracts as the source of truth:
   - the exact `X-Auth-*` header names the token-handler injects (from `ForwardAuthController`/
     `/auth/verify`);
   - the authz-service `/policy/{capabilities,can,refine}` request/response shapes.

Gate **D0**: baseline green; both contracts written down (feeds the contract tests below).

## Phase 1 — token-handler owns its auth code (B1)

1. Move the ~1570-line auth slice from `bff-core` into the token-handler module as its own
   `src/main/java` (package can stay `demo.bff.core` to avoid churn, or rename to
   `demo.tokenhandler`). Drop the `bff-core` dependency + `includeBuild` from the token-handler.
2. token-handler builds as a standalone module (no composite build).

Gate **D1**: token-handler unit tests green; image builds; deployed token-handler passes the
auth e2e (login, silent-first, `/auth/verify`, logout, back-channel) unchanged.

## Phase 2 — extract the data-BFF slice as `domain-sdk` (B2), de-fat the data BFFs

1. Create `domain-sdk` = `HeaderIdentity`, `AuthClaims`, `PolicyRuleClient`, `PolicyRuleGate`
   (~354 lines) as a thin library (or prepare it for inline-copy). Publish `demo.bff:domain-sdk:1.0.0`.
2. Each data BFF: depend on `domain-sdk` instead of `bff-core`; add its **own** `Application`
   main (B4) so it no longer boots `demo.bff.core.Bff` / the auth beans.
3. Delete `bff-core` (the module and the `includeBuild` lines).

Gate **D2**: each data BFF boots with NO auth beans present (verify the bean count / a startup
assertion that `AuthController`/`TokenRefreshFilter` are absent); `/api/**` still serves data
+ Tier-3 refine; images shrink (no auth jar). Full e2e green.

## Phase 3 — contract tests replace compile-time coupling (B3)

1. **Header contract test**: assert the token-handler injects the exact `X-Auth-*` set the
   SDK/data-BFF reads (a golden-header test on `/auth/verify`).
2. **Authz contract test**: assert `domain-sdk`'s `PolicyRuleClient` speaks the authz-service
   `/policy/*` shapes (reuse `authz-p1-parity.spec.ts` as the live check; add a schema/pact
   test for the wire shapes).

Gate **D3**: contract tests green; a deliberate header-name or wire-shape drift makes them
fail (proving they guard the contract the removed library used to enforce).

## Phase 4 — repo-per-domain dry run

1. Simulate the target: build one data BFF against the **published** `domain-sdk` coordinate
   (not `includeBuild`), with the token-handler as a separate deployable.
2. Confirm nothing needs the `bff-core` source tree.

Gate **D4**: a domain BFF builds + deploys + passes its slice of e2e using only the published
`domain-sdk` + the two HTTP contracts. `grep -rn "bff-core\|includeBuild" .` → zero in the
domain and token-handler builds.

## Final acceptance checklist

| # | Check | Pass condition |
|---|---|---|
| 1 | `bff-core` module gone | no `bff-core/`; no `includeBuild('..bff-core')`; no `demo.bff:bff-core` coordinate |
| 2 | Auth slice single-homed | the ~1570 auth lines live only in the token-handler; not bundled in any data BFF |
| 3 | Data BFF de-fatted | data BFF boots with no `AuthController`/`TokenRefreshFilter` beans; image smaller |
| 4 | Data BFF main | each data BFF runs its own `Application`, not `demo.bff.core.Bff` |
| 5 | SDK/inline slice | `HeaderIdentity`+`AuthClaims`+`PolicyRule*` delivered via `domain-sdk` or inline copy |
| 6 | Contract tests | header + `/policy/*` contract tests green; drift fails them |
| 7 | e2e | full suite green in K8s |
| 8 | Repo-per-domain dry run | a domain builds from the published SDK alone (D4) |

## Out of scope / trade-offs

- **DRY vs independence:** inlining the ~354-line slice into N repos duplicates it; the thin
  `domain-sdk` avoids that. Either is acceptable — but the **1570-line auth slice must never
  be duplicated**; it belongs solely to the token-handler.
- **No behavioural change intended:** decomposition is structural. `AUTHZ_FINE_ENABLED` is
  already gone (fine always on); refine + gwAdmin bypass semantics are unchanged.
- **OpenAPI-generated client** for authz-service `/policy/*` is a valid alternative to a
  hand-written `PolicyRuleClient` in the SDK; either restores compile-time safety.

## Rollback

- Structural, phase-by-phase, each an independently revertible commit. Until `bff-core` is
  deleted (Phase 2 step 3) the composite build still works, so any phase can be backed out
  without touching runtime behaviour.
