package demo.bff.core;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.security.authentication.Authentication;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * The per-subdomain authorization decision (firm membership OR the Tier 2 P1
 * permission gate), extracted from {@link SubdomainRequirementFilter} so it can
 * be applied both as a filter on {@code /api/**} (the classic in-BFF model) AND
 * by the Token Handler's {@code /auth/verify} forward-auth endpoint (so the data
 * BFF can be auth-unaware). Same rules either way; one place to fix them.
 *
 * <p><b>Multi-tenant:</b> when {@code app.tenants.*} is configured (one Token
 * Handler for N domains), the requirement is resolved by the request {@code Host}
 * via {@link SubdomainRequirements}. When it is not (a per-domain Token Handler /
 * in-BFF filter), it falls back to the single {@link SubdomainRequirement}. The
 * gate logic is identical; only where the {@code (type, firmCd, objectType,
 * permission)} tuple comes from differs.</p>
 */
@Singleton
public class SubdomainAuthorizer {

    private final SubdomainRequirement requirement;
    private final SubdomainRequirements tenants;
    private final PolicyRuleGate gate;

    @Inject
    public SubdomainAuthorizer(SubdomainRequirement requirement,
                               SubdomainRequirements tenants,
                               PolicyRuleGate gate) {
        this.requirement = requirement;
        this.tenants = tenants;
        this.gate = gate;
    }

    /** Single-tenant convenience constructor (tests / per-domain mode). */
    public SubdomainAuthorizer(SubdomainRequirement requirement, PolicyRuleGate gate) {
        this(requirement, new SubdomainRequirements(List.of()), gate);
    }

    /** Single-tenant entry point (per-domain Token Handler / in-BFF filter). */
    public void authorize(Authentication auth) {
        authorize(auth, null);
    }

    /**
     * Enforce the requirement for the authenticated user. In multi-tenant mode the
     * requirement is resolved from {@code host}; otherwise the single configured
     * {@link SubdomainRequirement} applies. Throws {@link HttpStatusException}
     * ({@code 403}) when denied; returns normally when no requirement applies.
     */
    public void authorize(Authentication auth, @Nullable String host) {
        Gate g = resolve(host);
        if (g.type == null || g.type.isBlank()) {
            return; // no per-subdomain gate configured
        }
        if (isFirm(g.type)) {
            if (!hasFirmMembership(auth, g.firmCd)) {
                throw new HttpStatusException(HttpStatus.FORBIDDEN,
                        "This subdomain is for firm " + g.firmCd
                                + "; your person has no account there.");
            }
        } else if (isResource(g.type) && g.objectType != null && g.permission != null) {
            // Throws HttpStatusException(FORBIDDEN) when the current login lacks the permission.
            gate.requireCapability(auth, g.objectType, g.permission);
        }
    }

    /** Multi-tenant: requirement for {@code host}; else the single fallback. */
    private Gate resolve(@Nullable String host) {
        if (tenants.isMultiTenant()) {
            TenantRequirement t = tenants.forHost(host);
            if (t == null) {
                // Multi-tenant Token Handler asked about an unconfigured host — deny
                // rather than silently allow (fail closed).
                throw new HttpStatusException(HttpStatus.FORBIDDEN,
                        "No tenant requirement configured for host " + host);
            }
            return new Gate(t.getType(), t.getFirmCd(), t.getObjectType(), t.getPermission());
        }
        return new Gate(requirement.getType(), requirement.getFirmCd(),
                requirement.getObjectType(), requirement.getPermission());
    }

    private static boolean isFirm(String type) {
        return "firm".equalsIgnoreCase(type);
    }

    private static boolean isResource(String type) {
        return "resource".equalsIgnoreCase(type);
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

    /** The resolved (type, firmCd, objectType, permission) tuple to enforce. */
    private record Gate(String type, Integer firmCd, Integer objectType, Integer permission) { }
}
