# 08 — Questions and Answers

The questions below are the ones a Solution Architect is likely to ask
during the review. Each is paired with the substantive answer plus a
pointer back to the relevant section.

---

## A. Architecture choices

### A1. Why a Token Handler service at all? Why not have each domain BFF do its own OIDC?

A per-domain BFF doing its own OIDC means every domain runs duplicate
auth code (session, token refresh, back-channel logout, OIDC discovery,
JWKS caching). Every time a security fix lands in that code, every
domain rebuilds and redeploys, and every restart drops users' sessions.
Centralising it in one service that fronts every domain — the OAuth 2.0
BFF / *Token Handler* pattern — has three concrete payoffs:

1. **One image to rebuild for a shared-auth fix.** The data BFFs do not
   change, do not restart, do not log users out.
2. **One place to audit.** Login, refresh, and back-channel logout
   activity is one log stream, not N.
3. **One client to register at the IdP.** Adding a domain does not
   require a Keycloak client + secret + mapper triplet — only an
   `app.tenants.<slug>` entry in `application.yml`.

The pattern is documented in the IETF *OAuth 2.0 for Browser-Based Apps*
draft, and is what Curity calls the *Token Handler* in its reference
implementation. See [`01-architecture-overview.md`](01-architecture-overview.md) §1
for the full picture.

### A2. Why is the Token Handler multi-tenant by host header? Why not one Token Handler per domain?

A per-domain Token Handler would:

- Force a per-domain OIDC client (cookies are host-scoped, so each
  Token Handler needs its own redirect URI registered at KC).
- Push the cookie cleanup story onto the operator: each pod has its own
  `GWSESSION` and its own SID registry, so a back-channel logout has to
  fan out to N Token Handlers.

A multi-tenant Token Handler that resolves the tenant by
`X-Forwarded-Host`:

- Lets one OIDC client (`demo-shared-client`) serve every domain.
- Puts the SID registry in one place; one back-channel POST clears every
  affected session.
- Scales by HPA on the shared pod.

The per-host requirement (Tier-2 ObjectType / Permission) lives in the
shared `app.tenants.<slug>` map. See
[`02-component-inventory.md`](02-component-inventory.md) §2 and
[`07-security-and-trust-model.md`](07-security-and-trust-model.md) §6.

### A3. Why forward-auth instead of in-process security filters in the data BFF?

Three reasons:

1. **The data BFFs become auth-unaware.** A change to the auth code
   cannot accidentally break a data BFF endpoint, because the data BFF
   has no auth code to break. The endpoint contract is: "read identity
   from the `X-Auth-*` headers, do nothing if they are absent".
2. **The same nginx layer that terminates TLS already runs as the SPA's
   reverse-proxy.** Adding an `auth_request` directive there is a
   one-line config addition; the data BFF stays a normal HTTP server.
3. **It scales horizontally for free.** Multiple Token Handler replicas
   are stateless from the forward-auth perspective; the subrequest
   resolves through the Service's load-balancer.

The Kubernetes equivalent uses `nginx.ingress.kubernetes.io/auth-url` on
the Ingress, which carries the same contract — see
[`05-kubernetes-deployment.md`](05-kubernetes-deployment.md) §6.

### A4. Why Keycloak as broker rather than letting P1 expose OIDC directly?

P1 is a SAML IdP today. Adding an OIDC IdP to the same Tomcat app would
mean duplicating identity-issuance logic (token signing, JWKS, refresh
tokens, discovery) inside a product runtime that does not need to grow
that responsibility. Keycloak is purpose-built for this and is a
stateless broker over P1 — no native users are stored in `demo-realm`;
every login goes round-trip to P1 over SAML. The trade-off is one
additional HTTPS request per login, in exchange for:

- A standard OIDC interface every modern SPA can consume.
- Centralised role/attribute mapping (saml-role-idp-mapper × 8 plus the
  user-attribute mappers) that can change without touching P1.
- A consistent place to add a second IdP if the org ever needs one.

### A5. Why not store JWT tokens in the browser (localStorage / sessionStorage)?

JWTs in browser storage are exposed to any XSS in the SPA bundle. The
*Token Handler* pattern explicitly chooses an HttpOnly server-side
session cookie so a stolen storage entry is no longer a usable
credential. See [`07-security-and-trust-model.md`](07-security-and-trust-model.md) §1
(threat table) and §11 ("what is intentionally not implemented").

### A6. Why is Tier-3 row refine *inside* the data BFF and not in the Token Handler?

