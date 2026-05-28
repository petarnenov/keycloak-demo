package demo.billing;

import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.security.authentication.Authentication;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link StepUpGuard}. The guard is the step-up enforcement
 * primitive for sensitive endpoints — verifies fresh-auth passes through and
 * linked-identity sessions get a populated 401 with RFC 9470 headers.
 */
class StepUpGuardTest {

    @Test
    void freshAuth_returnsNull_letsRequestThrough() {
        Authentication auth = Authentication.build(
                "tim1",
                java.util.List.of("billing-admin"),
                Map.of("sub", "uuid-1", "linkedIdentityAcr", ""));
        assertNull(StepUpGuard.requireFreshAuthOr401(auth),
                "Empty linkedIdentityAcr should let the request through (fresh auth)");
    }

    @Test
    void freshAuth_missingAttribute_returnsNull() {
        Authentication auth = Authentication.build(
                "tim1",
                java.util.List.of("billing-admin"),
                Map.of("sub", "uuid-1"));
        assertNull(StepUpGuard.requireFreshAuthOr401(auth),
                "Missing linkedIdentityAcr should let the request through (fresh auth)");
    }

    @Test
    void linkedSwap_returns401WithStepUpHeader() {
        Authentication auth = Authentication.build(
                "tim1",
                java.util.List.of("billing-admin"),
                Map.of("sub", "uuid-1", "linkedIdentityAcr",
                        StepUpGuard.LINKED_IDENTITY_ACR));
        MutableHttpResponse<?> resp = StepUpGuard.requireFreshAuthOr401(auth);
        assertNotNull(resp, "Linked-identity session should be blocked");
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatus());

        String wwwAuth = resp.getHeaders().get("WWW-Authenticate");
        assertNotNull(wwwAuth, "RFC 9470 requires the WWW-Authenticate header");
        assertTrue(wwwAuth.contains("Bearer"));
        assertTrue(wwwAuth.contains("error=\"insufficient_user_authentication\""));
        assertTrue(wwwAuth.contains("acr_values=\""));
    }

    @Test
    void unknownLinkedAcrUri_alsoBlocks_failClosed() {
        // Defence in depth: any non-empty linkedIdentityAcr value triggers
        // step-up, even an unrecognized one — a misconfiguration cannot
        // silently bypass the guard.
        Authentication auth = Authentication.build(
                "tim1",
                java.util.List.of("billing-admin"),
                Map.of("sub", "uuid-1", "linkedIdentityAcr", "urn:something:unknown"));
        MutableHttpResponse<?> resp = StepUpGuard.requireFreshAuthOr401(auth);
        assertNotNull(resp, "Unknown linkedIdentityAcr must still trigger step-up");
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatus());
    }
}
