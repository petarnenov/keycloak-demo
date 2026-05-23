# Keycloak in this stack — what it does, why it's here, what else could do it

**Question this report answers.** A reader looking at this repo
for the first time would reasonably ask: *what is Keycloak even
doing here?* This document grounds the answer in the actual code,
calls out the problems Keycloak is solving (not the buzzwords),
weighs the pros and cons, and surveys the industry alternatives
that could replace it. Pure analysis — no code change.

The companion document, `keycloak-sole-sso-strategy.md`, covers
the *forward-looking* question (what if Keycloak becomes the only
SSO). This one is about *today* — why we picked Keycloak in the
first place, and what we'd evaluate if we were starting over.

---

## 1. Where Keycloak sits in this topology

```
┌─────────────────────────────────────────────────────────────────┐
│  Browser at localhost:5173 (MFE Shell, mfe-shell-client)        │
└────────────────────────┬────────────────────────────────────────┘
                         │ OIDC Authorization Code + PKCE
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│              Keycloak demo-realm (localhost:8898)               │
│  ┌───────────────┐  ┌──────────────────┐  ┌──────────────────┐  │
│  │ OIDC issuer   │  │  Login page      │  │  Custom SPIs:    │  │
│  │  /token /jwks │  │  (FreeMarker     │  │   demo-user-prov │  │
│  │  /userinfo    │  │   theme)         │  │   email-otp      │  │
│  └───────────────┘  └──────────────────┘  │   p1 SAML broker │  │
│  ┌───────────────┐  ┌──────────────────┐  │   geowealth-     │  │
│  │ JWT mappers   │  │  Realm Postgres  │  │     branding     │  │
│  │  firmCd/email │  │  (users,         │  └──────────────────┘  │
│  │  /given/...   │  │   sessions,      │                        │
│  └───────────────┘  │   fed identities)│                        │
│                     └──────────────────┘                        │
└────────────┬──────────────────────┬─────────────────────┬───────┘
             │ REST                 │ REST                │ SAML
             ▼                      ▼                     ▼
┌─────────────────────┐  ┌───────────────────┐  ┌──────────────────┐
│ user-service:8090   │  │ branding-api:8080 │  │  P1 IdP (Tomcat) │
│ (3 demo users)      │  │ (whitelabel data) │  │  (Argon2 +       │
│ NO_CACHE SPI        │  │  cache TTL 60s    │  │   MFA email OTP) │
└─────────────────────┘  └───────────────────┘  └──────────────────┘
                                                         │ OIDC
                                                         ▼
                                        ┌────────────────────────────┐
                                        │ bff-client/ops/admin       │
                                        │ (JWT validation, role gate)│
                                        └────────────────────────────┘
```

Three independent identity sources feed one realm:

| Source | How Keycloak reaches it | What it provides |
|---|---|---|
| **user-service** (`demo-user-provider` SPI) | REST per request, `NO_CACHE` | `democlient`, `demouser`, `demoadmin` |
| **P1 SAML IdP** (`p1` IdP alias) | SAML 2.0 browser-redirect flow | every GeoWealth user (e.g. `tim1`) |
| **Native Keycloak** | nothing — local Postgres | the admin user, federated identities created on first SAML login |

Every browser-facing application — the MFE shell, the BFFs, the
geowealth-poc app — only knows about Keycloak. It validates JWTs
issued by `http://localhost:8898/realms/demo-realm` and is
deliberately unaware of where the user *actually* came from.

---

## 2. What Keycloak is concretely doing here

Stripped of the marketing, Keycloak in this repo does seven things:

### 2.1 Token issuance (OIDC)
Implements OAuth 2.0 + OpenID Connect Core 1.0. Exposes the
standard endpoints (`/protocol/openid-connect/auth`, `/token`,
`/userinfo`, `/logout`, `/.well-known/openid-configuration`,
`/certs` for JWKS). Signs JWT access tokens with RS256 by default.
The MFE shell uses **Authorization Code + PKCE (S256)** — the
modern SPA pattern that doesn't need a client secret.

### 2.2 Federation hub
The SAML broker takes a signed Response from P1, validates the
certificate chain, extracts attributes, and either creates or
matches a federated identity in Keycloak's user table. The MFE
shell never sees SAML — only JWT.

### 2.3 Login page (UI + theming)
Hosts the username/password form, the email OTP form, the broker
selection UI ("Sign in with P1"), the forgot-password flow. The
mfe-shell theme is a FreeMarker override of `keycloak.v2`, plus a
per-firm CSS-variable injection driven by the branding API.

### 2.4 Session + cookie management
Owns the `KEYCLOAK_SESSION_*` cookies, refresh tokens, idle
timeout, max lifespan, single-logout fan-out to every active
client. The MFE shell, the BFFs, and P1 all defer to whatever
Keycloak says is true.

