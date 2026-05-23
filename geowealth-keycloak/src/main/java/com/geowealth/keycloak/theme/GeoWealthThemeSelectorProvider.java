package com.geowealth.keycloak.theme;

import org.keycloak.models.KeycloakSession;
import org.keycloak.theme.Theme;
import org.keycloak.theme.ThemeSelectorProvider;

/**
 * Always picks the {@code geowealth-wl} theme for LOGIN requests in the
 * {@code geowealth-realm}; for every other realm it returns whatever theme
 * the realm itself declares ({@code realm.getLoginTheme()}) so the rest of
 * the Keycloak instance is unaffected.
 *
 * <p>Important: Keycloak's {@link ThemeSelectorProvider} contract treats
 * a {@code null} return from {@code getThemeName(...)} as "use built-in
 * default theme" rather than "delegate to another selector". The factory
 * ordering on {@code GeoWealthThemeSelectorProviderFactory} (order=100)
 * makes this selector win unconditionally, so returning null here would
 * silently strip every other realm's {@code loginTheme} setting. We
 * read {@code realm.getLoginTheme()} explicitly for the pass-through path
 * to preserve realm-level theme config (e.g. {@code mfe-shell} for the
 * demo-realm).</p>
 *
 * <p>The per-firm variation does NOT happen here — it happens in the
 * {@link com.geowealth.keycloak.forms.GeoWealthLoginFormsProvider} which
 * injects the {@code brand} attribute into the FreeMarker scope. The
 * ThemeSelectorProvider only decides which theme NAME serves the request;
 * the parametric theme (one name, many brands) is the architectural choice.</p>
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
        var realm = session.getContext().getRealm();
        if (type == Theme.Type.LOGIN
                && realm != null
                && REALM_NAME.equals(realm.getName())) {
            return THEME_NAME;
        }
        // Pass-through: honor whatever the realm has configured for this
        // theme type. For LOGIN this is realm.getLoginTheme(); for the
        // other Theme.Type values Keycloak's realm model exposes the
        // matching accessor — we mirror them here so non-opted-in realms
        // keep their declared themes.
        if (realm == null) return null;
        switch (type) {
            case LOGIN:   return realm.getLoginTheme();
            case ACCOUNT: return realm.getAccountTheme();
            case ADMIN:   return realm.getAdminTheme();
            case EMAIL:   return realm.getEmailTheme();
            default:      return null;
        }
    }

    @Override
    public void close() {}
}
