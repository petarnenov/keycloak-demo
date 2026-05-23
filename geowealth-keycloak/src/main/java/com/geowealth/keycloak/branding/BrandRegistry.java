package com.geowealth.keycloak.branding;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 2.a: in-memory brand resolver with two hardcoded firms (ChangePath
 * and the GeoWealth default). Mirrors the GeoWealth dev data so the visual
 * output matches what Phase 2.b will fetch live from
 * <code>/branding-api/keycloak/whitelabel/{code}</code>.
 *
 * Phase 2.b will replace this class with a {@code BrandingApiClient} backed
 * by an in-memory cache. The {@link #lookupByHost(String)} contract stays
 * the same so the {@code GeoWealthLoginFormsProvider} call-site does not
 * change.
 *
 * Lookup rule mirrors GeoWealth's own {@code identifyFirmByUrl()} pattern
 * (per the migration plan §6.2): the first hostname label is the firm code;
 * "localhost" / "127.0.0.1" / unknown → default ("cca").
 */
public final class BrandRegistry {

    /** Identifier of the default firm — matches WebConstants.GEOWEALTH_KEYWORD. */
    public static final String DEFAULT_CODE = "cca";

    private final Map<String, Brand> brandsByCode = new ConcurrentHashMap<>();

    public BrandRegistry() {
        seed();
    }

    public Brand lookupByHost(String host) {
        String code = firmCodeFromHost(host);
        Brand brand = brandsByCode.get(code);
        return brand != null ? brand : Objects.requireNonNull(brandsByCode.get(DEFAULT_CODE));
    }

    public Brand lookupByCode(String code) {
        Brand brand = brandsByCode.get(code);
        return brand != null ? brand : Objects.requireNonNull(brandsByCode.get(DEFAULT_CODE));
    }

    static String firmCodeFromHost(String host) {
        if (host == null || host.isEmpty()) return DEFAULT_CODE;
        String h = host.toLowerCase();
        // Strip port if present.
        int colon = h.indexOf(':');
        if (colon >= 0) h = h.substring(0, colon);
        if (h.equals("localhost") || h.equals("127.0.0.1")) return DEFAULT_CODE;
        int dot = h.indexOf('.');
        if (dot <= 0) return DEFAULT_CODE;
        return h.substring(0, dot);
    }

    /**
     * Feature flag — when {@code KC_SPI_LOGIN_FREEMARKER_GEOWEALTH_REGISTRY_PLACEHOLDERS}
     * is set to {@code "false"} (case-insensitive) the registry stops
     * surfacing the embedded logo/favicon SVG placeholders, falling back
     * to text-only brand rendering. The placeholders are useful in the
     * POC because they keep the visual story coherent when GeoWealth is
     * unreachable; production deployments typically want a stark
     * "something is broken" signal (logo-less login + the amber fallback
     * banner) instead of a misleading "everything is fine" rectangle.
     *
     * <p>Defaults to {@code true} — placeholders on — so existing
     * deployments keep the current behavior until they explicitly opt
     * out. Read once at class load; toggle requires a Keycloak restart.</p>
     */
    static final boolean PLACEHOLDERS_ENABLED = readPlaceholdersEnabled();

    private static boolean readPlaceholdersEnabled() {
        String raw = System.getenv("KC_SPI_LOGIN_FREEMARKER_GEOWEALTH_REGISTRY_PLACEHOLDERS");
        if (raw == null || raw.isBlank()) return true;
        return !raw.trim().equalsIgnoreCase("false");
    }

    // Tiny SVG placeholders so the registry-fallback path still shows
    // *something* on the login page. Pre-base64-encoded at build time;
    // a registry-fallback render and an api-hit render look visually
    // similar (same rectangle + firm name in firm-primary color).
    private static final String LOGO_CHANGEPATH =
        "data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyMjAgNjAiPjxyZWN0IHdpZHRoPSIyMjAiIGhlaWdodD0iNjAiIHJ4PSI2IiBmaWxsPSIjMTU1ZThmIi8+PHRleHQgeD0iMTEwIiB5PSIzOCIgdGV4dC1hbmNob3I9Im1pZGRsZSIgZm9udC1mYW1pbHk9IkFyaWFsLHNhbnMtc2VyaWYiIGZvbnQtc2l6ZT0iMjIiIGZvbnQtd2VpZ2h0PSI3MDAiIGZpbGw9IiNmZmYiPkNoYW5nZVBhdGg8L3RleHQ+PC9zdmc+";
    private static final String LOGO_CCA =
        "data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyMjAgNjAiPjxyZWN0IHdpZHRoPSIyMjAiIGhlaWdodD0iNjAiIHJ4PSI2IiBmaWxsPSIjYzg0ODJhIi8+PHRleHQgeD0iMTEwIiB5PSIzOCIgdGV4dC1hbmNob3I9Im1pZGRsZSIgZm9udC1mYW1pbHk9IkFyaWFsLHNhbnMtc2VyaWYiIGZvbnQtc2l6ZT0iMjIiIGZvbnQtd2VpZ2h0PSI3MDAiIGZpbGw9IiNmZmYiPkdlb1dlYWx0aDwvdGV4dD48L3N2Zz4=";
    private static final String FAVICON_CHANGEPATH =
        "data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAzMiAzMiI+PHJlY3Qgd2lkdGg9IjMyIiBoZWlnaHQ9IjMyIiByeD0iNiIgZmlsbD0iIzE1NWU4ZiIvPjx0ZXh0IHg9IjE2IiB5PSIyMiIgdGV4dC1hbmNob3I9Im1pZGRsZSIgZm9udC1mYW1pbHk9IkFyaWFsLHNhbnMtc2VyaWYiIGZvbnQtc2l6ZT0iMTgiIGZvbnQtd2VpZ2h0PSI3MDAiIGZpbGw9IiNmZmYiPkM8L3RleHQ+PC9zdmc+";
    private static final String FAVICON_CCA =
        "data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAzMiAzMiI+PHJlY3Qgd2lkdGg9IjMyIiBoZWlnaHQ9IjMyIiByeD0iNiIgZmlsbD0iI2M4NDgyYSIvPjx0ZXh0IHg9IjE2IiB5PSIyMiIgdGV4dC1hbmNob3I9Im1pZGRsZSIgZm9udC1mYW1pbHk9IkFyaWFsLHNhbnMtc2VyaWYiIGZvbnQtc2l6ZT0iMTgiIGZvbnQtd2VpZ2h0PSI3MDAiIGZpbGw9IiNmZmYiPkc8L3RleHQ+PC9zdmc+";

    private void seed() {
        // ChangePath — real palette from etc/whitelabel/changepath in the
        // geowealth dev tree. Same values the Phase 1 static CSS hardcoded.
        Map<String, String> changepath = new LinkedHashMap<>();
        changepath.put("--theme-link-color",            "#155e8f");
        changepath.put("--theme-gradient-start",        "#0e4e79");
        changepath.put("--theme-gradient-end",          "#b5dea4");
        changepath.put("--theme-body-background",       "#f5f5f5");
        changepath.put("--theme-button-background",     "#155e8f");
        changepath.put("--pf-v5-global--primary-color--100",    "#155e8f");
        changepath.put("--pf-v5-global--primary-color--200",    "#0e4e79");
        changepath.put("--pf-v5-global--link--Color",           "#155e8f");
        changepath.put("--pf-v5-global--link--Color--hover",    "#0e4e79");
        changepath.put("--pf-v5-global--active-color--100",     "#155e8f");
        brandsByCode.put("changepath",
            new Brand("changepath", "ChangePath", changepath,
                PLACEHOLDERS_ENABLED ? LOGO_CHANGEPATH    : null,
                PLACEHOLDERS_ENABLED ? FAVICON_CHANGEPATH : null,
                "supportemail@changepath.com", "888.798.2360", "http://www.changepath.com/"));

        // GeoWealth default — distinctive orange/teal so the difference vs
        // ChangePath is unmistakable in a side-by-side demo.
        Map<String, String> geowealth = new LinkedHashMap<>();
        geowealth.put("--theme-link-color",             "#c8482a");
        geowealth.put("--theme-gradient-start",         "#1f4d3f");
        geowealth.put("--theme-gradient-end",           "#e6b85c");
        geowealth.put("--theme-body-background",        "#fafaf6");
        geowealth.put("--theme-button-background",      "#c8482a");
        geowealth.put("--pf-v5-global--primary-color--100",     "#c8482a");
        geowealth.put("--pf-v5-global--primary-color--200",     "#a83a20");
        geowealth.put("--pf-v5-global--link--Color",            "#c8482a");
        geowealth.put("--pf-v5-global--link--Color--hover",     "#a83a20");
        geowealth.put("--pf-v5-global--active-color--100",      "#c8482a");
        brandsByCode.put(DEFAULT_CODE,
            new Brand(DEFAULT_CODE, "GeoWealth", geowealth,
                PLACEHOLDERS_ENABLED ? LOGO_CCA    : null,
                PLACEHOLDERS_ENABLED ? FAVICON_CCA : null,
                "support@geowealth.com", null, "https://www.geowealth.com/"));
    }
}
