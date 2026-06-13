package demo.bff.core;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Host → {@link TenantRequirement} resolution for the MULTI-TENANT Token Handler.
 * Built from every {@code app.tenants.<slug>} entry; one Token Handler instance
 * serves N domains and picks the requirement by the request {@code Host}.
 *
 * <p>When no {@code app.tenants.*} is configured this is empty
 * ({@link #isMultiTenant()} is {@code false}) and {@link SubdomainAuthorizer}
 * falls back to the single per-domain {@link SubdomainRequirement} — so the old
 * per-domain Token Handlers keep working unchanged.</p>
 */
@Singleton
public class SubdomainRequirements {

    private final Map<String, TenantRequirement> byHost;

    public SubdomainRequirements(List<TenantRequirement> tenants) {
        Map<String, TenantRequirement> m = new HashMap<>();
        for (TenantRequirement t : tenants) {
            if (t.getHost() != null && !t.getHost().isBlank()) {
                m.put(normalize(t.getHost()), t);
            }
        }
        this.byHost = Map.copyOf(m);
    }

    /** True when at least one {@code app.tenants.*} host requirement is configured. */
    public boolean isMultiTenant() {
        return !byHost.isEmpty();
    }

    /** The requirement for {@code host} (case-insensitive, port stripped), or {@code null}. */
    @Nullable
    public TenantRequirement forHost(@Nullable String host) {
        return host == null ? null : byHost.get(normalize(host));
    }

    /**
     * The effective requirement to apply for {@code host}: the host's tenant in
     * multi-tenant mode (empty if the host is unconfigured), else the single
     * {@code fallback}. Used by {@code /auth/me} and {@code /auth/verify} so both
     * resolve identical per-host rules.
     */
    public ResolvedRequirement effectiveFor(@Nullable String host, SubdomainRequirement fallback) {
        if (isMultiTenant()) {
            TenantRequirement t = forHost(host);
            return t == null ? ResolvedRequirement.EMPTY
                    : new ResolvedRequirement(t.getType(), t.getFirmCd(), t.getObjectType(), t.getPermission());
        }
        return new ResolvedRequirement(fallback.getType(), fallback.getFirmCd(),
                fallback.getObjectType(), fallback.getPermission());
    }

    private static String normalize(String host) {
        String h = host.toLowerCase();
        int colon = h.indexOf(':');
        return colon >= 0 ? h.substring(0, colon) : h;
    }

    /** A resolved per-host gate (type + the firm / resource parameters). */
    public record ResolvedRequirement(@Nullable String type, @Nullable Integer firmCd,
                                      @Nullable Integer objectType, @Nullable Integer permission) {
        public static final ResolvedRequirement EMPTY = new ResolvedRequirement(null, null, null, null);

        public boolean isFirmType() {
            return "firm".equalsIgnoreCase(type);
        }

        public boolean isResourceType() {
            return "resource".equalsIgnoreCase(type);
        }
    }
}
