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
    public String firmShortName;
    public String supportEmail;
    public String phone;
    public String website;
    public String footerText;
    public Address address;
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
        return toBrand(null, null, null, null);
    }

    /**
     * Two-arg legacy entry point that only threads in loginLogo + favicon
     * URIs. Phase 14 adds two more inlined asset slots (loginLogoSmall +
     * logoIcon); this legacy signature delegates with nulls so existing
     * callers (e.g. {@link BrandingApiClient} pre-Phase 14) keep
     * compiling.
     */
    public Brand toBrand(String loginLogoDataUri, String faviconDataUri) {
        return toBrand(loginLogoDataUri, null, faviconDataUri, null);
    }

    /**
     * Four-arg entry point matching the Phase 14 asset set. Callers
     * pass {@code null} for any slot they didn't fetch.
     */
    public Brand toBrand(String loginLogoDataUri, String loginLogoSmallDataUri,
                         String faviconDataUri, String logoIconDataUri) {
        if (code == null || code.isEmpty()) return null;
        if (cssVariables == null || cssVariables.isEmpty()) return null;
        Address a = address == null ? new Address() : address;
        return new Brand(
            code,
            displayName != null ? displayName : code,
            firmShortName,
            cssVariables,
            loginLogoDataUri, loginLogoSmallDataUri,
            faviconDataUri, logoIconDataUri,
            supportEmail, phone, website,
            a.street1, a.street2, a.city, a.state, a.zipCode, a.country,
            footerText
        );
    }

    /**
     * Contract-aligned per-asset metadata. All four entries are optional
     * — a firm without a small logo, favicon, or logo-icon simply leaves
     * the SPI to render text-only / theme-default favicon. {@link #loginLogo}
     * remains the primary asset.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Assets {
        public AssetRef loginLogo;
        public AssetRef loginLogoSmall;
        public AssetRef favicon;
        public AssetRef logoIcon;
    }

    /**
     * Per-firm postal address; emitted by P1's BrandingApiServlet when
     * the WHITELABEL_TBL row has any of street1, street2, city, state,
     * zipCode, country populated. All fields optional. The Brand object
     * exposes both the individual fields and a composed
     * {@code Brand.getAddressLine()} suitable for a one-line footer.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Address {
        public String street1;
        public String street2;
        public String city;
        public String state;
        public String zipCode;
        public String country;
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
