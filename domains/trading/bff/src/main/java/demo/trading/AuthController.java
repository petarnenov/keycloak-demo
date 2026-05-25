package demo.trading;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.session.Session;
import io.micronaut.session.SessionStore;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Identity + logout endpoints for the SPA in the BFF / Token Handler model. The
 * SPA holds no tokens; it talks to the BFF over the session cookie.
 *
 * <ul>
 *   <li>{@code GET /auth/me} — who am I (401 when signed out); also records the
 *       OIDC {@code sid} → BFF-session mapping for back-channel logout.</li>
 *   <li>{@code GET /auth/logout} — RP-initiated logout: destroy the BFF session,
 *       then redirect to Keycloak's end-session so the KC SSO session (and, via
 *       SAML SLO, P1) is terminated too. Without this the SPA would just
 *       re-SSO silently against the still-live KC session.</li>
 * </ul>
 */
@Controller("/auth")
public class AuthController {

    private final SidSessionRegistry registry;
    private final SessionStore<?> sessionStore;
    private final String endSessionEndpoint;

    public AuthController(SidSessionRegistry registry,
                          SessionStore<?> sessionStore,
                          @Value("${micronaut.security.oauth2.clients.keycloak.openid.issuer}") String issuer) {
        this.registry = registry;
        this.sessionStore = sessionStore;
        this.endSessionEndpoint = issuer + "/protocol/openid-connect/logout";
    }

    @Get("/me")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public Map<String, Object> me(Authentication authentication, @Nullable Session session) {
        Object sid = authentication.getAttributes().get("sid");
        if (session != null && sid != null) {
            registry.register(sid.toString(), session.getId());
        }
        Map<String, Object> out = new HashMap<>();
        out.put("authenticated", true);
        out.put("username", authentication.getName());
        out.put("email", authentication.getAttributes().get("email"));
        out.put("firmCd", authentication.getAttributes().get("firmCd"));
        out.put("roles", authentication.getRoles());
        return out;
    }

    @Get("/logout")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> logout(HttpRequest<?> request, Authentication authentication, @Nullable Session session) {
        Object idToken = authentication.getAttributes().get("idToken");
        Object sid = authentication.getAttributes().get("sid");
        if (session != null) {
            try {
                sessionStore.deleteSession(session.getId());
            } catch (Exception ignored) {
                // best-effort
            }
        }
        if (sid != null) {
            registry.invalidateBySid(sid.toString());
        }
        // Post-logout target = the SPA origin this request came through (nginx
        // sets X-Forwarded-Host with the :port); matches the client's registered
        // post.logout.redirect.uris.
        String host = request.getHeaders().get("X-Forwarded-Host");
        if (host == null) {
            host = request.getHeaders().get("Host");
        }
        String postLogout = "https://" + host + "/";
        StringBuilder url = new StringBuilder(endSessionEndpoint)
                .append("?post_logout_redirect_uri=").append(enc(postLogout));
        if (idToken != null) {
            url.append("&id_token_hint=").append(idToken);
        }
        return HttpResponse.redirect(URI.create(url.toString()));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
