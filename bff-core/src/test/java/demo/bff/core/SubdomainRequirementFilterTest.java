package demo.bff.core;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.security.authentication.Authentication;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubdomainRequirementFilterTest {

    private static HttpRequest<?> requestWith(Authentication auth) {
        HttpRequest<?> req = mock(HttpRequest.class);
        when(req.getUserPrincipal(Authentication.class)).thenReturn(Optional.ofNullable(auth));
        return req;
    }

    private static ServerFilterChain okChain() {
        ServerFilterChain chain = mock(ServerFilterChain.class);
        when(chain.proceed(any(HttpRequest.class))).thenReturn(Flux.just(HttpResponse.ok()));
        return chain;
    }

    private static Authentication authWithMemberships(String packed) {
        Authentication a = mock(Authentication.class);
        when(a.getAttributes()).thenReturn(Map.of("memberships", packed));
        return a;
    }

    private static HttpStatus run(SubdomainRequirementFilter f, HttpRequest<?> req, ServerFilterChain chain) {
        MutableHttpResponse<?> out = Mono.from(f.doFilter(req, chain)).block();
        return out.getStatus();
    }

    @Test
    void untypedRequirement_proceeds() {
        SubdomainRequirement r = new SubdomainRequirement(); // type null
        SubdomainRequirementFilter f = new SubdomainRequirementFilter(r, mock(Tier23Gate.class));
        assertEquals(HttpStatus.OK, run(f, requestWith(null), okChain()));
    }

    @Test
    void typedButAnonymous_proceedsToLetSecurityLayerAnswer() {
        SubdomainRequirement r = new SubdomainRequirement();
        r.setType("firm");
        r.setFirmCd(5);
        SubdomainRequirementFilter f = new SubdomainRequirementFilter(r, mock(Tier23Gate.class));
        assertEquals(HttpStatus.OK, run(f, requestWith(null), okChain()));
    }

    @Test
    void firmType_withMembership_proceeds() {
        SubdomainRequirement r = new SubdomainRequirement();
        r.setType("firm");
        r.setFirmCd(5);
        SubdomainRequirementFilter f = new SubdomainRequirementFilter(r, mock(Tier23Gate.class));
        assertEquals(HttpStatus.OK, run(f, requestWith(authWithMemberships("5:john")), okChain()));
    }

    @Test
    void firmType_withoutMembership_forbidden() {
        SubdomainRequirement r = new SubdomainRequirement();
        r.setType("firm");
        r.setFirmCd(5);
        SubdomainRequirementFilter f = new SubdomainRequirementFilter(r, mock(Tier23Gate.class));
        assertEquals(HttpStatus.FORBIDDEN, run(f, requestWith(authWithMemberships("7:jane")), okChain()));
    }

    @Test
    void resourceType_permissionHeld_proceeds() {
        SubdomainRequirement r = new SubdomainRequirement();
        r.setType("resource");
        r.setObjectType(12);
        r.setPermission(1);
        Tier23Gate gate = mock(Tier23Gate.class); // require() returns normally
        SubdomainRequirementFilter f = new SubdomainRequirementFilter(r, gate);
        assertEquals(HttpStatus.OK, run(f, requestWith(authWithMemberships("")), okChain()));
    }

    @Test
    void resourceType_permissionDenied_forbidden() {
        SubdomainRequirement r = new SubdomainRequirement();
        r.setType("resource");
        r.setObjectType(12);
        r.setPermission(1);
        Tier23Gate gate = mock(Tier23Gate.class);
        doThrow(new HttpStatusException(HttpStatus.FORBIDDEN, "denied"))
                .when(gate).require(any(), anyInt(), anyInt());
        SubdomainRequirementFilter f = new SubdomainRequirementFilter(r, gate);
        assertEquals(HttpStatus.FORBIDDEN, run(f, requestWith(authWithMemberships("")), okChain()));
    }
}
