package demo.bff.core;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Nullable;

/**
 * Declares what THIS subdomain (BFF) requires of a federated person — the
 * subdomain-agnostic authorization model (person-identity-via-existing-linkdelink.md):
 *
 * <ul>
 *   <li>{@code type=firm} — the subdomain is "a firm's app". Access requires the
 *       person to have an account in {@link #getFirmCd()} (checked against the
 *       {@code memberships} claim from the {@code LINKED_GW_USER} graph).</li>
 *   <li>{@code type=resource} — the subdomain is a shared, multi-firm resource.
 *       Access requires the CURRENT logged-in user to hold
 *       ({@link #getObjectType()}, {@link #getPermission()}) — checked via the
 *       existing Tier 2/3 authz ({@link PolicyRuleGate}/{@link PolicyRuleClient}).</li>
 *   <li>unset/blank {@code type} — no per-subdomain gate (any authenticated person).</li>
 * </ul>
 *
 * Bound from {@code app.requirement.*} in each BFF's {@code application.yml}.
 */
@ConfigurationProperties("app.requirement")
public class SubdomainRequirement {

    public static final String TYPE_FIRM = "firm";
    public static final String TYPE_RESOURCE = "resource";

    private String type;
    private Integer firmCd;
    private Integer objectType;
    private Integer permission;

    @Nullable public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    @Nullable public Integer getFirmCd() { return firmCd; }
    public void setFirmCd(Integer firmCd) { this.firmCd = firmCd; }

    @Nullable public Integer getObjectType() { return objectType; }
    public void setObjectType(Integer objectType) { this.objectType = objectType; }

    @Nullable public Integer getPermission() { return permission; }
    public void setPermission(Integer permission) { this.permission = permission; }

    public boolean isFirmType() { return TYPE_FIRM.equalsIgnoreCase(type); }
    public boolean isResourceType() { return TYPE_RESOURCE.equalsIgnoreCase(type); }
}
