package demo.trading;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.session.Session;
import io.micronaut.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Identity + logout endpoints for the SPA in the BFF / Token Handler model. See
 * {@code domains/billing/bff/.../AuthController.java} for why sign-out routes
 * through P1's IdP-initiated SLO rather than KC's OIDC end-session endpoint.
 */
@Controller("/auth")
public class AuthController {

    private static final Logger LOG = LoggerFactory.getLogger(AuthController.class);

    private final SidSessionRegistry registry;
    private final SessionStore<?> sessionStore;
    private final String p1InitiateSloUrl;

    public AuthController(SidSessionRegistry registry,
                          SessionStore<?> sessionStore,
                          @Value("${app.p1.initiate-slo-url}") String p1InitiateSloUrl) {
        this.registry = registry;
        this.sessionStore = sessionStore;
        this.p1InitiateSloUrl = p1InitiateSloUrl;
        LOG.info("Resolved p1InitiateSloUrl='{}'", p1InitiateSloUrl);
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
    public HttpResponse<?> logout(Authentication authentication, @Nullable Session session) {
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
        // See billing's twin for why we set Location literally instead of
        // letting HttpResponse.redirect(URI) reshape the URL.
        return HttpResponse.<Void>status(HttpStatus.SEE_OTHER)
                .header(HttpHeaders.LOCATION, p1InitiateSloUrl);
    }
}
