package demo.bff.core;

import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.security.authentication.Authentication;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Shared Tier 2/3 authorization gate (p1-auth-flow.md §2.2/§2.9). Wraps
 * {@link P1AuthzClient} so every domain BFF applies the fine-grained checks
 * identically: a single-object permission check (Tier 2) and a list refine
 * (Tier 3). No-op when fine checks are disabled or the caller is {@code gwAdmin}.
 *
 * <p>The coarse role gate stays in each controller's {@code @Secured} (Tier 1 —
 * domain policy); this bean carries only the firm-agnostic mechanics that were
 * previously copy-pasted into every controller as {@code requirePermission} /
 * {@code refineByObjectAccess}.</p>
 */
@Singleton
public class Tier23Gate {

    private final P1AuthzClient authz;

    public Tier23Gate(P1AuthzClient authz) {
        this.authz = authz;
    }

    /** Tier 2: 403 unless the user holds (objectType, permission) in P1. No-op when fine checks are off or gwAdmin. */
    public void require(Authentication authentication, int objectType, int permission) {
        if (!authz.fineEnabled() || isGwAdmin(authentication)) {
            return; // opt-in; coarse @Secured already applied; gwAdmin overrides (gwAdmin || canX)
        }
        String bearer = bearer(authentication);
        if (bearer == null || !authz.hasPermission(bearer, sub(authentication), objectType, permission)) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "fine permission denied");
        }
    }

    /**
     * Tier 3: keep only the items the user may act on, via P1's refine. Each
     * item's id is extracted with {@code idOf} because domains key their rows
     * differently (e.g. billing "number" vs trading "id"). No-op when fine
     * checks are off or gwAdmin; fails closed (empty list) when there is no
     * bearer.
     */
    public <T> List<T> refine(Authentication authentication, List<T> items,
                              Function<? super T, String> idOf, int objectType, int permission) {
        if (!authz.fineEnabled() || isGwAdmin(authentication)) {
            return items; // gwAdmin sees every row (gwAdmin || canX)
        }
        String bearer = bearer(authentication);
        if (bearer == null) {
            return List.of(); // fail closed
        }
        List<String> ids = new ArrayList<>();
        for (T it : items) {
            String id = idOf.apply(it);
            if (id != null) {
                ids.add(id);
            }
        }
        Set<String> allowed = new HashSet<>(authz.refine(bearer, objectType, permission, ids));
        List<T> out = new ArrayList<>();
        for (T it : items) {
            String id = idOf.apply(it);
            if (id != null && allowed.contains(id)) {
                out.add(it);
            }
        }
        return out;
    }

    /** The caller's own access token from the server-side session (Token Handler model), as a Bearer header value. */
    private static String bearer(Authentication authentication) {
        Object token = authentication.getAttributes().get("accessToken");
        return token == null ? null : "Bearer " + token;
    }

    private static String sub(Authentication authentication) {
        Object v = authentication.getAttributes().get("sub");
        return v == null ? authentication.getName() : v.toString();
    }

    /** Global cross-firm override carried as the gwAdmin realm role (gwAdminFlag). */
    private static boolean isGwAdmin(Authentication authentication) {
        return authentication.getRoles().contains("gwAdmin");
    }
}
