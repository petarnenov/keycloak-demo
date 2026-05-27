package demo.billing;

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
 * Identity + logout endpoints for the SPA in the BFF / Token Handler model. The
 * SPA holds no tokens; it talks to the BFF over the session cookie.
 *
 * <ul>
 *   <li>{@code GET /auth/me} — who am I (401 when signed out); also records the
 *       OIDC {@code sid} → BFF-session mapping for back-channel logout.</li>
 *   <li>{@code GET /auth/logout} — sign the user out everywhere. The BFF clears
 *       its own session and then redirects the browser to P1's
 *       <b>IdP-initiated SLO</b> endpoint (the same one P1's own React UI calls
 *       on sign-out). That action invalidates P1's {@code HttpSession}
 *       (same-origin, so {@code JSESSIONID} reaches it), emits a signed SAML
 *       LogoutRequest to Keycloak via a browser auto-submit POST, Keycloak
 *       terminates its SSO session and sends a LogoutResponse back, and P1
 *       lands the user on its own login form. End state: both P1 and KC
 *       sessions are gone, so a subsequent attempt to revisit the demo bounces
 *       through P1's login form (which is what the user expects to see).
 *
 *       Why not Keycloak's OIDC end-session ({@code /protocol/openid-connect/
 *       logout?id_token_hint=…})? That path needs the stored id_token's
 *       {@code sid} to still match KC's <i>current</i> session record, and in
 *       practice it drifts (KC silently rotates the session id, the
 *       refresh-token grant returns the OLD sid even after the session was
 *       destroyed by a previous sign-out, etc.). When the sid no longer
 *       matches, KC rejects the hint as {@code session_expired} and renders
 *       "Do you want to log out?" — exactly the KC "flash" the user complained
 *       about. KC's back-channel logout doesn't fix it either: it can revoke
 *       the refresh token but has no browser to relay the SAML LogoutRequest
 *       through, so P1's HttpSession stays alive and the next authorize
 *       silently re-auths the user.</li>
 * </ul>
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
        // Use a literal Location header rather than HttpResponse.redirect(URI)
        // — passing an http://localhost:8888/… URI through URI.create makes
        // Micronaut/Netty re-emit it as a relative path (the scheme + host
        // get dropped on the way out), and the browser then resolves it
        // against billing.geowealth.int instead of localhost:8888.
        return HttpResponse.<Void>status(HttpStatus.SEE_OTHER)
                .header(HttpHeaders.LOCATION, p1InitiateSloUrl);
    }
}
