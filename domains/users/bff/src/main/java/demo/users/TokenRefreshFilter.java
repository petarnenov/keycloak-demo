package demo.users;

import com.nimbusds.jwt.SignedJWT;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.http.filter.ServerFilterPhase;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.filters.SecurityFilter;
import io.micronaut.session.Session;
import io.micronaut.session.http.HttpSessionFilter;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BFF / Token Handler token refresh.
 *
 * <p>micronaut-security keeps the OP tokens in the server-side session and has
 * no built-in refresh for session logins ("Session based logins do not support
 * refresh"). Without refresh the access token dies after its (short) lifespan
 * and every later request 401s — forcing a re-login round-trip — while logout
 * presents a stale {@code id_token_hint} that Keycloak can't use for a silent
 * logout (it falls back to the "Do you want to log out?" page).</p>
 *
 * <p>This filter runs right after the security filter: when the stored access
 * token is within {@link #SKEW_SECONDS} of expiry it uses the refresh token to
 * mint a fresh token set at Keycloak, rebuilds the {@link Authentication} (same
 * shape as {@link KeycloakAuthenticationMapper}) and writes it back to both the
 * session (so future requests see it) and the current request attribute (so the
 * controller handling THIS request — e.g. {@code /auth/logout} — gets the fresh
 * id_token / access token). If the refresh fails the session is gone, so we
 * clear it and return 401; the SPA reacts by starting login.</p>
 */
@Filter({"/api/**", "/auth/**"})
public class TokenRefreshFilter implements HttpServerFilter {

    private static final Logger LOG = LoggerFactory.getLogger(TokenRefreshFilter.class);

    /** Refresh this many seconds before the access token actually expires. */
    private static final long SKEW_SECONDS = 60;

    private final HttpClient kc;
    private final String tokenEndpoint;
    private final String clientId;
    private final String clientSecret;

    public TokenRefreshFilter(@Client("kc") HttpClient kc,
                              @Value("${micronaut.security.oauth2.clients.keycloak.openid.issuer}") String issuer,
                              @Value("${micronaut.security.oauth2.clients.keycloak.client-id}") String clientId,
                              @Value("${micronaut.security.oauth2.clients.keycloak.client-secret}") String clientSecret) {
        this.kc = kc;
        this.tokenEndpoint = issuer + "/protocol/openid-connect/token";
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public int getOrder() {
        // After the security filter has resolved (and authorized) the request.
        return ServerFilterPhase.SECURITY.after();
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        Authentication auth = request.getAttribute(SecurityFilter.AUTHENTICATION, Authentication.class).orElse(null);
        if (auth == null) {
            return chain.proceed(request);
        }
        Object accessToken = auth.getAttributes().get("accessToken");
        Object refreshToken = auth.getAttributes().get("refreshToken");
        if (!(accessToken instanceof String) || !(refreshToken instanceof String)) {
            return chain.proceed(request);
        }
        if (!nearExpiry((String) accessToken)) {
            return chain.proceed(request);
        }

        return Mono.from(refresh((String) refreshToken))
                .flatMap(tokens -> {
                    Authentication refreshed = rebuild(auth, tokens);
                    if (refreshed == null) {
                        return Mono.from(chain.proceed(request));
                    }
                    request.setAttribute(SecurityFilter.AUTHENTICATION, refreshed);
                    session(request).ifPresent(s -> s.put(SecurityFilter.AUTHENTICATION, refreshed));
                    return Mono.from(chain.proceed(request));
                })
                .onErrorResume(err -> {
                    // Refresh token rejected (session ended / token revoked). Clear
                    // the dead session and 401 so the SPA reacts into a fresh login.
                    LOG.debug("token refresh failed, clearing session: {}", err.toString());
                    session(request).ifPresent(Session::clear);
                    return Mono.just(HttpResponse.unauthorized());
                });
    }

    private boolean nearExpiry(String jwt) {
        try {
            Date exp = SignedJWT.parse(jwt).getJWTClaimsSet().getExpirationTime();
            if (exp == null) {
                return false;
            }
            return Instant.now().plusSeconds(SKEW_SECONDS).isAfter(exp.toInstant());
        } catch (Exception e) {
            return false; // unparseable → leave it alone
        }
    }

    private Publisher<Map<String, Object>> refresh(String refreshToken) {
        String body = "grant_type=refresh_token"
                + "&refresh_token=" + enc(refreshToken)
                + "&client_id=" + enc(clientId)
                + "&client_secret=" + enc(clientSecret);
        HttpRequest<?> req = HttpRequest.POST(tokenEndpoint, body)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE)
                .accept(MediaType.APPLICATION_JSON_TYPE);
        return kc.retrieve(req, Argument.mapOf(String.class, Object.class));
    }

    /** Rebuild the Authentication from a fresh token set, mirroring {@link KeycloakAuthenticationMapper}. */
    @SuppressWarnings("unchecked")
    private Authentication rebuild(Authentication current, Map<String, Object> tokens) {
        Object access = tokens.get("access_token");
        Object refresh = tokens.get("refresh_token");
        Object id = tokens.get("id_token");
        if (!(access instanceof String) || !(id instanceof String)) {
            return null;
        }
        try {
            Map<String, Object> claims = SignedJWT.parse((String) id).getJWTClaimsSet().getClaims();

            Object rolesClaim = claims.get("roles");
            List<String> roles = (rolesClaim instanceof List) ? (List<String>) rolesClaim : current.getRoles().stream().toList();

            Object preferredUsername = claims.get("preferred_username");
            String username = preferredUsername != null ? preferredUsername.toString() : current.getName();

            Map<String, Object> attrs = new HashMap<>();
            attrs.put("sub", str(claims.get("sub")));
            attrs.put("email", str(claims.get("email")));
            attrs.put("firmCd", str(claims.get("firmCd")));
            attrs.put("sid", str(claims.get("sid")));
            attrs.put("accessToken", access);
            attrs.put("refreshToken", refresh != null ? refresh : current.getAttributes().get("refreshToken"));
            attrs.put("idToken", id);

            return Authentication.build(username, roles, attrs);
        } catch (Exception e) {
            LOG.debug("could not parse refreshed id_token: {}", e.toString());
            return null;
        }
    }

    private static java.util.Optional<Session> session(HttpRequest<?> request) {
        return request.getAttributes()
                .get(HttpSessionFilter.SESSION_ATTRIBUTE, Session.class);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
