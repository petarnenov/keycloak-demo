package com.geowealth.keycloak.theme;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.theme.ThemeSelectorProvider;
import org.keycloak.theme.ThemeSelectorProviderFactory;

public class GeoWealthThemeSelectorProviderFactory implements ThemeSelectorProviderFactory {

    public static final String ID = "geowealth";

    @Override
    public ThemeSelectorProvider create(KeycloakSession session) {
        return new GeoWealthThemeSelectorProvider(session);
    }

    @Override
    public void init(Config.Scope config) {}

    @Override
    public void postInit(KeycloakSessionFactory factory) {}

    @Override
    public void close() {}

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public int order() {
        // Keycloak's built-in DefaultThemeSelectorProviderFactory orders at 0.
        // We need to win, but only for the geowealth-realm — the
        // provider's null-return for other realms causes Keycloak's default
        // chain to handle them anyway, so this is safe globally.
        return 100;
    }
}