### 2.5 Brute-force / lockout
Built-in detector counts failed logins, locks accounts, exposes
the state to admin UI. We don't use it in this POC but it's
sitting there.

### 2.6 Admin API
A 400+-endpoint REST surface for everything (`/admin/realms/...`).
The CLAUDE.md role-matrix test and the geowealth-realm import
both lean on it. Without an admin API, a project this size would
need a bespoke admin UI for every change.

### 2.7 Standards compliance
JWT shape, claim names (`sub`, `iss`, `aud`, `exp`, `iat`,
`auth_time`), PKCE, nonce, state, ID token vs access token
separation, JWKS rotation — all per RFC. A consumer that knows
"OIDC" knows how to talk to it without reading any Keycloak docs.

---

## 3. The problems Keycloak is solving

In order of "what would break first if we removed it":

1. **A single trust boundary for every browser app.** Without
   Keycloak, every MFE / BFF / app would need to talk SAML
   to P1 directly. That's three SAML SP implementations to
   write, three pieces of certificate plumbing to maintain,
   three places to chase a signing bug.

2. **Translating SAML → JWT.** The MFE world is JWT-native
   (`keycloak-js`, OIDC discovery, JWKS rotation). P1 speaks SAML.
   Without a broker, every MFE would carry an XML parser. With
   Keycloak in the middle, the MFEs only ever see JSON.

3. **A place to hang custom auth flows.** The email OTP step
   (`demo-email-otp` authenticator) is a Keycloak SPI; the flow
   composition (`auth-username-password-form` → `demo-email-otp`)
   is a realm-level config. Without Keycloak we'd need to invent
   our own state machine for "username → password → OTP → done."

4. **Per-firm theming + branding pipeline.** The
   `GeoWealthLoginFormsProvider` SPI exists because Keycloak gives
   us a stable extension point. A bespoke login page would need
   its own templating + theming infrastructure.

5. **Standards-compliant token format.** Every server-side library
   in the world (Spring Security, Micronaut Security, Passport.js,
   `python-jose`, …) knows how to validate a Keycloak JWT because
   it's just a standard JWT signed with the issuer's JWKS. We get
   N×M interop for free.

6. **A single place to apply policy.** Disabling a user, forcing
   password reset, enrolling MFA, rotating signing keys — all in
   one admin surface. Without it, every app would need a custom
   admin UI.

7. **Session management.** Refresh tokens, sliding session
   expiry, SSO across all clients in the realm. The shell at
   `localhost:5173` and the geowealth-poc at `changepath.localhost:5174`
   share a session because they share a realm — that's a Keycloak
   feature, not application code.

---

## 4. Pros

- **Free and self-hosted.** Apache 2.0 license. No per-MAU bill.
  The whole demo runs in a single container with a Postgres
  sibling.
- **Standards-first.** Every protocol it speaks is an RFC. No
  vendor extensions in the JWT shape, no proprietary cookies in
  the redirect chain.
- **Extensible via SPI.** The five custom SPIs in this repo
  (user storage, authenticator, login forms, theme selector,
  identity-provider mapper) all hang off documented extension
  points that have been stable since Keycloak 19. The SPI API
  is the *product* — that's a strong stability signal.
- **Rich admin surface.** Web console + REST API + CLI cover the
  same operations. Tooling and IaC are easy.
- **Active project.** Red Hat–sponsored, large contributor base,
  predictable quarterly releases.
- **Federation breadth.** Out of the box: SAML, OIDC, LDAP/AD,
  GitHub, Google, Facebook, X, SSO via Kerberos. Adding a new
  IdP is a config click, not a code change.
- **Strong protocol coverage.** OAuth 2.0, OIDC, SAML 2.0,
  WebAuthn/FIDO2, OAuth 2.1 in progress. Few competitors cover
  this full surface.

## 5. Cons

- **Operational footprint.** Java + Postgres + JVM heap tuning +
  cluster mode (Infinispan) when scaling out. The 4 GB mem_limit
  on the Keycloak container in `docker-compose.yml` is not
  accidental.
- **Cold start latency.** ~7-9 seconds in this demo (visible in
  `start.sh`). Production with realm import takes longer.
- **`--import-realm` semantics.** `IGNORE_EXISTING` means a realm
  JSON edit doesn't take effect until you wipe the database.
  That's confusing the first time you hit it and an entire
  section of CLAUDE.md exists to warn about it.
- **SPI breakage on majors.** The internal SPI signature gets
  flagged by Keycloak itself on every startup
  (`KC-SERVICES0047: ... is implementing the internal SPI`),
  which is the developers' way of saying "we may change this."
  In practice it's stable across minors, but each major upgrade
  needs SPI smoke tests.
