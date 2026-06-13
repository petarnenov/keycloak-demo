package demo.bff.core;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.security.authentication.Authentication;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit tests for the forward-auth pieces: {@link HeaderIdentity},
 *  {@link SubdomainAuthorizer} and {@link ForwardAuthController}. */
class ForwardAuthTest {

    // ---- HeaderIdentity: rebuild an Authentication from X-Auth-* headers --------

    @Test
    void headerIdentity_buildsAuthenticationFromHeaders() {
        HttpRequest<?> req = mock(HttpRequest.class);
        io.micronaut.http.HttpHeaders h = mock(io.micronaut.http.HttpHeaders.class);
        when(req.getHeaders()).thenReturn(h);
        when(h.get("X-Auth-Username")).thenReturn("p-1");
        when(h.get("X-Auth-Roles")).thenReturn("advisor,gwAdmin");
        when(h.get("X-Auth-Sub")).thenReturn("kc-sub");
        when(h.get("X-Auth-Person-Id")).thenReturn("p-1");
        when(h.get("X-Auth-Firm-Cd")).thenReturn("5");
        when(h.get("X-Auth-Access-Token")).thenReturn("tok");
        when(h.get("X-Auth-Memberships")).thenReturn("5:john,9:jane");

        Authentication a = HeaderIdentity.from(req);

        assertEquals("p-1", a.getName());
        assertTrue(a.getRoles().contains("gwAdmin"));
        assertEquals("5", AuthClaims.firmCd(a));
        assertEquals("tok", a.getAttributes().get("accessToken"));
        // comma-joined memberships are re-split into the canonical list
        assertEquals(List.of("5:john", "9:jane"), AuthClaims.memberships(a));
    }

    @Test
    void headerIdentity_isPresent_reflectsUsernameHeader() {
        HttpRequest<?> req = mock(HttpRequest.class);
        io.micronaut.http.HttpHeaders h = mock(io.micronaut.http.HttpHeaders.class);
        when(req.getHeaders()).thenReturn(h);
        when(h.get("X-Auth-Username")).thenReturn(null);
        assertEquals(false, HeaderIdentity.isPresent(req));
        when(h.get("X-Auth-Username")).thenReturn("p-1");
        assertEquals(true, HeaderIdentity.isPresent(req));
    }

    // ---- SubdomainAuthorizer ----------------------------------------------------

    private static Authentication authWith(String packedMemberships) {
        Authentication a = mock(Authentication.class);
        when(a.getAttributes()).thenReturn(Map.of("memberships", packedMemberships));
        return a;
    }

    @Test
    void authorizer_untyped_allows() {
        SubdomainRequirement r = new SubdomainRequirement(); // type null
        new SubdomainAuthorizer(r, mock(Tier23Gate.class)).authorize(authWith("")); // no throw
    }

