package demo.billing;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import com.nimbusds.jwt.SignedJWT;
import io.micronaut.core.type.Argument;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.filters.SecurityFilter;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.session.Session;
import io.micronaut.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Identity + logout endpoints for the SPA in the BFF / Token Handler model. The
 * SPA holds no tokens; it talks to the BFF over the session cookie.
 *
 * <ul>
 *   <li>{@code GET /auth/me} — who am I (401 when signed out); also records the
 *       OIDC {@code sid} → BFF-session mapping for back-channel logout.</li>
 *   <li>{@code GET /auth/logout} — sign the user out everywhere. The flow has
 *       two halves that run in sequence on every call:
 *       <ol>
 *         <li><b>Server-side OIDC end-session to Keycloak</b> (POST to
 *             {@code /protocol/openid-connect/logout} with our stored
 *             {@code refresh_token}). This is the only Keycloak code path that
 *             reliably fans out {@code backchannel.logout.url} POSTs to every
 *             OIDC client in the SSO session — i.e. the sibling BFFs (trading,
 *             users) plus this one. The browser is not involved in this hop
 *             so there is no "Do you want to log out?" interstitial.</li>
 *         <li><b>Browser redirect to P1's IdP-initiated SLO</b>. P1 invalidates
 *             its {@code HttpSession} (same-origin, so {@code JSESSIONID}
 *             reaches it), emits a signed SAML LogoutRequest to Keycloak via
 *             browser auto-submit POST, Keycloak responds (its session is
 *             already gone from step 1, which is fine — P1 still gets a valid
 *             LogoutResponse), and P1 lands the user on its own login form.
 *             End state: P1 + KC + every demo BFF session is gone.</li>
 *       </ol>
 *
 *       Why both halves? Step 2 alone (the original flow) was insufficient:
 *       when Keycloak receives a SAML LogoutRequest from a brokered IdP it
 *       terminates the SSO session but does <i>not</i> trigger the back-channel
 *       POST fan-out to OIDC clients — see Keycloak issues
 *       <a href="https://github.com/keycloak/keycloak/issues/17318">#17318</a>
 *       and <a href="https://github.com/keycloak/keycloak/issues/21770">#21770</a>.
 *       KC logs "Some clients have not been logged out: …" and proceeds. Step 1
 *       runs through KC's standard RP-initiated logout code path
 *       ({@code LogoutEndpoint}), which <i>does</i> POST {@code logout_token}
 *       to every client's configured {@code backchannel.logout.url} — so the
 *       sibling BFFs destroy their sessions and the next {@code /auth/me} from
 *       a still-open trading / users tab returns 401.
 *
 *       The "sid drift" caveat that motivated the previous redirect-only flow
 *       (KC rejecting {@code id_token_hint} as {@code session_expired} and
 *       rendering a confirm page) doesn't apply to the POST variant: it
 *       authenticates by {@code refresh_token}, not by {@code id_token_hint},
 *       and renders nothing — KC returns 204 No Content and we move on.</li>
 * </ul>
 */
@Controller("/auth")
@ExecuteOn(TaskExecutors.BLOCKING)
public class AuthController {

    private static final Logger LOG = LoggerFactory.getLogger(AuthController.class);

    private final SidSessionRegistry registry;
    private final SessionStore<?> sessionStore;
    private final HttpClient kc;
    private final String clientId;
    private final String clientSecret;
    private final String tokenEndpoint;
    private final String logoutEndpoint;
    private final String p1InitiateSloUrl;

    public AuthController(SidSessionRegistry registry,
                          SessionStore<?> sessionStore,
                          @Client("kc") HttpClient kc,
                          @Value("${micronaut.security.oauth2.clients.keycloak.openid.issuer}") String issuer,
                          @Value("${micronaut.security.oauth2.clients.keycloak.client-id}") String clientId,
                          @Value("${micronaut.security.oauth2.clients.keycloak.client-secret}") String clientSecret,
                          @Value("${app.p1.initiate-slo-url}") String p1InitiateSloUrl) {
        this.registry = registry;
        this.sessionStore = sessionStore;
        this.kc = kc;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        // Use full URLs (issuer + path) because @Client("kc")'s base path is
        // dropped by Micronaut when the request path starts with "/".
        this.tokenEndpoint = issuer + "/protocol/openid-connect/token";
        this.logoutEndpoint = issuer + "/protocol/openid-connect/logout";
        this.p1InitiateSloUrl = p1InitiateSloUrl;
        LOG.info("Resolved tokenEndpoint='{}', logoutEndpoint='{}', p1InitiateSloUrl='{}'",
                tokenEndpoint, logoutEndpoint, p1InitiateSloUrl);
    }

