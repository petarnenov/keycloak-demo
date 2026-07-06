package demo.bff.core;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

/**
 * Helpers for fabricating JWTs in unit tests. Two flavours:
 *
 * <ul>
 *   <li>{@link #signedJwt} — a structurally valid, RS256-signed JWT that
 *       {@code SignedJWT.parse(...)} accepts (used by code paths that parse the
 *       id_token / access_token: {@code TokenRefreshFilter}). The signature is
 *       over a throwaway key — these tests only parse, never verify.</li>
 *   <li>{@link #compactJwtWithPayload} — a {@code header.payload.sig} string with
 *       a chosen base64url payload, for code that base64-decodes the payload
 *       directly without parsing the JWS ({@code KeycloakAuthenticationMapper}).</li>
 * </ul>
 */
final class JwtTestSupport {

    // One throwaway RSA key for the whole suite — 2048-bit keygen is the slow
    // part, so amortise it across every signed token we mint.
    private static final RSAPrivateKey PRIVATE_KEY;

    static {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            PRIVATE_KEY = (RSAPrivateKey) kp.getPrivate();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private JwtTestSupport() {
    }

    /** RS256-signed JWT carrying the given claims; {@code expEpochSeconds} &lt; 0 omits exp. */
    static String signedJwt(Map<String, Object> claims, long expEpochSeconds) {
        try {
            JWTClaimsSet.Builder b = new JWTClaimsSet.Builder();
            claims.forEach(b::claim);
            if (expEpochSeconds >= 0) {
                b.expirationTime(Date.from(Instant.ofEpochSecond(expEpochSeconds)));
            }
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), b.build());
            jwt.sign(new RSASSASigner(PRIVATE_KEY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Convenience: a signed JWT whose exp is {@code secondsFromNow} from now. */
    static String signedJwtExpiringIn(Map<String, Object> claims, long secondsFromNow) {
        return signedJwt(claims, Instant.now().getEpochSecond() + secondsFromNow);
    }

    /** A {@code header.payload.sig} string with the given raw JSON payload base64url-encoded. */
    static String compactJwtWithPayload(String jsonPayload) {
        String header = base64Url("{\"alg\":\"none\"}");
        String payload = base64Url(jsonPayload);
        return header + "." + payload + ".sig";
    }

    private static String base64Url(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
