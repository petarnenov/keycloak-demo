# 08 — Questions and Answers

The questions below are the ones a Solution Architect is likely to ask
during the review. Each is paired with the substantive answer plus a
pointer back to the relevant section. The set is updated for v2 — the
auth-extraction refactor changed enough that several of v1's Q&A
entries no longer apply, and several new ones do.

---

## A. Architecture choices

### A1. Why retire the SAML federation between Keycloak and P1?

Three reasons, in order of weight:

1. **Tangled responsibility.** P1's role as a SAML IdP put credential
   verification, MFA token storage, session minting, and SAML signing
   inside the same JVM that runs portfolio math, the Akka cluster, the
   `CrntCostBasisLoader` in-memory cache, and the Struts action graph.
   Any failure mode of the auth path was indistinguishable from any
   failure mode of the business code. Extracting auth into `user-service`
   plus the KC SPI lets each be scaled, deployed, and reasoned about
   independently.
2. **No horizontal scaling of P1.** The SAML signing keystore lived on
   local `/tmp` per replica; the SAML session establish/silent round-trip
   assumed sticky session affinity. Adding a second P1 replica was a
   non-starter. With the OIDC RP flow plus Redisson-backed Tomcat
   sessions plus the Redis `kc_sub` index, P1 now scales horizontally
   (Phase 7, HPA 1–5).
3. **The federation hop was overhead.** The demo's other domains
   (`bff-billing`, `bff-trading`) were already OIDC RPs behind a shared
   Token Handler; P1 was the odd one out. Standardising on OIDC removes
   one entire class of code (XMLDSig, `InResponseTo` correlation,
   binding-aware signature parsers) and one entire class of operational
   knowledge (rotating `/tmp/p1-idp-dev.p12`, validating against
   `KeycloakSpCert`).

See [`docs/plans/2026-06-26-auth-extraction.md`](../../docs/plans/2026-06-26-auth-extraction.md)
for the full motivation; the executive summary in
[`00-executive-summary.md`](00-executive-summary.md) compresses it to a
table.

### A2. Why a separate `user-service` instead of an in-process KC user federation?

The SPI provider could in principle embed an Oracle JDBC pool and run the
JDBC reads directly. We chose to put `user-service` in front for three
reasons:

1. **Independent scaling.** The JDBC pool size, the read-replica routing
   decision, and the legacy SHA1 (and future bcrypt) verification logic
   all want to scale on a different axis than Keycloak's session-handling
   does. A separate pod cleans that up.
2. **Standalone reusability.** A future write-path admin tool, an LDAP
   shim, or a service-account verifier can call `user-service` directly
   without dragging in the Keycloak JVM.
3. **Smaller KC image.** The SPI JAR is a thin HTTP client (no JDBC
   driver, no JSON library — just JDK `HttpClient` plus a tiny
   `MiniJson` parser). The custom KC image is ~20 MB larger than stock
   instead of significantly bigger.

The cost is one extra in-cluster network hop per cache-miss. KC's SPI
cache (`EVICT_DAILY`) makes that hop infrequent — the typical login is
*one* `getUserByUsername` + *one* `verifyCredentials`, both inside the
same TCP connection.

### A3. Why an email-OTP custom authenticator instead of KC's built-in OTP / TOTP?

The existing P1 user base authenticates via 6-digit codes delivered by
email and stored in `ENTITY_TBL.MFA_TOKEN`. Switching to TOTP would
require every MFA-using user to re-enrol with an authenticator app
(Google Authenticator, etc.) — a coordinated user-side migration we
explicitly want to avoid as part of this refactor.

The custom `EmailOtpAuthenticator` preserves the exact contract: 6-digit
random code, SHA1-hashed into `MFA_TOKEN`, 5-minute expiry, delivered by
email. user-service owns the token-issue / token-verify endpoints; KC's
`EmailSenderProvider` delivers the message. Conditional execution on the
realm flow ensures only users with `mfaRequiredFlag = true` see the OTP
step.

### A4. Why is the Token Handler unchanged?

The Token Handler / BFF pattern remains the right shape for the demo
SPAs: tokens stay server-side, the SPA holds only an HttpOnly cookie,
the data BFFs stay auth-unaware. The auth-extraction refactor changed
**where authentication happens** (user-service via KC SPI, not P1 via
SAML), but it did not change **how the SPA's session works**. The
Token Handler retained its multi-tenant Redis-backed shape; that is
why v1's §05 (Kubernetes) and §06 (Communication diagrams) for the
domain tier read the same in v2.

