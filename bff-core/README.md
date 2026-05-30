# bff-core

Shared Backend-for-Frontend / Token Handler infrastructure for the demo's
per-domain BFFs (`billing`, `trading`, `users`). Packaged as an
`io.micronaut.library` so the Micronaut annotation processor bakes the bean
definitions into the jar — a consuming BFF discovers the `@Filter` / `@Controller`
/ `@Singleton` beans below as if they were its own.

## What's in here

| Type | Role |
|---|---|
| `Bff` | shared `main()` — each BFF sets `mainClass = demo.bff.core.Bff` |
| `IdpHintFilter` | appends `kc_idp_hint=p1` to the authorize redirect |
| `TokenRefreshFilter` | server-side OP token refresh + KC-session liveness probe |
| `AuthController` | `/auth/me`, `/auth/logout` (OIDC end-session + P1 IdP-initiated SLO) |
| `BackchannelLogoutController` + `LogoutTokenValidator` + `SidSessionRegistry` | OIDC Back-Channel Logout 1.0 receiver |
| `KeycloakAuthenticationMapper` | maps the OIDC userinfo/claims into the session `Authentication` |
| `P1AuthzClient` | calls P1's Tier 2/3 authz endpoints |
| `Tier23Gate` | the fine-grained gate (`require` / `refine`) controllers call, keyed by per-domain `DemoAuthz` codes |
| `AuthClaims` | small claim read helpers (`firmCd`) for response bodies |

## How a BFF consumes it

`settings.gradle` (composite build — substitutes the dependency from source):

```groovy
includeBuild('../../../bff-core')
```

`build.gradle.kts`:

```kotlin
dependencies { implementation("demo.bff:bff-core:1.0.0") }   // Micronaut deps arrive transitively (api)
application { mainClass.set("demo.bff.core.Bff") }
micronaut { processing { annotations("demo.<domain>.*") } }  // only the domain's own package
```

At repo-split, drop the `includeBuild` line and add a registry repository
(`maven { url = … }`, GH Packages / Artifactory / Nexus). The `implementation`
coordinate is unchanged.

## Config contract

`bff-core` reads these keys from the **consuming** BFF's `application.yml` at
runtime (`@Value`). A consumer must provide them:

| Key | Used by |
|---|---|
| `micronaut.security.oauth2.clients.keycloak.{issuer,client-id,client-secret}` | `AuthController`, `LogoutTokenValidator` |
| `micronaut.http.services.kc.url` (the `@Client("kc")` base) | `AuthController`, `TokenRefreshFilter` |
| `app.p1.initiate-slo-url` | `AuthController` (P1 IdP-initiated SLO redirect) |
| `micronaut.security.token.jwt.signatures.jwks.keycloak.url` | `LogoutTokenValidator` (verifies the back-channel logout token) |
| `app.authz.{fine-enabled,cache-ttl-millis}`, `micronaut.http.services.p1authz.url` | `P1AuthzClient` |
