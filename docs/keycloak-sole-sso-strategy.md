# Keycloak as the sole SSO — migration strategy

**Question this report answers.** Today P1 is the authoritative
identity store and Keycloak federates from it via a SAML broker.
If we want Keycloak to become *the* identity provider for both
systems — P1 and the new MFE shell — what's the migration look like,
and what are the trade-offs?

This is an analysis, not a build plan. It surveys the options,
calls out the load-bearing technical decisions, and ranks the
risks. A concrete phased plan lives in §6; the numbers in there
are rough order-of-magnitude (days, not commitments).

---

## 1. Where we are today

```
                                   ┌──────────────────────┐
   User on localhost:5173 ─────────▶│  Keycloak demo-realm │
   (MFE Shell)                     │  (SP, federates from │
                                   │   p1 SAML IdP)       │
                                   └──────────┬───────────┘
                                              │ SAML AuthnRequest
                                              ▼
                                   ┌──────────────────────┐
   User on P1 portal ──────────────▶│  P1 (IdP + Apps)     │
                                   │  ├─ login.do form     │
                                   │  ├─ Argon2 hashes     │
                                   │  ├─ MFA email OTP     │
                                   │  ├─ IdpSsoAction      │
                                   │  └─ SAML Response     │
                                   └──────────────────────┘
```

- **Source of truth for credentials:** P1's `User` Hibernate entity, Argon2-hashed.
- **Source of truth for firm/role mapping:** P1's `Authorization` per-request decisions.
- **SAML wire format:** P1 signs the Response; Keycloak validates and creates
  a federated identity on first sign-in.
- **MFA:** email OTP, enforced in P1's `Login.confirmLogin` for browser flow.
- **Keycloak's role today:** SP that builds session cookies for the MFE shell.

The MFE shell at `localhost:5173` is **always** the SP. P1 has no
relationship with it. The two systems coexist because Keycloak
brokers between them.

---

## 2. Where we want to be

```
                                   ┌──────────────────────┐
   User on localhost:5173 ─────────▶│  Keycloak            │
   User on P1 portal ──────────────▶│  (sole IdP)          │
                                   │  ├─ user records     │
                                   │  ├─ password hashes  │
                                   │  ├─ MFA              │
                                   │  └─ OIDC/SAML out    │
                                   └──────────┬───────────┘
                                              │ assertion
                                              ▼
                                   ┌──────────────────────┐
                                   │  P1 (SP only)        │
                                   │  ├─ no login.do form │
                                   │  ├─ trusts Keycloak  │
                                   │  └─ builds session   │
                                   │     from assertion   │
                                   └──────────────────────┘
```

- **Keycloak holds:** username, email, password hash, firm/role
  attributes, MFA enrollment.
- **P1 keeps:** user *profile* (display name, CRM links, prefs) keyed
  by the same UUID Keycloak issues.
- **P1 stops doing:** the login form, the Argon2 verify, the MFA OTP,
  password reset, lockout/throttle. All of that moves to Keycloak.
- **Direction of trust flips.** Today P1 signs assertions for
  Keycloak. After: Keycloak signs assertions for P1. The SAML
  metadata exchange and certificate trust swap.

---

## 3. The two big technical decisions

### 3.1 OIDC or SAML to P1?

P1 already speaks SAML (it's currently the IdP, so the certificate
infrastructure, request parsing, and assertion building are all
already written and tested in the `com.geowealth.saml.idp.*`
package). **Repurposing that codebase as an SP** is cheaper than
introducing OIDC: most of the XML signing/parsing primitives are
the same library calls in reverse, and the existing test fixtures
cover the wire shape.

