package com.geowealth.keycloak.theme;

import org.keycloak.models.KeycloakSession;
import org.keycloak.theme.Theme;
import org.keycloak.theme.ThemeSelectorProvider;

/**
 * Always picks the {@code geowealth-wl} theme for LOGIN requests in the
 * {@code geowealth-realm}; defers to the default selector for everything
 * else (admin/account/email/welcome).
 *
 * The per-firm variation does NOT happen here — it happens in the
 * {@link com.geowealth.keycloak.forms.GeoWealthLoginFormsProvider} which
 * injects the {@code brand} attribute into the FreeMarker scope. The
 * ThemeSelectorProvider only decides which theme NAME serves the request;
 * the parametric theme (one name, many brands) is the architectural choice.
 */
public class GeoWealthThemeSelectorProvider implements ThemeSelectorProvider {

    private static final String THEME_NAME = "geowealth-wl";
    private static final String REALM_NAME = "geowealth-realm";

    private final KeycloakSession session;

    public GeoWealthThemeSelectorProvider(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public String getThemeName(Theme.Type type) {
        if (type != Theme.Type.LOGIN) {
            // Returning null tells Keycloak to fall through to its default
            // theme selector, which honors realm/client config.
            return null;
        }
        // Scope the override to the POC realm so the demo-realm and other
        // realms on this Keycloak instance are unaffected.
        if (session.getContext().getRealm() == null
                || !REALM_NAME.equals(session.getContext().getRealm().getName())) {
            return null;
        }
        return THEME_NAME;
    }

    @Override
    public void close() {}
}