    @Get("/me")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<Map<String, Object>> me(Authentication authentication, @Nullable Session session) {
        Object sid = authentication.getAttributes().get("sid");
        Object refreshToken = authentication.getAttributes().get("refreshToken");

        // Live-validate the Keycloak SSO session every /auth/me. The cached
        // Authentication in the BFF session is built once at login and never
        // re-checked; without this hop, an out-of-band session end — e.g. the
        // user signs out at P1, P1 SAML-SLOs Keycloak, Keycloak fails to
        // back-channel logout this client (Keycloak issue #17318) — leaves a
        // "zombie" BFF session that the SPA happily uses on refresh.
        //
        // Why refresh_token grant and not userinfo? Keycloak's userinfo
        // validates the access token's JWT signature + exp but does not
        // immediately reflect SSO-session revocation; for several seconds
        // after a SAML-broker SLO it keeps answering 200 while the session
        // is already dead. The refresh_token grant goes through Keycloak's
        // session lookup path and returns {@code invalid_grant: "Session not
        // active"} the moment the SSO session is ended.
        Authentication current = authentication;
        if (refreshToken instanceof String && !((String) refreshToken).isEmpty()) {
            Authentication refreshed = refreshAndRebuild(current, (String) refreshToken);
            if (refreshed == null) {
                LOG.info("KC refresh returned invalid_grant — BFF session is stale, clearing");
                if (session != null) {
                    try { sessionStore.deleteSession(session.getId()); } catch (Exception ignored) {}
                }
                if (sid != null) {
                    registry.invalidateBySid(sid.toString());
                }
                return HttpResponse.unauthorized();
            }
            current = refreshed;
            if (session != null) {
                session.put(SecurityFilter.AUTHENTICATION, refreshed);
            }
        }

        if (session != null && sid != null) {
            registry.register(sid.toString(), session.getId());
        }
        Map<String, Object> out = new HashMap<>();
        out.put("authenticated", true);
        out.put("username", current.getName());
        out.put("email", current.getAttributes().get("email"));
        out.put("firmCd", current.getAttributes().get("firmCd"));
        out.put("roles", current.getRoles());
        return HttpResponse.ok(out);
    }

    /**
     * Exchanges the stored refresh token for a fresh token set against Keycloak
     * and returns a rebuilt {@link Authentication}. Returns {@code null} if
     * Keycloak answers {@code invalid_grant} (SSO session has ended). On
     * transient network errors returns the current {@code Authentication}
     * unchanged so a flaky link doesn't bounce signed-in users.
     */
    @SuppressWarnings("unchecked")
    private Authentication refreshAndRebuild(Authentication current, String refreshToken) {
        try {
            String body = "grant_type=refresh_token"
                    + "&refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8)
                    + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                    + (clientSecret == null || clientSecret.isEmpty()
                        ? ""
                        : "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8));
            HttpRequest<?> req = HttpRequest.POST(tokenEndpoint, body)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
            Map<String, Object> tokens = kc.toBlocking().retrieve(req, Argument.mapOf(String.class, Object.class));

            Object access = tokens.get("access_token");
            Object refresh = tokens.get("refresh_token");
            Object id = tokens.get("id_token");
            if (!(access instanceof String) || !(id instanceof String)) {
                LOG.warn("KC refresh succeeded but tokens missing — keeping current Authentication");
                return current;
            }
            Map<String, Object> claims = SignedJWT.parse((String) id).getJWTClaimsSet().getClaims();
            Object rolesClaim = claims.get("roles");
            List<String> roles = (rolesClaim instanceof List)
                    ? (List<String>) rolesClaim
                    : current.getRoles().stream().toList();
            Object preferredUsername = claims.get("preferred_username");
            String username = preferredUsername != null ? preferredUsername.toString() : current.getName();

            Map<String, Object> attrs = new HashMap<>();
            attrs.put("sub", strOrNull(claims.get("sub")));
            attrs.put("email", strOrNull(claims.get("email")));
            attrs.put("firmCd", strOrNull(claims.get("firmCd")));
            attrs.put("sid", strOrNull(claims.get("sid")));
            attrs.put("accessToken", access);
            attrs.put("refreshToken", refresh != null ? refresh : current.getAttributes().get("refreshToken"));
            attrs.put("idToken", id);
            return Authentication.build(username, roles, attrs);
        } catch (HttpClientResponseException e) {
            int code = e.getStatus().getCode();
            if (code == 400 || code == 401) {
                LOG.debug("KC refresh -> HTTP {}: session is dead", code);
                return null;
            }
            LOG.warn("KC refresh probe HTTP {} (treating as alive): {}", code, e.getMessage());
            return current;
        } catch (Exception e) {
            LOG.debug("KC refresh probe transient error (treating as alive): {}", e.toString());
            return current;
        }
    }

    private static String strOrNull(Object o) {
        return o == null ? null : o.toString();
    }

    @Get("/logout")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> logout(Authentication authentication, @Nullable Session session) {
        Object sid = authentication.getAttributes().get("sid");
        Object refreshToken = authentication.getAttributes().get("refreshToken");

        endSessionAtKeycloak(refreshToken);

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

    /**
     * Server-side RP-initiated logout to Keycloak's {@code /protocol/openid-connect/logout}
     * endpoint with our stored {@code refresh_token}. Best-effort: failures are
     * logged but do not block the user-facing redirect to P1 SLO. See class
     * javadoc for why this hop is necessary.
     */
    private void endSessionAtKeycloak(Object refreshToken) {
        if (!(refreshToken instanceof String) || ((String) refreshToken).isEmpty()) {
            LOG.debug("KC end-session: no refresh_token in session, skipping");
            return;
        }
        try {
            StringBuilder body = new StringBuilder()
                    .append("client_id=").append(URLEncoder.encode(clientId, StandardCharsets.UTF_8))
                    .append("&refresh_token=").append(URLEncoder.encode((String) refreshToken, StandardCharsets.UTF_8));
            if (clientSecret != null && !clientSecret.isEmpty()) {
                body.append("&client_secret=").append(URLEncoder.encode(clientSecret, StandardCharsets.UTF_8));
            }
            HttpRequest<?> req = HttpRequest.POST(logoutEndpoint, body.toString())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
            HttpResponse<?> resp = kc.toBlocking().exchange(req);
            LOG.info("KC end-session returned status={}", resp.getStatus().getCode());
        } catch (Exception e) {
            LOG.warn("KC end-session call failed (continuing with P1 SLO): {}", e.toString());
        }
    }
}
