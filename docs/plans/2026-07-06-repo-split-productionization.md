# Plan: repo-per-domain split + productionization (retire the "demo" term)

Date: 2026-07-06
Status: ready to execute (no code changed yet)
Feed this file to `/goal` verbatim: *"Execute docs/plans/2026-07-06-repo-split-productionization.md.
The goal is complete only when the Final verification checklist passes 100%."*

## Goal statement

Split the current monorepo so that (1) **each domain lives in its own mono-repo with
`web/` + `bff/`**, (2) **everything logically related to SSO/SLO lives in one separate
identity repo**, and (3) the platform **leaves the demo phase**: the term "demo"
disappears from every identifier, config, doc and code path, replaced by
production-grade, industry-standard names. Verify the split with the checklist at the
end — full e2e green across the physically separated repos.

## Current-state inventory (measured 2026-07-06)

| Area | Contents | "demo" load |
|---|---|---|
| `domains/{billing,trading,portfolio,custodian}/` | web (Vite/React) + bff (Micronaut, thin, on `domain-sdk`) | packages `demo.<slug>`, compose services `demo-<slug>`, client ids |
| `token-handler/` | ALL auth/session/logout/verify (owns former bff-core auth half) | group `demo.tokenhandler`, realm/client config |
| `domain-sdk/` | the 4-class contract library (X-Auth-* + `/policy/*`) | group `demo.bff`, package `demo.bff.core` |
| `authz-service/` | PolicyRule read DAO (`/policy/*`) | group `demo.authzservice` (pkg `com.gw.authzservice`) |
| `user-service/` | read-only Oracle identity DAO behind the KC User Storage SPI | minor |
| `keycloak/` + `keycloak-providers/` + `auth-spa/` | custom KC image, realm export, SPIs, geowealth theme, auth SPA | realm `demo-realm`, clients `demo-shared-client` (+ vestigial `demo-billing-client`, `demo-trading-client`) |
| `e2e/` | 20 Playwright specs — ALL cross-domain SSO/SLO/authz invariants | realm/client URLs, tenant fixtures |
| `k8s/`, `scripts/`, `db/`, `proxy/`, root `*.md` | manifests (namespace `geowealth-demo`, images `keycloak-demo-*`), up/toggle/portforward/add-domain, Oracle GP seed, mkcert certs, SSO analyses | image names, namespace, docs |

Key facts the plan relies on: the data BFFs are auth-unaware (forward-auth) and build
from `domain-sdk` alone; the token-handler owns all auth code; the User Storage SPI is
realm-agnostic (no hardcoded realm); 26 files reference `demo-realm`; the P1 sidebar
links (`~/geowealth/.../useIntegrationLinks.js`) are an EXTERNAL touchpoint.

## Target repo topology (industry naming)

All repos live under one org/group (`geowealth`); names are kebab-case, product-scoped
(no org prefix inside an org namespace — use `gw-` prefixes only if a flat namespace is
unavoidable). Java packages use reverse-DNS `com.geowealth.*`; Maven coordinates
`com.geowealth.platform:*`; images are registry-scoped `geowealth/<service>:<tag>`.

| New repo | Contents (from monorepo) | Rationale |
|---|---|---|
| **`identity-platform`** | `token-handler/`, `authz-service/`, `user-service/`, `keycloak/` + `keycloak-providers/` + `auth-spa/` (custom KC image, realm, SPIs, theme), `domain-sdk/` (published from here), `e2e/` (the cross-domain SSO/SLO suite), SSO scripts (`reconcile-realm.sh`, sso tooling), SSO/SLO docs (`docs/solution-architect/`, root SSO analyses) | Everything logically SSO/SLO in ONE repo. The platform team owns the two integration contracts (X-Auth-* headers, `/policy/*`) and therefore owns + publishes `domain-sdk`. The cross-domain e2e suite guards platform invariants → lives with the platform. |
| **`billing`**, **`trading`**, **`portfolio`**, **`custodian`** | each: `web/` + `bff/` + own Dockerfiles + own k8s manifests (`web-*.yaml`, `bff-*.yaml`, ingress fragment, cert tooling) + domain smoke tests | The required domain mono-repos. Each builds standalone from the published `com.geowealth.platform:domain-sdk` — no cross-repo source paths. |
| **`platform-infra`** | `k8s/` (base for data tier: oracle/redis; P1 manifests; overlays composing the whole stack; `up.sh`, `toggle.sh`, `portforward.sh`, env files, profiles), `db/` (Oracle GP Flyway seed), `docker-compose.yml` (composed local dev), `start.sh`/`stop.sh`, `proxy/` cert tooling | GitOps/umbrella repo: composes the other repos' manifests (kustomize remote bases or vendored), owns environments. Industry: deployment configuration separate from application code. |
| **`domain-template`** | `scripts/add-domain.sh` reworked into a scaffold/template repo (cookiecutter-style) | "Add a domain" becomes "instantiate the template as a new repo", not "edit the monorepo". |

