# Production TLS topology — SSO bridge

Scope: the four HTTP legs that carry SAML or OIDC traffic between the
browser, the keycloak-demo shell, Keycloak, and P1 must terminate
TLS. This document describes the reference topology — code wiring and
cert provisioning are operational tasks (Bamboo / Vault / DNS).

## Listeners

| # | Leg | Today | Production listener |
|---|---|---|---|
| 1 | Browser ↔ React shell `:5173` | `http://` Vite dev/preview | HTTPS via reverse proxy (nginx / Cloudflare / ALB) terminating on `:443`, proxying to the shell container on the internal network |
| 2 | Browser ↔ Keycloak `:8898` | `http://` Keycloak dev | HTTPS via Keycloak's own HTTPS port (`KC_HTTPS_PORT=8443`) **or** a reverse proxy in front; Keycloak's `KC_PROXY=edge` mode lets a TLS-terminating proxy forward HTTP back-channel |
| 3 | Browser ↔ P1 `:8080` | `http://` Tomcat connector | HTTPS via Tomcat's `Http11NioProtocol` connector on `:8443` configured in `server.xml` **or** a reverse proxy in front; `setenv.sh` must set `-Dhttps.redirect.forced=yes` and the existing P1 `RemoteIpValve` honors `X-Forwarded-*` |
| 4 | Keycloak ↔ P1 back-channel (broker SLO) | `http://host.docker.internal:8080` | HTTPS to a stable P1 hostname; Keycloak must trust the P1 cert chain (mount the CA into Keycloak's `truststore`, set `KC_HTTP_RELATIVE_PATH` / `KC_HOSTNAME_URL`) |

## Cert provisioning

Single wildcard `*.<env>.geowealth.com` covers all three listener hostnames:

- `shell.<env>.geowealth.com` → shell ingress
- `auth.<env>.geowealth.com` → Keycloak ingress
- `p1.<env>.geowealth.com` → P1 ingress

Use ACME (Let's Encrypt or internal CA) with 90-day rotation, automated
through cert-manager (k8s) or Caddy (VM). The IdP signing key in
`P1_IDP_KEYSTORE_PATH` is a separate trust domain — see
[`PROD-KEYSTORE-PROVISIONING.md`](PROD-KEYSTORE-PROVISIONING.md) — and
must NOT be conflated with the TLS server cert.

## Configuration touchpoints

### Tomcat / P1

In `server.xml`:

```xml
<Connector port="8443" protocol="org.apache.coyote.http11.Http11NioProtocol"
           maxThreads="200" SSLEnabled="true" scheme="https" secure="true"
           defaultSSLHostConfigName="p1.prod.geowealth.com">
    <SSLHostConfig hostName="p1.prod.geowealth.com">
        <Certificate certificateKeystoreFile="/etc/tls/p1-keystore.p12"
                     certificateKeystorePassword="${TLS_KEYSTORE_PASSWORD}"
                     type="RSA"/>
    </SSLHostConfig>
</Connector>
<!-- behind a TLS-terminating proxy, keep :8080 and add RemoteIpValve -->
<Valve className="org.apache.catalina.valves.RemoteIpValve"
       remoteIpHeader="X-Forwarded-For"
       protocolHeader="X-Forwarded-Proto"
       internalProxies="..."/>
```

`setenv.sh` adds:

```bash
export TLS_KEYSTORE_PASSWORD=...  # sourced from /etc/p1-secrets/env.sh
CATALINA_OPTS="$CATALINA_OPTS -Dhttps.redirect.forced=yes"
```

`IdpMetadataAction.baseUrl()` already honors `request.getScheme()` /
`request.getServerName()` — once `RemoteIpValve` is wired, the emitted
SAML metadata advertises `https://p1.prod.geowealth.com/saml/idp` with
no code change.

### Keycloak

```bash
KC_PROXY=edge                # for proxy-terminated TLS
# or
KC_HTTPS_PORT=8443
KC_HTTPS_CERTIFICATE_FILE=/etc/tls/kc-cert.pem
KC_HTTPS_CERTIFICATE_KEY_FILE=/etc/tls/kc-key.pem

KC_HOSTNAME=auth.prod.geowealth.com
KC_HOSTNAME_STRICT=true
```

The realm-export's `singleSignOnServiceUrl` and `singleLogoutServiceUrl`
must be updated for prod:

```json
"singleSignOnServiceUrl": "https://p1.prod.geowealth.com/saml/idp/sso.do",
"singleLogoutServiceUrl": "https://p1.prod.geowealth.com/saml/idp/slo.do"
```

### P1 → Keycloak back-channel

`P1_IDP_KEYCLOAK_ACS_URL` and `P1_IDP_KEYCLOAK_SLO_URL` env vars on
the Tomcat side flip to HTTPS:

```bash
export P1_IDP_KEYCLOAK_ACS_URL="https://auth.prod.geowealth.com/realms/demo-realm/broker/p1/endpoint"
export P1_IDP_KEYCLOAK_SLO_URL="https://auth.prod.geowealth.com/realms/demo-realm/broker/p1/endpoint"
```

If P1's JVM doesn't trust the Keycloak CA out of the box, add it to
the Java truststore or pass `-Djavax.net.ssl.trustStore=...` in
`setenv.sh`.

## Verification (production runbook)

1. `curl -I https://shell.prod.geowealth.com/` returns 200 with a
   valid cert (no `-k`).
2. `curl -I https://auth.prod.geowealth.com/realms/demo-realm/.well-known/openid-configuration` returns 200.
3. `curl -I https://p1.prod.geowealth.com/saml/idp/metadata.do` returns
   200 + the `EntityDescriptor` advertises `https://p1.prod.geowealth.com/saml/idp`.
4. End-to-end click-through from the P1 sidebar entry lands in the
   shell with no mixed-content warnings; browser dev tools show every
   request on `https://`.

## What this does NOT cover

- Mutual TLS between P1 and Keycloak (mTLS) — overkill for SAML, where
  message-level signatures already authenticate the IdP.
- HSTS preloading — operational decision per env.
- Certificate rotation cadence — owned by the cert-manager or the
  ACME automation, not by this codebase.