### A5. Why does P1 use PKCE if it's a confidential client?

Strictly, PKCE is not required for a confidential client (the client
secret already authenticates the token exchange). We use it anyway for
two reasons:

1. **Defence against authorization-code injection.** A confidential
   client is still vulnerable if an attacker can substitute their own
   code into the callback URL of a victim's browser tab. PKCE binds the
   code to a verifier the legitimate `OidcLoginAction` generated; the
   token exchange fails without that verifier.
2. **Cross-pod safety for free.** The Redis-backed `OidcStateStore`
   keeps the verifier and the `state` together. Replica A handles the
   login redirect; replica B handles the callback. Either way the
   `state → verifier` lookup works.

### A6. Why is the SAML signing-credential cache code still in the P1 tree?

Dead code that the refactor left in place because removing it cleanly
would have grown the diff. It is unreachable from any live Struts
action (`struts-saml-idp.xml` no longer registers the SAML IdP actions
that referenced it). A follow-up cleanup commit can delete it once the
auth-extraction PR train has fully merged. The realm has no SAML IdP,
no Tomcat action serves SAML AuthnRequests; the dead code does no harm
in the meantime.

### A7. Why is the realm export `identityProviders: []` instead of having the SPI listed there?

`identityProviders` is the SAML / OIDC **broker** section — federations
to *external* IdPs. The User Storage SPI is a **user federation**, a
different realm concept entirely; it appears under the realm's
`components` block as `org.keycloak.storage.UserStorageProvider` with
`providerId: user-service-spi`. The two concepts are sometimes confused
in Keycloak documentation because both involve "external users"; in
practice they are entirely different SPI hierarchies.

---

## B. Login and logout

### B1. What stops a brute-force attack against `user-service`'s verify-credentials?

Today: nothing — `user-service` accepts unbounded calls. Mitigation
levels:

1. **KC-side rate limiting.** Keycloak's brute-force-detection feature
   can be enabled on the realm; with it on, KC stops sending
   `isValid()` calls past a threshold and locks the user account.
2. **`user-service` `loginAttempts` counter.** `ENTITY_TBL` has a
   `LOGIN_INACTIVATED_FLAG` column we already honour; a follow-up MD
   adds a `LOGIN_FAILED_COUNT` increment + auto-lockout, mirroring the
   `LoginActivityManager` P1 already had.
3. **NetworkPolicy.** Today no policy blocks anything inside the
   namespace from calling user-service. The policy in
   [`05-kubernetes-deployment.md`](05-kubernetes-deployment.md) §8 caps
   `user-service` ingress to `keycloak` only.

Both mitigations are listed under known production gaps in
[`07-security-and-trust-model.md`](07-security-and-trust-model.md) §10.

### B2. What guarantees the user is fully logged out across every place state lives?

Two parallel cleanup paths converge:

1. **Token Handler → KC end-session.** Drives KC's back-channel fan-out:
   `logout_token` POSTs to every subscribed client (Token Handler itself
   and `p1-client`).
2. **Token Handler → Redis.** Synchronously clears the local session
   before the 303 returns. This is what makes the endpoint idempotent.

The KC fan-out covers:

- **The Token Handler's siblings**: a different domain's `GWSESSION` on
  the same `demo-shared-client` is invalidated by the `bff:sid:<sid>`
  SET lookup.
- **P1's Tomcat sessions**: `OidcBackChannelLogoutAction` validates the
  `logout_token`, calls `P1RedisKcSubIndex.invalidate(sub)`, and that
  drops every Redisson session bound to that `kc_sub` across every
  `p1-tomcat` replica.

If any one path fails (KC unreachable, P1 unreachable, etc), the others
still complete; the failed path is recoverable on the next attempt
(token refresh fails 4xx → session cleared anyway). See
[`04-logout-flows.md`](04-logout-flows.md) §4.1 and §4.6.

### B3. What is the upper bound on how long an admin-side logout takes to invalidate the SPA's session?

