package com.geowealth.keycloak.branding;

/**
 * Tiny helper to validate CSS variable values before they cross the trust
 * boundary into a {@code <style>} block in the rendered HTML.
 *
 * Defense in depth: Phase 2.b's GeoWealth API also validates at the source.
 * Re-validating here protects against:
 *   - Cache corruption / future code paths that bypass the API.
 *   - Test fixtures or hardcoded values that drift.
 *   - A future contributor casually weakening server-side validation.
 *
 * Accepts: CSS hex colors (#rgb/#rrggbb/#rrggbbaa), short keyword set used
 * by the brand palettes today, and the {@code linear-gradient(...)} shape
 * if we ever inline a gradient value. Anything else → reject.
 */
public final class BrandCss {

    private BrandCss() {}

    private static final java.util.regex.Pattern SAFE_VALUE =
        java.util.regex.Pattern.compile(
            "^(?:#[0-9a-fA-F]{3,8}" +
            "|transparent|inherit|initial|unset|none|currentColor" +
            ")$"
        );

    /**
     * CSS custom property names must start with {@code --} per the spec,
     * and the ident portion is restricted to letters, digits, underscore,
     * and hyphen. Keeping the cap tight (64 chars) defends against a
     * pathologically long name silently embedding a CSS escape sequence
     * via length alone.
     */
    private static final java.util.regex.Pattern SAFE_KEY =
        java.util.regex.Pattern.compile("^--[a-zA-Z0-9_-]{1,64}$");

    public static boolean isSafe(String value) {
        if (value == null || value.length() > 64) return false;
        return SAFE_VALUE.matcher(value).matches();
    }

    public static boolean isSafeKey(String key) {
        if (key == null) return false;
        return SAFE_KEY.matcher(key).matches();
    }

    public static String escapeOrEmpty(String value) {
        return isSafe(value) ? value : "";
    }
}