- **Theme system is FreeMarker.** Not a deal-breaker, but it's a
  templating engine SPAs developers don't usually know. Our
  Phase 14 pixel work is several hundred lines of CSS + .ftl
  edits that would have been a couple of React components in a
  modern stack.
- **No first-class multi-tenant model.** Multi-tenancy is "use
  multiple realms" (heavy, separate admin) or "use one realm and
  hand-roll the per-tenant logic" (what we're doing). Auth0 and
  Cognito handle this nicer.
- **Documentation is uneven.** The reference docs cover happy
  paths well; SPI development and edge-cases require reading
  source. The CLAUDE.md "non-obvious runtime gotchas" section
  exists because every one of those was discovered the hard way.

---

## 6. Industry standards Keycloak implements (and why we care)

The whole reason "swap Keycloak for X" is even discussable is
that everything Keycloak does is built on these standards. The
JWT we issue today would be valid for any standards-compliant
consumer tomorrow.

| Standard | What it is | Where it shows up here |
|---|---|---|
| **OAuth 2.0** (RFC 6749) | Authorization framework — how a client gets a token | `mfe-shell-client` OIDC client, `/auth` + `/token` endpoints |
| **OpenID Connect Core 1.0** | Identity layer on top of OAuth — adds the ID token, `userinfo`, discovery | The MFE shell uses `keycloak-js`, which is an OIDC client |
| **PKCE** (RFC 7636) | Proof Key for Code Exchange — prevents auth-code interception on SPAs/mobile | `pkceMethod: 'S256'` in `AuthProvider.tsx` |
| **JWT** (RFC 7519) | JSON Web Token format | Every `Authorization: Bearer …` header in this repo |
| **JWS** (RFC 7515) + **JWA** (RFC 7518) | How a JWT is signed; algorithm registry | RS256 by default; `/.well-known/openid-configuration` advertises it |
| **JWKS** (RFC 7517) | JSON Web Key Set — public key publication for token verification | BFFs fetch from `/realms/demo-realm/protocol/openid-connect/certs` |
| **OAuth 2.0 Token Revocation** (RFC 7009) | `/revoke` endpoint | Used by `keycloak-js` logout |
| **OAuth 2.0 Token Introspection** (RFC 7662) | Server-side token validation | Optional; we use JWKS instead |
| **SAML 2.0** (OASIS) | XML-based federation protocol | The `p1` IdP broker; P1's `IdpSsoAction` |
| **WebAuthn** (W3C) / **FIDO2** | Passwordless / hardware-backed authentication | Not used in this demo; Keycloak supports it |
| **TOTP** (RFC 6238) | Time-based one-time passwords for MFA | Not used here (we have email OTP); built-in to Keycloak |
| **OAuth 2.1** (draft) | The "current best practice" rollup — PKCE for all, no implicit flow, no password grant in browsers | Keycloak 26 broadly conforms |

The point of listing these: a discussion about "could we use
something other than Keycloak" is really a discussion about
"do we want the same standards from a different vendor." The
contract we expose to apps doesn't change.

---

## 7. Alternatives — what else could fill this role

### 7.1 The hosted-SaaS class

| Product | Hosting | Pricing model | When it fits |
|---|---|---|---|
| **Auth0** (now Okta CIC) | SaaS, regions | Per-MAU tiered | Fast time-to-value, no infra. Expensive past ~10k MAU. |
| **Okta CIAM / Workforce** | SaaS | Per-user + features | Enterprise SSO with extensive directory integration. |
| **AWS Cognito** | SaaS in AWS | Per-MAU, generous free tier | If the rest of the stack is AWS. Less flexible custom flows. |
| **Azure AD B2C** | SaaS in Azure | Per-MAU | If on Azure / consuming Microsoft directory. Custom policies use XML. |
| **GCP Identity Platform** (Firebase Auth underneath) | SaaS in GCP | Per-MAU | If on GCP. Smaller SAML/SCIM surface than Auth0. |
| **Stytch / Clerk / WorkOS** | SaaS, dev-first | Tiered | Smaller stacks, easier SDKs, less standards surface. WorkOS is strongest for enterprise SSO/SCIM out of the box. |

**Verdict.** Hosted SaaS removes operational burden but you lose:
- code-level customization (every SPI in this repo would need to
  be reimagined as a hosted rule/action, often with stricter limits)
- on-prem deployment option (matters for regulated industries)
- predictable cost at scale

### 7.2 The self-hosted class

