package demo.bff.core;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OIDC Back-Channel Logout 1.0 receiver. Keycloak calls this (server-to-server)
 * when the user's KC SSO session ends — including a P1 (IdP) logout that KC
 * cannot front-channel back to an open SPA tab. We verify the logout token and
 * destroy the matching BFF session; the SPA's next {@code /auth/me} then returns
 * 401 and it signs out. No browser, no third-party cookies.
 */
@Controller("/backchannel-logout")
public class BackchannelLogoutController {

    private static final Logger LOG = LoggerFactory.getLogger(BackchannelLogoutController.class);

    private final LogoutTokenValidator validator;
    private final SidSessionRegistry registry;

    public BackchannelLogoutController(LogoutTokenValidator validator, SidSessionRegistry registry) {
        this.validator = validator;
        this.registry = registry;
    }

    @Post
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Secured(SecurityRule.IS_ANONYMOUS)
    public HttpResponse<?> logout(@Nullable @Body("logout_token") String logoutToken) {
        LOG.info("back-channel logout: received POST, token present={}", logoutToken != null && !logoutToken.isBlank());
        if (logoutToken == null || logoutToken.isBlank()) {
            return HttpResponse.badRequest();
        }
        String sid = validator.validateAndGetSid(logoutToken);
        if (sid == null) {
            LOG.warn("back-channel logout: token failed validation");
            return HttpResponse.badRequest();
        }
        int killed = registry.invalidateBySid(sid);
        LOG.info("back-channel logout OK: sid={} killed-sessions={}", sid, killed);
        // Spec: 200 with no caching.
        return HttpResponse.ok().header("Cache-Control", "no-store");
    }
}