`~/geowealth` (P1) stays its own repo, unchanged topology.

## Rename map — "demo" → production (the load-bearing identifiers)

| Current | New | Blast radius |
|---|---|---|
| realm `demo-realm` | **`geowealth`** | issuer URL changes (`/realms/geowealth`) → token-handler + P1 RP + user-service JWKS + e2e config + `urls.*.env`; live sessions reset at cut-over (accepted) |
| client `demo-shared-client` | **`token-handler`** (the RP it actually is) | realm + token-handler env + reconcile script + e2e |
| clients `demo-billing-client`, `demo-trading-client` | **DELETED** (documented vestigial) | realm export + reconcile only |
| client `p1-client` | unchanged | — |
| Java pkg `demo.bff.core` (domain-sdk) | **`com.geowealth.platform.sdk`** | imports in 4 BFF controllers + token-handler (mechanical) |
| Java pkgs `demo.<slug>` | **`com.geowealth.<slug>`** | per-domain, mechanical |
| pkg `com.gw.authzservice` | **`com.geowealth.authz`** | standardize reverse-DNS |
| Gradle groups `demo.*` | **`com.geowealth.*`**; SDK coordinate **`com.geowealth.platform:domain-sdk`** | build files + Dockerfiles |
| images `keycloak-demo-*` | **`geowealth/<service>`** (`geowealth/token-handler`, `geowealth/billing-web`, `geowealth/oracle-seed`, …) | compose + k8s manifests + up.sh/add-domain |
| namespace `geowealth-demo` | **`geowealth`** | all manifests, scripts, port-forwards, memory notes |
| compose services `demo-<slug>` | **`<slug>-web`** | docker-compose + nginx docs |
| `MICRONAUT_APPLICATION_NAME`, `APP_SOURCE` etc. | already production-ish (`billing-bff`) — keep | — |
| cookie `GWSESSION`, hosts `*.geowealth.int` | already production-ish — keep | — |
| repo `keycloak-demo` | becomes **`identity-platform`** (via history filter) | remotes, fork, memory |
| docs/mentions of "demo" (README, CLAUDE.md, root analyses, comments, e2e titles) | rewritten to production wording ("the platform", "the stack") | text-only sweep |

External touchpoints to update at cut-over: P1 sidebar `useIntegrationLinks.js`
(`kcAuthorize('demo-shared-client', …)` → `'token-handler'`), P1's OIDC RP env
(issuer/realm), `/etc/hosts` unchanged.

## Decisions locked

| # | Decision | Choice | Rationale |
|---|---|---|---|
| N1 | Rename first or split first | **Rename in place FIRST, then split** | One atomic, e2e-verifiable change in one repo; new repos are born clean (no "demo" in their history tip). |
| N2 | History preservation | **`git filter-repo` per target path set** | Industry standard; each new repo keeps the relevant history. |
| N3 | `domain-sdk` distribution | **Published Maven artifact from `identity-platform`** (GitHub Packages or org registry) | Contract owned by the platform team; domains consume a version, not a path. `includeBuild` dies at split. |
| N4 | Cross-domain e2e home | **`identity-platform`** (runs against a composed stack) | The suite asserts platform invariants; domains keep only their own smoke tests. |
| N5 | Deploy composition | **`platform-infra`** umbrella (kustomize remote bases / vendored manifests per release) | Standard GitOps separation; each app repo still carries its own base manifests. |
| N6 | Realm rename mechanics | **New realm `geowealth` imported from the transformed export + `reconcile-realm.sh`; `demo-realm` retired after cut-over** | Realm rename-in-place is not supported cleanly; import + reconcile is the repo's established pattern. |
| N7 | CI | each repo gets its own pipeline (build + unit + image); `identity-platform` additionally runs the cross-domain e2e against a composed stack | Independent release cycles — the point of the split. |

## Execution phases

