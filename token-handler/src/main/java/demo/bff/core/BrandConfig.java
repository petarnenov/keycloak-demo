package demo.bff.core;

import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.core.annotation.Nullable;

import java.util.Collections;
import java.util.Map;

/**
 * One whitelabel brand entry — the demo-side analogue of P1's
 * {@code WHITELABEL_TBL} row, but driven entirely from config so the Token
 * Handler can theme SPAs without reaching into P1's Hibernate or its
 * {@code BrandingApiServlet}. Long-term we can swap the lookup to the
 * existing {@code /branding-api/keycloak/whitelabel/{code}} servlet; this
 * config-backed source keeps the MVP self-contained.
 *
 * <p>Bound from {@code app.brands.<wlcode>.*}, e.g.</p>
 * <pre>
 *   app:
 *     brands:
 *       geowealth:
 *         display-name: GeoWealth
 *         logo-url: /brand-assets/geowealth/logo.svg
 *         favicon-url: /brand-assets/geowealth/favicon.ico
 *         css-vars:
 *           "--accent": "#307492"
 *           "--theme-link-color": "#1d6f8a"
 *       cca:
 *         display-name: Creative One
 *         logo-url: /brand-assets/cca/logo.svg
 *         favicon-url: /brand-assets/cca/favicon.ico
 *         css-vars:
 *           "--accent": "#d04a02"
 * </pre>
 *
 * <p>The CSS-variable shape matches what the React SPA writes onto
 * {@code document.documentElement} (see {@code BrandProvider.tsx}) and what
 * P1's {@code _color_theme.json} files already use, so a future migration
 * to streaming P1's payload through {@code BrandingApiServlet} is a drop-in
 * replacement.</p>
 */
@EachProperty("app.brands")
public class BrandConfig {

    private final String wlcode;
    private String displayName;
    private String logoUrl;
    private String faviconUrl;
    private Map<String, String> cssVars = Collections.emptyMap();

    public BrandConfig(@Parameter String wlcode) {
        this.wlcode = wlcode;
    }

    public String getWlcode() { return wlcode; }

    @Nullable public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    @Nullable public String getLogoUrl() { return logoUrl; }
    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }

    @Nullable public String getFaviconUrl() { return faviconUrl; }
    public void setFaviconUrl(String faviconUrl) { this.faviconUrl = faviconUrl; }

    public Map<String, String> getCssVars() { return cssVars; }
    public void setCssVars(Map<String, String> cssVars) {
        if (cssVars == null || cssVars.isEmpty()) {
            this.cssVars = Collections.emptyMap();
            return;
        }
        // Micronaut's property binder eats one leading `-` when flattening YAML
        // map keys (`--accent` becomes `-accent` in the bound Map), so callers
        // who write the natural CSS variable name in config still get a usable
        // key out: any key missing the `--` prefix gets it added back here.
        // Keys already prefixed (e.g. an env-var override that escaped twice)
        // are left untouched.
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>(cssVars.size());
        for (Map.Entry<String, String> e : cssVars.entrySet()) {
            String k = e.getKey();
            if (k == null) continue;
            if (!k.startsWith("--")) {
                k = k.startsWith("-") ? "-" + k : "--" + k;
            }
            out.put(k, e.getValue());
        }
        this.cssVars = java.util.Collections.unmodifiableMap(out);
    }
}
