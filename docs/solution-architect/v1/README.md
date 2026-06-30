# Solution-Architect Documentation Set — Brokered SSO/SLO for the GeoWealth Demo Platform

> **Audience.** Solution Architect review.
> **Scope.** SSO and SLO across the identity tier and the per-domain demo
> applications, plus the Kubernetes deployment that runs them end-to-end.
> **Format.** One topic per Markdown file so each maps cleanly to a Confluence
> sub-page beneath one parent space.

---

## How this set is organised

The document set is intentionally split into ten files. Each file is
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

A reader pressed for time should read the executive summary, skim the
architecture overview, then jump straight to the Q&A.

---

## What this documents

The platform brokers every user login through a SAML federation between two
systems:

1. **GeoWealth Platform 1 (P1)** — the existing Java/Tomcat product, acting as
   a SAML Identity Provider for the demo.
2. **The demo identity tier** — Keycloak `demo-realm` (the SP towards P1, the
   OIDC IdP towards the demo apps) plus a multi-tenant **Token Handler** service
   that fronts every demo application.

Each demo application (`billing`, `trading`, and any future one) is a thin pair:
a React/Vite SPA and a Micronaut data BFF. The data BFFs are deliberately
**auth-unaware** — nginx in front of each app runs forward-auth against the
Token Handler and injects `X-Auth-*` identity headers, so all session, token
refresh, and authorization plumbing lives in exactly one place.

The Kubernetes deployment runs all of this in one namespace (`geowealth-demo`),
with environment-specific URLs and data-tier endpoints externalised into
`k8s/env/urls.<env>.env` and `k8s/env/data-tier.<env>.env`.

---

## Source repositories covered

| Repo | Branch examined | Role |
|---|---|---|
| `/home/petar/keycloak-demo` | `petarnenov/full-stack-k8s` | Identity tier (Keycloak + Token Handler) and demo applications |
| `/home/petar/nodejs/geowealth` | `team/petarnenov/k8s-full-stack` | Platform 1 (SAML IdP), Tomcat + Akka agents |

Every claim in this set is backed by a file path and (where useful) a line
number from one of these two repositories. See
[`09-glossary-and-references.md`](09-glossary-and-references.md) for the index
of source references.

---

## Conventions used in this set

- **File and line references** are written as `path/to/file.java:123` so they
  can be navigated directly from a checkout of the relevant repo.
- **Mermaid diagrams** are inlined. Confluence renders them natively in
  recent versions; if a target Confluence space cannot, the `.md` source is
  enough to regenerate them as images.
- **Code excerpts** are kept short and quoted verbatim; they are illustrative,
  not authoritative — the authoritative source is the file referenced
  immediately above each excerpt.
- The set is written in English so it can be circulated to a wider audience
  than the immediate team.