Tier-3 needs the row IDs. Only the data BFF knows them. Moving Tier-3 to
the Token Handler would either require it to fetch the rows itself (a
second round-trip) or to push every row ID up to the Token Handler in a
header (impractical for large result sets). The current split co-locates
Tier-3 with the data while keeping the Tier-2 authz answer cached in
the Token Handler so the per-call cost stays low — see
[`07-security-and-trust-model.md`](07-security-and-trust-model.md) §3 and
[`06-communication-diagrams.md`](06-communication-diagrams.md) §4.

### A7. Why is the SAML signing-credential cache mtime-based instead of TTL-based?

A TTL cache would still re-read the keystore every TTL period regardless
of whether the file changed, which is wasted I/O. mtime-based means: if
the file is rotated in place, the very next signature detects it; if it
is not, every signature uses the in-memory credential. The bonus
property — `lastModified() == 0` is treated as "file gone, keep cached
credential" — survives the macOS `/tmp` wipe scenario that initially
motivated the change (`AbstractSamlAuthenticationResponseBuilder.java:290–318`).
See [`07-security-and-trust-model.md`](07-security-and-trust-model.md) §4.1.

---

## B. Login and logout

### B1. What stops the silent SSO probe from looping forever?

A two-part guard:

- **Server side**: `SilentSsoAction.SESSION_KEY_ESTABLISH_DONE` flag on
  the P1 `HttpSession`. Once a successful establish has happened in this
  session, the server short-circuits any further establish to a 302
  back to `return_to` without bouncing through KC.
- **Client side**: a `silent_failed=1` hash fragment that the SPA only
  re-fires the probe when *absent* OR when `performance.getEntriesByType('navigation')[0].type === 'reload'`.
  A user reload (F5 / Cmd-R) is the escape hatch that lets the probe try
  again after a failed attempt.

A previous client-side `sessionStorage` flag was removed because a stale
flag on a long-lived tab silently suppressed the round-trip across
re-logins. See [`03-login-flows.md`](03-login-flows.md) §3.5 and the
inline comment at `geowealth/.../appService.js:213–222`.

### B2. What guarantees the user is fully logged out across every place state lives?

Three parallel cleanup paths converge:

1. **Token Handler → KC end-session.** Drives KC's back-channel fan-out:
   every other client subscribed to the SSO session gets a
   `/backchannel-logout` POST and, in P1's case, a SAML LogoutRequest
   via the browser.
2. **Token Handler → Redis.** Synchronously clears this session and
   drops the SID before returning the 303. This is what makes the
   endpoint **idempotent**: even a request that arrives during an
   active back-channel race ends with the local session gone.
3. **KC → P1 SAML LogoutRequest.** P1's `IdpSloAction` invalidates the
   request session **and** walks `GeowealthSessionListener` killing
   every session matching the inbound NameID (handles multi-tab P1).

If any one path fails (KC unreachable, P1 unreachable, etc), the others
still complete; the failed path is recoverable on the next attempt
(token refresh fails 4xx → session cleared anyway). See
[`04-logout-flows.md`](04-logout-flows.md) §4.1 and §4.6.

### B3. What is the upper bound on how long a P1-side logout takes to invalidate the SPA's session?

`VALIDATE_INTERVAL_SECONDS = 30` in `TokenRefreshFilter`. Every 30 s,
the next request to the Token Handler attempts a token refresh
unconditionally; if KC has ended the session, the refresh fails 4xx and
the session is dropped. So:

- If KC's back-channel logout reaches the Token Handler, invalidation is
  immediate.
- If it fails to reach the Token Handler (network issue, KC outage),
  invalidation is *at most 30 seconds late* because the next API call
  hits the refresh path.

### B4. What happens to in-flight requests when the user logs out?

The Token Handler's logout handler:

- Returns 303 immediately. In-flight requests on other tabs continue.
- The back-channel logout posts asynchronously; by the time the other
  tabs' next request hits `/auth/verify`, the SID-based invalidation has
  already removed the Redis session.

Worst case: an in-flight `/api/billing/invoices` started right before
logout returns its 200 with stale data (the user already has all the
identity they need from the forward-auth headers; the BFF then queries
P1 with the access token, which is still valid until the next refresh).
This is acceptable for the demo's domain; if a future product handles
sensitive operations that need stronger guarantees, the data BFF can
explicitly re-query the Token Handler before the response.

### B5. How does the realm differentiate between a user that has never logged in vs a returning user?

`p1-first-broker-login` flow runs on every SSO event. The first
execution looks up an existing `demo-realm` user by email; if found,
creates a federated identity link (idp=`p1`, sub=NameID); if not,
creates a new user *and* the link. Subsequent events skip the user
creation but still re-run the user-attribute mappers (because
`syncMode=FORCE`), so any attribute change at P1 propagates. See
[`03-login-flows.md`](03-login-flows.md) §3.4.

---