| Product | Tech | Notes |
|---|---|---|
| **Keycloak** | Java/Quarkus + Postgres | What we're using. Largest mindshare, broadest protocol coverage. |
| **Authentik** | Python + Postgres | Modern UI, growing community. Strong for k8s shops. Smaller SPI surface than Keycloak. |
| **Ory** stack (Kratos + Hydra + Keto + Oathkeeper) | Go, decomposed | Unix-philosophy: each piece does one job. Steeper integration cost; rewards if you want to swap parts. |
| **Zitadel** | Go + cockroachdb/postgres | Multi-tenant first-class, event-sourced. Newer; smaller community. |
| **Gluu / Janssen** | Java | The other Java OIDC server. Less mindshare than Keycloak. |
| **Authelia** | Go | Lighter, focused on reverse-proxy auth (forward-auth). Not a full IdP. |
| **FreeIPA + SSSD** | Java/Python | Identity directory, not really a modern IdP. Good for Linux fleets. |

**Verdict.** Authentik is the closest like-for-like swap. Ory is
the strongest pick if you wanted to decompose: Hydra for OIDC,
Kratos for identity, Keto for policy. Zitadel is the newest
serious entrant.

### 7.3 The build-your-own class

It is *possible* to write an OIDC provider in a few hundred lines
of Go/Rust/Java if you only need: one signing key, one client,
no MFA, no admin UI, no federation. Several large companies have
done it.

What you give up:
- federation breadth (every IdP integration is custom code)
- admin UI (you build one or live in psql)
- protocol coverage (SAML alone is ~3000 LOC of XML parsing)
- security review surface (you're now responsible for it)

**When build-your-own makes sense:** you have very simple needs,
very strong security engineering, and a strategic reason to own
the auth layer (e.g. your product *is* an auth product).

**When it doesn't:** any time the answer to "do we need OAuth +
OIDC + SAML + LDAP + MFA + an admin UI" is yes for more than two
of those.

### 7.4 The "no SSO" class

Worth naming: many small stacks don't need an SSO at all. A
single app with a single user table, sessions on cookies, password
hashed with Argon2. Industry-standard auth libraries
(`devise`, `next-auth`, `Spring Security`, `passport.js`) cover
this.

The moment you have *two* apps that should share a login, or *one*
app that needs to federate from a corporate directory, the calculus
flips and Keycloak (or an alternative) becomes worth its weight.

---

## 8. Decision framework — when each option wins

Use the table below to put a starter project on the right path
without reading all of §7:

| If you need… | Use |
|---|---|
| Fast SaaS, paying per MAU is fine, < 10k users | Auth0 or Clerk |
| AWS-native, < 50k MAU, no SAML | Cognito |
| Enterprise SSO + SCIM provisioning, hosted | WorkOS or Okta |
| Self-hosted, full SPI extensibility, on-prem option | **Keycloak** |
| Self-hosted, modern UI, kube-native | Authentik |
| Self-hosted, want to swap individual components | Ory |
| One app, one user table, no federation | a server-side auth library, not an IdP |
| Multi-tenant where each tenant is fully isolated | Zitadel or one-realm-per-tenant Keycloak |

In this repo specifically, the qualifying criteria were:
1. Federate from an existing SAML IdP (P1) → eliminates
   "no SSO" and most pure-OIDC providers
2. Self-hosted for cost + privacy → eliminates SaaS-only
3. SPI extensibility for the per-firm branding pipeline → favors
   Keycloak over Authentik
4. SAML 2.0 production-grade → favors Keycloak / Ory Hydra over
   smaller competitors
5. Apache 2.0 license → all the self-hosted picks

Keycloak wins on every criterion except "operational simplicity,"
where Authentik would have edged it. The deciding factor was the
SPI maturity — five custom SPIs in this repo would have been
harder to ship on Authentik in 2026.

---

## 9. What we'd evaluate if we were starting over today

1. **Was the requirement "P1 federation" or "an IdP we control"?**
   If the latter, we could have gone Auth0/SaaS day one and pushed
   P1 to also federate from it later. Cost would have been the
   blocker; ops simplicity would have been the win.

2. **Do we need SAML now, or only OIDC?** If only OIDC, we drop
   to a smaller alternative set (Ory Hydra, Zitadel, Logto). SAML
   is the reason we're holding Keycloak's weight.

3. **What's the MAU horizon?** SaaS gets expensive linearly;
   self-hosted is roughly flat. The crossover is usually around
   5-20k MAU depending on vendor and feature mix.

4. **Who owns this thing in year 3?** SaaS: a vendor TAM. Self-hosted:
   somebody on the platform team. The TCO difference is real even
   if the line item is "free."

5. **What's our regulatory posture?** PCI / SOC2 / FINRA scopes
   may rule out the SaaS option entirely or mandate dedicated
   tenants (which usually negate the cost savings).

---

## 10. One-line verdict

Keycloak is *not* magic — it's a competent implementation of well-known
IETF/OASIS standards. The reason it's here is the combination of
(a) the SAML brokerage requirement to integrate P1, (b) the on-prem
cost ceiling, and (c) the SPI extensibility we leaned on for branding
and custom auth flows. Take any one of those three away and a smaller
or hosted alternative might have been cleaner. As long as all three
hold, the call stands.
