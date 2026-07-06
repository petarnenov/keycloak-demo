package demo.bff.core;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;

import java.util.Map;

/**
 * Forward-auth endpoint for the Token Handler (industry-standard
 * nginx {@code auth_request} / Envoy ext-authz pattern). nginx calls this as a
 * subrequest before proxying {@code /api/<name>} to the (auth-unaware) domain
 * BFF:
 *
 * <ul>
 *   <li>no valid session → {@code 401} (the {@code @Secured} rule), so nginx
 *       returns 401 to the SPA;</li>
 *   <li>session valid but the subdomain requirement denies (firm membership /
 *       capability check permission) → {@code 403};</li>
 *   <li>allowed → {@code 200} with the user's identity as {@code X-Auth-*}
 *       response headers, which nginx copies onto the upstream request.</li>
 * </ul>
 *
 * <p>This moves ALL of session validation, token refresh (the
 * {@link TokenRefreshFilter} runs here on {@code /auth/**}), coarse + capability check
 * authorization into the Token Handler — so the domain BFF runs no auth at all.
 * The access token is forwarded too, because the domain BFF's refine list
 * {@code refine} (data-row filtering, intrinsically next to the data) still needs
 * to call P1.</p>
 */
@Controller("/auth")
@ExecuteOn(TaskExecutors.BLOCKING)
public class ForwardAuthController {

    private final SubdomainAuthorizer authorizer;

    public ForwardAuthController(SubdomainAuthorizer authorizer) {
        this.authorizer = authorizer;
    }

    @Get("/verify")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> verify(Authentication auth, HttpRequest<?> request) {
        // In multi-tenant mode the authorizer resolves the per-domain requirement by
        // host. nginx forwards the ORIGINAL client host as X-Forwarded-Host on the
        // auth_request subrequest (the Host header here is the Token Handler's own).
        // In single-tenant mode the host is ignored.
        String host = request.getHeaders().get("X-Forwarded-Host");
        if (host == null || host.isBlank()) {
            host = request.getHeaders().get(HttpHeaders.HOST);
        }
        try {
            authorizer.authorize(auth, host);
        } catch (HttpStatusException e) {
            return HttpResponse.status(e.getStatus());
        }
        Map<String, Object> a = auth.getAttributes();
        MutableHttpResponse<?> r = HttpResponse.ok();
        header(r, "X-Auth-Username", auth.getName());
        header(r, "X-Auth-Sub", str(a.get("sub")));
        header(r, "X-Auth-Person-Id", str(a.get("personId")));
        header(r, "X-Auth-Firm-Cd", AuthClaims.firmCd(auth));
        // memberships are "<firmCd>:<ldapUid>" (no comma), roles are role names —
        // comma-join is a safe single-header encoding the data BFF splits back.
        header(r, "X-Auth-Memberships", String.join(",", AuthClaims.memberships(auth)));
        header(r, "X-Auth-Roles", String.join(",", auth.getRoles()));
        header(r, "X-Auth-Access-Token", str(a.get("accessToken")));
        return r;
    }

    private static void header(MutableHttpResponse<?> r, String name, String value) {
        if (value != null && !value.isEmpty()) {
            r.header(name, value);
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