## C. Kubernetes

### C1. Why is `kc-ext` not just an Ingress rule?

It is *also* an Ingress rule (for the browser-facing path). The pod
exists because **the Token Handler running in-cluster also needs to
resolve and reach `auth.geowealth.int` over HTTPS** for OIDC discovery.
The Ingress controller terminates TLS only for traffic that comes from
outside the cluster; pod-originated traffic to `auth.geowealth.int`
needs an in-cluster HTTPS listener. That is what `kc-ext` is. A
production deployment with a real CA-signed cert that the JVM trusts
can simplify this — see [`05-kubernetes-deployment.md`](05-kubernetes-deployment.md) §5.

### C2. Why does `up.sh` patch a `hostAliases` entry instead of having a static manifest?

The `kc-ext` ClusterIP is only known after `kubectl apply` creates the
Service. There is no way to write that IP into a static manifest
because it does not exist at edit time. The runtime patch in `up.sh`
reads the ClusterIP after apply and writes it into the Token Handler's
`hostAliases`. The patch is idempotent — re-running `up.sh` re-applies
the same alias.

### C3. Why is `--import-realm` `IGNORE_EXISTING` and how do we apply realm changes later?

`--import-realm` only seeds an *empty* Postgres. If the realm already
exists, the import does nothing. This is the documented Keycloak
behaviour and is correct for non-destructive bring-ups.

To change the realm afterwards (URLs that differ per environment, new
roles, new mappers, …), the only non-destructive option is the
admin API. `scripts/reconcile-realm.sh <env-file>` does this
idempotently: it PATCHes the IdP SAML URLs and every active client's
URL lists. `up.sh` runs it after every apply, so a clean install and
any drift both converge. See [`05-kubernetes-deployment.md`](05-kubernetes-deployment.md) §2.

### C4. What happens when `data-tier.<env>.env` swaps from in-cluster to external Oracle?

`up.sh`:

1. After the `kubectl apply` re-creates the in-cluster ClusterIP
   Service, scales the in-cluster `oracle-0` StatefulSet to 0.
2. Deletes the freshly applied ClusterIP `oracle` Service.
3. Re-applies the `oracle` Service as
   `type: ExternalName, externalName: 192.168.1.42`.
4. If `oracle-config` or `oracle-creds` changed hashes AND the eleven
   P1 consumers pre-existed, rollout-restarts them so they pick up the
   new env.

Going the other way (external back to in-cluster) is the symmetric
operation: detect the stale `ExternalName` Service in the pre-apply
phase, delete it so the apply can re-create it as `ClusterIP`, scale
the StatefulSet back up.

### C5. What is the failure mode if Redis dies?

- **Existing sessions are evicted** if Redis is wiped (`appendonly yes`
  in the demo config means restart preserves them; a `redis-cli
  flushall` does not).
- **New logins cannot persist.** `/auth/me` after a successful code
  exchange would fail to write the session and the Token Handler would
  return 500.

Production deployments swap the single `redis-0` StatefulSet for a
managed Redis Cluster (AWS ElastiCache, GCP Memorystore) without any
application change — the Token Handler talks to it as a normal Redis
client.

### C6. What is the failure mode if `p1-devcommonagents` OOMs?

The agent CrashLoopBackOffs. `AuthorizationManager`, `BillingManager`,
and several other Managers live on it, so any Tomcat action that asks
those Managers a question dead-letters on `IdentifyFirmByUrlMsg`. The
user-facing symptom is a blank `localhost:8080` because the page
cannot resolve the firm context. Mitigation: the agent ships with
`-Xmx4G` and `limits.memory: 5Gi` to fit `CrntCostBasisLoader` against
a real `COST_BASIS_ACCOUNT_TBL`. See
[`05-kubernetes-deployment.md`](05-kubernetes-deployment.md) §3 and the
`CLAUDE.md` "agents are heap-sized for the baked PDB, not real data"
note.

---

## D. Security and operations

### D1. What is your defence against a stolen `GWSESSION`?

The cookie is HttpOnly and Secure, so it cannot be lifted by SPA-level
XSS and never traverses plain HTTP. SameSite=Lax stops it from being
carried on cross-site POSTs. Even if it were stolen, the access token
inside the corresponding Redis session has a 5-minute lifespan; the
refresh token grants new tokens only if the KC SSO session is still
alive, which dies in 7 hours idle / 10 hours max. So the practical
upper bound on misuse from a stolen cookie is "until the KC SSO session
expires or an admin force-logs the user out". An admin-side force
logout takes effect on the next refresh interval (30 s) on the Token
Handler.

### D2. Is `demo-shared-client` a shared secret across domains? What's the rotation story?

