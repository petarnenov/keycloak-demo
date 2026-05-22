# Work-in-progress — GeoWealth white-labeling POC

Self-handoff doc for resuming on a second machine. Records branch names,
what's committed, what local state has to be re-created (env vars,
hosts entries, secrets that don't live in git), and what's actually
left to do.

Living doc — overwrite it on the next checkpoint, don't append.

Last touched: 2026-05-23.

---

## TL;DR

POC is **functionally complete**: Phase 4 (failure modes / telemetry / security / docs) closed in code, Phase 5 (production-readiness graduation across the 9 hardening gaps from `PRODUCTION-RISK.md`) closed 7 of 9 in code with the remaining two reduced to documented design / partial implementation. **43/43 JUnit integration tests passing** against the running stack. Both repo branches up to date with their remotes.

Start with [`HANDOFF.md`](HANDOFF.md) for the stakeholder view; this doc is the operator-side resume guide.

---

## Branches in flight

| Repo | Branch | Tip | Status |
|---|---|---|---|
| `keycloak-demo` (this repo) | `petarnenov/geowealth-whitelabel-poc` | `be312ba` "HANDOFF.md — §9.4 stakeholder summary" | All commits pushed. Working tree clean (modulo 2 long-untracked files — see below). |
| `geowealth` | `team/petarnenov/keycloak-whitelabel-poc` | `9bbaf42b591` "Branding API: env-tunable limits + per-request audit log" | All commits pushed to GitLab. Open MR pending (manual). |

Both branches are deliberately isolated from `main` / `master`. `PRODUCTION-RISK.md` §"Scenarios if the geowealth branch is merged" walks through the merge implications.

---

## Where we are in the plan

Phase 4 (`geowealth-keycloak-poc-migration-plan.md` §9) close-out:

