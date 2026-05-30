package demo.users;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import jakarta.inject.Inject;

import java.util.Map;

/**
 * Person-registry CRUD surface for the Persons tab in the users SPA.
 *
 * <p>Wraps the P1 {@code /saml/idp/bff-persons.do} endpoint with the same
 * Tier-1 role gate as the existing {@link UsersController}. Authorisation
 * at the P1 layer is stricter (read = back-office, write = gwAdmin from
 * firm 1); the BFF gate is the coarse first line.</p>
 */
/**
 * Endpoint path is {@code /api/persons} (post-nginx-rewrite). The SPA
 * sees {@code /api/users/persons}; nginx in the users-web container
 * rewrites that to {@code /api/persons} before proxying to this BFF —
 * same convention {@link UsersController} (@Controller("/api")) uses.
 */
@Controller("/api/persons")
@Produces(MediaType.APPLICATION_JSON)
@ExecuteOn(TaskExecutors.BLOCKING)  // P1Client uses BlockingHttpClient — keep off the Netty event loop.
public class PersonsController {

    @Inject
    P1Client p1;

    /** Tier-1 read gate — the SPA's Persons tab is admin-only UX. */
    @Get
    @Secured({"users-admin", "admin", "gwAdmin"})
    public Map<String, Object> list(Authentication authentication) {
        return p1.listPersons(bearer(authentication));
    }

    @Get("/{personId}")
    @Secured({"users-admin", "admin", "gwAdmin"})
    public Map<String, Object> get(@PathVariable String personId,
                                   Authentication authentication) {
        return p1.getPerson(personId, bearer(authentication));
    }

    /** Upsert by full payload — POST body shape:
     * {@code { personId, displayName, usernames: [], aliases: { slug: alias }, roles: { slug: [...] } } }.
     * The path variable mirrors the personId in the body for clarity; mismatches
     * are rejected to avoid silently rewriting a different row. */
    @Put("/{personId}")
    @Secured({"users-admin", "admin", "gwAdmin"})
    public Map<String, Object> upsert(@PathVariable String personId,
                                       @Body Map<String, Object> body,
                                       Authentication authentication) {
        Object inBody = body.get("personId");
        if (inBody != null && !personId.equals(inBody.toString())) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST,
                    "personId in path (" + personId + ") doesn't match body (" + inBody + ")");
        }
        body.put("personId", personId);
        return p1.upsertPerson(body, bearer(authentication));
    }

    @Delete("/{personId}")
    @Secured({"users-admin", "admin", "gwAdmin"})
    public Map<String, Object> delete(@PathVariable String personId,
                                      Authentication authentication) {
        return p1.deletePerson(personId, bearer(authentication));
    }

    private static String bearer(Authentication authentication) {
        Object token = authentication.getAttributes().get("accessToken");
        if (token == null) {
            throw new HttpStatusException(HttpStatus.UNAUTHORIZED, "missing access token in session");
        }
        return token.toString();
    }
}
