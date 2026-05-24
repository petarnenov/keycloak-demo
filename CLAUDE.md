# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Survival notes for working in this repo. The **README.md** is the user-facing doc; this file only captures things that matter when editing the code and that you can't reliably infer from a first read.

## What this is

A Keycloak SSO demo with the smallest possible identity tier in front of a per-domain app stack. Each demo "product" lives under `domains/<name>/` with its own React+Vite frontend and its own Micronaut BFF; the two share only a Keycloak realm (`demo-realm`) and the SAML federation to P1.

There are no native users in this realm. Every login is brokered through `kc_idp_hint=p1` → SAML to P1 → first-broker-login auto-link by email → OIDC code → SPA token. Direct login on Keycloak's login screen and direct-grant against `demo-realm` are both effectively broken — there's nothing in the realm's local user store to authenticate against. That's intentional: the demo is about SAML federation, not local auth.

Current domains:

| Domain | FE host | FE port | BFF service | BFF port | OIDC client |
|---|---|---|---|---|---|
| billing | `billing.geowealth.int` | 5184 | `bff-billing` | 8084 | `demo-billing-client` |
| trading | `trading.geowealth.int` | 5185 | `bff-trading` | 8085 | `demo-trading-client` |

Each FE is a small standalone React + Vite + keycloak-js app, packaged into its own image via its own `Dockerfile` (no bind-mount). Each BFF is a standalone Micronaut module with its own `Dockerfile` and its own image.

## Layout

- `domains/<name>/web/` — the FE. Standalone npm project (no monorepo), Vite + React + keycloak-js. Builds into its own image. Talks only to its own BFF via Vite preview's `/api/<name>` proxy.
- `domains/<name>/bff/` — the BFF. Standalone Micronaut module, fat-jar via `com.gradleup.shadow`, separate image. Validates JWTs against Keycloak's JWKS endpoint.
- `keycloak/realm-export.json` — `demo-realm` seed: the two OIDC clients, the `p1` SAML IdP (with embedded signing certificate), the SAML attribute mappers (email/firstName/lastName/firmCd/roles), the `p1-first-broker-login` flow.
- `docker-compose.yml` — services: `postgres`, `keycloak` (stock `quay.io/keycloak/keycloak:26.0.7`), and one `demo-<name>` + one `bff-<name>` per domain.
- `start.sh` / `stop.sh` — canonical entrypoints. Auto-detect docker vs podman, source `.envrc`, tear down (volumes preserved by default), rebuild, bring everything back up. `./start.sh --reset` and `./stop.sh --wipe` are the only opt-in destructive paths.

The P1 sidebar that links into these domains lives **outside** this repo, in `~/geowealth/WebContent/react/app/src/pages/PlatformOne/sidebar/_hooks/useIntegrationLinks.js`. Each domain gets a `links.push(...)` with the Keycloak authorize URL + `kc_idp_hint=p1` + `redirect_uri` pointing at the domain SPA.

## Adding a new domain

The shape is fixed; copy `domains/billing/` (or `trading/`) and adapt. Roughly:

1. Pick a slug (`reporting`), a host (`reporting.geowealth.int`), a FE port (next free `518X`), a BFF port (next free `808X`).
2. `cp -r domains/billing domains/reporting` and rename: `package.json#name`, `vite.config.ts` (proxy path, ports, allowedHosts), `src/auth/keycloak.ts` (KC_CLIENT default), Gradle group/rootProject, Java package, controller class, all stub data.
3. Add a new OIDC client to `keycloak/realm-export.json` (model after `demo-billing-client`); add it to the live realm via admin API too, since realm imports are `IGNORE_EXISTING` on existing DBs.
4. Add `demo-<name>` and `bff-<name>` services to `docker-compose.yml`.
5. Add `/etc/hosts` entry `127.0.0.1 <name>.geowealth.int` and generate an mkcert cert pair at `proxy/certs/<name>.geowealth.int.{crt,key}`.
6. In `~/geowealth/...useIntegrationLinks.js`, push a link with `kcAuthorize('demo-<name>-client', 'https://<name>.geowealth.int:<port>/', 'demo-<name>')`.

