package demo;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller("/api")
@Produces(MediaType.APPLICATION_JSON)
public class UserController {

    private final String source;
    private final List<String> allowedRoles;

    public UserController(@Value("${app.source:bff}") String source,
                          @Value("${app.allowed-roles:}") List<String> allowedRoles) {
        this.source = source;
        this.allowedRoles = allowedRoles.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    @Get("/user")
    @Secured({"isAuthenticated()"})
    public HttpResponse<Map<String, Object>> getUser(Authentication authentication) {
        Collection<String> roles = authentication.getRoles();
        if (!allowedRoles.isEmpty() && Collections.disjoint(allowedRoles, roles)) {
            Map<String, Object> body = new HashMap<>();
            body.put("error", "forbidden");
            body.put("reason", "not_allowed");
            body.put("message", "This app is restricted to role(s): " + String.join(", ", allowedRoles) + ".");
            body.put("username", authentication.getName());
            body.put("allowedRoles", allowedRoles);
            body.put("yourRoles", roles);
            body.put("source", source);
            return HttpResponse.<Map<String, Object>>status(HttpStatus.FORBIDDEN).body(body);
        }
        Map<String, Object> user = new HashMap<>();
        user.put("username", authentication.getName());
        user.put("roles", roles);
        user.put("allowedRoles", allowedRoles);
        user.put("source", source);
        authentication.getAttributes().forEach(user::put);
        return HttpResponse.ok(user);
    }

    /**
     * Returns the user's identity plus the list of microfrontends their roles
     * grant access to. The shell uses this to decide which MFE routes to mount
     * and which nav links to grey out; the per-MFE BFF endpoints are still the
     * authoritative gate, so this is a UX hint, not a security boundary.
     *
     * Role → allowedMfes mapping (mirrors the old per-app APP_ALLOWED_ROLES
     * matrix from CLAUDE.md):
     *   - any authenticated user → "client"
     *   - role "user" or "admin"  → "ops"
     *   - role "admin"            → "admin"
     */
    @Get("/whoami")
    @Secured({"isAuthenticated()"})
    public Map<String, Object> whoami(Authentication authentication) {
        Collection<String> roles = authentication.getRoles();
        List<String> allowedMfes = new ArrayList<>();
        allowedMfes.add("client");
        if (roles.contains("user") || roles.contains("admin")) {
            allowedMfes.add("ops");
        }
        if (roles.contains("admin")) {
            allowedMfes.add("admin");
        }
        Map<String, Object> body = new HashMap<>();
        body.put("username", authentication.getName());
        body.put("roles", roles);
        body.put("allowedMfes", allowedMfes);
        body.put("source", source);
        Object email = authentication.getAttributes().get("email");
        body.put("email", email != null ? email : "");
        // firmCd is the whitelabel transmission anchor: P1 emits it as a
        // SAML attribute, Keycloak's saml-user-attribute-idp-mapper writes
        // it to the federated user record, and a protocol mapper on
        // mfe-shell-client puts it in the JWT as a top-level "firmCd"
        // claim. The shell uses it to call P1's branding API and apply
        // per-firm theming. Default to "" when absent so the shell can
        // still render (anonymous brand path).
        Object firmCd = authentication.getAttributes().get("firmCd");
        body.put("firmCd", firmCd != null ? String.valueOf(firmCd) : "");
        return body;
    }

    @Get("/secure")
    @Secured({"isAuthenticated()"})
    public Map<String, String> getSecure() {
        Map<String, String> response = new HashMap<>();
        response.put("message", "Protected endpoint");
        response.put("source", source);
        response.put("timestamp", String.valueOf(System.currentTimeMillis()));
        return response;
    }

    @Get("/public")
    @Secured({"isAnonymous()"})
    public Map<String, String> getPublic() {
        Map<String, String> response = new HashMap<>();
        response.put("message", "Public endpoint");
        response.put("source", source);
        response.put("timestamp", String.valueOf(System.currentTimeMillis()));
        return response;
    }
}
