# E2E tests — cross-subdomain SSO

Playwright suite that pins the source document's
[cross-subdomain SSO architecture](../cross-subdomain-sso-keycloak%20(1).md) to
what this repo actually emits. The implementation rationale is in
[../cross-subdomain-sso-implementation.md](../cross-subdomain-sso-implementation.md);
this directory verifies it.

## What's tested

| Spec | What it pins |
|---|---|
| `tests/sso-claims.spec.ts` | The `(person, tenant) → (alias, roles)` table from `PersonRegistry.java` is what the access token carries on each subdomain; `personId` is identical across subdomains; per-tenant roles do not leak. |
| `tests/silent-first-flow.spec.ts` | Cold-start: `/oauth/login/silent` → `prompt=none` → `error=login_required` → `/auth/login-failed` → interactive. Warm-start (KC SSO cookie alive): silent completes without ever touching P1. |
| `tests/token-handler.spec.ts` | The SPA holds no OP tokens. `document.cookie` is empty (the `GWSESSION` cookie is httpOnly); no `access_token` / `id_token` / JWT-shaped strings ever land in `localStorage` or `sessionStorage`. |
| `tests/logout.spec.ts` | `GET /auth/logout` on one subdomain back-channels logout to all sibling BFFs — `/auth/me` on every subdomain answers 401 after. |

## ⚠️ One-time setup: disable MFA on tim1

P1 enforces email-OTP MFA on `tim1` by default. There is no programmatic way
for the suite to fetch the code from email, so MFA blocks automation by
design. Disable it once on the local DB:

```bash
./e2e/scripts/disable-mfa-for-tim1.sh
```

The script `UPDATE`s `ENTITY_TBL.MFA_REQUIRED_FLAG=0` for `tim1` against the
`geo-oracle` container's FREEPDB1. Re-enable later with `--restore`.

This is a developer-machine accommodation only; never run against anything
but localhost.

## Prerequisites

The suite drives the live stack — it does not start anything itself.

1. `./start.sh` from the repo root brings up postgres, Keycloak, the three
   demo BFFs, the three demo web containers, and the `auth` TLS proxy.
2. P1 (geowealth) is running on `http://localhost:8080` (local Tomcat directly,
   or `kubectl port-forward svc/p1-tomcat 8080:8080` for the in-cluster stack).
   The login flow requires the SamlManager agent up; see
   [../CLAUDE.md](../CLAUDE.md) → "P1 SAML federation flow".
3. `/etc/hosts` has entries for `auth.geowealth.int`,
   `billing.geowealth.int`, `trading.geowealth.int`, `users.geowealth.int`
   → `127.0.0.1` (already required to run the demo at all).
4. The realm patch in `../scripts/apply-cross-subdomain-sso.sh` has been
   applied to the live KC (the suite assumes the per-client mappers from
   that script are in place).

The KC user created by the demo's first-broker-login carries stale user
attributes between runs. Every spec begins with `deleteDemoKcUser()` — a
direct admin-API delete that lets the next P1 login recreate the user
cleanly. Admin creds come from `docker-compose.yml`
(`KEYCLOAK_ADMIN=admin` / `KEYCLOAK_ADMIN_PASSWORD=admin`).

## Running

```bash
cd e2e
npm install
npx playwright install chromium   # one-off, downloads the browser binary
npm test
```

`npm run test:headed` shows the browser if you want to watch the flow.
`npm run test:report` opens the HTML report (also written under
`playwright-report/`).

## CI notes (none yet)

The suite assumes a working dev stack on the host — it isn't wired into CI.
When that changes, the boot-up sequence above is what the CI job has to
script (likely `start.sh` followed by `./scripts/apply-cross-subdomain-sso.sh`
followed by `npm test`).