Same as v1: `VALIDATE_INTERVAL_SECONDS = 30` in `TokenRefreshFilter`.
Every 30 s, the next request attempts a token refresh; if KC has ended
the session, the refresh fails 4xx and the session is dropped. So:

- If KC's back-channel logout reaches the Token Handler, invalidation is
  immediate.
- If it fails to reach the Token Handler (network issue, KC outage),
  invalidation is **at most 30 seconds late** because the next API call
  hits the refresh path.

### B4. What happens to in-flight requests when the user logs out?

Unchanged from v1. The Token Handler's logout handler returns 303
immediately. In-flight requests on other tabs continue. The back-channel
logout posts asynchronously; by the time the other tabs' next request
hits `/auth/verify`, the SID-based invalidation has already removed the
Redis session.

Worst case: an in-flight `/api/billing/invoices` started right before
logout returns its 200 with data the user could already see. This is
acceptable for the demo's domain.

### B5. How does the realm differentiate between a user that has never logged in vs a returning user?

It does not, by design. Every login is a fresh SPI lookup
(`getUserByUsername`) plus a fresh credential check
(`verifyCredentials`). The realm has no federated identity row; there is
nothing to "link" because the user is read-through, not imported.

The implication: a column update on `ENTITY_TBL` (a role grant, a firm
change, a flag flip) is visible to KC at the next user-cache miss
(`EVICT_DAILY` by default). There is no `syncMode=FORCE` ceremony to
force a refresh — the cache TTL drives it.

### B6. Why is there still a "silent-first" login variant in the Token Handler?

The reason is unchanged from v1, just simplified. The variant exists so
a return visit on a host that has no `GWSESSION` cookie but KC SSO is
alive (the cross-domain SSO scenario, flow 3.6) completes with no UI
flash. The silent variant adds `prompt=none` to the authorize URL; KC
returns a code if its SSO session is alive, or `error=login_required`
if not, and the Token Handler's `loginFailed` controller upgrades to
interactive. The `IdpHintFilter` is still in the codebase but now a
no-op — there is no IdP to hint at.

---

## C. Kubernetes

### C1. Why is `kc-ext` not just an Ingress rule?

Unchanged from v1. It is *also* an Ingress rule for the browser-facing
path. The pod exists because **both the Token Handler and the new
`p1-tomcat` OIDC RP** need to resolve and reach `auth.geowealth.int`
over HTTPS for OIDC discovery from inside the cluster. The Ingress
controller terminates TLS only for traffic coming from outside the
cluster; pod-originated traffic to `auth.geowealth.int` needs an
in-cluster HTTPS listener. That is `kc-ext`.

### C2. Why does `up.sh` patch a `hostAliases` entry instead of having a static manifest?

Unchanged from v1, with one expansion: the hostAlias is now applied to
**both** the Token Handler and `p1-tomcat` Deployments, because both do
OIDC discovery against the public KC issuer.

### C3. Why is `--import-realm` `IGNORE_EXISTING` and how do we apply realm changes later?

Unchanged from v1. `--import-realm` only seeds an empty Postgres. To
change the realm afterwards, the only non-destructive option is the
admin API. `scripts/reconcile-realm.sh <env-file>` does this
idempotently. The reconciliation surface shrank in v2: no more IdP SAML
URL patches, just per-client `redirectUris` / `webOrigins` /
`post.logout.redirect.uris` / `backchannel.logout.url`.

### C4. What happens when `data-tier.<env>.env` swaps from in-cluster to external Oracle?

Same shape as v1, with one new consumer rotation: `user-service` now
gets rollout-restarted on the same trigger as the eleven P1 workloads
that read from Oracle, because user-service also has Oracle env in its
config.

### C5. What is the failure mode if Redis dies?

Worse than v1, because Redis now carries three classes of state.

- **Existing Token Handler sessions are evicted** if Redis is wiped.
- **Existing P1 Tomcat sessions are evicted** for the same reason —
  Redisson keys are gone, every P1 tab gets `JSESSIONID` → no session
  → 302 to `/oidc/login.do`.
- **New logins cannot persist** on either side.
- **The L2 cache** is rebuilt by P1 at the next miss, no user impact
  beyond a brief latency bump.

`appendonly yes` in the demo config means a Redis restart preserves
sessions; a `redis-cli flushall` does not. Production deployments swap
the single `redis-0` for a managed Redis Cluster.

