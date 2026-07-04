package demo.bff.core;

import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.http.filter.ServerFilterPhase;
import io.micronaut.security.authentication.Authentication;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Per-subdomain authorization gate (person-identity-via-existing-linkdelink.md).
 * Keycloak maps nothing to a subdomain — it emits subdomain-agnostic person
 * facts. Each subdomain declares what it requires via {@link SubdomainRequirement}
 * and THIS filter enforces it on every {@code /api/**} call, after
 * {@link TokenRefreshFilter} (so the token is fresh):
 *
 * <ul>
 *   <li>{@code type=firm} → the person must have an account in the configured
 *       firm, i.e. {@code "<firmCd>:"} appears in the {@code memberships} claim
 *       (from the {@code LINKED_GW_USER} graph). Otherwise 403.</li>
 *   <li>{@code type=resource} → the CURRENT logged-in user must hold the
 *       configured {@code (objectType, permission)}, via {@link PolicyRuleGate}
 *       (the existing PolicyRule capability/refine P1 authz). Otherwise 403.</li>
 *   <li>unset → no gate (any authenticated person reaches the controller).</li>
 * </ul>
 */
@Filter("/api/**")
public class SubdomainRequirementFilter implements HttpServerFilter {

    private static final Logger LOG = LoggerFactory.getLogger(SubdomainRequirementFilter.class);

    private final SubdomainAuthorizer authorizer;

    public SubdomainRequirementFilter(SubdomainAuthorizer authorizer) {
        this.authorizer = authorizer;
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        Authentication auth = request.getUserPrincipal(Authentication.class).orElse(null);
        if (auth == null) {
            // Not authenticated — let the security / token-refresh layer answer (401).
            return chain.proceed(request);
        }
        try {
            // shared decision (firm membership / capability check gate); host-aware in multi-tenant mode
            authorizer.authorize(auth, request.getHeaders().get(io.micronaut.http.HttpHeaders.HOST));
        } catch (HttpStatusException e) {
            LOG.debug("subdomain access denied: {}", e.getMessage());
            return Publishers.just(HttpResponse.status(e.getStatus())
                    .body(Map.of("error", "subdomain_access_denied",
                            "reason", String.valueOf(e.getMessage()))));
        }
        return chain.proceed(request);
    }

    @Override
    public int getOrder() {
        // After TokenRefreshFilter (which is SECURITY.after()) so the session/token
        // is validated/refreshed before we authorize.
        return ServerFilterPhase.SECURITY.after() + 10;
    }
}
