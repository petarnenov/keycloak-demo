package demo.bff.core;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashMap;
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

    private static final Logger LOG = LoggerFactory.getLogger(LogoutTokenValidator.class);

    private static final String BACKCHANNEL_LOGOUT_EVENT =
            "http://schemas.openid.net/event/backchannel-logout";

    /** Bounded LRU of recently-seen {@code jti}s — defends against logout-token replay. */
    private static final int JTI_CACHE_MAX = 4096;

    private final String issuer;
    private final String clientId;
    private final ConfigurableJWTProcessor<SecurityContext> processor;
    private final Map<String, Boolean> seenJtis =
            Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > JTI_CACHE_MAX;
                }
            });

    public LogoutTokenValidator(
            @Value("${micronaut.security.oauth2.clients.keycloak.openid.issuer}") String issuer,
            @Value("${micronaut.security.oauth2.clients.keycloak.client-id}") String clientId,
            @Value("${KEYCLOAK_AUTH_SERVER_URL:http://keycloak:8080/realms/demo-realm}") String kcInternal) {
        this.issuer = issuer;
        this.clientId = clientId;
        URL jwksUrl;
        try {
            jwksUrl = new URL(kcInternal + "/protocol/openid-connect/certs");
        } catch (java.net.MalformedURLException e) {
            throw new IllegalStateException("bad JWKS url", e);
        }
        // Bound the JWKS fetch: the default RemoteJWKSet retriever has very long
        // timeouts, so a cold fetch (first back-channel logout after startup) can
        // stall for ~60s if KC is briefly slow — long enough that the BFF session
        // outlives a logout's poll window and a revoked user still looks
        // authenticated. Cap connect/read at 3s so a cold fetch is bounded.
        DefaultResourceRetriever retriever = new DefaultResourceRetriever(3000, 3000);
        RemoteJWKSet<SecurityContext> remote = new RemoteJWKSet<>(jwksUrl, retriever);
        JWSKeySelector<SecurityContext> keySelector =
                new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, remote);
        DefaultJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
        p.setJWSKeySelector(keySelector);
        // KC's back-channel logout token carries header typ "logout+jwt"; the
        // default processor would reject any typ other than JWT/absent.
        p.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(
                new JOSEObjectType("logout+jwt"), JOSEObjectType.JWT, null));
        this.processor = p;

        // Eagerly warm the JWKS cache so the FIRST real back-channel logout isn't
        // the one that pays the cold fetch — best-effort, never fail startup.
        try {
            remote.get(new JWKSelector(new JWKMatcher.Builder().build()), null);
        } catch (Exception warmEx) {
            LOG.warn("LogoutTokenValidator: JWKS warm-up skipped: {}", warmEx.toString());
        }
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
            // Spec §2.4/§2.6: a Logout Token MUST NOT contain a `nonce` — its
            // presence signals an ID Token being replayed as a logout token.
            if (c.getClaim("nonce") != null) {
                return null;
            }
            // Replay defence: reject a logout token whose `jti` we've already
            // acted on. KC always sets jti; if absent, fall through (the sid
            // teardown is idempotent anyway).
            String jti = c.getJWTID();
            if (jti != null && seenJtis.putIfAbsent(jti, Boolean.TRUE) != null) {
                return null;
            }
            return c.getStringClaim("sid");
        } catch (Exception e) {
            return null;
        }
    }
}
