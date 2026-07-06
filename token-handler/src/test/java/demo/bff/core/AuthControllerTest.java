package demo.bff.core;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.cookie.Cookies;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.session.Session;
import io.micronaut.session.SessionStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class AuthControllerTest {

    private static final String SLO = "http://localhost:8888/saml/idp/initiate-slo.do";

    private static AuthController controller(SidSessionRegistry reg, SessionStore store,
                                             HttpClient kc, SubdomainRequirement req) {
        // Empty tenants list → single-tenant mode → effectiveFor() uses `req`.
        // A same-thread executor runs the fire-and-forget KC end-session inline,
        // so logout assertions on the KC call stay deterministic.
        BrandResolver brands = mock(BrandResolver.class);
        when(brands.resolve(any(), any())).thenReturn(new BrandConfig("geowealth"));
        return new AuthController(reg, store, kc, req, new SubdomainRequirements(List.of()),
                brands, mock(PolicyRuleClient.class), directExecutor(), "https://auth/realms/demo",
                "demo-client", "secret", SLO, false);
    }

    /** Runs submitted tasks synchronously on the calling thread (test determinism). */
    private static ExecutorService directExecutor() {
        return new AbstractExecutorService() {
            @Override public void execute(Runnable command) { command.run(); }
            @Override public void shutdown() { }
            @Override public List<Runnable> shutdownNow() { return List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        };
    }

    /** A request whose host headers are absent — single-tenant /auth/me ignores the host. */
    private static HttpRequest<?> meRequest() {
        HttpRequest<?> req = mock(HttpRequest.class);
        io.micronaut.http.HttpHeaders h = mock(io.micronaut.http.HttpHeaders.class);
        when(req.getHeaders()).thenReturn(h);
        return req;
    }

    private static Authentication auth(Set<String> roles, Map<String, Object> attrs, String name) {
        Authentication a = mock(Authentication.class);
        when(a.getRoles()).thenReturn(roles);
        when(a.getAttributes()).thenReturn(attrs);
        when(a.getName()).thenReturn(name);
        return a;
    }

    // ---------- /auth/me ----------

    @Test
    void me_firmSubdomain_surfacesFirmIdentityFromMemberships() {
        SidSessionRegistry reg = mock(SidSessionRegistry.class);
        SubdomainRequirement req = new SubdomainRequirement();
        req.setType("firm");
        req.setFirmCd(5);
        AuthController c = controller(reg, mock(SessionStore.class), mock(HttpClient.class), req);

        Authentication a = auth(Set.of("billing-viewer"),
                Map.of("sid", "kc-sid", "email", "j@x.com", "personId", "p-1",
                        "firmCd", "9", "memberships", "5:john\n9:johnny"),
                "p-1");
        Session session = mock(Session.class);
        when(session.getId()).thenReturn("bff-sess");

        Map<String, Object> out = c.me(a, session, meRequest());

        assertEquals(true, out.get("authenticated"));
        assertEquals("5", out.get("firmCd"));
        assertEquals("john", out.get("tenantIdentity"));
        assertEquals("firm-5", out.get("activeTenant"));
        assertEquals(List.of("5:john", "9:johnny"), out.get("memberships"));
        verify(reg).register("kc-sid", "bff-sess");
    }

    @Test
    void me_resourceSubdomain_surfacesLoginIdentity() {
        SidSessionRegistry reg = mock(SidSessionRegistry.class);
        SubdomainRequirement req = new SubdomainRequirement();
        req.setType("resource");
        AuthController c = controller(reg, mock(SessionStore.class), mock(HttpClient.class), req);

        Authentication a = auth(Set.of("advisor"),
                Map.of("firmCd", "7", "memberships", "7:jane"),
                "p-uuid");

        Map<String, Object> out = c.me(a, null, meRequest());

        assertEquals("7", out.get("firmCd"));
        assertEquals("jane", out.get("tenantIdentity"));
        assertEquals("resource", out.get("activeTenant"));
    }

    @Test
    void me_resourceSubdomain_fallsBackToNameWhenNoMembership() {
        SubdomainRequirement req = new SubdomainRequirement();
        req.setType("resource");
        AuthController c = controller(mock(SidSessionRegistry.class),
                mock(SessionStore.class), mock(HttpClient.class), req);

        Authentication a = auth(Set.of(), Map.of("memberships", ""), "p-uuid");

        Map<String, Object> out = c.me(a, null, meRequest());
        assertEquals("p-uuid", out.get("tenantIdentity"));
    }

    // ---------- /auth/login-failed ----------

    private static HttpRequest<?> requestWithCookie(String value) {
        HttpRequest<?> req = mock(HttpRequest.class);
        Cookies cookies = mock(Cookies.class);
        if (value == null) {
            when(cookies.findCookie(SilentLoginController.SILENT_ATTEMPT_COOKIE))
                    .thenReturn(Optional.empty());
        } else {
            Cookie cookie = mock(Cookie.class);
            when(cookie.getValue()).thenReturn(value);
            when(cookies.findCookie(SilentLoginController.SILENT_ATTEMPT_COOKIE))
                    .thenReturn(Optional.of(cookie));
        }
        when(req.getCookies()).thenReturn(cookies);
        return req;
    }

    @Test
    void loginFailed_silentCookie_upgradesToInteractiveLogin() {
        AuthController c = controller(mock(SidSessionRegistry.class),
                mock(SessionStore.class), mock(HttpClient.class), new SubdomainRequirement());

        HttpResponse<?> resp = c.loginFailed(requestWithCookie("1"));

        assertEquals(HttpStatus.SEE_OTHER, resp.getStatus());
        assertEquals("/oauth/login/keycloak", resp.getHeaders().get(HttpHeaders.LOCATION));
    }

    @Test
    void loginFailed_noCookie_showsSpaErrorPage() {
        AuthController c = controller(mock(SidSessionRegistry.class),
                mock(SessionStore.class), mock(HttpClient.class), new SubdomainRequirement());

        HttpResponse<?> resp = c.loginFailed(requestWithCookie(null));

        assertEquals(HttpStatus.SEE_OTHER, resp.getStatus());
        assertEquals("/?login_error=true", resp.getHeaders().get(HttpHeaders.LOCATION));
    }

    // ---------- /auth/logout ----------

    @Test
    void logout_withRefreshToken_callsKeycloakEndSessionAndRedirectsToSlo() {
        SidSessionRegistry reg = mock(SidSessionRegistry.class);
        SessionStore store = mock(SessionStore.class);
        HttpClient kc = mock(HttpClient.class);
        BlockingHttpClient blocking = mock(BlockingHttpClient.class);
        when(kc.toBlocking()).thenReturn(blocking);
        when(blocking.exchange(any(HttpRequest.class))).thenReturn(HttpResponse.ok());

        AuthController c = controller(reg, store, kc, new SubdomainRequirement());

        Authentication a = auth(Set.of(),
                Map.of("sid", "kc-sid", "refreshToken", "r-token"), "u");
        Session session = mock(Session.class);
        when(session.getId()).thenReturn("bff-sess");

        HttpResponse<?> resp = c.logout(a, session, meRequest());

        assertEquals(HttpStatus.SEE_OTHER, resp.getStatus());
        assertEquals(SLO, resp.getHeaders().get(HttpHeaders.LOCATION));
        verify(blocking).exchange(any(HttpRequest.class));
        verify(store).deleteSession("bff-sess");
        verify(reg).invalidateBySid("kc-sid");
    }

    @Test
    void logout_anonymousAndNoSession_stillRedirectsToSlo() {
        HttpClient kc = mock(HttpClient.class);
        AuthController c = controller(mock(SidSessionRegistry.class),
                mock(SessionStore.class), kc, new SubdomainRequirement());

        HttpResponse<?> resp = c.logout(null, null, meRequest());

        assertEquals(HttpStatus.SEE_OTHER, resp.getStatus());
        assertEquals(SLO, resp.getHeaders().get(HttpHeaders.LOCATION));
        // no refresh token → never hit Keycloak
        verify(kc, never()).toBlocking();
    }

    @Test
    void logout_keycloakFailure_isSwallowedAndStillRedirects() {
        HttpClient kc = mock(HttpClient.class);
        BlockingHttpClient blocking = mock(BlockingHttpClient.class);
        when(kc.toBlocking()).thenReturn(blocking);
        when(blocking.exchange(any(HttpRequest.class))).thenThrow(new RuntimeException("kc down"));

        AuthController c = controller(mock(SidSessionRegistry.class),
                mock(SessionStore.class), kc, new SubdomainRequirement());

        Authentication a = auth(Set.of(), Map.of("refreshToken", "r-token"), "u");
        HttpResponse<?> resp = c.logout(a, null, meRequest());

        assertEquals(HttpStatus.SEE_OTHER, resp.getStatus());
        assertEquals(SLO, resp.getHeaders().get(HttpHeaders.LOCATION));
    }
}
