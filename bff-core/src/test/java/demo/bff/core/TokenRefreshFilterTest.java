package demo.bff.core;

import io.micronaut.core.convert.value.MutableConvertibleValues;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.filters.SecurityFilter;
import io.micronaut.session.Session;
import io.micronaut.session.http.HttpSessionFilter;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class TokenRefreshFilterTest {

    private static TokenRefreshFilter filter(HttpClient kc) {
        return new TokenRefreshFilter(kc, "https://auth/realms/demo", "demo-client", "secret");
    }

    private static HttpClient kcReturning(Map<String, Object> tokens) {
        HttpClient kc = mock(HttpClient.class);
        when(kc.retrieve(any(HttpRequest.class), any(Argument.class))).thenReturn(Flux.just(tokens));
        return kc;
    }

    private static ServerFilterChain okChain() {
        ServerFilterChain chain = mock(ServerFilterChain.class);
        when(chain.proceed(any(HttpRequest.class))).thenReturn(Flux.just(HttpResponse.ok()));
        return chain;
    }

    private static HttpRequest<?> request(Authentication auth, Session session) {
        HttpRequest<?> req = mock(HttpRequest.class);
        when(req.getPath()).thenReturn("/api/data"); // non-logout path (logout skips refresh)
        when(req.getAttribute(SecurityFilter.AUTHENTICATION, Authentication.class))
                .thenReturn(Optional.ofNullable(auth));
        Map<CharSequence, Object> attrs = new HashMap<>();
        if (session != null) {
            attrs.put(HttpSessionFilter.SESSION_ATTRIBUTE, session);
        }
        when(req.getAttributes()).thenReturn(MutableConvertibleValues.of(attrs));
        return req;
    }

    private static Authentication authWith(Map<String, Object> attrs) {
        Authentication a = mock(Authentication.class);
        when(a.getAttributes()).thenReturn(attrs);
        when(a.getRoles()).thenReturn(Set.of("advisor"));
        when(a.getName()).thenReturn("u");
        return a;
    }

    private static HttpStatus statusOf(Publisher<MutableHttpResponse<?>> publisher) {
        MutableHttpResponse<?> out = Mono.from(publisher).block();
        return out.getStatus();
    }

    @Test
    void anonymousRequest_passesThrough() {
        HttpClient kc = mock(HttpClient.class);
        ServerFilterChain chain = okChain();
        assertEquals(HttpStatus.OK, statusOf(filter(kc).doFilter(request(null, null), chain)));
        verify(kc, never()).retrieve(any(HttpRequest.class), any(Argument.class));
    }

    @Test
    void missingTokens_passesThrough() {
        HttpClient kc = mock(HttpClient.class);
        Authentication a = authWith(Map.of("sub", "u")); // no access/refresh token
        assertEquals(HttpStatus.OK, statusOf(filter(kc).doFilter(request(a, null), okChain())));
        verify(kc, never()).retrieve(any(HttpRequest.class), any(Argument.class));
    }

    @Test
    void freshTokenWithRecentValidation_skipsRefresh() {
        HttpClient kc = mock(HttpClient.class);
        String access = JwtTestSupport.signedJwtExpiringIn(Map.of(), 3600); // not near expiry
        Authentication a = authWith(Map.of("accessToken", access, "refreshToken", "r"));

        Session session = mock(Session.class);
        when(session.get("kc.lastValidatedAt", Long.class))
                .thenReturn(Optional.of(System.currentTimeMillis()));

        assertEquals(HttpStatus.OK, statusOf(filter(kc).doFilter(request(a, session), okChain())));
        verify(kc, never()).retrieve(any(HttpRequest.class), any(Argument.class));
    }

    @Test
    void nearExpiryToken_refreshesAndProceeds() {
        String access = JwtTestSupport.signedJwtExpiringIn(Map.of(), -100); // already expired → refresh
        Map<String, Object> idClaims = new HashMap<>();
        idClaims.put("sub", "u");
        idClaims.put("preferred_username", "john");
        idClaims.put("roles", List.of("advisor", "billing-viewer"));
        idClaims.put("memberships", List.of("5:john"));
        String idToken = JwtTestSupport.signedJwt(idClaims, -1);

        Map<String, Object> tokens = new HashMap<>();
        tokens.put("access_token", "new-access");
        tokens.put("refresh_token", "new-refresh");
        tokens.put("id_token", idToken);

        HttpClient kc = kcReturning(tokens);
        Authentication a = authWith(Map.of("accessToken", access, "refreshToken", "r"));
        ServerFilterChain chain = okChain();

        HttpRequest<?> req = request(a, null);
        assertEquals(HttpStatus.OK, statusOf(filter(kc).doFilter(req, chain)));
        verify(kc, times(1)).retrieve(any(HttpRequest.class), any(Argument.class));
        verify(req).setAttribute(eq(SecurityFilter.AUTHENTICATION), any(Authentication.class));
    }

    @Test
    void refresh4xx_clearsSessionAndReturns401() {
        // M2: a 4xx from the token endpoint (invalid_grant / revoked) means the
        // session is genuinely dead → clear it and 401.
        String access = JwtTestSupport.signedJwtExpiringIn(Map.of(), -100);
        HttpClient kc = mock(HttpClient.class);
        when(kc.retrieve(any(HttpRequest.class), any(Argument.class)))
                .thenReturn(Flux.error(new io.micronaut.http.client.exceptions.HttpClientResponseException(
                        "invalid_grant", HttpResponse.badRequest())));

        Authentication a = authWith(Map.of("accessToken", access, "refreshToken", "r"));
        Session session = mock(Session.class);

        HttpStatus status = statusOf(filter(kc).doFilter(request(a, session), okChain()));
        assertEquals(HttpStatus.UNAUTHORIZED, status);
        verify(session).clear();
    }

    @Test
    void refreshTransientError_keepsSessionAndProceeds() {
        // M2: a transport error / 5xx is NOT proof the session is dead (KC may be
        // momentarily down) → keep the session and serve the request.
        String access = JwtTestSupport.signedJwtExpiringIn(Map.of(), -100);
        HttpClient kc = mock(HttpClient.class);
        when(kc.retrieve(any(HttpRequest.class), any(Argument.class)))
                .thenReturn(Flux.error(new RuntimeException("connection refused")));

        Authentication a = authWith(Map.of("accessToken", access, "refreshToken", "r"));
        Session session = mock(Session.class);

        HttpStatus status = statusOf(filter(kc).doFilter(request(a, session), okChain()));
        assertEquals(HttpStatus.OK, status);
        verify(session, never()).clear();
    }

    @Test
    void unparseableRefreshedIdToken_proceedsWithoutRebuild() {
        String access = JwtTestSupport.signedJwtExpiringIn(Map.of(), -100);
        Map<String, Object> tokens = new HashMap<>();
        tokens.put("access_token", "new-access");
        // no id_token → rebuild returns null → just proceed
        HttpClient kc = kcReturning(tokens);

        Authentication a = authWith(Map.of("accessToken", access, "refreshToken", "r"));
        assertEquals(HttpStatus.OK, statusOf(filter(kc).doFilter(request(a, null), okChain())));
    }

    @Test
    void getOrder_isAfterSecurity() {
        org.junit.jupiter.api.Assertions.assertEquals(
                io.micronaut.http.filter.ServerFilterPhase.SECURITY.after(),
                filter(mock(HttpClient.class)).getOrder());
    }
}
