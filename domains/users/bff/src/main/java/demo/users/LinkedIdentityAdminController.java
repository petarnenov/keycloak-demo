package demo.users;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;

import java.util.Map;

/**
 * BFF proxy over the P1 monolith's bearer-auth linked-identity admin endpoint
 * ({@code /saml/idp/linked-identity-admin.do}, see
 * {@code com.geowealth.saml.idp.LinkedIdentityAdminAction}). Backs the
 * "Linked identities" admin UI in the users SPA.
 *
 * <p><b>Token Handler model.</b> The SPA carries no token — calls reach the
 * BFF over the {@code USESSION} httpOnly cookie. The controller picks the
 * user's KC access token from the server-side session and forwards it to P1
 * as {@code Authorization: Bearer …}. Authority stays user-bound (§2.6); P1
 * re-enforces gw-admin authorization on every call.</p>
 *
 * <p><b>Authorization.</b> Class-level {@code @Secured("gwAdmin")} gates the
 * whole surface at the BFF layer (defence in depth). P1's
 * {@link com.geowealth.saml.idp.LinkedIdentityAdminAction} re-checks
 * {@code gwAdminFlag} on the token's subject, so a forged token with
 * {@code gwAdmin} role but a non-gw-admin {@code sub} still gets 403 from
 * the monolith.</p>
 */
@Controller("/api/linked-identities")
@Produces(MediaType.APPLICATION_JSON)
@ExecuteOn(TaskExecutors.BLOCKING)
@Secured("gwAdmin")
public class LinkedIdentityAdminController {

    private final LinkedIdentityAdminClient p1;

    public LinkedIdentityAdminController(LinkedIdentityAdminClient p1) {
        this.p1 = p1;
    }

    /**
     * {@code GET /api/linked-identities[?sourceUserUuid=…]} — list all bindings
     * (admin overview) or filter by source identity.
     */
    @Get
    public Map<String, Object> list(@QueryValue(value = "sourceUserUuid", defaultValue = "") @Nullable String sourceUserUuid,
                                    Authentication authentication) {
        return p1.list(sourceUserUuid, bearer(authentication));
    }

    /**
     * {@code GET /api/linked-identities/get?sourceUserUuid=…&targetClient=…} —
     * fetch one row by composite key, including soft-deleted history.
     */
    @Get("/get")
    public Map<String, Object> getOne(@QueryValue("sourceUserUuid") String sourceUserUuid,
                                      @QueryValue("targetClient") String targetClient,
                                      Authentication authentication) {
        return p1.get(sourceUserUuid, targetClient, bearer(authentication));
    }

    /**
     * {@code POST /api/linked-identities/upsert} with JSON body
     * {@code {sourceUserUuid, targetClient, targetUserUuid, mfaRequired?, active?}}.
     */
    @Post("/upsert")
    public Map<String, Object> upsert(@Body Map<String, Object> body, Authentication authentication) {
        return p1.upsert(body, bearer(authentication));
    }

    /**
     * {@code POST /api/linked-identities/delete} with JSON body
     * {@code {sourceUserUuid, targetClient, hard?}}.
     */
    @Post("/delete")
    public Map<String, Object> delete(@Body Map<String, Object> body, Authentication authentication) {
        return p1.delete(body, bearer(authentication));
    }

    private static String bearer(Authentication authentication) {
        Object token = authentication.getAttributes().get("accessToken");
        if (token == null) {
            throw new HttpStatusException(HttpStatus.UNAUTHORIZED, "missing access token in session");
        }
        return token.toString();
    }
}
