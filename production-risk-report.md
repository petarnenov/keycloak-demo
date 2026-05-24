# Production-risk report — SSO Phase 15 + 16 changes

Independent review of the changes shipped in:

- `keycloak-demo` commits `6ec7943` (Phase 15) and `9a9fab2` (Phase 16)
- `geowealth` commits `1da8a7dc5df` (Phase 15) and `5c110bb088c` (Phase 16)

Scope: identify side effects and blockers for taking this code to a
real-customer production environment. The goal is honesty, not
self-promotion — every item is something a senior security or SRE
reviewer would flag during pre-prod review.

Severity ladder:

- **🔴 CRITICAL** — must be fixed before any prod deploy
- **🟠 HIGH** — security or correctness gap that prod will hit reliably
- **🟡 MEDIUM** — quality / UX issue, fix before scaling
- **🟢 LOW** — code-quality / ops debt

## 🔴 CRITICAL

### C1. Back-channel logout endpoint is unauthenticated

`BackChannelLogoutAction` at `/saml/idp/back-channel-logout.do`:

```java
private static JSONObject decodeJwtPayload(String jwt) {
    // base64-decode payload, return claims — NO signature check
}
```

**Risk**: any caller able to POST to the endpoint can craft a
`logout_token` containing an arbitrary `sub`, and we will invalidate
every HttpSession matching it. In the demo this is local-only via
`host.docker.internal`, but in prod the URL has to be reachable from
KC's network — which means typically the same internal network where
other services run, and possibly anyone else with east-west access.

An attacker who learns a user's KC `sub` (visible in any id_token
they ever exchanged, or guessable from username via a brute-force) can
DoS that user's session at will.

**Fix**:

1. Fetch the realm JWKS at startup
   (`/realms/demo-realm/protocol/openid-connect/certs`).
