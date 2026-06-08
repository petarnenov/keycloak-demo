package demo.bff.core;

import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class P1AuthzClientTest {

    private static final long TTL = 60_000L;

    private static BlockingHttpClient blockingReturning(Map<String, Object> body) {
        BlockingHttpClient blocking = mock(BlockingHttpClient.class);
        when(blocking.retrieve(any(HttpRequest.class), any(Argument.class))).thenReturn(body);
        return blocking;
    }

    private static HttpClient clientWith(BlockingHttpClient blocking) {
        HttpClient http = mock(HttpClient.class);
        when(http.toBlocking()).thenReturn(blocking);
        return http;
    }

    @Test
    void fineEnabled_reflectsConfig() {
        assertTrue(new P1AuthzClient(mock(HttpClient.class), true, TTL).fineEnabled());
        assertFalse(new P1AuthzClient(mock(HttpClient.class), false, TTL).fineEnabled());
    }

    @Test
    void permissions_returnsMapAndCachesPerSub() {
        BlockingHttpClient blocking = blockingReturning(
                Map.of("permissions", Map.of("12_1", true)));
        P1AuthzClient c = new P1AuthzClient(clientWith(blocking), true, TTL);

        Map<String, Object> first = c.permissions("Bearer t", "u");
        Map<String, Object> second = c.permissions("Bearer t", "u");

        assertEquals(Boolean.TRUE, first.get("12_1"));
        assertEquals(first, second);
        // cached: only one network hit for the same sub
        verify(blocking, times(1)).retrieve(any(HttpRequest.class), any(Argument.class));
    }

    @Test
    void permissions_failsClosedToEmptyMapOnError() {
        BlockingHttpClient blocking = mock(BlockingHttpClient.class);
        when(blocking.retrieve(any(HttpRequest.class), any(Argument.class)))
                .thenThrow(new RuntimeException("P1 down"));
        P1AuthzClient c = new P1AuthzClient(clientWith(blocking), true, TTL);

        assertTrue(c.permissions("Bearer t", "u").isEmpty());
    }

    @Test
    void hasPermission_trueOnlyWhenKeyPresentAndTrue() {
        BlockingHttpClient blocking = blockingReturning(
                Map.of("permissions", Map.of("12_1", true, "12_2", "false")));
        P1AuthzClient c = new P1AuthzClient(clientWith(blocking), true, TTL);

        assertTrue(c.hasPermission("Bearer t", "u", 12, 1));
        assertFalse(c.hasPermission("Bearer t", "u", 12, 2));
        assertFalse(c.hasPermission("Bearer t", "u", 99, 9));
    }

    @Test
    void can_trueWhenAllowed_falseOnError() {
        P1AuthzClient ok = new P1AuthzClient(
                clientWith(blockingReturning(Map.of("allowed", true))), true, TTL);
        assertTrue(ok.can("Bearer t", 12, 1, "obj-1"));

        BlockingHttpClient boom = mock(BlockingHttpClient.class);
        when(boom.retrieve(any(HttpRequest.class), any(Argument.class)))
                .thenThrow(new RuntimeException("down"));
        P1AuthzClient err = new P1AuthzClient(clientWith(boom), true, TTL);
        assertFalse(err.can("Bearer t", 12, 1, "obj-1"));
    }

    @Test
    void refine_emptyInputShortCircuits() {
        HttpClient http = mock(HttpClient.class);
        P1AuthzClient c = new P1AuthzClient(http, true, TTL);

        assertTrue(c.refine("Bearer t", 12, 1, List.of()).isEmpty());
        assertTrue(c.refine("Bearer t", 12, 1, null).isEmpty());
        // never touched the network
        verify(http, times(0)).toBlocking();
    }

    @Test
    void refine_returnsAllowedSubset() {
        BlockingHttpClient blocking = blockingReturning(
                Map.of("allowed", List.of("a", "c")));
        P1AuthzClient c = new P1AuthzClient(clientWith(blocking), true, TTL);

        assertEquals(List.of("a", "c"), c.refine("Bearer t", 12, 1, List.of("a", "b", "c")));
    }

    @Test
    void refine_failsClosedToEmptyOnError() {
        BlockingHttpClient blocking = mock(BlockingHttpClient.class);
        when(blocking.retrieve(any(HttpRequest.class), any(Argument.class)))
                .thenThrow(new RuntimeException("down"));
        P1AuthzClient c = new P1AuthzClient(clientWith(blocking), true, TTL);

        assertTrue(c.refine("Bearer t", 12, 1, List.of("a")).isEmpty());
    }
}
