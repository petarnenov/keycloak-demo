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
 *       configured {@code (objectType, permission)}, via {@link Tier23Gate}
 *       (the existing Tier 2/3 P1 authz). Otherwise 403.</li>
 *   <li>unset → no gate (any authenticated person reaches the controller).</li>
 * </ul>
 */
@Filter("/api/**")
public class SubdomainRequirementFilter implements HttpServerFilter {

    private static final Logger LOG = LoggerFactory.getLogger(SubdomainRequirementFilter.class);

    private final SubdomainRequirement requirement;
    private final Tier23Gate gate;

    public SubdomainRequirementFilter(SubdomainRequirement requirement, Tier23Gate gate) {
        this.requirement = requirement;
        this.gate = gate;
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        String type = requirement.getType();
        if (type == null || type.isBlank()) {
            return chain.proceed(request); // no per-subdomain gate configured
        }
        Authentication auth = request.getUserPrincipal(Authentication.class).orElse(null);
        if (auth == null) {
            // Not authenticated — let the security / token-refresh layer answer (401).
            return chain.proceed(request);
        }
        try {
            if (requirement.isFirmType()) {
                if (!hasFirmMembership(auth, requirement.getFirmCd())) {
                    LOG.debug("subdomain access denied: no account in firm {}", requirement.getFirmCd());
                    return Publishers.just(forbidden(
                            "This subdomain is for firm " + requirement.getFirmCd()
                                    + "; your person has no account there."));
                }
            } else if (requirement.isResourceType()
                    && requirement.getObjectType() != null && requirement.getPermission() != null) {
                // Throws HttpStatusException(FORBIDDEN) when the current login lacks the permission.
                gate.require(auth, requirement.getObjectType(), requirement.getPermission());
            }
        } catch (HttpStatusException e) {
            return Publishers.just(HttpResponse.status(e.getStatus())
                    .body(Map.of("error", "subdomain_access_denied",
                            "reason", String.valueOf(e.getMessage()))));
        }
        return chain.proceed(request);
    }

    /** True when the {@code memberships} claim contains an entry for {@code firmCd}. */
    private static boolean hasFirmMembership(Authentication auth, Integer firmCd) {
        if (firmCd == null) {
            return false;
        }
        String prefix = firmCd + ":";
        for (String e : AuthClaims.memberships(auth)) {
            if (e != null && e.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static MutableHttpResponse<?> forbidden(String reason) {
        return HttpResponse.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "subdomain_access_denied", "reason", reason));
    }

    @Override
    public int getOrder() {
        // After TokenRefreshFilter (which is SECURITY.after()) so the session/token
        // is validated/refreshed before we authorize.
        return ServerFilterPhase.SECURITY.after() + 10;
    }
}