2. Verify the JWT's `kid` against JWKS, validate `alg`, `iss`, `aud`,
   `iat`, `exp` per
   [OpenID Connect Back-Channel Logout 1.0 §2.6](https://openid.net/specs/openid-connect-backchannel-1_0.html#Validation).
3. Reject any token that fails verification.
4. Add an IP allowlist as a belt-and-suspenders: only accept from
   KC's egress range.

### C2. OIDC callback decodes id_token without signature verification

`OidcCallbackAction.extractClaims`:

```java
private static JSONObject extractClaims(String idToken) {
    byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
    return new JSONObject(new String(payload, StandardCharsets.UTF_8));
}
```

**Risk**: lower than C1 because the id_token arrives only as the
response to our own server-to-server POST to the token endpoint —
attacker would need to intercept the TLS connection or compromise
the network path. Class javadoc explicitly notes "TLS hop is the
trust boundary".

Still: a single misconfiguration (an HTTP fallback, a downgraded TLS
version, a corporate MITM proxy) downgrades C2 to C1-equivalent. The
fix is small and the cost of being wrong is large.

**Fix**: same as C1 — verify against realm JWKS, validate `iss`,
`aud=p1-self-client`, `azp`, `exp`, `iat`, `nonce`.

### C3. Hardcoded `localhost:8888` and `host.docker.internal` URLs

Several env-var fallbacks default to dev-host values:

| File | Default |
|---|---|
| `SilentSsoAction.AUTH_ENDPOINT` | `https://auth.geowealth.int:5180/realms/demo-realm/protocol/openid-connect/auth` |
| `SilentSsoAction.REDIRECT_URI` | `http://localhost:8888/saml/idp/oidc-callback.do` |
| `SilentSsoAction.LOGIN_PAGE_URL` | `http://localhost:8888/#login?silent_failed=1` |
| `OidcCallbackAction.TOKEN_ENDPOINT` | same KC URL above |
| `OidcCallbackAction.REDIRECT_URI` | localhost:8888 |
| `OidcCallbackAction.restoreLoggedUserSession` (line 175) | hardcoded `"http://localhost:8888"` prefix on `return_to`, **not** env-overridable |
| Realm `backchannel.logout.url` | `http://host.docker.internal:8888/...` |
| Realm `frontchannel.logout.url` per SPA | hardcoded `https://{billing,trading}.geowealth.int:518{4,5}` |

**Risk**: a single forgotten `P1_OIDC_*` env var, or a deploy on a
non-Docker-Desktop runtime (Kubernetes, ECS, plain EC2) where
`host.docker.internal` doesn't resolve, breaks silent SSO and
back-channel logout silently. The `failToLogin` fallthrough means
users will see the credential form instead of an error — operationally
invisible until somebody notices their KC sessions never end.

**Fix**: parameterize all of these. Mandatory env vars at startup
(fail fast if missing), no localhost defaults. For the realm side,
the realm-export.json is fine for the demo seed but should not be
used in prod — use Terraform/realm-management-cli to apply the right
URLs per environment.

### C4. KC admin credentials default to `admin`/`admin` in cert sync script

`scripts/sync-saml-cert.sh`:

```bash
KC_ADMIN_USER="${KC_ADMIN_USER:-admin}"
KC_ADMIN_PASS="${KC_ADMIN_PASS:-admin}"
```

**Risk**: if an operator runs the script on a prod KC without setting
the env vars (which is the easy thing to forget), the script tries
admin/admin, fails, and may leak the attempted credentials into logs
or shell history. A cron'd version with default creds and no audit
trail is a textbook compliance finding.

**Fix**:

1. `set -u` mode is on already — extend to require explicit
   `KC_ADMIN_USER` and `KC_ADMIN_PASS` (no defaults; die if unset).
2. Better: use a service account / client-credentials grant with a
   dedicated client and a minimum role
   (`manage-identity-providers` only), not the master admin.
3. Source credentials from a secret store (Vault, AWS Secrets Manager)
   not env, so they don't end up in shell history or process listings.

## 🟠 HIGH

### H1. TTL alignment to 7h sharply weakens session security

Realm changes in `9a9fab2`:

- `ssoSessionIdleTimeout`: 1800 (30 min) → 25200 (7h) — **14× increase**
- `accessTokenLifespan`: 300 (5 min) → 1800 (30 min) — **6× increase**

**Context**: P1 is a wealth-management platform. Account balances,
transfer instructions, beneficiary updates flow through it. The 30-min
idle / 5-min token defaults were not arbitrary — they're aligned with
FINRA / SOC 2 / PCI guidance for financial systems.

**Risks**:

- Stolen `KEYCLOAK_IDENTITY` cookie now usable for 7h instead of 30
  min. With a typical XSS-in-a-shared-tab attack, the blast radius is
  much bigger.
- 30-min access tokens mean revocation effectively delayed up to 30
  min after the KC session ends. A compromised refresh-token chain
  could mint multiple 30-min tokens before back-channel notifications
  catch up.
- Internal threat model: a workstation left unlocked for 6h still has
  an authenticated session.

**Fix**: revert to short idle (30 min) and short access-token TTL
(5–15 min) in any environment that ships to real customers. The drift
gap that motivated the change was already mitigated by Phase 15 silent
SSO (the user gets bounced back through `prompt=none` and never sees a
credential form) — extending the idle window was a UX optimization
that traded too much security for it.

### H2. JSESSIONID cookie is not Secure / SameSite-tagged

Confirmed via:

```
Set-Cookie: JSESSIONID=...; Path=/; HttpOnly
```

No `Secure`, no `SameSite`.

**Risk**: in prod over HTTPS, missing `Secure` means the cookie can
leak over any HTTP fallback path. Missing `SameSite` makes CSRF
substantially easier on session-bound endpoints.

**Fix**: configure Tomcat's `web.xml` `<cookie-config>`:

```xml
<session-config>
  <cookie-config>
    <http-only>true</http-only>
    <secure>true</secure>
  </cookie-config>
</session-config>
```

…and set `SameSite=Lax` via a `<filter>` or by upgrading to Tomcat 10
which supports the attribute natively.

### H3. `BackChannelLogoutAction` walks every HttpSession on every call

```java
Map<String, HttpSession> all = GeowealthSessionListener.getAllSessions();
for (HttpSession session : all.values()) {
    Object stashed = session.getAttribute(...);
    ...
}
```

**Risk**: at small scale this is fine. At 10k+ concurrent sessions,
each back-channel logout (one per active KC client per logout)
serializes through this map. A logout storm — say, an admin force
log-out-all triggering N back-channel calls — fans out to N×M
attribute lookups.

**Fix**: maintain a side-index `Map<String /* kc_sub */, Set<String
/* sessionId */>>` populated in `OidcCallbackAction` and cleared in a
`HttpSessionListener.sessionDestroyed`. Back-channel logout becomes
O(1) lookup → O(k) invalidations where k = sessions for that user.

### H4. User-lookup fallback chain can match wrong user across firms

`OidcCallbackAction.resolveUser`:

```java
String email = claims.optString("email");
if (email != null && !email.isBlank()) {
    User u = UserManager.getSole().lookupByUsername(email);
    ...
}
```

`lookupByUsername` is firm-agnostic. If two P1 firms have a user with
the same email-as-username, the fallback returns whichever comes
first. The `preferred_username` path is safer (UUID-based) but if it
ever misses we silently fall to the dangerous one.

**Risk**: catastrophic in multi-tenant prod — a user from firm A could
end up with a `LoggedUser` for an unrelated firm-B user, with all the
permissions that user has. This is the kind of bug that turns into a
breach report.

**Fix**:

1. Stop falling back to email. If `preferred_username` doesn't
   resolve, fail closed.
2. Even better: bake a `p1_uuid` claim into `p1-self-client` via a
   protocol mapper that reads from the federated identity record, and
   require it. Failure to resolve by `p1_uuid` → reject the silent
   SSO entirely.
3. If we ever do need an email fallback, scope the lookup to the
   federated identity's claimed firm.

### H5. `restoreLoggedUserSession` is not a complete `loginUser` replacement

Side-by-side with `LoginAction.loginUser`:

| Session key | `LoginAction` | `OidcCallback` |
|---|---|---|
| `LOGGED_USER` | ✓ | ✓ |
| `LOGGED_ADVISER` | ✓ | ✓ |
| `ConversationManager` | ✓ | ✓ |
| `LOGGED_USER_CRM_URL` | ✓ | ✓ |
| firm-props via `setInSessionFirm` | ✓ | ✓ |
| `WHO_IS_LOGGED_IN_KEY` | ✓ | ✓ |
| `LOGGED_USER_LOGIN_KEY` | ✓ | ✗ |
| `LOGGED_USER_CHECK = "new"` | ✓ | ✗ |
| `FIRM_ADD_TO_BLOCK_TRADE_DEFAULT_FLAG` | ✓ | ✗ |
| `SHOW_PROFILE_SECTION_FLAG` | ✓ | ✗ |
| `FIRM_SUPPORT_EMAIL` | ✓ | ✗ |
| `PolicyRuleManager.createPolicyRulesForClient` | ✓ (client only) | ✗ |
| post-login redirect handling | ✓ | ✗ |

**Risk**: a user who arrived via silent SSO has a slightly different
session shape than a user who typed credentials. UI features keyed
on `FIRM_ADD_TO_BLOCK_TRADE_DEFAULT_FLAG`, `SHOW_PROFILE_SECTION_FLAG`,
or the policy-rules cache will silently misbehave. Hard to debug
because the symptom is feature-specific.

**Fix**: refactor `LoginAction.loginUser` to take a "loginMethod"
parameter (credential / silent-sso / impersonation) and centralize all
session-setup. Or, more pragmatically, replicate the full set in
`restoreLoggedUserSession` with a unit test that diffs the resulting
session map against `LoginAction.loginUser`'s output.

### H6. Front-channel iframe wipes the SPA's entire storage

`frontchannel-logout.html`:

```js
try { sessionStorage.clear(); } catch (e) {}
try { localStorage.clear(); } catch (e) {}
```

**Risk**: trading and billing SPAs use localStorage for UI
preferences, draft order state, cached market data — anything not
auth-related disappears when any sibling SPA signs out. Users on
multi-tab workflows lose unsaved work.

**Fix**: namespace auth state under a known prefix
(`auth.*`, `kc.*`) and only delete keys that match. Don't touch
localStorage state owned by feature code.

### H7. BroadcastChannel reload interrupts in-flight work

`AuthProvider.tsx` listens for `BroadcastChannel('auth')` `logout`
events and calls `window.location.reload()`.

**Risk**: the user is mid-trade-entry on trading SPA when they sign
out on billing in another tab. Trading reloads instantly, form data
lost.

**Fix**: before reload, check for `beforeunload` indicators (open
forms, pending requests) and prompt the user, or persist the
in-progress state and restore after reauth.

## 🟡 MEDIUM

### M1. PII in logs

`OidcCallbackAction` line 152:

```java
LOG.info("OidcCallbackAction: id_token claims: sub=" + claims.optString("sub")
    + " preferred_username=" + claims.optString("preferred_username")
    + " email=" + claims.optString("email"));
```

**Risk**: email logged at INFO. GDPR / CCPA / SOC 2 logging
requirements typically forbid raw PII in app logs.

**Fix**: redact email (`u****@d****.com`) or move the line to DEBUG
and ensure DEBUG is off in prod.

### M2. No rate limiting on `/saml/idp/silent-sso.do`

A user opening localhost:8888 repeatedly triggers KC token-endpoint
calls. An attacker can script millions of requests to load both KC
and the P1 token-exchange path.

**Fix**: rate-limit per source IP via nginx (front of P1 in prod) or
in `SilentSsoAction.execute` with a simple `Map<ip, lastHitTimestamp>`.

### M3. Cert sync script has no rollback on partial failure

`sync-saml-cert.sh` PUTs the new realm config. If the PUT body is
malformed or KC rejects it, the realm could be left in a half-applied
state. No backup is taken before the PUT.

**Fix**: `GET` the current config, save to a timestamped file, only
then `PUT` — and on PUT failure, log the backup path so the operator
can restore.

### M4. No CI wiring for cert sync

The script is a one-shot manual tool. After a Tomcat keystore
rotation, the operator has to remember to run it. This is exactly the
class of "forgot to run X after Y" failure that the script was meant
to prevent.

**Fix**: hook it into `start.sh` post-up phase; better, add a
self-check at Tomcat startup (`ServletContextListener`) that compares
its own keystore to the realm and either fixes or fails loudly.

### M5. No metrics / observability

Silent SSO success / failure rates, back-channel logout invocations,
front-channel iframe loads — none of these emit metrics. Operators
have to grep logs to know if the new auth flow is working in prod.

**Fix**: add a `MeterRegistry` (Micrometer) and emit:
`p1.silent_sso.success`, `p1.silent_sso.failure{reason}`,
`p1.backchannel_logout.killed{count}`, `p1.frontchannel.iframe.loaded`.

### M6. No automated tests

No JUnit tests for `OidcCallbackAction`, `SilentSsoAction`,
`BackChannelLogoutAction`. Future refactors can break the auth flow
silently — the only signal is a real user noticing they can't log in.

**Fix**: at minimum, unit-test the JWT payload extractor, the user
resolution chain, and the session-restore step against mocked
`UserManager`. Add an end-to-end integration test that runs the
silent SSO + back-channel logout flow against a Testcontainers-managed
Keycloak.

## 🟢 LOW

### L1. Heredoc + Python in cert sync script is fragile

Mixing bash and embedded Python heredocs makes the script hard to
read and easy to break. The earlier bug (Python reading from stdin
that bash already consumed) cost an iteration.

**Fix**: rewrite the whole thing in Python with `requests`. Same
~80 lines, less footgun surface.

### L2. The 5 lines of localhost:8888 prefix in `restoreLoggedUserSession`

```java
String target = "http://localhost:8888"
    + (returnTo == null || returnTo.isBlank() ? "/" : returnTo);
```

The `return_to` already has the path; we should prepend
`getServletRequest().getRequestURL()`'s scheme+host or an env var.

**Fix**: use `P1_BASE_URL` env, or build from the current request.

### L3. `WebContent/favicon-c1wealth.ico` + `logo_c1wealth.png` left untracked in geowealth

Not from these phases — likely leftover from another branch. Hygiene
issue: contributors may accidentally commit unrelated assets.

**Fix**: `.gitignore` or `git clean` the working tree.

### L4. `p1-first-broker-login Account verification options` etc. have stale aliases in realm-export

Pre-existing, not introduced here, but worth flagging: realm-export
contains 6 sub-flows with `p1-first-broker-login Account ...` aliases
that look auto-generated by an older KC version. Easier to read if
they're consolidated under a single flow with proper child entries.

### L5. `kc_sub` is stored in HttpSession but never used outside back-channel logout

It's a single use case; fine for now, but if more cross-tier coordination
features arrive (per-device session listing, "log me out everywhere"),
a proper session-store abstraction would scale better.

## Coverage summary

| Severity | Count | Items |
|---|---|---|
| 🔴 CRITICAL | 4 | C1–C4 |
| 🟠 HIGH | 7 | H1–H7 |
| 🟡 MEDIUM | 6 | M1–M6 |
| 🟢 LOW | 5 | L1–L5 |

The 4 critical items are blocking for any prod deploy. The 7 high
items are pre-prod hardening that should ship with the same release.
Medium and low items can land in follow-up sprints but should be on a
tracked backlog before the first customer touches the code.

## What we got right (so the report isn't all doom)

These changes do solve real bugs, and the choices behind them aren't
random:

- The silent OIDC re-auth pattern is industry-standard (it's how
  Okta and Auth0 implement check-session). Implementing it inside the
  IdP rather than relying on KC's built-in iframe is unconventional
  but correct given P1's federation role.
- The BroadcastChannel hook for cross-tab logout sync is a clean
  in-browser primitive — most apps reach for `storage` events, which
  is less reliable.
- The cert sync script, while having the production gaps listed
  above, encodes a runbook that was previously tribal knowledge. Even
  in its current form it's an improvement.
- Splitting the demo across two repos (keycloak-demo + geowealth)
  keeps the federation concerns separate from the wealth-management
  app, which is a sound architectural choice.
- The auth-flow scenario document captures the test matrix
  explicitly — pre-prod review can use it as the acceptance gate.

The critical issues are fixable, not fundamental. Two weeks of focused
work on C1–C4 + H1–H4 would bring this to a place where a real
security review wouldn't reject it outright.