| Section | Item | State | Where |
|---|---|---|---|
| §9.1 | Failure-mode coverage (4 + real-BE scenario) | ✅ done | `FAILURE-MODES.md` (5 scenarios, all <5 s, fail-open verified) |
| §9.2 | INFO-level branding log (Keycloak side) | ✅ done | `BrandingService` (now DEBUG by default, WARN on fallback after Phase 5 #4) |
| §9.2 | Audit log (GeoWealth side) | ✅ done | `BrandingApiServlet.auditLog` per call (Phase 5 #7) |
| §9.2 | OPERATIONS.md ops dashboard | ✅ done | `frontend/apps/geowealth-poc/OPERATIONS.md` |
| §9.3 | Security checks (token leak, path traversal, CSS injection, CSP) | ✅ done | `SECURITY-CHECKS.md` + CSP tightened in realm export (Phase 5 #9) |
| §9.4 | POC README + CLAUDE.md applying-changes rows | ✅ done | `frontend/apps/geowealth-poc/README.md`, `CLAUDE.md` |
| §9.4 | GeoWealth-side README | ✅ done | Lives on the geowealth branch |
| §9.4 | Hand-off doc | ✅ done | `HANDOFF.md` (stakeholder synthesis) |
| §9.5 | 60-90 s demo recording | **Not started** (manual) | — |

Phase 5 (production-readiness gaps from `PRODUCTION-RISK.md`, addressed in this session):

| # | Gap | State |
|---|---|---|
| #1 | Token rotation | Design only — `PRODUCTION-HARDENING.md §"Token rotation design"`. Pending vault choice. |
| #2 | Rate limit on `/branding-api/*` | ✅ Filter-level via Caffeine, env-tunable `POC_BRANDING_API_RATE_LIMIT_PER_MIN` |
| #3 | BLOB size cap | ✅ 2 MB default, env-tunable `POC_BRANDING_API_MAX_ASSET_BYTES` |
| #4 | INFO log volume | ✅ DEBUG default, WARN on fallback |
| #5 | Cache invalidation hook | **Partial** — programmatic `BrandingService.invalidate(code)` ready; REST endpoint via `RealmResourceProvider` deferred |
| #6 | Realm gate via attribute | ✅ `geowealthBrandingProvider` realm attribute; legacy name fallback retained |
| #7 | Audit log on success | ✅ Structured INFO line per `/branding-api/*` call |
| #8 | Registry placeholders | ✅ Feature-flagged via `KC_SPI_LOGIN_FREEMARKER_GEOWEALTH_REGISTRY_PLACEHOLDERS` |
| #9 | CSP tightening | ✅ Explicit `default-src 'self'`, `style-src 'self' 'unsafe-inline'`, `img-src 'self' data:` etc. in realm export |

Full gap-by-gap detail in `PRODUCTION-HARDENING.md`.

---

## Working tree state

```
$ git status
On branch petarnenov/geowealth-whitelabel-poc
Your branch is up to date with 'petarnenov/petarnenov/geowealth-whitelabel-poc'.

Untracked files:
	login-flow-bg.md      (May 21 Bulgarian login-flow notes; long-untracked, decide commit/delete)
	proxy/                (HTTPS dev certs; long-untracked, gitignore candidate)

nothing added to commit but untracked files present
```

Both untracked items predate this session — they're not blockers. Decide separately whether to commit, gitignore, or delete.

---

## Local state that lives outside git

Has to be re-created on the second machine — none of it is in the branch.

1. **`.envrc`** at repo root. Gitignored. Minimum content:

   ```bash
   # Single static token shared with the geowealth Tomcat (see setenv.sh below).
   # Stable value across machines simplifies the dev-stack <-> geowealth handshake.
   export POC_BRANDING_API_TOKEN="AV2EPegvxRi5zVt10IQHSUAk16HhuS40UAJ60UmoaV8="

   # Optional — leave blank to log the OTP instead of emailing it.
   export RESEND_API_TOKEN=""
   ```

2. **`~/tools/tomcat9/bin/setenv.sh`** on the GeoWealth dev machine — same token mirrored on the Tomcat side so the `BrandingApiAuthFilter` accepts requests from Keycloak. Also bumps the rate limit so the JUnit suite fits in the window:

   ```bash
   export POC_BRANDING_API_TOKEN="AV2EPegvxRi5zVt10IQHSUAk16HhuS40UAJ60UmoaV8="
   export POC_BRANDING_API_RATE_LIMIT_PER_MIN="500"
   ```

3. **`/etc/hosts`** — required on **all** OSes when testing the production firm URLs in a browser, because `*.geowealth.com` resolves to real production IPs unless overridden:

   ```text
   127.0.0.1 c1wealth.geowealth.com
   127.0.0.1 c1securities.geowealth.com
   127.0.0.1 bcj.geowealth.com
   127.0.0.1 smithandcox.geowealth.com
   127.0.0.1 riverwaterpartners.geowealth.com
   127.0.0.1 wisewealthkc.geowealth.com
   127.0.0.1 wisewealthkcadv.geowealth.com
   ```

   `*.localhost` resolves to `127.0.0.1` automatically on macOS; entries above are only needed for the `.com` hostnames.

4. **Dev Oracle seed** — `WHITELABEL_TBL` rows for the 7 demo firms + 1 `EMPLOYEE_TBL` advisor (pass 3/4 override). The exact SQL is in `TEST-SCENARIOS.md` §"Seeded data" and §"Advisor-level seed (passes 3 / 4)". Critical: every `ENTITY_ID` / `WHITELABEL_ID` must be **32-char hex** (see the "Critical gotcha" section in the same doc).

5. **Container engine** — Docker Desktop or Podman. `./start.sh` auto-detects; force with `CONTAINER_ENGINE=docker|podman ./start.sh`.

---

## Resume on the other machine

```bash
# 1. Fetch the work
git fetch origin
git checkout petarnenov/geowealth-whitelabel-poc
git pull --ff-only

# 2. Re-create .envrc per the section above
source .envrc

# 3. Bring the stack up
./start.sh

# 4. Verify with the JUnit suite — comprehensive matrix
cd geowealth-keycloak
gradle test
#   → 43 tests, 43 passing

# 5. (optional) shell smoke for a quick health check
cd ..
./test-whitelabel-scenarios.sh
```

For the geowealth side:

```bash
cd ~/geowealth
git fetch
git checkout team/petarnenov/keycloak-whitelabel-poc
git pull --ff-only
# Re-create ~/tools/tomcat9/bin/setenv.sh per the section above, then:
./bin/nfstart_dev petar
```

---

## What's actually left

Sorted by leverage / commitment:

1. **§9.5 demo recording** — 60-90 s screencap. Manual; needs screen-recording tooling.
2. **Open the GitLab MR** for the geowealth branch (`team/petarnenov/keycloak-whitelabel-poc`). GitLab returns the create-MR link on every push.
3. **CI workflows** — GitHub Actions for `gradle test` + `test-whitelabel-scenarios.sh` in keycloak-demo; GitLab pipeline equivalent for geowealth. ~1-2 h.
4. **Production-readiness gap #1** (token rotation, full implementation) — blocked on the security team picking a vault; ~half-day each side once picked. Design is in `PRODUCTION-HARDENING.md`.
5. **Production-readiness gap #5** (cache invalidation REST endpoint) — `RealmResourceProvider` Keycloak SPI extension + auth + geowealth admin webhook. ~half-day SPI + 2 h admin side. Design + interface in `PRODUCTION-HARDENING.md §"#5 Cache invalidation REST endpoint"`.
6. **Restore Tomcat env** when no longer testing — `~/tools/tomcat9/bin/setenv.sh` currently has `POC_BRANDING_API_RATE_LIMIT_PER_MIN=500`; for prod-shape simulation, unset it so the 60/min default kicks in.

Nothing else from the original migration plan is open. The 5 docs (`HANDOFF.md`, `PRODUCTION-RISK.md`, `PRODUCTION-HARDENING.md`, `TEST-SCENARIOS.md`, `FAILURE-MODES.md`) are the comprehensive deliverable set.

---

## What you can safely skip on the second machine

- Pre-Phase-4 work is committed and reproducible from `./start.sh`.
- The `geowealth-keycloak/` SPI source is on the branch; no out-of-band artifacts. `./start.sh` rebuilds the JAR inside the keycloak image.
- The realm import happens on a fresh DB. If your second machine has an old volume cached, `docker compose down -v` before `./start.sh` to force a re-import of `geowealth-realm` (otherwise the realm attribute and CSP changes from Phase 5 won't apply until you patch them via the admin API).
- Anything mentioned in `geowealth-keycloak-poc-migration-plan.md` §11 (risk register) is informational, not pending work.

---

## Doc map

| Doc | When to open |
|---|---|
| `HANDOFF.md` | Stakeholder summary — what worked / what didn't / prod gap |
| `PRODUCTION-RISK.md` | Break-probability analysis for a hypothetical merge to master |
| `PRODUCTION-HARDENING.md` | Gap-by-gap status of the 9 production-readiness items |
| `TEST-SCENARIOS.md` | Production resolution matrix + seed SQL + manual test recipe |
| `FAILURE-MODES.md` | §9.1 walkthrough — 5 failure scenarios with reproduce blocks |
| `SECURITY-CHECKS.md` | §9.3 walkthrough — token leak, traversal, CSS injection, CSP |
| `geowealth-keycloak-poc-migration-plan.md` | Original 10-phase plan; this WIP-STATUS tracks against it |
| `CLAUDE.md` | Codebase survival notes for working in the repo |
