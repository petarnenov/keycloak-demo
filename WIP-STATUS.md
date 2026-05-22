# Work-in-progress — GeoWealth white-labeling POC

Self-handoff doc for resuming on a second machine. Records branch names,
what's committed vs. dirty, what local state has to be re-created (env
vars, hosts entries, secrets that don't live in git), and what the next
two or three actions are.

Living doc — overwrite it on the next checkpoint, don't append.

Last touched: 2026-05-22.

---

## Branches in flight

| Repo | Branch | Base | Status |
|---|---|---|---|
| `keycloak-demo` (this repo) | `petarnenov/geowealth-whitelabel-poc` | `petarnenov/onboarding-mfe-monorepo` | Phase 4 in progress; tip = `2bdbe8d` "Phase 4 part 2". 3 uncommitted files (see below). |
| `geowealth` | `team/petarnenov/keycloak-whitelabel-poc` | `master` | Findings doc at `~/geowealth/keycloak-poc-findings.md` is on this branch. Branding-api endpoint + token plumbing live here. |

Both branches are deliberately isolated from `main` / `master` — see
§14 of `geowealth-keycloak-poc-migration-plan.md` (rollback plan).

---

## Where we are in the plan

Tracking `geowealth-keycloak-poc-migration-plan.md`. Phases 0–3 done.
Phase 4 (hardening / telemetry / docs) is partly done:

| Section | Item | State |
|---|---|---|
| §9.1 Failure-mode coverage | GeoWealth down, unknown firm, malformed JSON, slow API | **Not yet** — needs the running stack to walk through; should be quick once back at the machine. |
| §9.2 Telemetry — Keycloak logs | INFO-level branding decision log | Done in `cbd1f40` (Phase 4 part 1). |
| §9.2 Telemetry — GeoWealth logs | INFO logs on the API side | Lives on the geowealth branch, not this one. |
| §9.2 Telemetry — `OPERATIONS.md` | One-page dashboard | **Just written, uncommitted** at `frontend/apps/geowealth-poc/OPERATIONS.md`. |
| §9.3 Security checks | Token leak, path traversal, CSS injection, CSP | Done in `2bdbe8d`; results in `SECURITY-CHECKS.md`. |
| §9.4 POC README | `apps/geowealth-poc/README.md` | **Just written, uncommitted** at `frontend/apps/geowealth-poc/README.md`. |
| §9.4 CLAUDE.md update | New rows in "Applying changes" table | **Just edited, uncommitted** in `CLAUDE.md`. |
| §9.4 GeoWealth-side README | Endpoints + auth pattern + env-var | Lives on the geowealth branch. |
| §9.4 Hand-off doc | One-page "what worked, what didn't, prod gap" | Not started. |
| §9.5 Demo recording | 60–90 s screencap of both firms logging in | Not started (manual). |

---

## Uncommitted on this machine

`git status` at last checkpoint:

```
modified:   CLAUDE.md
?? frontend/apps/geowealth-poc/OPERATIONS.md
?? frontend/apps/geowealth-poc/README.md
```

All three are §9.2 / §9.4 deliverables. Nothing else is dirty. Stash
or commit before pulling on the other machine — they aren't on the
remote yet.

### Suggested commit shape

One commit, message body something like:

> Phase 4 part 3: POC README, ops one-pager, CLAUDE.md applying-changes rows
>
> §9.4 deliverables for the operator side — apps/geowealth-poc/README.md
> for runbook and what-to-expect, OPERATIONS.md for the §9.2 telemetry
> dashboard with the actual log line shapes from BrandingService /
> BrandingApiClient / BrandCss, and three new rows in CLAUDE.md's
> "Applying changes" table for the geowealth-keycloak SPI source,
> the new realm export, and the branding-api contract lockstep rule.

(`Co-Authored-By: Claude` trailer per `git log` style.)

---

## Local state that lives outside git

Has to be re-created on the second machine — none of it is in the
branch.

1. **`.envrc`** at repo root. Gitignored. Minimum content:

   ```bash
   export POC_BRANDING_API_TOKEN="<32 random bytes; openssl rand -base64 32>"
   export RESEND_API_TOKEN="<optional — empty falls back to logged OTP>"
   # If using podman: DOCKER_HOST line per CLAUDE.md "Running this on macOS + Podman".
   ```

   The token value itself doesn't have to match between machines — the
   same value is used by `dev-branding-api.sh` and the Keycloak SPI, so
   just regenerate locally.

2. **`/etc/hosts`** — only needed on Linux. macOS resolves `*.localhost`
   for free. If your second machine is Linux:

   ```text
   127.0.0.1 changepath.localhost
   127.0.0.1 default.localhost
   127.0.0.1 cca.localhost
   127.0.0.1 unknown.localhost
   ```

3. **Container engine** — Docker Desktop or Podman. `./start.sh` auto-
   detects; force with `CONTAINER_ENGINE=docker|podman ./start.sh`. On
   Linux podman, you may need
   `extra_hosts: ["host.docker.internal:host-gateway"]` on the keycloak
   service so the SPI can reach the host-bound fake branding API.

4. **Python 3.8+** — only if running `./dev-branding-api.sh`.

---

## Resume on the other machine

Assuming you've already committed and pushed the three dirty files
above on this machine:

```bash
# 1. fetch the work
git fetch origin
git checkout petarnenov/geowealth-whitelabel-poc
git pull --ff-only

# 2. re-create .envrc per the section above, then:
source .envrc

# 3. bring the stack up
./start.sh

# 4. (recommended) run the fake branding API in a second terminal
./dev-branding-api.sh

# 5. green check: two tabs, two themes
#    http://changepath.localhost:5174/  → ChangePath login
#    http://default.localhost:5174/     → GeoWealth login
#    user: poc-user / 123

# 6. one INFO line per render
docker compose logs --since 1m keycloak | grep 'Branding:'
```

For the geowealth side:

```bash
cd ~/geowealth   # or wherever you clone
git fetch
git checkout team/petarnenov/keycloak-whitelabel-poc
git pull --ff-only
# findings live in ~/geowealth/keycloak-poc-findings.md on this branch
```

---

## Next two or three actions when resuming

In rough priority order — pick one, don't try to batch:

1. **Walk §9.1 failure modes.** Four small scenarios; the §9.3 security
   walk-through pattern in `SECURITY-CHECKS.md` is the template. Add a
   `FAILURE-MODES.md` (or extend the same file) with the repro for
   each: stop the fake, hit unknown subdomain, run with
   `BREAK_MODE=json`, run with `SLEEP_MS=4000`. Each should produce a
   visible login + expected log lines.
2. **Write the §9.4 hand-off doc.** One page — "what worked, what
   didn't, production gap". Most of the prod-gap table is already in
   §10 of the migration plan; the hand-off doc is the readable
   narrative on top of that, plus the lessons-learned bullets.
3. **Record the §9.5 demo.** Once §9.1 is clean, the recording is a
   60–90 s capture of both browser tabs. Manual; only doable from
   wherever the screen-recording tool is set up.

---

## What you can safely skip on the second machine

- Pre-Phase-4 work is committed and reproducible from `./start.sh`.
- The `geowealth-keycloak/` SPI source is on the branch; no out-of-band
  artifacts. `./start.sh` rebuilds the JAR inside the keycloak image.
- The realm imports both happen on a fresh DB. If your second machine
  has an old volume cached from a prior session, `docker compose down -v`
  before `./start.sh` to force a re-import of `geowealth-realm`.
- Anything mentioned in `geowealth-keycloak-poc-migration-plan.md` §11
  (risk register) is informational, not pending work.
