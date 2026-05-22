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
            new Brand("changepath", "ChangePath", changepath));

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
            new Brand(DEFAULT_CODE, "GeoWealth", geowealth));
    }
}