## Persistence model — what survives a restart

| Lives in | Persists across | Wiped only by |
|---|---|---|
| Keycloak realms, federated identities, sessions, live admin-API edits | `docker compose restart`, `docker compose down` + `up`, `./start.sh`, `./stop.sh` then `./start.sh`, image rebuild + `--force-recreate` | `docker compose down -v`, **`./start.sh --reset`**, or **`./stop.sh --wipe`** |
| Postgres data backing all of the above | same | same |
| `keycloak/data` (import sources, KeyStore, exported state) | same | same |

Practical consequences:

- **A SAML-brokered login through P1 writes a federated identity to Keycloak's Postgres on first sign-in.** That identity survives every routine `restart` / `down+up` / image rebuild. The next time the same P1 user lands on the broker flow, Keycloak finds the existing record and skips first-broker-login.
- **`./start.sh` and `./stop.sh` are non-destructive.** Both run `down` (without `-v`) so the Postgres volume stays in place.

## Non-obvious runtime gotchas

- **Realm imports are `IGNORE_EXISTING`.** `--import-realm` only seeds an empty Postgres. To apply a `realm-export.json` edit on a running stack, either `./start.sh --reset` (destructive) or change the live realm via admin API (fast, preserves federated identities).
- **The `p1` IdP `signingCertificate` is baked into `realm-export.json`.** It must match the certificate P1's Tomcat presents when signing SAML Responses. If the dev keystore is regenerated on the P1 side, paste the new cert (DER, base64) into the realm export and either reset or update via admin API.
- **All BFFs run as baked fat jars.** Each domain BFF is built once into its own image (`<domain>/bff/Dockerfile`) using the `com.gradleup.shadow` plugin → `eclipse-temurin:17-jre` base. PID 1 is `java -jar /app/<svc>.jar`; no source tree, no Gradle cache, no bind mount. A source edit needs a `--build --force-recreate`.
- **Shadow plugin is declared explicitly in every BFF's `build.gradle.kts`.** Micronaut 4.4's application plugin doesn't register `shadowJar` on its own — each BFF adds `id("com.gradleup.shadow") version "8.3.5"` so `gradle shadowJar` produces `build/libs/*-all.jar` for the Dockerfile to copy.
- **Keycloak runs the stock image — no custom SPIs.** Compose uses `quay.io/keycloak/keycloak:26.0.7` directly. Any change that wants a SPI would need a fresh `Dockerfile.keycloak` and a `build.context` in compose.
- **Keycloak's admin API is at `https://auth.geowealth.int:5180`.** Get a token at `/realms/master/protocol/openid-connect/token` with `client_id=admin-cli&grant_type=password&username=admin&password=admin`. Admin endpoints live under `/admin/realms/demo-realm/...`. The `auth` nginx terminates TLS and proxies to `keycloak:8080` inside the docker network — Keycloak itself is no longer exposed on the host. `KC_HOSTNAME=https://auth.geowealth.int:5180` makes Keycloak emit consistent issuer / broker URIs.
- **`keycloak-js` must be ≥ 26.x.** Older versions validate a `nonce` claim that Keycloak 26 no longer emits.
- **Domain hosts need HTTPS.** keycloak-js v26 uses `crypto.subtle`, which the browser only exposes in secure contexts: HTTPS, or the loopback hostnames `localhost`/`127.0.0.1`. A custom hostname like `billing.geowealth.int` resolves to 127.0.0.1 via `/etc/hosts`, but the browser classifies secure-context by hostname literal, not resolved IP — so plain `http://billing.geowealth.int:5184` is NOT secure and `crypto.subtle` is `undefined` there. Each domain serves HTTPS via Vite preview with mkcert-issued certs at `proxy/certs/<name>.geowealth.int.{crt,key}`. Generate with `mkcert -cert-file proxy/certs/<name>.geowealth.int.crt -key-file proxy/certs/<name>.geowealth.int.key <name>.geowealth.int localhost 127.0.0.1`.

## P1 SAML federation flow