### Phase 0 — freeze + baseline
Tag the monorepo; record green: full e2e (36 passed baseline), all unit tests, image builds.
**Gate G0:** baseline green, tag pushed.

### Phase 1 — productionize IN PLACE (the "demo" purge)
1. Realm: transform `realm-export.json` → realm `geowealth`, client `token-handler`,
   drop vestigial clients; stand up the new realm alongside, repoint `urls.*.env` +
   token-handler + P1 RP + user-service; retire `demo-realm`.
2. Code: package renames (`demo.*` → `com.geowealth.*`), Gradle groups/coordinates,
   image names, namespace, compose service names, scripts, k8s manifests.
3. Text: docs/README/CLAUDE.md/e2e titles/comments — "demo" wording out.
4. Update P1 sidebar + P1 RP env (external touchpoint).
**Gate G1:** full e2e green on the renamed stack;
`grep -ri --include=* "demo"` in the repo returns ZERO functional hits (allowed:
historical plan docs under `docs/plans/`, which record the old names).

### Phase 2 — physical split
1. `git filter-repo` the monorepo into: `identity-platform`, `billing`, `trading`,
   `portfolio`, `custodian`, `platform-infra`, `domain-template` (each with its path
   set + shared root files as applicable).
2. Publish `com.geowealth.platform:domain-sdk` to the org Maven registry from
   `identity-platform`; switch every consumer from `includeBuild` to the registry
   coordinate; delete composite-build wiring.
3. Each domain repo gets its own CI (build + test + image); `identity-platform` CI
   builds its four services + publishes the SDK; `platform-infra` composes.
**Gate G2:** every repo builds standalone from a FRESH clone with no sibling checkout
(`grep -rn "includeBuild\|\.\./\.\." */settings.gradle*` → zero cross-repo paths);
SDK resolves from the registry.

### Phase 3 — deployment composition
1. `platform-infra` overlays reference the split repos' manifests (remote bases or
   vendored pins); `up.sh`/`toggle.sh`/`portforward.sh` live here and target namespace
   `geowealth`.
2. Bring the full stack up in K8s from the split repos only.
**Gate G3:** stack up from split repos; all pods healthy in namespace `geowealth`.

### Phase 4 — final verification (the checklist below)
Run the cross-domain e2e suite from `identity-platform` against the Phase-3 stack.

## Final verification checklist (acceptance — run after Phase 3)

| # | Check | How | Pass condition |
|---|---|---|---|
| 1 | Repo topology | list org repos | `identity-platform`, 4 domain repos (web+bff each), `platform-infra`, `domain-template` exist; monorepo archived |
| 2 | Standalone builds | fresh clone of EACH repo on a clean machine/dir, build | all green with no sibling checkouts |
| 3 | SDK from registry | domain BFF dependency resolution | `com.geowealth.platform:domain-sdk` resolves from the registry; zero `includeBuild` |
| 4 | "demo" purge | `grep -ri "demo"` in every new repo (code, config, manifests, docs) | zero functional hits (historical plan docs exempt) |
| 5 | Realm/issuer | OIDC discovery | `/realms/geowealth` serves discovery; `demo-realm` gone; client `token-handler` active; vestigial clients absent |
| 6 | Naming standards | inspect | packages `com.geowealth.*`; images `geowealth/*`; namespace `geowealth`; kebab-case repos |
| 7 | Cross-domain e2e | full suite from `identity-platform` vs the composed stack | 100% green (baseline count, adjusted only for renamed titles) |
| 8 | SSO/SLO manual spot | login P1 → new tab domain (silent SSO); domain sign-out → P1 splash | both directions work (the two historically-broken flows) |
| 9 | Contract guard | header + `/policy/*` contract tests in `identity-platform` | green; a deliberate header rename fails them |
| 10 | External touchpoints | P1 sidebar + P1 RP env | point at realm `geowealth` / client `token-handler`; P1 login green |
| 11 | Scaffold | instantiate `domain-template` into a throwaway repo | it builds + deploys against the platform, then is deleted cleanly |

## Out of scope

- Moving P1 (`~/geowealth`) — already a separate repo.
- Production registry/secrets management beyond naming (org decision).
- Per-firm rollout mechanics (see the authz sync plan).

## Rollback

- Phase 1 is one revertable commit series on the monorepo (tag from G0).
- Phases 2–3 create NEW repos without destroying the monorepo; the monorepo is archived
  only after the checklist passes, so rollback = keep using the monorepo.
