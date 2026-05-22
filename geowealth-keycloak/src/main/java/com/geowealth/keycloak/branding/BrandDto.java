package com.geowealth.keycloak.branding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Wire-shape DTO for the GeoWealth branding API response.
 *
 * Matches GET /branding-api/keycloak/whitelabel/{code} per the proposal
 * doc (geowealth-keycloak-whitelabel-sync.md §4.1). Phase 2.b only consumes
 * the fields needed for the login screen: code, displayName, cssVariables.
 * Wider fields (assets, supportEmail, etc.) are tolerated but ignored —
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} keeps us forward-
 * compatible if the API grows.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class BrandDto {

    public String code;
    public String displayName;
    public Map<String, String> cssVariables;
    /**
     * POC shortcut: the fake emits the login logo inline as a
     * {@code data:image/svg+xml;base64,...} URI in this field. The
     * contract proper (see {@code contracts/branding-api.openapi.yaml})
     * routes logos through {@code assets.loginLogo.url} pointing at a
     * separate {@code /asset/{kind}} endpoint. The real Tomcat servlet
     * will follow the contract; this field is kept tolerant on the wire
     * so a contract-emitting server that doesn't set it still parses.
     * Brand.java sanitizes the value before it reaches the template.
     */
    public String loginLogoDataUri;

    public BrandDto() {}

    /**
     * Map to the immutable {@link Brand} used by the FreeMarker layer.
     * Rejects payloads that don't have a code or have no CSS variables —
     * those are useless for rendering anyway, and forcing a failure here
     * lets the caller fall back to the hardcoded default.
     */
    public Brand toBrand() {
        if (code == null || code.isEmpty()) return null;
        if (cssVariables == null || cssVariables.isEmpty()) return null;
        return new Brand(code, displayName != null ? displayName : code, cssVariables,
            loginLogoDataUri);
    }
}
