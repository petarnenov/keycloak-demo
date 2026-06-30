package demo.bff.core;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.cookie.SameSite;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.session.Session;
import io.micronaut.session.SessionStore;
import jakarta.inject.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

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
    private final SubdomainRequirements requirements;
    private final BrandResolver brands;
    private final ExecutorService blockingExecutor;

    public AuthController(SidSessionRegistry registry,
                          SessionStore<?> sessionStore,
                          @Client("kc") HttpClient kc,
                          SubdomainRequirement requirement,
                          SubdomainRequirements requirements,
                          BrandResolver brands,
                          @Named(TaskExecutors.BLOCKING) ExecutorService blockingExecutor,
                          @Value("${micronaut.security.oauth2.clients.keycloak.openid.issuer}") String issuer,
                          @Value("${micronaut.security.oauth2.clients.keycloak.client-id}") String clientId,
                          @Value("${micronaut.security.oauth2.clients.keycloak.client-secret}") String clientSecret,
                          @Value("${app.p1.initiate-slo-url}") String p1InitiateSloUrl) {
        this.registry = registry;
        this.sessionStore = sessionStore;
        this.kc = kc;
        this.requirement = requirement;
        this.requirements = requirements;
        this.brands = brands;
        this.blockingExecutor = blockingExecutor;
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
    public Map<String, Object> me(Authentication authentication, @Nullable Session session,
                                  HttpRequest<?> request) {
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
        // Per-subdomain identity, resolved by the request host so the single
        // multi-tenant Token Handler surfaces each domain's own tenant facts
        // (host map in app.tenants.*); falls back to the single requirement in
        // per-domain mode. For a firm-bound subdomain we surface the firm it is
        // bound to and the person's username IN that firm (resolved from
        // memberships) — not the login account — so billing always shows its own
        // firm's identity regardless of which account the person logged in with.
        String host = forwardedHost(request);
        SubdomainRequirements.ResolvedRequirement req = requirements.effectiveFor(host, requirement);
        if (req.isFirmType() && req.firmCd() != null) {
            out.put("firmCd", req.firmCd().toString());
            out.put("tenantIdentity", usernameForFirm(memberships, req.firmCd()));
            out.put("activeTenant", "firm-" + req.firmCd());
        } else {
            // Resource (or ungated) subdomain: surface the login account's identity.
            // authentication.getName() is the personId UUID (KC username = SAML
            // NameID = personId), so resolve the human ldapUid for the login firm
            // from memberships and fall back to the raw name only if absent.
            Object firmCdAttr = authentication.getAttributes().get("firmCd");
            out.put("firmCd", firmCdAttr);
            String uname = usernameForFirm(memberships, parseFirm(firmCdAttr));
            out.put("tenantIdentity", uname != null ? uname : authentication.getName());
            out.put("activeTenant", req.type());
        }
        out.put("roles", authentication.getRoles());
        // Whitelabel slug + display name resolved per-session: SPA reads these to
        // pick the brand and to wait for /auth/brand without an extra round-trip
        // when the brand is the default. Mirrors the firmKeyword + whitelabel
        // fields P1's /react/indexCommonAsJSON.do returns (LoggedUserJTO.wlcode).
        Integer brandFirm = parseFirm(out.get("firmCd"));
        BrandConfig brand = brands.resolve(brandFirm, host);
        out.put("wlcode", brand.getWlcode());
        out.put("brandDisplayName", brand.getDisplayName());
        return out;
    }

    /** The original client host (nginx sets X-Forwarded-Host; Host as fallback). */
    private static String forwardedHost(HttpRequest<?> request) {
        String h = request.getHeaders().get("X-Forwarded-Host");
        return (h != null && !h.isBlank()) ? h : request.getHeaders().get(HttpHeaders.HOST);
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
    /**
     * Ops/version marker for the deployed shared-auth code. Also the live proof
     * that a bff-core change ships by redeploying the Token Handler alone: bump
     * {@code marker}, rebuild + redeploy token-handler-*, and this reflects the
     * new value via the SPA's /auth/* route — with the domain data apps untouched.
     */
    @Get("/th-version")
    @Secured(SecurityRule.IS_ANONYMOUS)
    public Map<String, Object> thVersion() {
        Map<String, Object> m = new HashMap<>();
        m.put("service", "token-handler");
        m.put("marker", "v2-demo");
        return m;
    }

    // Browser navigations (Accept: text/html) land here after KC returns
    // error=login_required on a silent attempt — micronaut-security follows
    // redirect.login-failure with a top-level redirect, not fetch. Without
    // produces=ALL the default JSON-only route match yields 406 and the
    // silent→interactive upgrade never runs.
    @Get(value = "/login-failed", produces = MediaType.ALL)
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

    // POST-only (M1, logout-CSRF defence). Logout is state-changing — it ends the
    // KC SSO session and every sibling BFF session — so it must not be reachable by
    // a top-level GET navigation a third-party page can trigger. With the session
    // cookie SameSite=Lax, the browser sends it on a same-site top-level navigation
    // (a GET) but NOT on a cross-site POST, so requiring POST means a cross-site
    // forced-logout form-submit arrives without the cookie and tears nothing down.
    // The SPA's "Sign out" submits a same-site form POST here (see AuthProvider).
    //
    // Idempotent — accepts both authenticated and anonymous callers so a double
    // click, an already-expired session, or a back-channel race never surfaces
    // as a 401 to the SPA. If nothing is left to sign out, we still send the
    // browser through the P1 SLO redirect so the user lands somewhere sensible.
    @Post("/logout")
    // The SPA signs out via an HTML form POST, which sends
    // Content-Type: application/x-www-form-urlencoded. Without this the method
    // defaults to consuming application/json and the form POST 404s ("no matching
    // route"). The body itself is empty and unused. MediaType.ALL also keeps a
    // bodyless POST (e.g. a smoke-test curl with no content-type) matching.
    @Consumes({MediaType.APPLICATION_FORM_URLENCODED, MediaType.ALL})
    @Secured(SecurityRule.IS_ANONYMOUS)
    // session.clear() + sessionStore.deleteSession() are blocking Redis calls.
    // Without @ExecuteOn(BLOCKING) they run on the Netty event loop and stall
    // every other in-flight request on the same pod — the symptom is nginx
    // returning 504 Gateway Time-out for /auth/logout while the logout
    // eventually completes server-side seconds later.
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<?> logout(@Nullable Authentication authentication, @Nullable Session session) {
        Object sid = authentication != null ? authentication.getAttributes().get("sid") : null;
        Object refreshToken = authentication != null ? authentication.getAttributes().get("refreshToken") : null;

        // Fire-and-forget the KC end-session on a background thread instead of
        // blocking the request on it. Keycloak's RP-initiated logout fans
        // back-channel-logout POSTs out to every client in the SSO session — one
        // leg of which targets THIS token-handler (demo-shared-client's
        // backchannel.logout.url). Done inline on a single replica it deadlocked:
        // the request thread blocked waiting for KC while KC waited for the
        // self-directed back-channel POST, which needed a free thread — starving
        // /health until the liveness probe killed the pod (502). Detaching frees
        // the request thread at once; the local session is torn down below and the
        // 303 returns immediately, while the SSO end-session + sibling fan-out run
        // in the background (well within the seconds a client polls /auth/me after
        // logout). Still best-effort: failures are logged, never surfaced.
        final Object rt = refreshToken;
        blockingExecutor.submit(() -> endSessionAtKeycloak(rt));

        if (session != null) {
            // Clear the session FIRST: this drops the stored Authentication, so the
            // session is unauthenticated even if the session filter re-persists it
            // at response time. With the Redis store (B2) — unlike the in-memory one
            // — a mid-request deleteSession of the CURRENT request's own session does
            // not reliably stick (the filter re-saves it), so a plain delete left the
            // user logged in. Clearing guarantees a subsequent /auth/me finds an empty
            // session → 401. The delete is then best-effort cleanup of the empty key.
            session.clear();
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
     * endpoint with our stored {@code refresh_token}. Runs on the blocking
     * executor (NOT the request thread — see {@link #logout}) so the self-directed
     * back-channel-logout POST can never deadlock the caller. Best-effort: failures
     * are logged but never surfaced. See class javadoc for why this hop is necessary.
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
