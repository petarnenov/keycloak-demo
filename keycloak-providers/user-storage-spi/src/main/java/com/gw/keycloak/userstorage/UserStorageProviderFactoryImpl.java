package com.gw.keycloak.userstorage;

import org.jboss.logging.Logger;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;
import org.keycloak.storage.UserStorageProviderFactory;

import java.util.List;

/**
 * Registers the user-service-spi provider. Realm admin sees it under
 * "User Federation → user-service-spi" in the KC admin UI and configures
 * `userServiceUrl` (default http://user-service:8080).
 */
public class UserStorageProviderFactoryImpl implements UserStorageProviderFactory<UserStorageProviderImpl> {

    public static final String PROVIDER_ID = "user-service-spi";

    private static final Logger LOG = Logger.getLogger(UserStorageProviderFactoryImpl.class);

    private static final List<ProviderConfigProperty> CONFIG = ProviderConfigurationBuilder.create()
            .property()
                .name("userServiceUrl")
                .label("user-service base URL")
                .helpText("Base URL of the GeoWealth user-service. Example: http://user-service:8080")
                .type(ProviderConfigProperty.STRING_TYPE)
                .defaultValue("http://user-service:8080")
                .add()
            .build();

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public UserStorageProviderImpl create(KeycloakSession session, ComponentModel model) {
        String url = model.getConfig().getFirst("userServiceUrl");
        if (url == null || url.isBlank()) url = "http://user-service:8080";
        return new UserStorageProviderImpl(session, model, new UserServiceClient(url));
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return CONFIG;
    }

    @Override
    public String getHelpText() {
        return "Federates users from the GeoWealth user-service REST endpoint.";
    }
}
