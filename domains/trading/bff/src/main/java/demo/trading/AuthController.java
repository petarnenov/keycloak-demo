package demo.trading;

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
 * Identity + logout endpoints for the SPA in the BFF / Token Handler model. See
 * {@code domains/billing/bff/.../AuthController.java} for the full explanation
 * of the two-step logout: server-side OIDC end-session to Keycloak (so KC fans
 * out back-channel logout to every sibling BFF) followed by a browser redirect
 * to P1's IdP-initiated SLO (so P1's HttpSession dies too).
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

        // See billing's twin for why /auth/me probes Keycloak via refresh_token
        // grant on every call: catches out-of-band session ends (P1 SLO, admin
        // revoke) that Keycloak's broker-initiated SLO fails to propagate.
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
        // See billing's twin for why we set Location literally instead of
        // letting HttpResponse.redirect(URI) reshape the URL.
        return HttpResponse.<Void>status(HttpStatus.SEE_OTHER)
                .header(HttpHeaders.LOCATION, p1InitiateSloUrl);
    }

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
