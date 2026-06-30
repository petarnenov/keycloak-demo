# Solution-Architect Documentation Set — KC-Native Auth + P1 as OIDC RP (auth-extraction phase)

> **Audience.** Solution Architect review.
> **Scope.** SSO and SLO across the identity tier and the per-domain demo
> applications, **after the auth-extraction refactor** (Phases 0–7 of
> `docs/plans/2026-06-26-auth-extraction.md`). The SAML federation between
> Keycloak and P1 is **retired**; Keycloak now owns authentication directly,
> federating user storage to an Oracle-backed micro-service via the Keycloak
> User Storage SPI; P1 is just another OIDC Relying Party.
> **Format.** One topic per Markdown file so each maps cleanly to a Confluence
> sub-page beneath one parent space.

---

## What changed since v1

Everything labelled "the SAML federation" in v1 is gone. The new shape:

| v1 — SAML brokering | v2 — KC-native auth + P1 as OIDC RP |
|---|---|
| KC `demo-realm` has no users; `p1` SAML IdP brokers every login | KC `demo-realm` federates users from `user-service` (Micronaut + Oracle JDBC) via a custom User Storage SPI |
| P1 Tomcat is the SAML IdP (`IdpSsoAction`, `IdpSloAction`, …) | P1 Tomcat is an OIDC RP (`OidcLoginAction`, `OidcCallbackAction`, `OidcBackChannelLogoutAction`, …) |
| Login UI = P1's `LoginTemplate1` | Login UI = KC theme `geowealth` (React+Vite-built FTL pages, **visually 1:1 with P1 LoginTemplate1**) |
| MFA = P1 `EmailManager.sendMfaTokenMail` | MFA = KC `email-otp-authenticator` SPI provider that calls `user-service` for token issue/verify |
| P1 Tomcat HttpSession in-memory (single replica) | P1 Tomcat HttpSession in Redis via **Redisson** session manager; `kc_sub → sessionId` cross-pod index in Redis (`P1RedisKcSubIndex`); `p1-tomcat` scales horizontally |
| Two parallel logout fan-outs (KC OIDC back-channel + KC→P1 SAML LogoutRequest) | One logout fan-out: KC OIDC back-channel POSTs `logout_token` to **every client** including `p1-client` |
| Memcached holds an L2 cache | Memcached **retired** — Redis serves both sessions and L2 cache |
| No monitoring stack | `kube-prometheus-stack` (Helm) wired in via `k8s/monitoring.sh`; Redis-specific Prometheus rules in `k8s/base/redis-alerts.yaml` |

The Token Handler, the forward-auth contract, the three-tier authorisation
model, the per-host tenant resolution, the `X-Auth-*` header forwarding and
the multi-tenant `demo-shared-client` are all **unchanged**. The pieces that
moved are the **authentication source** (now user-service / Oracle, no longer
P1 SAML) and the **P1-side identity wiring** (now OIDC RP, no longer SAML
IdP).

---

## How this set is organised

The document set is intentionally split into eleven files. Each file is
self-contained (it can be read on its own) but they are also designed to be
read in order during a first walkthrough.

| # | File | Confluence sub-page title |
|---|---|---|
| 00 | [`00-executive-summary.md`](00-executive-summary.md) | Executive summary |
| 01 | [`01-architecture-overview.md`](01-architecture-overview.md) | Architecture overview |
| 02 | [`02-component-inventory.md`](02-component-inventory.md) | Component inventory (every pod and its role) |
| 03 | [`03-login-flows.md`](03-login-flows.md) | Login flows (all scenarios) |
| 04 | [`04-logout-flows.md`](04-logout-flows.md) | Logout / SLO flows (all scenarios) |
| 05 | [`05-kubernetes-deployment.md`](05-kubernetes-deployment.md) | Kubernetes deployment topology |
| 06 | [`06-communication-diagrams.md`](06-communication-diagrams.md) | Communication diagrams |
| 07 | [`07-security-and-trust-model.md`](07-security-and-trust-model.md) | Security and trust model |
| 08 | [`08-questions-and-answers.md`](08-questions-and-answers.md) | Questions & answers |
| 09 | [`09-glossary-and-references.md`](09-glossary-and-references.md) | Glossary and references |
| 10 | [`10-user-service-schema-contract.md`](10-user-service-schema-contract.md) | user-service ↔ Oracle schema contract |
| 11 | [`11-horizontal-scaling-report.md`](11-horizontal-scaling-report.md) | Horizontal scaling report (live cluster audit) |
| 12 | [`12-dev-environment-plan.md`](12-dev-environment-plan.md) | Development environment plan (FE + BE single-command ≤60 s) |
| 13 | [`13-dev-environment-setup.md`](13-dev-environment-setup.md) | Development environment setup (ports, /etc/hosts, mkcert, Oracle/ES/Redis, FE/BE commands) |