### C6. What is the failure mode if `user-service` dies?

A new failure mode in v2:

- **New logins fail** at the SPI lookup or the credential check.
  Keycloak's authentication flow returns "invalid credentials" or a
  generic error to the user.
- **Existing sessions keep working.** The Token Handler's session and
  the P1 Tomcat session both hold the access/refresh tokens; token
  refresh against KC does not touch user-service.
- **KC SPI cache (`EVICT_DAILY`) survives**: an active session's
  `UserModel` is in the in-process cache; subsequent introspection
  calls do not re-query user-service.

The two-replica deployment + readiness probes mean a node failure is
a few seconds of slow logins, not a full outage.

### C7. Why is `p1-tomcat` now horizontally scaled?

The two prerequisites went in as Phase 6 (Redisson Tomcat session
manager) and the `P1RedisKcSubIndex` change. With those in place, a
`p1-tomcat` Deployment can run N replicas safely:

- Any request can land on any replica because the session is in Redis.
- A back-channel logout received on one replica invalidates sessions
  living on every other replica via the shared Redis `kc_sub` index.
- The Akka cluster does not care — `p1-tomcat` is an Akka client, not
  a cluster member. Akka tolerates multiple clients trivially.

Phase 7 of the auth-extraction plan flipped `replicas: 1` to an HPA
with `min: 1, max: 5, target CPU 70%`. The agents (`p1-coordinator`,
the eight Manager agents) remain single-seed; Akka multi-replica
agent topology is an out-of-scope follow-up.

### C8. What is the new `monitoring` namespace for?

`kube-prometheus-stack` Helm install (Prometheus + Grafana +
Alertmanager + node-exporter + kube-state-metrics + the Prometheus
Operator). Installed by `k8s/monitoring.sh`, configured via
`k8s/monitoring-values.yaml`. The demo-side rules
(`k8s/base/redis-alerts.yaml`) ship eleven Redis-specific alerts (down,
memory high, eviction, slow command, AOF rewrite stuck, etc.) and live
in the demo namespace; the Prometheus instance picks them up because
the helm install sets
`serviceMonitorSelectorNilUsesHelmValues=false`.

`k8s/portforward.sh` is an idempotent persistent port-forward manager
for the development UX (Grafana on `:3000`, Prometheus on `:9090`,
Alertmanager on `:9093`, Redis/Postgres exporters on `:9121`/`:9187`).

---

## D. Security and operations

### D1. What is your defence against a stolen `GWSESSION`?

Same as v1. The cookie is HttpOnly and Secure, so it cannot be lifted
by SPA-level XSS and never traverses plain HTTP. SameSite=Lax stops it
from being carried on cross-site POSTs. Even if it were stolen, the
access token inside the corresponding Redis session has a 5-minute
lifespan; the refresh token grants new tokens only if the KC SSO
session is still alive (10 h max). An admin-side force logout takes
effect on the next refresh interval (30 s) on the Token Handler.

### D2. What is your defence against a stolen `p1-client` OIDC secret?

The secret never leaves the `p1-tomcat` pod (mounted via K8s Secret,
never logged). Rotation is an admin-API call:

```bash
curl -X PUT $KC/admin/realms/demo-realm/clients/<id>/client-secret \
     -H "Authorization: Bearer $ADMIN_TOKEN"
```

then update the K8s Secret and rolling-restart `p1-tomcat`. Sessions in
Redis survive the restart.

A leaked `p1-client` secret would let an attacker mint OIDC code
exchanges against KC impersonating P1. The PKCE check still protects
against that: the attacker does not have the verifier matching any
in-flight `state`.

### D3. What does production add or change?

Concretely:

- **NetworkPolicy** on the `KC → user-service` hop (and on every other
  edge listed in
  [`05-kubernetes-deployment.md`](05-kubernetes-deployment.md) §8).
- **mTLS** on the same hop (via service mesh).
- **Bcrypt rehash-on-login** for the legacy SHA1 passwords.
- **Brute-force protection** on `user-service`'s verify-credentials
  (KC realm brute-force-detection + `user-service`-side counter).
- **Real CA certs** at the ingress; `kc-ext` may be removable if KC's
  hostname is reachable directly through ingress with a JVM-trusted cert.