The client secret is bound to the *client*, not to a domain. Rotation is
an admin-API call:

```bash
curl -X PUT $KC/admin/realms/demo-realm/clients/<id>/client-secret \
     -H "Authorization: Bearer $ADMIN_TOKEN"
```

The Token Handler reads the secret from the `oauth.client-secret` Secret.
A rotation step is:

1. Update the secret in Keycloak via admin API.
2. Update the K8s Secret (`kubectl edit secret token-handler-oauth-secret`).
3. Rolling restart the Token Handler (sessions survive in Redis).

No domain BFF involvement. No user impact.

### D3. What does production add or change?

Concretely:

- The mkcert CA goes away; real CA-signed certs at the ingress.
- `kc-ext` may be removable if the Token Handler can trust the prod CA
  without an extra in-cluster HTTPS listener.
- Realm admin password is rotated; bootstrap default is replaced.
- `P1_IDP_KC_SP_CERT` is set so `IdpSloAction` enforces signed
  LogoutRequests.
- NetworkPolicy objects are added per the four-policy plan.
- Redis becomes a managed cluster.
- Oracle / ES are external (via `data-tier.prod.env`).
- A real CSI driver replaces `storage-provisioner`.
- An audit pipeline subscribes to KC events and the Token Handler's
  access log (or a future structured-audit emitter).

See [`07-security-and-trust-model.md`](07-security-and-trust-model.md) §10
and `production-risk-report.md` at the keycloak-demo repo root.

### D4. How would you add a second SAML IdP (e.g. for a partner organisation)?

Three additions, all entirely in Keycloak:

1. Register the new IdP in `realm-export.json` (alias `partner`,
   signing cert, SAML SSO/SLO URLs).
2. Add the IdP-side mappers (email, firmCd, roles) — the
   `partner-role-idp-mapper` set, modelled on the `p1-*` set.
3. Optionally add a per-IdP first-broker-login flow if linking semantics
   differ.

`kc_idp_hint=partner` on the authorize URL routes the new IdP. The
Token Handler does not change. The data BFFs do not change. The SPA
needs a new sidebar link if partner users have a deep-link entry.

### D5. What's the rationale for the 5-minute access token lifespan?

It is the standard "short-lived access token, long-lived refresh token"
trade-off. A stolen access token is useful for at most 5 minutes; the
refresh token, which never leaves the Token Handler, is the real
credential. A longer access token would reduce refresh load but extend
the window of misuse if it leaked. Five minutes is the Keycloak default
and matches industry guidance.

---

## E. Roadmap and trade-offs

### E1. What is the scaling ceiling of the current Token Handler design?

HPA bounds are 2–12 today. The bottleneck on the way up is *Redis* for
session reads/writes; one Token Handler pod can comfortably serve
several thousand concurrent sessions, and Redis Cluster scales beyond
that. CPU is dominated by JWT validation (RS256) and token refresh
(infrequent). The next ceiling above 12 replicas would be Keycloak
itself (its session-cache Infinispan cluster); production sizing is in
`k8s/README-scale.md`.

### E2. Could a third (or fourth) domain reuse this without code changes?

Yes. The recipe is in
[`05-kubernetes-deployment.md`](05-kubernetes-deployment.md) §11 and
the analogous compose recipe in `CLAUDE.md`. The only code change is
new domain controllers + stub data; the auth/session/logout layer is
untouched.

### E3. What is the migration path from the current single-Postgres / single-Redis to a managed substrate?

Postgres: `kc-postgres-0` swaps for `external-postgres` ExternalName
Service plus an `oracle-config`-style Secret with the JDBC URL and
credentials. Keycloak's `KC_DB_URL` reads from the Secret. No source
change.

Redis: same shape via the Token Handler's `redis.uri` env. Sessions
written before the swap are not migrated; one rolling restart of the
Token Handler means users re-authenticate.

A coordinated swap in a maintenance window: keep the old Token Handler
replicas serving until the new Redis is up, drain by HPA scale-down,
swap, scale back up.

### E4. Where does Confluence rendering fall short of these MD files?

Two known points:

- **Mermaid block size.** Confluence's Mermaid render has a max-size
  policy that can clip very large diagrams. The largest diagram here
  is the Level 2 container picture; if it clips, the recommended split
  is "identity tier + domain tier" and "P1 + data tier" as two sub-
  diagrams.
- **Anchor links between sub-pages.** GitHub auto-generates anchors
  per heading; Confluence's heading anchors are `h_<hex>`. The
  `[some link](04-logout-flows.md#section)` references in this set
  need to be re-pointed when the markdown is uploaded — Confluence's
  Markdown import preserves the link **target** filename but converts
  the heading anchors. Re-link via the Confluence editor after
  upload.