1. Entry point (either): user clicks a P1/Integrations sidebar link, OR the SPA's `keycloak.init({ onLoad: 'check-sso' })` finds no session and fires `keycloak.login({ idpHint: 'p1' })`. Both build the same URL: `https://auth.geowealth.int:5180/realms/demo-realm/protocol/openid-connect/auth?client_id=demo-<name>-client&response_type=code&scope=openid&redirect_uri=https://<name>.geowealth.int:518X/&kc_idp_hint=p1&state=…`.
2. Keycloak sees `kc_idp_hint=p1`, skips its own login screen, emits a SAML AuthnRequest to P1's `/saml/idp/sso.do`.
3. P1's `IdpSsoAction` parses the AuthnRequest ID, reads the active P1 session, builds a signed Response with `InResponseTo=ID`, posts back to Keycloak's broker endpoint.
4. Keycloak correlates by `InResponseTo`, runs first-broker-login (auto-link by email, silent), issues an OIDC code, redirects to `redirect_uri` (the domain SPA).
5. The SPA's `AuthProvider` picks up the code, exchanges it for a token, and renders the dashboard.

Pure IdP-init (P1 → Keycloak with unsolicited Response) does **not** work cleanly against an OIDC client target in Keycloak. The `/endpoint` path needs SP-init correlation, and the `/endpoint/clients/{id}` variant requires SAML protocol clients. SP-init via `kc_idp_hint` is the supported flow.

## Applying changes — what needs what

| Change | Minimum action |
|---|---|
| `domains/<name>/web/` source | `podman compose up -d --build --force-recreate demo-<name>` — its own image (`keycloak-demo-<name>-web`) built by `domains/<name>/web/Dockerfile`. No bind-mount, so a source edit needs the full build. |
| `domains/<name>/bff/` source | `podman compose up -d --build --force-recreate bff-<name>` — its own image (`keycloak-demo-<name>-bff`). |
| Add a new domain | follow the recipe in "Adding a new domain" above. |
| Env var on a service | `podman compose up -d --force-recreate <service>` |
| `docker-compose.yml` structural change | `podman compose up -d` (compose picks up the diff) |
| `keycloak/realm-export.json` | takes effect on fresh DB only; otherwise patch the live realm via admin API |
| New realm role needed for a running demo | POST `/admin/realms/demo-realm/roles` with admin token; also add to `realm-export.json` for future fresh installs |
| New OIDC client needed for a running demo | POST `/admin/realms/demo-realm/clients` with admin token; also add to `realm-export.json` |
| P1/Integrations link source (`~/geowealth/.../useIntegrationLinks.js`) | rebuild the GeoWealth Tomcat app — outside this repo. |

## Running this on macOS + Podman

`./start.sh` is the normal way in — it auto-detects docker or podman and handles the macOS podman quirks below. Force one or the other with `CONTAINER_ENGINE=docker|podman ./start.sh`.

If you're using `podman compose` directly, two things to know:

1. **`DOCKER_HOST` socket path.** The compose CLI talks through a Docker-style socket; Podman uses a different socket path than the machine-default claims:

   ```bash
   export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
   ```

   The `.envrc` has a version of this, but if `podman compose` errors with "Cannot connect to the Docker daemon," export the path directly.

2. **`depends_on: condition: service_healthy` is ignored.** `podman compose` doesn't honor compose healthcheck gating, so a naive `podman compose up -d` starts `keycloak` before postgres is reachable. `start.sh` works around this by `up -d`-ing postgres first, polling its health, then bringing keycloak up, then everything else. If you skip `start.sh`, replicate the sequence by hand or wait long enough.

## Commit style

Recent commits use plain prose bodies, no trailers other than `Co-Authored-By: Claude ...`. Prefer one commit per logical change; match the existing style.

## Language: English for everything that lands in the repo

All persistent artifacts must be in English: markdown docs, code comments, identifiers, file names, commit messages. Conversational replies in chat can mirror whatever language the user is writing in (often Bulgarian), but the moment something is written to disk and committed it must be English. The repo is a public-shareable demo, and contributors / future readers won't necessarily read Bulgarian.

When the user dictates content in Bulgarian and asks for it to be saved, translate as you write rather than transcribing. Filenames stay in kebab-case English.
