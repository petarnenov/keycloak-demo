package com.geowealth.keycloak.branding;

import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Immutable per-firm branding payload exposed to FreeMarker templates as
 * <code>${brand}</code>.
 *
 * Phase 2.a fields are deliberately minimal — just what the login template
 * needs to differentiate firms visually. Phase 2.b will widen this to mirror
 * the GeoWealth branding-API JSON shape (assets, locale, support contact).
 *
 * The CSS variables map preserves insertion order so the rendered
 * <code>:root { ... }</code> block reads predictably in DevTools.
 *
 * <p><b>Defense-in-depth filtering.</b> The constructor passes every
 * cssVariables entry through {@link BrandCss#isSafeKey(String)} and
 * {@link BrandCss#isSafe(String)}. Anything that fails either check is
 * dropped from the map and logged at WARN, so a poisoned API row or a
 * mistakenly-broad fallback can't sneak a CSS-context escape into the
 * rendered {@code <style>} block. The template trusts this map; the
 * constructor is the single chokepoint that keeps the trust honest.</p>
 */
public final class Brand {

    private static final Logger LOG = Logger.getLogger(Brand.class);

    /**
     * Tight allowlist for the login-logo data URI. Only inline SVG and PNG/JPEG
     * are accepted; anything else (incl. an attacker-supplied {@code javascript:}
     * URI smuggled into a poisoned DB row) is dropped at construction time.
     * Charset is also restricted so the URI can't terminate the surrounding
     * HTML attribute context — quote, angle bracket, newline are all out.
     */
    private static final Pattern SAFE_LOGO_DATA_URI = Pattern.compile(
        "^data:image/(?:svg\\+xml|png|jpeg);base64,[A-Za-z0-9+/]{1,200000}={0,2}$"
    );

    private final String code;
    private final String displayName;
    private final Map<String, String> cssVariables;
    private final String loginLogoDataUri; // nullable
    private final String faviconDataUri;   // nullable

    public Brand(String code, String displayName, Map<String, String> cssVariables) {
        this(code, displayName, cssVariables, null, null);
    }

    public Brand(String code, String displayName, Map<String, String> cssVariables,
                 String loginLogoDataUri) {
        this(code, displayName, cssVariables, loginLogoDataUri, null);
    }

    public Brand(String code, String displayName, Map<String, String> cssVariables,
                 String loginLogoDataUri, String faviconDataUri) {
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("brand code must be non-empty");
        }
        this.code = code;
        this.displayName = displayName == null ? code : displayName;
        this.cssVariables = Collections.unmodifiableMap(
            filterUnsafe(code, cssVariables == null ? Map.of() : cssVariables));
        this.loginLogoDataUri = sanitizeImageUri(code, loginLogoDataUri, "login-logo");
        this.faviconDataUri = sanitizeImageUri(code, faviconDataUri, "favicon");
    }

    private static String sanitizeImageUri(String code, String value, String kind) {
        if (value == null || value.isEmpty()) return null;
        if (SAFE_LOGO_DATA_URI.matcher(value).matches()) return value;
        // Log a prefix only — payload may be a base64 megablob, and we
        // never want a poisoned value to fill the log file.
        LOG.warnf("Brand[%s]: dropping unsafe %s data URI (prefix: '%s')",
            code, kind, truncate(value));
        return null;
    }

    private static Map<String, String> filterUnsafe(String code, Map<String, String> source) {
        // LinkedHashMap to preserve declaration order for template rendering.
        Map<String, String> safe = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : source.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (!BrandCss.isSafeKey(k)) {
                // Truncate the logged key so a deliberately-huge key can't
                // be used to spam the log file. Same logic for value below.
                LOG.warnf("Brand[%s]: dropping unsafe CSS variable key '%s'",
                    code, truncate(k));
                continue;
            }
            if (!BrandCss.isSafe(v)) {
                LOG.warnf("Brand[%s]: dropping unsafe CSS value for key '%s' (value prefix: '%s')",
                    code, k, truncate(v));
                continue;
            }
            safe.put(k, v);
        }
        return safe;
    }

    private static String truncate(String s) {
        if (s == null) return "<null>";
        return s.length() <= 32 ? s : s.substring(0, 32) + "…";
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }
    public Map<String, String> getCssVariables() { return cssVariables; }
    public String getLoginLogoDataUri() { return loginLogoDataUri; }
    public String getFaviconDataUri() { return faviconDataUri; }
}
