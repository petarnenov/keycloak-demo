package demo.users;

import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import jakarta.inject.Inject;

import java.util.Map;

/**
 * Firm-CRUD surface for the Firms tab in the users SPA. Read happens via
 * the existing {@link UsersController#firms} endpoint; this controller
 * adds write ops (create + deactivate).
 *
 * <p>Endpoint path is {@code /api/firms} (post-nginx-rewrite). The SPA
 * sees {@code /api/users/firms-admin/…}; the nginx rule in
 * {@code domains/users/web/nginx.conf} strips the {@code /users} segment
 * so this controller's {@code /api/firms} matches.</p>
 */
@Controller("/api/firms")
@Produces(MediaType.APPLICATION_JSON)
@ExecuteOn(TaskExecutors.BLOCKING)
public class FirmsController {

    @Inject
    P1Client p1;

    /** POST /api/users/firms — create a new firm. body = {@code { firmCd?, firmName, code? }}. */
    @Post
    @Secured({"users-admin", "admin", "gwAdmin"})
    public Map<String, Object> create(@Body Map<String, Object> body, Authentication authentication) {
        return p1.createFirm(body, bearer(authentication));
    }

    /** DELETE /api/users/firms/{firmCd} — soft-delete (sets INACTIVE=1). */
    @Delete("/{firmCd}")
    @Secured({"users-admin", "admin", "gwAdmin"})
    public Map<String, Object> deactivate(@PathVariable int firmCd, Authentication authentication) {
        return p1.deactivateFirm(firmCd, bearer(authentication));
    }

    private static String bearer(Authentication authentication) {
        Object token = authentication.getAttributes().get("accessToken");
        if (token == null) {
            throw new HttpStatusException(HttpStatus.UNAUTHORIZED, "missing access token in session");
        }
        return token.toString();
    }
}
