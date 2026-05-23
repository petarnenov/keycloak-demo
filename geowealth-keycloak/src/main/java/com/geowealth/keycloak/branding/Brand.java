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

    /** Email per RFC 5322 lite — enough to keep an `href="mailto:"` honest. */
    private static final Pattern SAFE_EMAIL = Pattern.compile(
        "^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,253}\\.[A-Za-z]{2,24}$"
    );

    /** Allow {@code http://} and {@code https://} URLs only — never javascript:, data:, file:. */
    private static final Pattern SAFE_HTTP_URL = Pattern.compile(
        "^https?://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]{1,2048}$"
    );

    /** Phone display string: digits, spaces, +, -, (, ), . — short. */
    private static final Pattern SAFE_PHONE = Pattern.compile(
        "^[0-9 +().-]{3,32}$"
    );

    /** Plain-text safe pattern: letters, digits, common punctuation, spaces. */
    private static final Pattern SAFE_PLAIN_TEXT = Pattern.compile(
        "^[\\p{L}\\p{N} .,'\\-&()/#:+]{1,512}$"
    );

    private final String code;
    private final String displayName;
    private final String firmShortName;        // nullable
    private final Map<String, String> cssVariables;
    private final String loginLogoDataUri;     // nullable
    private final String loginLogoSmallDataUri;// nullable
    private final String faviconDataUri;       // nullable
    private final String logoIconDataUri;      // nullable
    private final String supportEmail;         // nullable
    private final String phone;                // nullable
    private final String website;              // nullable
    private final String streetAddress1;       // nullable
    private final String streetAddress2;       // nullable
    private final String city;                 // nullable
    private final String state;                // nullable
    private final String zipCode;              // nullable
    private final String country;              // nullable
    private final String footerText;           // nullable

    public Brand(String code, String displayName, Map<String, String> cssVariables) {
        this(code, displayName, cssVariables, null, null, null, null, null);
    }

    public Brand(String code, String displayName, Map<String, String> cssVariables,
                 String loginLogoDataUri) {
        this(code, displayName, cssVariables, loginLogoDataUri, null, null, null, null);
    }

    public Brand(String code, String displayName, Map<String, String> cssVariables,
                 String loginLogoDataUri, String faviconDataUri) {
        this(code, displayName, cssVariables, loginLogoDataUri, faviconDataUri, null, null, null);
    }

    public Brand(String code, String displayName, Map<String, String> cssVariables,
                 String loginLogoDataUri, String faviconDataUri,
                 String supportEmail, String phone, String website) {
        this(code, displayName, null, cssVariables,
            loginLogoDataUri, null, faviconDataUri, null,
            supportEmail, phone, website,
            null, null, null, null, null, null,
            null);
    }

    /**
     * Full-fidelity constructor mirroring every WhitelabelDTO field that
     * affects the login surface. Optional fields default to {@code null}
     * which the FreeMarker template treats as "fall back to the static
     * theme default". Each scalar is run through its allowlist regex; a
     * value that fails validation is dropped + logged at WARN, never
     * threaded into rendered HTML/CSS.
     */
    public Brand(String code, String displayName, String firmShortName,
                 Map<String, String> cssVariables,
                 String loginLogoDataUri, String loginLogoSmallDataUri,
                 String faviconDataUri, String logoIconDataUri,
                 String supportEmail, String phone, String website,
                 String streetAddress1, String streetAddress2,
                 String city, String state, String zipCode, String country,
                 String footerText) {
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("brand code must be non-empty");
        }
        this.code = code;
        this.displayName = displayName == null ? code : displayName;
        this.firmShortName = sanitizeAgainst(code, firmShortName, SAFE_PLAIN_TEXT, "firm-short-name");
        this.cssVariables = Collections.unmodifiableMap(
            filterUnsafe(code, cssVariables == null ? Map.of() : cssVariables));
        this.loginLogoDataUri      = sanitizeImageUri(code, loginLogoDataUri,      "login-logo");
        this.loginLogoSmallDataUri = sanitizeImageUri(code, loginLogoSmallDataUri, "login-logo-small");
        this.faviconDataUri        = sanitizeImageUri(code, faviconDataUri,        "favicon");
        this.logoIconDataUri       = sanitizeImageUri(code, logoIconDataUri,       "logo-icon");
        this.supportEmail   = sanitizeAgainst(code, supportEmail,   SAFE_EMAIL,     "support-email");
        this.phone          = sanitizeAgainst(code, phone,          SAFE_PHONE,    "phone");
        this.website        = sanitizeAgainst(code, website,        SAFE_HTTP_URL,  "website");
        this.streetAddress1 = sanitizeAgainst(code, streetAddress1, SAFE_PLAIN_TEXT,"street1");
        this.streetAddress2 = sanitizeAgainst(code, streetAddress2, SAFE_PLAIN_TEXT,"street2");
        this.city           = sanitizeAgainst(code, city,           SAFE_PLAIN_TEXT,"city");
        this.state          = sanitizeAgainst(code, state,          SAFE_PLAIN_TEXT,"state");
        this.zipCode        = sanitizeAgainst(code, zipCode,        SAFE_PLAIN_TEXT,"zip");
        this.country        = sanitizeAgainst(code, country,        SAFE_PLAIN_TEXT,"country");
        this.footerText     = sanitizeAgainst(code, footerText,     SAFE_PLAIN_TEXT,"footer-text");
    }

    private static String sanitizeAgainst(String code, String value, Pattern pattern, String kind) {
        if (value == null || value.isEmpty()) return null;
        if (pattern.matcher(value).matches()) return value;
        LOG.warnf("Brand[%s]: dropping unsafe %s value (prefix: '%s')",
            code, kind, truncate(value));
        return null;
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
    public String getFirmShortName() { return firmShortName; }
    public Map<String, String> getCssVariables() { return cssVariables; }
    public String getLoginLogoDataUri() { return loginLogoDataUri; }
    public String getLoginLogoSmallDataUri() { return loginLogoSmallDataUri; }
    public String getFaviconDataUri() { return faviconDataUri; }
    public String getLogoIconDataUri() { return logoIconDataUri; }
    public String getSupportEmail() { return supportEmail; }
    public String getPhone() { return phone; }
    public String getWebsite() { return website; }
    public String getStreetAddress1() { return streetAddress1; }
    public String getStreetAddress2() { return streetAddress2; }
    public String getCity() { return city; }
    public String getState() { return state; }
    public String getZipCode() { return zipCode; }
    public String getCountry() { return country; }
    public String getFooterText() { return footerText; }

    /**
     * Single-line composed address suitable for a one-line footer slot.
     * Returns {@code null} if no address parts are present. Components
     * are joined with comma-space; empty parts are skipped.
     */
    public String getAddressLine() {
        StringBuilder sb = new StringBuilder();
        appendPart(sb, streetAddress1);
        appendPart(sb, streetAddress2);
        appendPart(sb, city);
        // State + zip share a slot ("CA 94025") so they read naturally
        if (state != null || zipCode != null) {
            if (sb.length() > 0) sb.append(", ");
            if (state != null) sb.append(state);
            if (state != null && zipCode != null) sb.append(' ');
            if (zipCode != null) sb.append(zipCode);
        }
        appendPart(sb, country);
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void appendPart(StringBuilder sb, String part) {
        if (part == null || part.isBlank()) return;
        if (sb.length() > 0) sb.append(", ");
        sb.append(part);
    }
}
