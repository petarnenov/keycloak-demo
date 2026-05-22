package com.geowealth.keycloak.branding;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

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
 */
public final class Brand {

    private final String code;
    private final String displayName;
    private final Map<String, String> cssVariables;

    public Brand(String code, String displayName, Map<String, String> cssVariables) {
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("brand code must be non-empty");
        }
        this.code = code;
        this.displayName = displayName == null ? code : displayName;
        // Defensive copy. LinkedHashMap to preserve declaration order for
        // template rendering.
        this.cssVariables = Collections.unmodifiableMap(
            new LinkedHashMap<>(cssVariables == null ? Map.of() : cssVariables)
        );
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }
    public Map<String, String> getCssVariables() { return cssVariables; }
}
