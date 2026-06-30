package demo.bff.core;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.cookie.SameSite;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Silent-first login flow (cross-subdomain-sso-implementation.md § 4.2).
 *
 * <p>The SPA's {@code AuthProvider} hits this endpoint instead of going
 * straight to {@code /oauth/login/keycloak}. It:
 *
 * <ol>
 *   <li>sets a short-lived browser cookie {@link #SILENT_ATTEMPT_COOKIE}.
 *       It has to be a cookie, not a session attribute: at this point the
 *       BFF has no session for the user (they're anonymous), so any session
 *       Micronaut creates here would be replaced by the one
 *       micronaut-security spawns on the actual {@code /oauth/login/keycloak}
 *       call — the marker would be lost. A cookie outlives that
 *       session-rotation.</li>
 *   <li>redirects the browser to {@code /oauth/login/keycloak?silent=1}.
 *       {@link IdpHintFilter} sees the {@code silent=1} marker on the request
 *       and appends {@code prompt=none} to the Keycloak authorize URL on top
 *       of the usual {@code kc_idp_hint=p1};</li>
 *   <li>at Keycloak — if the realm SSO session is alive, KC emits a code
 *       immediately (no IdP round-trip), the regular callback completes, and
 *       the SPA renders. If not, KC redirects the browser back to the BFF's
 *       callback with {@code error=login_required}; micronaut-security treats
 *       that as a failure and follows {@code redirect.login-failure} →
 *       {@code /auth/login-failed} → {@link AuthController#loginFailed},
 *       which sees the silent-attempt cookie and 302s to the interactive
 *       {@code /oauth/login/keycloak}.</li>
 * </ol>
 *
 * <p>Net effect: the first subdomain the browser ever sees performs the full
 * interactive P1 login; every subsequent subdomain visited within the realm
 * SSO session lifetime completes silently — no flash, no IdP UI, no extra
 * P1 round-trip.</p>
 */
@Controller("/oauth/login/silent")
@ExecuteOn(TaskExecutors.BLOCKING)
public class SilentLoginController {

    /** Cookie name for the silent-attempt marker — see class javadoc. */
    public static final String SILENT_ATTEMPT_COOKIE = "kc_silent_attempt";

    /** Cookie TTL: just long enough to bounce through KC and come back. */
    private static final int COOKIE_TTL_SECONDS = 120;

    private static final Logger LOG = LoggerFactory.getLogger(SilentLoginController.class);

    // Browser navigation (Accept: text/html) — same produces=ALL requirement as
    // AuthController#loginFailed; default JSON-only route match yields 406.
    @Get(produces = MediaType.ALL)
    @Secured(SecurityRule.IS_ANONYMOUS)
    public HttpResponse<?> startSilent() {
        LOG.debug("starting silent login attempt (prompt=none) — setting silent-attempt cookie");
        MutableHttpResponse<Object> response = HttpResponse.status(HttpStatus.SEE_OTHER);
        response.getHeaders().add(HttpHeaders.LOCATION, "/oauth/login/keycloak?silent=1");
        response.cookie(Cookie.of(SILENT_ATTEMPT_COOKIE, "1")
                .maxAge(COOKIE_TTL_SECONDS)
                .path("/")
                .httpOnly(true)
                .secure(true)
                .sameSite(SameSite.Lax));
        return response;
    }
}
