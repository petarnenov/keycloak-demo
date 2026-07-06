package demo.bff.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LogoutTokenValidatorTest {

    private static final String ISSUER = "https://auth/realms/demo";
    private static final String CLIENT = "demo-client";
    private static final String KC_INTERNAL = "http://keycloak:8080/realms/demo-realm";

    @Test
    void constructor_rejectsMalformedJwksUrl() {
        assertThrows(IllegalStateException.class,
                () -> new LogoutTokenValidator(ISSUER, CLIENT, "no-scheme-here"));
    }

    @Test
    void validate_returnsNullForGarbageToken() {
        LogoutTokenValidator v = new LogoutTokenValidator(ISSUER, CLIENT, KC_INTERNAL);
        assertNull(v.validateAndGetSid("not-a-jwt"));
    }

    @Test
    void validate_returnsNullForStructurallyValidButUnsignedToken() {
        LogoutTokenValidator v = new LogoutTokenValidator(ISSUER, CLIENT, KC_INTERNAL);
        // Well-formed three-part token, but the signature can't be verified
        // against KC's (unreachable) JWKS → processor.process throws → null.
        String token = JwtTestSupport.compactJwtWithPayload(
                "{\"iss\":\"" + ISSUER + "\",\"sid\":\"s-1\"}");
        assertNull(v.validateAndGetSid(token));
    }

    @Test
    void validate_returnsNullForNull() {
        LogoutTokenValidator v = new LogoutTokenValidator(ISSUER, CLIENT, KC_INTERNAL);
        assertNull(v.validateAndGetSid(null));
    }
}
