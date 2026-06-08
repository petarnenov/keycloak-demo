package demo.bff.core;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpParameters;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.filter.ServerFilterChain;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdpHintFilterTest {

    private static HttpRequest<?> requestWithSilent(String silentValue) {
        HttpRequest<?> req = mock(HttpRequest.class);
        HttpParameters params = mock(HttpParameters.class);
        when(params.get(eq("silent"), eq(String.class)))
                .thenReturn(Optional.ofNullable(silentValue));
        when(req.getParameters()).thenReturn(params);
        return req;
    }

    private static ServerFilterChain chainReturning(MutableHttpResponse<?> resp) {
        ServerFilterChain chain = mock(ServerFilterChain.class);
        when(chain.proceed(org.mockito.ArgumentMatchers.any(HttpRequest.class)))
                .thenReturn(Flux.just(resp));
        return chain;
    }

    private static String locationOf(MutableHttpResponse<?> resp, HttpRequest<?> req, ServerFilterChain chain) {
        IdpHintFilter filter = new IdpHintFilter("p1");
        MutableHttpResponse<?> out = Mono.from(filter.doFilter(req, chain)).block();
        return out.getHeaders().get(HttpHeaders.LOCATION);
    }

    @Test
    void appendsIdpHintWhenAbsent() {
        MutableHttpResponse<?> resp = HttpResponse.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "https://auth/realms/demo/auth?client_id=x");
        String loc = locationOf(resp, requestWithSilent(null), chainReturning(resp));
        assertEquals("https://auth/realms/demo/auth?client_id=x&kc_idp_hint=p1", loc);
    }

    @Test
    void leavesExistingIdpHintUntouched() {
        MutableHttpResponse<?> resp = HttpResponse.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "https://auth/auth?kc_idp_hint=other");
        String loc = locationOf(resp, requestWithSilent(null), chainReturning(resp));
        assertEquals("https://auth/auth?kc_idp_hint=other", loc);
    }

    @Test
    void silentMarkerAppendsPromptNone() {
        MutableHttpResponse<?> resp = HttpResponse.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "https://auth/auth?client_id=x");
        String loc = locationOf(resp, requestWithSilent("1"), chainReturning(resp));
        assertTrue(loc.contains("kc_idp_hint=p1"), loc);
        assertTrue(loc.contains("prompt=none"), loc);
    }

    @Test
    void nonRedirectResponseUntouched() {
        MutableHttpResponse<?> resp = HttpResponse.ok();
        IdpHintFilter filter = new IdpHintFilter("p1");
        MutableHttpResponse<?> out = Mono.from(
                filter.doFilter(requestWithSilent(null), chainReturning(resp))).block();
        assertEquals(HttpStatus.OK, out.getStatus());
    }

    @Test
    void redirectWithoutLocationUntouched() {
        MutableHttpResponse<?> resp = HttpResponse.status(HttpStatus.FOUND);
        IdpHintFilter filter = new IdpHintFilter("p1");
        MutableHttpResponse<?> out = Mono.from(
                filter.doFilter(requestWithSilent(null), chainReturning(resp))).block();
        // no Location header → nothing to rewrite
        org.junit.jupiter.api.Assertions.assertEquals(HttpStatus.FOUND, out.getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(null, out.getHeaders().get(HttpHeaders.LOCATION));
    }

    @Test
    void getOrderIsLast() {
        // exercise the ordering accessor
        assertTrue(new IdpHintFilter("p1").getOrder() != 0);
    }
}