    @Test
    void authorizer_firm_withoutMembership_throwsForbidden() {
        SubdomainRequirement r = new SubdomainRequirement();
        r.setType("firm");
        r.setFirmCd(5);
        SubdomainAuthorizer a = new SubdomainAuthorizer(r, mock(Tier23Gate.class));
        HttpStatusException ex = org.junit.jupiter.api.Assertions.assertThrows(
                HttpStatusException.class, () -> a.authorize(authWith("7:jane")));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    void authorizer_resource_delegatesToGate() {
        SubdomainRequirement r = new SubdomainRequirement();
        r.setType("resource");
        r.setObjectType(59);
        r.setPermission(5);
        Tier23Gate gate = mock(Tier23Gate.class);
        doThrow(new HttpStatusException(HttpStatus.FORBIDDEN, "denied"))
                .when(gate).require(any(), anyInt(), anyInt());
        SubdomainAuthorizer a = new SubdomainAuthorizer(r, gate);
        org.junit.jupiter.api.Assertions.assertThrows(HttpStatusException.class,
                () -> a.authorize(authWith("")));
    }

    // ---- ForwardAuthController: 200 + headers on allow, 403 on deny -------------

    private static Authentication fullAuth() {
        Authentication a = mock(Authentication.class);
        when(a.getName()).thenReturn("p-1");
        when(a.getRoles()).thenReturn(java.util.Set.of("advisor"));
        when(a.getAttributes()).thenReturn(Map.of(
                "sub", "kc-sub", "personId", "p-1", "firmCd", "5",
                "accessToken", "tok", "memberships", "5:john"));
        return a;
    }

    private static HttpRequest<?> reqWithHost(String forwardedHost) {
        HttpRequest<?> req = mock(HttpRequest.class);
        io.micronaut.http.HttpHeaders h = mock(io.micronaut.http.HttpHeaders.class);
        when(req.getHeaders()).thenReturn(h);
        when(h.get("X-Forwarded-Host")).thenReturn(forwardedHost);
        return req;
    }

    @Test
    void verify_allowed_returns200WithIdentityHeaders() {
        SubdomainAuthorizer authorizer = mock(SubdomainAuthorizer.class); // authorize() returns normally
        HttpResponse<?> r = new ForwardAuthController(authorizer)
                .verify(fullAuth(), reqWithHost("billing.geowealth.int"));
        assertEquals(HttpStatus.OK, r.getStatus());
        assertEquals("p-1", r.getHeaders().get("X-Auth-Username"));
        assertEquals("5", r.getHeaders().get("X-Auth-Firm-Cd"));
        assertEquals("tok", r.getHeaders().get("X-Auth-Access-Token"));
        assertEquals("5:john", r.getHeaders().get("X-Auth-Memberships"));
    }

    @Test
    void verify_denied_returns403_noIdentityHeaders() {
        SubdomainAuthorizer authorizer = mock(SubdomainAuthorizer.class);
        doThrow(new HttpStatusException(HttpStatus.FORBIDDEN, "denied"))
                .when(authorizer).authorize(any(), any());
        HttpResponse<?> r = new ForwardAuthController(authorizer)
                .verify(fullAuth(), reqWithHost("billing.geowealth.int"));
        assertEquals(HttpStatus.FORBIDDEN, r.getStatus());
        assertNull(r.getHeaders().get("X-Auth-Username"));
    }

    // ---- Multi-tenant host-aware resolution -------------------------------------

    private static TenantRequirement tenant(String slug, String host, String type,
                                            Integer firmCd, Integer objType, Integer perm) {
        TenantRequirement t = new TenantRequirement(slug);
        t.setHost(host);
        t.setType(type);
        t.setFirmCd(firmCd);
        t.setObjectType(objType);
        t.setPermission(perm);
        return t;
    }

    @Test
    void multiTenant_resolvesRequirementByHost() {
        // billing → resource gate on (59,5); trading → firm 5 membership
        SubdomainRequirements reqs = new SubdomainRequirements(List.of(
                tenant("billing", "billing.geowealth.int", "resource", null, 59, 5),
                tenant("trading", "trading.geowealth.int", "firm", 5, null, null)));
        Tier23Gate gate = mock(Tier23Gate.class);
        SubdomainAuthorizer a = new SubdomainAuthorizer(new SubdomainRequirement(), reqs, gate);

        // trading host: needs a firm-5 membership
        HttpStatusException denied = org.junit.jupiter.api.Assertions.assertThrows(
                HttpStatusException.class,
                () -> a.authorize(authWith("7:jane"), "trading.geowealth.int:5185"));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatus());
        a.authorize(authWith("5:john"), "trading.geowealth.int"); // member → allowed

        // billing host: delegates to the Tier-2 gate with that host's (objType,perm)
        a.authorize(authWith(""), "billing.geowealth.int");
        org.mockito.Mockito.verify(gate).require(any(), org.mockito.ArgumentMatchers.eq(59),
                org.mockito.ArgumentMatchers.eq(5));
    }

    @Test
    void multiTenant_unknownHost_failsClosed() {
        SubdomainRequirements reqs = new SubdomainRequirements(List.of(
                tenant("billing", "billing.geowealth.int", "resource", null, 59, 5)));
        SubdomainAuthorizer a = new SubdomainAuthorizer(
                new SubdomainRequirement(), reqs, mock(Tier23Gate.class));
        HttpStatusException ex = org.junit.jupiter.api.Assertions.assertThrows(
                HttpStatusException.class, () -> a.authorize(authWith("5:john"), "unknown.host"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }
}
