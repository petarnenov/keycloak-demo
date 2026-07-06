package demo.bff.core;

import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.core.annotation.Nullable;

/**
 * One tenant's authorization requirement for the MULTI-TENANT Token Handler — the
 * host-aware equivalent of the single {@link SubdomainRequirement}. One Token
 * Handler instance serves N domains; it resolves the requirement by the request
 * {@code Host}, so adding a domain is one config entry, not a new Deployment.
 *
 * <p>Bound from {@code app.tenants.<slug>.*}, e.g.</p>
 * <pre>
 *   app:
 *     tenants:
 *       billing:
 *         host: billing.geowealth.int
 *         type: resource
 *         object-type: 59
 *         permission: 5
 *       trading:
 *         host: trading.geowealth.int
 *         type: resource
 *         object-type: 5
 *         permission: 5
 * </pre>
 */
@EachProperty("app.tenants")
public class TenantRequirement {

    private final String slug;
    private String host;
    private String type;
    private Integer firmCd;
    private Integer objectType;
    private Integer permission;

    public TenantRequirement(@Parameter String slug) {
        this.slug = slug;
    }

    public String getSlug() { return slug; }

    @Nullable public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    @Nullable public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    @Nullable public Integer getFirmCd() { return firmCd; }
    public void setFirmCd(Integer firmCd) { this.firmCd = firmCd; }

    @Nullable public Integer getObjectType() { return objectType; }
    public void setObjectType(Integer objectType) { this.objectType = objectType; }

    @Nullable public Integer getPermission() { return permission; }
    public void setPermission(Integer permission) { this.permission = permission; }
}
