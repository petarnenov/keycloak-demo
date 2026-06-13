# OIDC (OpenID Connect) — Primer in the Context of This Demo

A short, practical explainer of OpenID Connect with concrete references to
how the keycloak-demo stack uses it. Pairs with
[`p1-auth-flow.md`](p1-auth-flow.md) (the P1-side authorization model) and
[`auth-flow-production-readiness.md`](auth-flow-production-readiness.md)
(the production-readiness audit).

## TL;DR

**OIDC = OAuth 2.0 + an identity layer.**

OAuth 2.0 on its own is an **authorization** protocol ("can this app read
my calendar?"). OIDC adds **authentication** on top ("who is this user?").
Standardised in 2014; successor to OpenID 2.0 and a modern web/mobile
alternative to SAML.

In this stack, everything between the browser, the BFFs and Keycloak
speaks OIDC. SAML is only used on the KC ↔ P1 leg, because P1 is a legacy
SAML IdP that doesn't speak OIDC natively.

---

## Why OIDC exists

OAuth 2.0 is great at delegating access (Google's "Sign in with Google"
button, Facebook integrations) but doesn't say **who** the user is. Apps
worked around this by asking for an OAuth access token and then making an
extra "userinfo" API call — but there was no standard for what an identity
claim looked like or how session lifecycle worked.

OIDC fixes that with:

- A new kind of token — the **id_token** (always a JWT) — carrying
  standardised identity claims.
- A standardised `/userinfo` endpoint.
- Standardised scopes (`openid`, `profile`, `email`).
- Standardised logout flows (RP-initiated, back-channel, front-channel).
- A session-management spec.

---

## Key concepts

### Actors

| OIDC term | This demo |
|---|---|
| **End-User** | The actual person (`tim1`) |
| **OpenID Provider (OP)** | Keycloak — the entity that issues tokens |
| **Relying Party (RP)** | Each BFF (`bff-billing`, `bff-trading`) — the entity that trusts the OP |
| **User-Agent** | The browser |

### The three tokens

| Token | What it is | What it's for |
|---|---|---|
| **`access_token`** | Bearer token (JWT in KC; opaque in some other OPs) | Authorization to resource servers — "I'm allowed to call `/api/foo`" |
| **`id_token`** | Always a JWT (RS256-signed) | Authentication proof — "here's who I am and when I authenticated" |
| **`refresh_token`** | Opaque (or JWT in KC) | Exchange for a fresh access_token when the previous one expires |

### Standard claims in an id_token

Sample decoded id_token from this demo's logs (BFF Token Handler shape):

```json
{
  "iss": "https://auth.geowealth.int:5180/realms/demo-realm",
  "sub": "6dea5071-cd75-4bdd-a2fb-c81e1c8b4afc",
  "aud": "demo-billing-client",
  "exp": 1779890342,
  "iat": 1779888542,
  "auth_time": 1779888512,
  "nonce": "...",
  "sid": "1475a035-1edd-4c5d-...",
  "azp": "demo-billing-client",
  "preferred_username": "tim1",
  "email": "gidpncljautyubiiupia@fake.net",
  "name": "Tim Arnold",
  "roles": ["advisor","admin","client"],
  "firmCd": "1"
}
```

`iss`, `sub`, `aud`, `exp`, `iat` are mandatory. `nonce`, `auth_time`,
`sid`, `azp` are spec-defined. `roles` and `firmCd` are custom claims
emitted by the realm's `protocolMappers` (see `realm-export.json`).

---

## Login flow — Authorization Code with PKCE

This is the OIDC flow that the demo uses. Concrete trace of one billing
sign-in:

```
1. Browser: GET https://billing.geowealth.int:5184/
2. SPA: fetch /auth/me → 401
3. SPA: location.assign('/oauth/login/keycloak')
4. BFF: builds authorize URL with state + nonce + PKCE code_challenge
5. Browser → KC: GET /protocol/openid-connect/auth?
       client_id=demo-billing-client
       &response_type=code                 ← we want a code, not a token directly
       &scope=openid+email+profile         ← "openid" is mandatory for OIDC
       &redirect_uri=…/oauth/callback/keycloak
       &state=eyJ…                         ← anti-CSRF, BFF validates on the way back
       &nonce=…                            ← anti-replay value, embedded in id_token
       &code_challenge=…                   ← PKCE
       &code_challenge_method=S256
       &kc_idp_hint=p1                     ← KC-specific: skip its own login, broker to P1

6. KC → P1: SAML AuthnRequest (federated)
7. P1 → User: login form
8. User: enters credentials
9. P1 → KC: signed SAML Response
10. KC: first-broker-login auto-link by email → KC user found/created
11. KC → Browser: 302 to /oauth/callback/keycloak?code=ABC&state=eyJ…
12. Browser → BFF: GET /oauth/callback/keycloak?code=ABC&state=…

13. BFF → KC: POST /protocol/openid-connect/token (server-to-server)
       grant_type=authorization_code
       &code=ABC
       &redirect_uri=…
       &code_verifier=…                    ← the PKCE proof
       &client_id=demo-billing-client
       (&client_secret=… if confidential)

14. KC: validates code_verifier matches the original code_challenge
15. KC → BFF: { access_token, id_token, refresh_token, expires_in }
16. BFF: stores all three tokens in the server-side session,
         sets the GWSESSION cookie on the browser
17. BFF → Browser: 302 to /
18. SPA: fetch /auth/me → 200 → render dashboard
```

Why **Authorization Code** rather than another flow:

- **Implicit flow** (`response_type=token`): deprecated. The token came back
  through the URL fragment, which leaks easily.
- **Code flow**: the token is exchanged server-to-server in step 13, never
  passes through the URL.
- **PKCE** adds protection against stolen code: even if an attacker captures
  the `code` in step 11, they can't redeem it without the `code_verifier`,
  which never leaves the BFF.

---

## What PKCE is and why you need it

**PKCE** = "Proof Key for Code Exchange" (RFC 7636). It addresses a specific
attack: on mobile/SPA, the authorization code comes back through a redirect
URI that another app could intercept (a custom URL scheme on iOS, a
malicious browser extension, a misconfigured open redirector). Without
PKCE, intercepting the code is enough to mint tokens.

With PKCE:

1. The client generates a random `code_verifier` (high-entropy string).
2. The client sends `code_challenge = SHA256(code_verifier)` to `/authorize`.
3. KC remembers the `code_challenge` paired with the code it issued.
4. The client sends the `code_verifier` to `/token`.
5. KC checks `SHA256(code_verifier) == code_challenge`. Match → exchange
   succeeds.

The `code_verifier` never travels via the browser. An attacker who steals
the code can't redeem it.

This demo has it enabled — `code_challenge_method=S256` is visible in the
Location header of every `/oauth/login/keycloak` redirect.

---

## OIDC vs SAML — why this stack uses both

Both protocols solve "federated identity". The differences:

| Aspect | OIDC | SAML 2.0 |
|---|---|---|
| **Wire format** | JSON + JWT | XML |
| **Token transport** | HTTP, JSON bodies | HTTP-POST (form auto-submit) / HTTP-Redirect / SOAP |
| **Complexity** | Simpler | Heavier (XML signing, canonicalization, SOAP) |
| **Sweet spot** | Web / mobile / SPA | Enterprise (legacy IdPs, B2B SSO) |
| **Age** | 2014 | 2005 |
| **Library support** | Native in modern SDKs | Specialised libraries (OpenSAML in Java) |

Why this stack has both:

```
[Browser] —OIDC— [BFF] —OIDC— [Keycloak] —SAML— [P1]
                                  ↑
                          KC is bilingual:
                          OIDC OP outward,
                          SAML SP inward
```

- **The BFFs can't easily talk SAML**. Micronaut doesn't have a stable SAML
  library; OpenSAML works but is heavyweight. OIDC is native to
  `micronaut-security-oauth2`.
- **P1 is a legacy enterprise application** with a SAML IdP capability
  embedded into its Struts layer (`IdpSsoAction`, `IdpSloAction`). It
  could in principle expose OIDC too, but nobody has built that.
- **Keycloak is bilingual** — it speaks OIDC and SAML simultaneously. It
  acts as a translator: "I receive SAML from P1, I emit OIDC to the BFFs."

This is the canonical pattern in enterprise: modern BFF / SPA apps speak
OIDC to an identity hub, which federates out to legacy SAML IdPs.

---

## OIDC logout — three standardised flows

OIDC defines three logout mechanisms. This demo ends up using a fourth,
non-standard route — see below.

### 1. RP-Initiated Logout (OIDC spec)

The client redirects the browser to
`/protocol/openid-connect/logout?id_token_hint=…`. KC ends the SSO session
and redirects the browser back. This is the path that broke for this demo —
the `sid` claim in the stored id_token drifts from KC's current session id
over time, KC rejects it as `session_expired`, and the user sees KC's
"Do you want to log out?" confirm screen instead of a silent logout.
See `auth-flow-production-readiness.md` §9 for the full diagnosis.

### 2. Front-Channel Logout (OIDC spec)

When a logout fires, KC renders an HTML page with one iframe per registered
`frontchannel.logout.url`. Each iframe is same-origin for its respective
client, so it can run cleanup JS (wipe localStorage, broadcast a logout
event to sibling tabs). The demo has `domains/*/web/public/frontchannel-logout.html`
files that do exactly this.

### 3. Back-Channel Logout (OIDC Back-Channel Logout 1.0)

When the KC session ends, KC `POST`s a signed `logout_token` JWT to each
client's registered `backchannel.logout.url`. This is the most reliable
path (server-to-server, no browser involved). The demo's
`LogoutTokenValidator` and `BackchannelLogoutController` implement the
receiver side, and `SidSessionRegistry` correlates the OIDC `sid` back to
the BFF session that needs to die.

### 4. The workaround in this demo

`/auth/logout` currently redirects the browser to P1's
`/saml/idp/initiate-slo.do` (IdP-initiated SAML SLO) rather than KC's
RP-initiated logout. The reason is a P1-side bug (`GeowealthSessionListener.java`):
when KC sends a browser-mediated SAML LogoutRequest to P1, the
`JSESSIONID` cookie doesn't reach P1's SLO endpoint over the cross-port
hop, so P1 silently does nothing and its session stays alive. P1's
IdP-initiated SLO runs same-origin, so it can invalidate its own
HttpSession; it then emits the SAML LogoutRequest to KC via a browser
auto-submit POST, ending both sessions cleanly.

---

## How OIDC is wired in this demo, concretely

| Component | Role in OIDC terms |
|---|---|
| KC realm `demo-realm` | The OIDC OP for all three clients |
| `demo-billing-client` / `demo-trading-client` | OIDC clients (RPs) |
| The three BFFs (`bff-*`) | RP logic: hold tokens server-side, do code exchange + refresh |
| `KeycloakAuthenticationMapper` | Translates `OpenIdTokenResponse` + `OpenIdClaims` into the Micronaut `Authentication` stored in the session |
| `TokenRefreshFilter` | Runs the OIDC refresh_token grant before access tokens expire |
| `BackchannelLogoutController` + `LogoutTokenValidator` | OIDC Back-Channel Logout 1.0 receiver |
| `IdpHintFilter` | Appends `kc_idp_hint=p1` to the outbound authorize URL — KC-specific extension, not part of standard OIDC |
| Realm `protocolMappers` | OIDC custom claim plumbing — they decide how user attributes become JWT claims (`firmCd`, `roles`) |

---

## Further reading

- **OAuth 2.0 Authorization Framework** — RFC 6749
- **PKCE** — RFC 7636
- **OpenID Connect Core 1.0** — <https://openid.net/specs/openid-connect-core-1_0.html>
- **OpenID Connect Back-Channel Logout 1.0** — <https://openid.net/specs/openid-connect-backchannel-1_0.html>
- **OpenID Connect Front-Channel Logout 1.0** — <https://openid.net/specs/openid-connect-frontchannel-1_0.html>
- **OpenID Connect RP-Initiated Logout 1.0** — <https://openid.net/specs/openid-connect-rpinitiated-1_0.html>
- **OAuth 2.0 for Browser-Based Apps** (IETF draft) — the rationale behind
  the BFF / Token Handler pattern this stack adopts:
  <https://datatracker.ietf.org/doc/html/draft-ietf-oauth-browser-based-apps>

---

## Bottom line for this repo

OIDC is the modern web auth protocol Keycloak uses to talk to the BFFs.
At the P1 boundary it hands off to SAML, because P1 is a legacy SAML IdP.
Everything under `/oauth/*`, `/auth/*`, the three JWT token types, the
session lifecycle, the logout flows — that's all OIDC.
