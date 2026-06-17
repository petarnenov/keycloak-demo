# Restart impact matrix

What happens to currently logged-in users when each component is rebuilt or
restarted. "Logged in" here means: browser holds a valid `GWSESSION` cookie,
Redis holds the matching server-side session, and (usually) Keycloak holds the
corresponding KC session.

## 1) Rebuild `token-handler`

**Nobody is logged out.** Sessions live in Redis, not in the token-handler's
JVM. The `GWSESSION` cookie stays in the browser. During the restart window
(~5–15s):

- In-flight `/auth/verify` calls fail → nginx `auth_request` returns 502 →
  the SPA sees a few transient XHR errors (usually silently retried).
- Once the container is back, `/auth/verify` finds the same session in Redis,
  refreshes tokens as needed, and emits `X-Auth-*` headers to the data BFF.
  The user sees, at most, one retried request.

This is the whole point of extracting the token-handler: a shared-auth fix is
deployed by rebuilding **one** image; the data BFFs are not touched and users
keep their sessions.

## 2) Rebuild a data BFF (`bff-billing` / `bff-trading`)

**Nobody is logged out.** The data BFFs are auth-unaware (forward-auth), hold
no session, and run no token refresh. During the restart:

- In-flight `/api/<name>` requests fail with 502 / connection refused → the
  SPA either retries or surfaces an error until the container comes back.
- The token-handler, Redis, and Keycloak are untouched. The cookie and the
  Redis session remain valid.

## 3) Rebuild a web domain (`demo-billing` / `demo-trading`)

**Open tabs are not logged out.** The SPA bundle and the `keycloak-js` token
are already in the browser. While the web container restarts:

- `/api/<name>` routes through this container's nginx → 502 until it is back.
- New tabs / hard reload pick up the new bundle.
- If the new bundle changes the `keycloak-js` config or OIDC client id, old
  tabs keep running on the old code and new tabs use the new flow — that is
  expected, not a problem.

## 4) Rebuild P1 (Tomcat)

**Existing KC sessions survive, the P1 session is lost.** This is the
critical distinction:

- Keycloak has its own session (`KEYCLOAK_IDENTITY` cookie on
  `auth.geowealth.int`) and the token-handler holds tokens in Redis. Neither
  depends on P1 once the initial SAML login has happened.
- `GWSESSION` → token-handler → Redis → refresh against KC continues to work
  without P1 being up.
- Caveats:
  - If `/tmp/p1-idp-dev.p12` is gone (e.g. macOS wiped `/tmp` on reboot), the
    first SAML signature 500s. Recovery is one script:
    `./scripts/sso-dev-keystore.sh`.
  - Any **new** SAML login (a new user, or SLO followed by re-login) blocks
    until P1 is back up.
  - The server-side guard `SilentSsoAction.SESSION_KEY_ESTABLISH_DONE` lives
    in the P1 `HttpSession`, so a P1 restart clears it. The next credential
    login will redo the `establish=true` round-trip — that is the intended
    behaviour (see [[p1-establish-round-trip]]).

## 5) Rebuild an Akka agent

**No direct effect on the identity tier.** The login flow (KC + P1 SAML +
token-handler) does not go through Akka. But see [[be-akka-cluster-split]]:
restarting an agent without the right order can split the cluster and
manifest as "click Login hangs". If the split affects the P1-side login, see
section 4.

## 6) Rebuild Redis

**Depends entirely on persistence.** `docker-compose.yml` sets
`--appendonly yes` with a `redis_data` volume → AOF persistence is enabled.

- Plain `docker compose restart redis` or an image rebuild without `-v` →
  sessions survive, **nobody is logged out**. There is a 1–2s window during
  which the token-handler returns 5xx while the Lettuce client reconnects.
- `./start.sh --reset` / `docker compose down -v` → the AOF file is deleted
  → **every user is logged out globally**. The cookie remains in the browser
  but `/auth/verify` no longer finds a session, returns 401, and the SPA
  starts a fresh `kc_idp_hint=p1` flow. If the Keycloak session on
  `auth.geowealth.int` is still alive, silent SSO returns the user
  immediately (no P1 prompt).

## 7) Rebuild Postgres

**Depends on the type of rebuild and on `persistent-user-sessions`.**

- Clean restart (volume intact): Keycloak loses its DB connection for a few
  seconds. Existing in-memory KC sessions continue; new logins block until
  the DB is back. Realm config, federated identities, and (under KC 26's
  default `persistent-user-sessions=true`) user sessions all survive.
- `down -v` / `--reset`: the realm is re-seeded from `realm-export.json`
  (which is `IGNORE_EXISTING` against an empty DB). All federated identities
  are gone, so the next SAML login redoes first-broker-login (auto-link by
  email — silent). Any realm-level edits made via admin API that are **not**
  in the export are lost — see the URL reconciler scripted under
  `scripts/reconcile-realm.sh`.
- The token-handler and data BFFs do not touch Postgres directly, so their
  Redis sessions survive a DB restart — but until KC is back, refresh tokens
  cannot be exchanged, so after the access-token lifespan (~5 min) requests
  start failing with 401.

## TL;DR

| Rebuild | Logout? | User-visible impact |
|---|---|---|
| `token-handler` | No | ~5–15s of 502 on API calls |
| data BFF | No | ~5–15s of 502 on that domain only |
| web domain | No | ~5–15s of 502; open tabs keep working |
| P1 Tomcat | No (for existing KC sessions) | New SAML logins blocked; keystore caveat |
| Akka agent | Not directly | Can trigger cluster split → login hang |
| Redis (no `-v`) | No (AOF) | ~1–2s of 5xx |
| Redis (`-v`) | **Yes, everyone** | SPA starts a new login (silent if KC session is alive) |
| Postgres (no `-v`) | No | New logins blocked ~10s; token refresh fails after ~5 min |
| Postgres (`-v`) | **Yes, everyone** (federated identities + realm config wiped) | First-broker-login redone, auto-link by email |
