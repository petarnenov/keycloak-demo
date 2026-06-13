package demo.bff.core;

import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.security.authentication.Authentication;
import jakarta.inject.Singleton;

/**
 * The per-subdomain authorization decision (firm membership OR the Tier 2 P1
 * permission gate), extracted from {@link SubdomainRequirementFilter} so it can
 * be applied both as a filter on {@code /api/**} (the classic in-BFF model) AND
 * by the Token Handler's {@code /auth/verify} forward-auth endpoint (so the data
 * BFF can be auth-unaware). Same rules either way; one place to fix them.
 */
@Singleton
public class SubdomainAuthorizer {

    private final SubdomainRequirement requirement;
    private final Tier23Gate gate;

    public SubdomainAuthorizer(SubdomainRequirement requirement, Tier23Gate gate) {
        this.requirement = requirement;
        this.gate = gate;
    }

    /**
     * Enforce this subdomain's requirement for the authenticated user.
     * Throws {@link HttpStatusException}({@code 403}) when denied; returns
     * normally (no gate) when no requirement is configured.
     */
    public void authorize(Authentication auth) {
        String type = requirement.getType();
        if (type == null || type.isBlank()) {
            return; // no per-subdomain gate configured
        }
        if (requirement.isFirmType()) {
            if (!hasFirmMembership(auth, requirement.getFirmCd())) {
                throw new HttpStatusException(HttpStatus.FORBIDDEN,
                        "This subdomain is for firm " + requirement.getFirmCd()
                                + "; your person has no account there.");
            }
        } else if (requirement.isResourceType()
                && requirement.getObjectType() != null && requirement.getPermission() != null) {
            // Throws HttpStatusException(FORBIDDEN) when the current login lacks the permission.
            gate.require(auth, requirement.getObjectType(), requirement.getPermission());
        }
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
}
