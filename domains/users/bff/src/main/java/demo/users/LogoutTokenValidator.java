package demo.users;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import java.net.URL;
import java.util.Map;

/**
 * Verifies the OIDC Back-Channel Logout token Keycloak POSTs to the BFF
 * (server-to-server, on the docker network). Per the spec we check: RS256
 * signature against KC's JWKS, {@code iss} == our issuer, {@code aud} contains
 * our client id, and the {@code events} claim carries the backchannel-logout
 * event. On success the {@code sid} is returned so the matching BFF session can
 * be destroyed.
 */
@Singleton
public class LogoutTokenValidator {

    private static final String BACKCHANNEL_LOGOUT_EVENT =
            "http://schemas.openid.net/event/backchannel-logout";

    private final String issuer;
    private final String clientId;
    private final ConfigurableJWTProcessor<SecurityContext> processor;

    public LogoutTokenValidator(
            @Value("${micronaut.security.oauth2.clients.keycloak.openid.issuer}") String issuer,
            @Value("${micronaut.security.oauth2.clients.keycloak.client-id:demo-users-client}") String clientId,
            @Value("${KEYCLOAK_AUTH_SERVER_URL:http://keycloak:8080/realms/demo-realm}") String kcInternal) {
        this.issuer = issuer;
        this.clientId = clientId;
        URL jwksUrl;
        try {
            jwksUrl = new URL(kcInternal + "/protocol/openid-connect/certs");
        } catch (java.net.MalformedURLException e) {
            throw new IllegalStateException("bad JWKS url", e);
        }
        JWKSource<SecurityContext> keySource = new RemoteJWKSet<>(jwksUrl);
        JWSKeySelector<SecurityContext> keySelector =
                new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource);
        DefaultJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
        p.setJWSKeySelector(keySelector);
        // KC's back-channel logout token carries header typ "logout+jwt"; the
        // default processor would reject any typ other than JWT/absent.
        p.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(
                new JOSEObjectType("logout+jwt"), JOSEObjectType.JWT, null));
        this.processor = p;
    }

    /** @return the {@code sid} if the logout token is valid, otherwise {@code null}. */
    public String validateAndGetSid(String logoutToken) {
        try {
            JWTClaimsSet c = processor.process(logoutToken, null);
            if (!issuer.equals(c.getIssuer())) {
                return null;
            }
            if (c.getAudience() == null || !c.getAudience().contains(clientId)) {
                return null;
            }
            Object events = c.getClaim("events");
            if (!(events instanceof Map) || !((Map<?, ?>) events).containsKey(BACKCHANNEL_LOGOUT_EVENT)) {
                return null;
            }
            return c.getStringClaim("sid");
        } catch (Exception e) {
            return null;
        }
    }
}