A reader pressed for time should read the executive summary, skim the
architecture overview, then jump straight to the Q&A.

---

## What this documents

The platform is a **direct-authentication SSO substrate**:

1. **Keycloak `demo-realm`** owns authentication. Users are federated from
   `user-service` via a custom **User Storage SPI** (the SPI provider
   delegates `getUserByUsername`, `isValid`, and the rest of the
   `CredentialInputValidator` contract to user-service over HTTP).
2. **`user-service`** is a stateless Micronaut module that reads
   `ENTITY_TBL` / `ENTITY_ROLE_TBL` / `ROLE_TBL` from Oracle and exposes a
   small HTTP contract (lookup, verify-credentials, roles, attributes,
   MFA-token issue/verify). The password algorithm is P1's
   `SHAPassword` (SHA1 + optional salt + Base64) ported 1:1.
3. **GeoWealth Platform 1 (P1)** is one of three OIDC clients:
   - `demo-shared-client` — used by the multi-tenant Token Handler to
     authenticate users on the demo SPAs (`billing`, `trading`).
   - `p1-client` — used by the P1 Tomcat app itself (its `OidcLoginAction`
     redirects to KC, its `OidcCallbackAction` exchanges the code and
     populates Tomcat's `HttpSession`).
   - (Vestigial) `demo-billing-client` / `demo-trading-client` — kept in
     the realm export so the existing per-domain client tests still pass,
     but the live login path runs entirely through `demo-shared-client`.
4. **Token Handler** is unchanged from v1: one multi-tenant deployment in
   front of every domain SPA; sessions in Redis; per-host Tier-2 authz; the
   data BFFs stay auth-unaware via the `X-Auth-*` forward-auth contract.

The Kubernetes deployment runs all of this in one namespace
(`geowealth-demo`), with environment-specific URLs in
`k8s/env/urls.<env>.env` and data-tier endpoints in
`k8s/env/data-tier.<env>.env`. A `kube-prometheus-stack` monitoring stack is
installed via Helm in the `monitoring` namespace and watches the demo via
`PrometheusRule` resources living in the demo namespace.

---

## Source repositories covered

| Repo | Branch examined | Role |
|---|---|---|
| `/home/petar/keycloak-demo` | `petarnenov/auth-extraction` | Identity tier (Keycloak + custom SPI + email-OTP authenticator + `auth-spa` theme + Token Handler) plus the demo applications |
| `/home/petar/nodejs/geowealth` | `team/petarnenov/auth-extraction` | Platform 1 (Tomcat + Akka agents); now an **OIDC RP**, not a SAML IdP |

Every claim in this set is backed by a file path and (where useful) a line
number from one of these two repositories. See
[`09-glossary-and-references.md`](09-glossary-and-references.md) for the
index of source references.

---

## Conventions used in this set

- **File and line references** are written as `path/to/file.java:123` so they
  can be navigated directly from a checkout of the relevant repo.
- **Mermaid diagrams** are inlined. Confluence renders them natively in
  recent versions; if a target Confluence space cannot, the `.md` source is
  enough to regenerate them as images.
- **Code excerpts** are kept short and quoted verbatim; they are
  illustrative, not authoritative — the authoritative source is the file
  referenced immediately above each excerpt.
- The set is written in English so it can be circulated to a wider audience
  than the immediate team.
