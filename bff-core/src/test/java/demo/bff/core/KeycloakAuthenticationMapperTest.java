package demo.bff.core;

import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.authentication.AuthenticationResponse;
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdClaims;
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdTokenResponse;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KeycloakAuthenticationMapperTest {

    private static OpenIdClaims claims(Object roles, String username, String sub) {
        OpenIdClaims c = mock(OpenIdClaims.class);
        when(c.get("roles")).thenReturn(roles);
        when(c.get("preferred_username")).thenReturn(username);
        when(c.get("email")).thenReturn("user@example.com");
        when(c.get("firmCd")).thenReturn("5");
        when(c.get("sid")).thenReturn("kc-sid");
        when(c.get("personId")).thenReturn("p-1");
        when(c.getSubject()).thenReturn(sub);
        return c;
    }

    private static OpenIdTokenResponse tokens(String idToken, String accessToken) {
        OpenIdTokenResponse t = mock(OpenIdTokenResponse.class);
        when(t.getIdToken()).thenReturn(idToken);
        when(t.getAccessToken()).thenReturn(accessToken);
        when(t.getRefreshToken()).thenReturn("refresh-1");
        return t;
    }

    private static Authentication run(OpenIdClaims claims, OpenIdTokenResponse tokens) {
        AuthenticationResponse resp = Mono.from(new KeycloakAuthenticationMapper()
                .createAuthenticationResponse("keycloak", tokens, claims, null)).block();
        Optional<Authentication> a = resp.getAuthentication();
        assertTrue(a.isPresent());
        return a.get();
    }

    @Test
    void mapsClaimsAndTokensIntoAuthentication() {
        String idToken = JwtTestSupport.compactJwtWithPayload(
                "{\"memberships\":[\"5:john\",\"9:jane\"]}");
        Authentication a = run(claims(List.of("advisor", "billing-viewer"), "john", "p-1"),
                tokens(idToken, "access-1"));

        assertEquals("john", a.getName());
        assertTrue(a.getRoles().contains("advisor"));
        assertEquals("user@example.com", a.getAttributes().get("email"));
        assertEquals("5", a.getAttributes().get("firmCd"));
        assertEquals("access-1", a.getAttributes().get("accessToken"));
        assertEquals("refresh-1", a.getAttributes().get("refreshToken"));
        // memberships packed as a delimited string
        assertEquals("5:john\n9:jane", a.getAttributes().get("memberships"));
    }

    @Test
    void membershipsFallBackToAccessTokenWhenIdTokenLacksThem() {
        String idToken = JwtTestSupport.compactJwtWithPayload("{\"sub\":\"p-1\"}"); // no memberships
        String accessToken = JwtTestSupport.compactJwtWithPayload(
                "{\"memberships\":[\"7:johnny\"]}");
        Authentication a = run(claims(List.of("advisor"), "john", "p-1"),
                tokens(idToken, accessToken));

        assertEquals("7:johnny", a.getAttributes().get("memberships"));
    }

    @Test
    void nonListRolesYieldEmptyRoles_usernameFallsBackToSubject() {
        String idToken = JwtTestSupport.compactJwtWithPayload("{}");
        Authentication a = run(claims("not-a-list", null, "subject-uuid"),
                tokens(idToken, idToken));

        assertEquals("subject-uuid", a.getName());
        assertTrue(a.getRoles().isEmpty());
        assertEquals("", a.getAttributes().get("memberships"));
    }

    @Test
    void nullIdTokenTolerated() {
        Authentication a = run(claims(List.of("advisor"), "john", "p-1"),
                tokens(null, JwtTestSupport.compactJwtWithPayload("{}")));
        assertEquals("", a.getAttributes().get("memberships"));
    }
}
