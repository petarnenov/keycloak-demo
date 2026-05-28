package demo.trading;

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
 * Unit tests for trading-side {@link StepUpGuard} — mirrors the billing-side
 * StepUpGuardTest. Both BFFs implement the same step-up contract.
 */
class StepUpGuardTest {

    @Test
    void freshAuth_returnsNull_letsRequestThrough() {
        Authentication auth = Authentication.build(
                "tim1",
                java.util.List.of("trading-trader"),
                Map.of("sub", "uuid-1", "linkedIdentityAcr", ""));
        assertNull(StepUpGuard.requireFreshAuthOr401(auth));
    }

    @Test
    void freshAuth_missingAttribute_returnsNull() {
        Authentication auth = Authentication.build(
                "tim1",
                java.util.List.of("trading-trader"),
                Map.of("sub", "uuid-1"));
        assertNull(StepUpGuard.requireFreshAuthOr401(auth));
    }

    @Test
    void linkedSwap_returns401WithStepUpHeader() {
        Authentication auth = Authentication.build(
                "tim1",
                java.util.List.of("trading-trader"),
                Map.of("sub", "uuid-1", "linkedIdentityAcr",
                        StepUpGuard.LINKED_IDENTITY_ACR));
        MutableHttpResponse<?> resp = StepUpGuard.requireFreshAuthOr401(auth);
        assertNotNull(resp);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatus());

        String wwwAuth = resp.getHeaders().get("WWW-Authenticate");
        assertNotNull(wwwAuth);
        assertTrue(wwwAuth.contains("Bearer"));
        assertTrue(wwwAuth.contains("error=\"insufficient_user_authentication\""));
        assertTrue(wwwAuth.contains("acr_values=\""));
    }

    @Test
    void unknownLinkedAcrUri_alsoBlocks_failClosed() {
        Authentication auth = Authentication.build(
                "tim1",
                java.util.List.of("trading-trader"),
                Map.of("sub", "uuid-1", "linkedIdentityAcr", "urn:something:unknown"));
        MutableHttpResponse<?> resp = StepUpGuard.requireFreshAuthOr401(auth);
        assertNotNull(resp);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatus());
    }
}