- **Realm admin password rotation**.
- **Redis as a managed cluster** (ElastiCache, GCP Memorystore, …).
- **Oracle and Elasticsearch external** via `data-tier.prod.env`.
- **A real CSI driver** replacing `storage-provisioner`.
- **An audit pipeline** subscribing to KC events plus a structured
  audit emitter on the Token Handler side.

See [`07-security-and-trust-model.md`](07-security-and-trust-model.md) §10
and `production-risk-report.md` at the keycloak-demo repo root.

### D4. How would you add a second OIDC client (e.g. for a partner organisation's tooling)?

One addition, entirely in Keycloak via admin API:

1. Create the new client in `realm-export.json` (and PATCH it onto the
   live realm) with `redirectUris`, `webOrigins`,
   `post.logout.redirect.uris`, `backchannel.logout.url`.
2. Add the same `firmCd-claim` / `memberships-claim` / `roles-claim`
   protocol mappers if the client needs those claims.

The Token Handler does not change. The data BFFs do not change. The
SPAs do not change. The new client gets the same SPI-backed user model
because the realm only has one user federation, and every client sees
the same realm-level set of users.

### D5. What's the rationale for the 5-minute access token lifespan?

Unchanged from v1. Short-lived access tokens limit the misuse window of
a leaked token; the refresh token, which never leaves the Token Handler
or P1's Tomcat session, is the real credential. Five minutes is the
Keycloak default and matches industry guidance.

### D6. What rotates if the SHA1-to-bcrypt migration ships?

The migration would be a `user-service`-only change:

1. Add a `LDAP_PSWD_HASH_ALGO` column to `ENTITY_TBL`.
2. `user-service` runs a dual-verifier: try bcrypt first (if column says
   `bcrypt`), fall back to SHA1.
3. On a successful SHA1 login, transparently rehash to bcrypt and write
   back, flipping the column.
4. Over time the column converges to `bcrypt` for every active user;
   inactive rows can be force-rehashed on a scheduled job.
5. Eventually drop the SHA1 branch.

No KC change, no SPI change, no realm change. The contract between KC
and `user-service` is `verify-credentials` returns `{valid: true|false}`
regardless of which algorithm matched.

---

## E. Roadmap and trade-offs

### E1. What is the scaling ceiling of the current Token Handler design?

Unchanged from v1: HPA bounds are 2–12 today; the bottleneck on the way
up is Redis for session reads/writes. The new ceiling consideration in
v2 is that **`p1-tomcat` and the Token Handler now share the same
Redis** for sessions, so a sizing exercise that worked for v1 (Token
Handler alone) must factor in P1's session count too. Production
sizing notes live in `k8s/README-scale.md`.

### E2. Could a third (or fourth) domain reuse this without code changes?

Yes, same as v1. The recipe is in
[`05-kubernetes-deployment.md`](05-kubernetes-deployment.md) §11. The
new domain inherits the same realm, the same user federation, and the
same role vocabulary — it just needs an `app.tenants.<slug>` entry, a
host on `demo-shared-client`, and an ingress rule.

### E3. What is the migration path from the current single-Postgres / single-Redis to a managed substrate?

Same as v1 for Postgres and Redis:

- **Postgres.** `kc-postgres-0` swaps for `external-postgres` ExternalName
  Service plus an `oracle-config`-style Secret with the JDBC URL and
  credentials. Keycloak's `KC_DB_URL` reads from the Secret.
- **Redis.** Same shape via the Token Handler's `REDIS_URI` env and
  P1's `REDIS_HOST` / `REDIS_PORT`. Sessions written before the swap
  are not migrated; one rolling restart of both consumers means
  users re-authenticate.

A coordinated swap in a maintenance window is the recommended path.

### E4. Where does Confluence rendering fall short of these MD files?

Same two known points as v1:

- **Mermaid block size.** Confluence's Mermaid render has a max-size
  policy that can clip very large diagrams. The largest diagram here
  is the Level 2 container picture in
  [`06-communication-diagrams.md`](06-communication-diagrams.md) §2;
  if it clips, the recommended split is "identity tier + domain tier"
  and "P1 + data tier" as two sub-diagrams.
- **Anchor links between sub-pages.** GitHub auto-generates anchors per
  heading; Confluence's heading anchors are `h_<hex>`. Re-link via the
  Confluence editor after upload.
