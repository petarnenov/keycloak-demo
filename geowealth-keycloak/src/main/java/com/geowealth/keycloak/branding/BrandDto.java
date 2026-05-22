package com.geowealth.keycloak.branding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Wire-shape DTO for the GeoWealth branding API response.
 *
 * <p>Matches {@code GET /branding-api/keycloak/whitelabel/{code}} per the
 * OpenAPI contract at {@code contracts/branding-api.openapi.yaml}.
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} keeps the consumer
 * forward-compatible if the API grows fields the SPI doesn't read yet.</p>
 *
 * <p>Asset URIs (login logo, favicon) are not in the brand payload — the
 * contract routes them through {@link Assets#loginLogo} /
 * {@link Assets#favicon}, each a {@link AssetRef} pointing at a separate
 * {@code /asset/{kind}} GET. {@link BrandingApiClient#fetchBrand(String)}
 * follows those references, base64-encodes the bytes, and constructs the
 * immutable {@link Brand} with inlined data URIs.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class BrandDto {

    public String code;
    public String displayName;
    public Map<String, String> cssVariables;
    public Assets assets;

    public BrandDto() {}

    /**
     * Map to the immutable {@link Brand} used by the FreeMarker layer
     * <em>without</em> the asset URIs — those require additional HTTP
     * fetches done by {@link BrandingApiClient}. Returns {@code null}
     * on payloads that don't carry the minimum required fields so the
     * caller can fall back to the hardcoded default.
     */
    public Brand toBrand() {
        return toBrand(null, null);
    }

    /**
     * Same as {@link #toBrand()} but with the (already-fetched) asset
     * data URIs threaded in. Callers that don't have assets pass
     * {@code null} for both.
     */
    public Brand toBrand(String loginLogoDataUri, String faviconDataUri) {
        if (code == null || code.isEmpty()) return null;
        if (cssVariables == null || cssVariables.isEmpty()) return null;
        return new Brand(code, displayName != null ? displayName : code, cssVariables,
            loginLogoDataUri, faviconDataUri);
    }

    /**
     * Contract-aligned per-asset metadata. Both {@link #loginLogo} and
     * {@link #favicon} are optional — a server that doesn't emit them
     * just leaves the SPI to render text-only / theme-default favicon.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Assets {
        public AssetRef loginLogo;
        public AssetRef favicon;
    }

    /**
     * Reference to a fetchable binary. {@code url} may be absolute or
     * API-relative (resolved against the branding API base URI in
     * {@link BrandingApiClient}). {@code contentType} is informative —
     * the SPI uses the response Content-Type header when constructing
     * the data URI so a misdeclared field can't break rendering.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class AssetRef {
        public String url;
        public String contentType;
        public String etag;
    }
}
