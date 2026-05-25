package demo.billing;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.session.Session;

import java.util.HashMap;
import java.util.Map;

/**
 * Identity endpoint for the SPA in the BFF / Token Handler model. The SPA holds
 * no tokens; it asks the BFF "who am I?" over the session cookie.
 *
 * <p>{@code GET /auth/me} returns the user when the session is valid (and, as a
 * side effect, records the OIDC {@code sid}→BFF-session mapping so a later
 * back-channel logout can target this session). When the session is gone it
 * answers 401 (redirect is disabled for protected routes), which the SPA reads
 * as "signed out" — the prompt-logout path after a P1/IdP logout.</p>
 */
@Controller("/auth")
public class AuthController {

    private final SidSessionRegistry registry;

    public AuthController(SidSessionRegistry registry) {
        this.registry = registry;
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
}
