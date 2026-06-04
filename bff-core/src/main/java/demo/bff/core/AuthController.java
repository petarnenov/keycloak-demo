package demo.bff.core;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.cookie.SameSite;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
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
 *       and renders nothing — KC returns 204 No Content and we move on.
 *
 *       Idempotency: {@code /auth/logout} is {@link SecurityRule#IS_ANONYMOUS}
 *       and accepts a {@code @Nullable Authentication}. A double-click, a
 *       session that already expired on the BFF, or a back-channel race where
 *       another tab killed the session first no longer surface as
 *       {@code 401 Unauthorized}; the controller does best-effort cleanup
 *       (KC end-session if we still hold a refresh token, session delete,
 *       SID invalidate — all null-safe) and unconditionally returns
 *       {@code 303 See Other} to the P1 SLO redirect. Without this, the SPA's
 *       sign-out button intermittently 401'd in normal use because a KC
 *       back-channel logout token from a sibling tab could land between
 *       {@code /auth/me} and the user's click.</li>
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
    private final SubdomainRequirement requirement;

    public AuthController(SidSessionRegistry registry,
                          SessionStore<?> sessionStore,
                          @Client("kc") HttpClient kc,
                          SubdomainRequirement requirement,
                          @Value("${micronaut.security.oauth2.clients.keycloak.openid.issuer}") String issuer,
                          @Value("${micronaut.security.oauth2.clients.keycloak.client-id}") String clientId,
                          @Value("${micronaut.security.oauth2.clients.keycloak.client-secret}") String clientSecret,
                          @Value("${app.p1.initiate-slo-url}") String p1InitiateSloUrl) {
        this.registry = registry;
        this.sessionStore = sessionStore;
        this.kc = kc;
        this.requirement = requirement;
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
    public Map<String, Object> me(Authentication authentication, @Nullable Session session) {
        // KC session liveness is now validated by TokenRefreshFilter on every
        // /auth/** + /api/** call (throttled to once per 30s). When KC's SSO
        // session has been ended out-of-band, the filter clears the BFF session
        // and returns 401 before we ever get here.
        Object sid = authentication.getAttributes().get("sid");
        if (session != null && sid != null) {
            registry.register(sid.toString(), session.getId());
        }
        List<String> memberships = AuthClaims.memberships(authentication);

        Map<String, Object> out = new HashMap<>();
        out.put("authenticated", true);
        out.put("username", authentication.getName());
        out.put("email", authentication.getAttributes().get("email"));
        out.put("personId", authentication.getAttributes().get("personId"));
        // Subdomain-agnostic facts (person-identity-via-existing-linkdelink.md):
        // the firms the person has an account in, as "<firmCd>:<ldapUid>".
        out.put("memberships", memberships);
        // Per-subdomain identity. For a firm-bound subdomain we surface the firm
        // it is bound to and the person's username IN that firm (resolved from
        // memberships) — not the login account — so billing always shows its own
        // firm's identity regardless of which account the person logged in with.
        if (requirement.isFirmType() && requirement.getFirmCd() != null) {
            out.put("firmCd", requirement.getFirmCd().toString());
            out.put("tenantIdentity", usernameForFirm(memberships, requirement.getFirmCd()));
            out.put("activeTenant", "firm-" + requirement.getFirmCd());
        } else {
            // Resource (or ungated) subdomain: surface the login account's identity.
            // authentication.getName() is the personId UUID (KC username = SAML
            // NameID = personId), so resolve the human ldapUid for the login firm
            // from memberships and fall back to the raw name only if absent.
            Object firmCdAttr = authentication.getAttributes().get("firmCd");
            out.put("firmCd", firmCdAttr);
            String uname = usernameForFirm(memberships, parseFirm(firmCdAttr));
            out.put("tenantIdentity", uname != null ? uname : authentication.getName());
            out.put("activeTenant", requirement.getType());
        }
        out.put("roles", authentication.getRoles());
        return out;
    }

    /** Parse the (login) {@code firmCd} attribute to an Integer; {@code null} when absent/non-numeric. */
    private static Integer parseFirm(Object firmCd) {
        if (firmCd == null) {
            return null;
        }
        try {
            return Integer.valueOf(firmCd.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The person's {@code ldapUid} in {@code firmCd}, parsed from the
     *  {@code "<firmCd>:<ldapUid>"} memberships entries; {@code null} if absent. */
    private static String usernameForFirm(List<String> memberships, Integer firmCd) {
        if (memberships == null || firmCd == null) {
            return null;
        }
        String prefix = firmCd + ":";
        for (String m : memberships) {
            if (m != null && m.startsWith(prefix)) {
                return m.substring(prefix.length());
            }
        }
        return null;
    }

    /**
     * Hook invoked by micronaut-security on OAuth callback failure (see
     * {@code redirect.login-failure: /auth/login-failed} in application.yml).
     *
     * <p>The single use we care about: the silent-first variant set
     * {@link SilentLoginController#SILENT_ATTEMPT_COOKIE} on the browser and
     * the Keycloak callback came back with {@code error=login_required} (no
     * realm SSO session). We clear the cookie and redirect the browser to the
     * interactive login — same URL the SPA would have used directly. If the
     * cookie isn't set, this is a genuine failure and we fall back to the SPA
     * error page.</p>
     */
    @Get("/login-failed")
    @Secured(SecurityRule.IS_ANONYMOUS)
    public HttpResponse<?> loginFailed(HttpRequest<?> request) {
        boolean wasSilent = request.getCookies()
                .findCookie(SilentLoginController.SILENT_ATTEMPT_COOKIE)
                .map(c -> "1".equals(c.getValue()))
                .orElse(false);
        if (wasSilent) {
            LOG.debug("silent login attempt produced login_required; upgrading to interactive");
            MutableHttpResponse<Object> resp = HttpResponse.status(HttpStatus.SEE_OTHER);
            resp.getHeaders().add(HttpHeaders.LOCATION, "/oauth/login/keycloak");
            // Expire the cookie so a real failure on the follow-up
            // interactive login doesn't loop through this branch.
            resp.cookie(Cookie.of(SilentLoginController.SILENT_ATTEMPT_COOKIE, "")
                    .maxAge(0)
                    .path("/")
                    .httpOnly(true)
                    .secure(true)
                    .sameSite(SameSite.Lax));
            return resp;
        }
        // Genuine failure — show the SPA error UI (matches the existing
        // pre-silent behaviour).
        return HttpResponse.<Void>status(HttpStatus.SEE_OTHER)
                .header(HttpHeaders.LOCATION, "/?login_error=true");
    }

    // Idempotent — accepts both authenticated and anonymous callers so a double
    // click, an already-expired session, or a back-channel race never surfaces
    // as a 401 to the SPA. If nothing is left to sign out, we still send the
    // browser through the P1 SLO redirect so the user lands somewhere sensible.
    @Get("/logout")
    @Secured(SecurityRule.IS_ANONYMOUS)
    public HttpResponse<?> logout(@Nullable Authentication authentication, @Nullable Session session) {
        Object sid = authentication != null ? authentication.getAttributes().get("sid") : null;
        Object refreshToken = authentication != null ? authentication.getAttributes().get("refreshToken") : null;

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