| | SAML to P1 | OIDC to P1 |
|---|---|---|
| Existing P1 code reuse | high (idp/* package, OpenSAML 5.x already on classpath) | none — pure new code path |
| Wire size | ~5KB signed XML | ~1KB JWT + small POST |
| Discovery | manual metadata exchange (file or URL) | well-known/openid-configuration |
| Refresh tokens | n/a — long-lived sessions only | possible (P1 keeps a short access token, refreshes from Keycloak) |
| Logout fan-out | SAML SLO already wired (Phase 10) | requires OIDC back-channel logout, more state to manage |

**Recommendation: keep SAML for the P1↔Keycloak link.** It's the
incumbent infrastructure on both sides. The MFE shell stays on
OIDC because that's what `keycloak-js` is built for. Keycloak
happily serves both protocols against the same realm.

### 3.2 What happens to the Argon2 password hashes?

P1's hashes are Argon2id with 3 iters / 64MB / 1 parallelism (see
`com.geowealth.model.entity.Argon2Hasher`). Keycloak 26 ships with
PBKDF2-SHA512 by default but exposes the `PasswordHashProvider` SPI
for custom algorithms. Three migration paths:

**(a) One-shot rehash on import.** Bulk-import users with their
existing Argon2 hash *unhashed* — impossible because we don't know
the plaintext. So this path requires every user to be force-reset
on first login. Big UX cost: ~all users have to reset at once.
Rejected.

**(b) Custom `PasswordHashProvider` that understands Argon2.** Ship
a tiny SPI jar in Keycloak that recognizes the P1 hash format, can
*verify* against an imported hash, and rehashes to Keycloak's
default algorithm on the next successful login. Users never notice.
**Recommended.** ~80 lines of Java + an Argon2 jar already on the
P1 classpath we can copy over. After ~3 months of natural login
traffic, the realm is on PBKDF2 and the Argon2 provider can be
retired. Pattern: same as Auth0/Okta's "gradual rehash on login."

**(c) Federate via P1 for verification, migrate over time.** Keep
P1 as the credential source initially (Keycloak User Storage SPI
calls P1 to verify). Then incrementally migrate users into Keycloak
native storage. **The "single-system feel" approach the user has
already asked about.** This is a transition state of (b), not a
permanent solution — useful as a stepping stone during the cutover
weekend.

The (c) → (b) path is the right shape:
1. Initial cutover: Keycloak hits a P1 `verify-credentials` REST
   endpoint (one to be added — analysis already done).
2. On every successful login, Keycloak also dumps the user's
   hash into its own table and switches that user to "native"
   storage from the next login onward.
3. After 3 months, anyone who hasn't logged in gets a forced
   password reset email; the P1 verify endpoint is then retired.

---

## 4. User attribute and role migration

Keycloak's `UserModel` carries:
- username, email, firstName, lastName, enabled flag (built-in)
- arbitrary `attributes` map (used today for `firmCd`)
- realm roles via `RoleMappingProvider`

P1's `User` carries roughly the same shape plus `crmId`,
`crmPrimaryEmail`, `mfaEmail`, etc. The mapping is straightforward:

| Keycloak field | P1 field | Notes |
|---|---|---|
| username | `User.userID` | unique, case-preserving |
| email | `User.emailAddress` (or `crmPrimaryEmail` fallback) | already used in JWT sub claim today |
| firstName | `User.givenName` | |
| lastName | `User.surname` | |
| attributes.firmCd | `User.crmId` | already wired as a protocol mapper |
| attributes.crmEmail | `User.crmPrimaryEmail` | optional, for downstream tools |
| realm roles | derived from P1's Authorization | see below |

**Roles are the awkward part.** P1's `Authorization` makes
per-request authorization decisions (per-page, per-firm,
per-account-id). Keycloak's role model is a *static* per-user set.
We'd need to define a coarse role set ("client", "advisor", "ops",
"admin") and map each P1 role bundle to one of those buckets. The
authoritative permission check still lives in P1 (where the
business rules are); Keycloak's role is only for *coarse* gating
(does this user see the admin MFE at all?). This is what the demo
already does with `client` / `user` / `admin`.

---

## 5. Session, MFA, logout

### Sessions
Today P1 owns the session for users on the P1 portal; Keycloak
owns the session for users on `localhost:5173`. After the cutover
Keycloak owns both — each application validates the assertion and
creates its own application-scoped session. Keycloak's session is
the master; SLO from Keycloak fan-outs to P1 and the MFE shell.

### MFA
Keycloak 26 ships:
- Email OTP (we already have a custom `EmailOtpAuthenticator` in
  `keycloak-provider/` that's nearly identical to P1's flow)
- TOTP (Google Authenticator / Authy)
- WebAuthn / passkeys
- Step-up authentication via `acr` claim

P1's MFA email OTP code can be retired entirely. We already have
the equivalent flow in Keycloak; users would re-enroll at first
login post-cutover (or we migrate the `mfa_secret` if it's
storage-compatible, which it isn't — P1 uses email-only OTP, no
shared secret).

### Logout
Keycloak SLO already works in the current direction (Phase 10).
Flipping the direction means P1 has to implement a
`/saml/logout` endpoint that accepts a signed `LogoutRequest`
from Keycloak and clears its session. Doable in the existing P1
SAML codebase — same OpenSAML primitives, opposite direction.

---

## 6. Phased plan (rough)

| Phase | Goal | Effort | Risk |
|---|---|---|---|
| **0 — Inventory** | Catalogue P1 users, firm/role distribution, password-reset eligibility, MFA-enrolled count. Decide the user-mapping rules. | 2-3 days | low |
| **1 — Argon2 hash provider** | Ship `keycloak-provider/Argon2HashProvider.java` — registers as a `PasswordHashProvider` for the `argon2` algorithm id. Verified locally with copy-pasted hashes from P1. No production impact yet. | 2 days | low |
| **2 — Bulk import** | One-shot dump of P1 user table → Keycloak realm export JSON (or admin REST POST). Hashes carried over as Argon2; users not pre-enrolled in Keycloak MFA. **Realm import wins** because volumes are preserved across `down + up`. | 1-2 days | medium — risk of duplicate users if data is dirty |
| **3 — P1 as SP, Keycloak as IdP (dark launch)** | Add an opt-in `?via=keycloak` query param on P1's login page. Behind it: redirect to Keycloak, accept signed assertion, build P1 session as if `LoginAction` had run. SAML metadata exchanged out of band. **Both flows live in parallel.** | 5-7 days (mostly P1-side SP code) | medium — wire-format bugs, session-cookie interplay |
| **4 — Cutover weekend** | Switch P1's default login flow to the Keycloak redirect. Old `LoginAction` stays disabled but reachable for ~4 weeks via a feature flag. Monitor login error rates. | 1 day + 2 weeks watch | high — this is the visible breakage point if anything's wrong |
| **5 — Retire P1 auth** | After ~3 months of natural rehash traffic, retire the Argon2 hash provider in Keycloak (algorithm migration complete). Delete `LoginAction`, `Argon2Hasher`, MFA email OTP code, password reset flow from P1. The P1 user-credential tables become a profile table only. | 2-3 days | low |
| **6 — MFE shell aligns** | The MFE shell at `localhost:5173` no longer needs the `p1` SAML broker. Users type credentials directly in Keycloak's login form. The "Sign in with P1" button disappears (it was always a transitional artifact). | <1 day | low |

Net effort: **~3 weeks of dev + a 3-month natural-decay window**
for the hash migration. The 3-week figure assumes one engineer with
familiarity on both sides; longer if knowledge is split.

---

## 7. Risks worth flagging up front

1. **Per-firm scoping.** P1's `User.userID` is unique *per firmCd*,
   not globally. Two firms could legitimately both have a `tim1`.
   Keycloak's `username` is realm-globally-unique. Options: prefix
   usernames with firmCd (`1:tim1`), use email as the unique
   identifier, or stand up one realm per firm. The third option
   is the safest for multi-tenant isolation but has the highest
   ops cost. Decision needed in Phase 0.

2. **`LoginActivityManager` lockout state.** P1 currently tracks
   bad-password attempts per username+firmCd. Keycloak has its own
   brute-force detector but the counter doesn't migrate. Users
   currently locked out of P1 would be silently unlocked at
   cutover. Mitigation: export the lockout state to Keycloak's
   `UserActionEvent` table, or accept the one-time silent reset
   (small window, low risk).

3. **CRM linkages.** P1's `crmId` is used for downstream
   reporting/notifications. The Keycloak protocol mapper for
   `crmId` (we already have one for `firmCd`) is straightforward,
   but every consumer downstream that expected a P1-issued
   identity needs to be re-pointed at the Keycloak JWT.

4. **MFA re-enrollment friction.** Cutover users have to re-enroll
   in Keycloak MFA on first login. Communicate ahead of time;
   pair the cutover with a "set up your authenticator" walkthrough.

5. **Operational ownership.** P1 currently owns user lifecycle
   (create/disable/reset). After cutover, that's Keycloak. Admin
   tooling — for ops to disable a compromised account, force a
   reset, look up MFA status — needs to be either Keycloak's
   built-in admin console (works for engineers, less so for
   non-technical ops staff) or a new internal admin app calling
   Keycloak's admin REST API.

6. **Rollback.** Once the cutover happens, P1's password hashes
   keep getting written by anyone who logs in via the legacy flag
   (Phase 4 fallback), so rolling back inside the 4-week window
   is mechanical. After the legacy flag is retired (Phase 5),
   rollback means restoring P1's hash table from backup and
   replaying the MFA enrollments — expensive but not impossible.

---

## 8. What this means for the current POC

The work already in this repo lines up:

- **`geowealth-keycloak/`** SPI: the LoginFormsProvider + branding
  pipeline are reusable as-is. Keycloak's login page becomes the
  P1 login page by virtue of the per-firm theme.
- **`keycloak-provider/`**: `EmailOtpAuthenticator` is already a
  drop-in replacement for P1's MFA email OTP. No new code needed
  on that surface.
- **`mfe-shell-client` realm wiring**: the OIDC client + JWT
  protocol mappers (`firmCd`, roles) are the template every other
  app would re-use. P1 becomes one more SAML client of the same
  realm.
- **`user-service`**: gone in the target state. The demo's three
  hardcoded users (`democlient`/`demouser`/`demoadmin`) become
  native Keycloak users on a fresh realm import.
- **P1 SAML IdP (`p1` alias) on demo-realm**: gone in the target
  state. The MFE shell talks to Keycloak directly; there is no
  broker. The "Sign in with P1" button disappears.

The POC is best read as Phase 3 of the migration: P1 as IdP,
Keycloak federating. The next phase is the inversion.

---

## 9. One-line recommendation

Start with Phase 1 (the Argon2 `PasswordHashProvider`) in a
sandbox realm. It's the smallest, lowest-risk, highest-information
step — it tells us in two days whether the hash migration is
feasible without committing to anything visible. Everything else
in the plan depends on that answer.
