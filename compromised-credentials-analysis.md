# Compromised Credentials — What Happens to a BFF Call?

What happens when a call hits a BFF but the credentials are compromised.
Walking from the bottom up — what the BFF does to the request in each
scenario, and what the current code catches (or doesn't).

## How the BFF gates a call (baseline)

Every `/api/...` request to a BFF goes through:

1. **Bearer-token extraction** — Micronaut security reads
   `Authorization: Bearer <jwt>`.
2. **JWT signature** — validated against the Keycloak JWKS
   (`KEYCLOAK_AUTH_SERVER_URL`). Tampered / signed with a different key
   → 401.
3. **Issuer + `exp`** — `iss` has to match
   `http://localhost:8898/realms/demo-realm`; expired → 401.
4. **Role gate** — `APP_ALLOWED_ROLES` (per-BFF env) is checked against
   the roles in the JWT. Mismatch → 403.
5. **Endpoint logic** — only now does `UserController` run.

The chain is fail-closed — any of 1-4 rejects the request before the
handler sees it.

## Scenarios for "compromised credentials"

### A) Username + password leaked (no 2FA material)

- Login to Keycloak will get through step 1 (forms), but **email OTP
  blocks** the finalize.
- The attacker also needs **either** control of the user's inbox **or**
  a valid `KC_DEMO_OTP_TRUSTED` cookie.
- The BFF never sees such a request — Keycloak doesn't issue an access
  token.
- **How well we're protected:** well, provided the email isn't also
  compromised and the trust-cookie window isn't active.

### B) Valid access token (JWT) leaked

- The attacker can replay the token until `exp` (default
  `accessTokenLifespan` ≈ 5 min).
- The BFF accepts every call — the signature is valid, the roles are
  in the payload.
- It can only hit **the BFFs whose role gate the token's roles
  satisfy**: a `client`-only token → 403 from `bff-ops`/`bff-admin`.
- **No further gate** — no IP check, no device binding (DPoP), no
  audience check beyond `iss`. A stolen token works from any browser,
  any network.
- **How well we're protected:** weakly. Short lifetime is the only
  barrier.

### C) Refresh token leaked

- Worse than B — the attacker can mint new access tokens for the entire
  refresh window (which by default is much longer, days).
- Keycloak refresh-token rotation is **not enabled** in
  `realm-export.json` (needs verification), so the same refresh token
  can be reused.
- **How well we're protected:** weakly. Refresh-token rotation + reuse
  detection would help here (Keycloak supports both, but they're not on
  by default).

### D) Keycloak SSO cookie (`KEYCLOAK_SESSION` / `KEYCLOAK_IDENTITY`) leaked

- The attacker can hit `/auth` and get a fresh auth code without
  entering a password → new tokens.
- The cookie is `HttpOnly` + `Secure` in a proper production setup; in
  dev it's HTTP, so it's sniffable.
- **How well we're protected:** in the demo (HTTP) — weakly. In
  production with HTTPS everywhere — well.

### E) OTP trust cookie (`KC_DEMO_OTP_TRUSTED`) leaked

- Skips the email OTP step. Combined with scenario A → full login.
- The cookie is `HttpOnly` + HMAC-signed + realm-scoped + `SameSite=Lax`.
- `Lax` means it's **not sent** on cross-origin POST/iframe — but the
  Keycloak login is a redirect (top-level navigation), which `Lax`
  allows. So it's usable for targeted phishing.
- **How well we're protected:** moderate. A Keycloak restart
  invalidates every outstanding trust cookie (the HMAC key is
  process-local), which is a crude but effective kill switch.

### F) `user-service` / SPI compromised (more exotic)

- `UserServiceClient` is **fail-closed** — any non-200 returns `false`
  from password verify and `null` from lookups. If the attacker takes
  `user-service` down, nothing can log in.
- But if the attacker **controls** `user-service`, they can return
  `true` for any password → full Keycloak login.
- Mitigation in the current code: none. `KEYCLOAK_AUTH_SERVER_URL`
  points at `user-service:8080` over the compose network, plain HTTP,
  no auth between the SPI and the REST service.

## What's **missing** and would need to be added for production

| Defence | Today | For production |
|---|---|---|
| HTTPS everywhere | no (dev HTTP) | required |
| Token binding (DPoP / mTLS) | none | recommended |
| Audience check on the BFF | likely no (verify in Micronaut config) | required |
| Refresh-token rotation + reuse detection | not enabled | required |
| Short `accessTokenLifespan` (≤ 5 min) | default | OK |
| Rate limiting on `/auth` and the BFFs | none | required |
| Anomaly detection (impossible travel, new device) | none | for higher tiers |
| SPI ↔ user-service auth (mTLS or shared secret) | none | required |
| CSP + Subresource Integrity for federation chunks | none | recommended |
| `keycloak-js` token storage in memory only | yes | OK (XSS is still a risk) |

## Short answer for "what if the BFF receives a compromised call?"

- Properly signed, unexpired, with the right roles → **the BFF serves
  it normally.** It sees a valid JWT and has no way to tell "real user"
  from "attacker with a stolen token."
- Invalid signature / expired / wrong role → **401 / 403** automatically.
- Token signed by a foreign IdP / different realm → **401** (issuer
  mismatch).
- Token with role `client` against `bff-admin` → **403**.

This is the standard "bearer token is a bearer of trust" model — the
default OIDC behaviour. Stronger protection requires token binding
(DPoP) or mTLS, which are out of scope for the demo. The full security
audit and the production checklist live in [`LOGIN.md`](./LOGIN.md).
