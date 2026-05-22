package com.geowealth.keycloak.forms;

import com.geowealth.keycloak.branding.BrandRegistry;
import com.geowealth.keycloak.branding.BrandingApiClient;
import com.geowealth.keycloak.branding.BrandingService;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.forms.login.LoginFormsProviderFactory;
import org.keycloak.forms.login.freemarker.FreeMarkerLoginFormsProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

import java.time.Duration;

/**
 * Factory for {@link GeoWealthLoginFormsProvider}.
 *
 * <p>Replaces Keycloak's default factory by returning the same ID
 * ({@code "freemarker"}) at a higher {@link #order()} so the provider
 * manager picks this one.</p>
 *
 * <p>Configuration is read from the SPI scope {@code login-freemarker} at
 * {@link #init(Config.Scope) init} time:</p>
 *
 * <pre>
 *   --spi-login-freemarker-geowealth-branding-api-url=http://host.docker.internal:8080
 *   --spi-login-freemarker-geowealth-branding-api-token=&lt;bearer-token&gt;
 *   --spi-login-freemarker-geowealth-branding-cache-ttl-seconds=60
 * </pre>
 *
 * <p>Or the matching environment variables on the Keycloak container:</p>
 *
 * <pre>
 *   KC_SPI_LOGIN_FREEMARKER_GEOWEALTH_BRANDING_API_URL
 *   KC_SPI_LOGIN_FREEMARKER_GEOWEALTH_BRANDING_API_TOKEN
 *   KC_SPI_LOGIN_FREEMARKER_GEOWEALTH_BRANDING_CACHE_TTL_SECONDS
 * </pre>
 *
 * <p>Both URL and TOKEN must be set for the HTTP client to be wired. If
 * either is missing the {@link BrandingService} falls through directly to
 * the hardcoded {@link BrandRegistry} — Phase 2.a behavior. This keeps the
 * provider usable in environments where GeoWealth is unreachable.</p>
 */
public class GeoWealthLoginFormsProviderFactory implements LoginFormsProviderFactory {

    private static final Logger LOG = Logger.getLogger(GeoWealthLoginFormsProviderFactory.class);

    /** SPI config keys (rendered as KC_SPI_LOGIN_FREEMARKER_<KEY-IN-UPPER-CASE>). */
    private static final String CFG_API_URL    = "geowealth-branding-api-url";
    private static final String CFG_API_TOKEN  = "geowealth-branding-api-token";
    private static final String CFG_CACHE_TTL  = "geowealth-branding-cache-ttl-seconds";
    private static final int    DEFAULT_TTL_S  = 60;

    private BrandingService brandingService;

    @Override
    public LoginFormsProvider create(KeycloakSession session) {
        return new GeoWealthLoginFormsProvider(session, brandingService);
    }

    @Override
    public void init(Config.Scope config) {
        String apiUrl   = config.get(CFG_API_URL);
        String apiToken = config.get(CFG_API_TOKEN);
        int    ttlSecs  = config.getInt(CFG_CACHE_TTL, DEFAULT_TTL_S);

        BrandingApiClient client = null;
        if (apiUrl != null && !apiUrl.isBlank() && apiToken != null && !apiToken.isBlank()) {
            client = new BrandingApiClient(apiUrl, apiToken);
            LOG.infof("GeoWealth branding API enabled: %s (cache TTL %ds)", apiUrl, ttlSecs);
        } else {
            LOG.info("GeoWealth branding API not configured — using hardcoded registry fallback only");
        }

        this.brandingService = new BrandingService(
            client, Duration.ofSeconds(ttlSecs), new BrandRegistry());
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {}

    @Override
    public void close() {}

    @Override
    public String getId() {
        return "freemarker";
    }

    @Override
    public int order() {
        return 100;
    }
}
