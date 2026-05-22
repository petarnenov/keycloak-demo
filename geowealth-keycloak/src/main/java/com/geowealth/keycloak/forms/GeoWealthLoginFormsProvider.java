package com.geowealth.keycloak.forms;

import com.geowealth.keycloak.branding.Brand;
import com.geowealth.keycloak.branding.BrandingService;
import com.geowealth.keycloak.branding.BrandingService.BrandResolution;

import org.jboss.logging.Logger;
import org.keycloak.forms.login.freemarker.FreeMarkerLoginFormsProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.theme.Theme;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

import java.util.Locale;

/**
 * Drop-in replacement for Keycloak's default {@link FreeMarkerLoginFormsProvider}
 * that, just before any login template renders, resolves the firm from the
 * incoming request's {@code Host} header (or {@code X-Forwarded-Host} if
 * present) and exposes the brand as the {@code ${brand}} FreeMarker variable.
 *
 * <p>Resolution flows through {@link BrandingService}: cache → GeoWealth API
 * → hardcoded fallback. The provider never blocks login on a branding
 * outage — the service guarantees a non-null Brand return.</p>
 *
 * <p>Override hook: {@link #processTemplate(Theme, String, Locale)} is the
 * single funnel through which every {@code createXxx(...)} method in the
 * parent renders. Mutating the {@code attributes} map (protected on the
 * parent) lets the parent class do all its resource/message work
 * unchanged.</p>
 *
 * <p>Scope: only injects {@code brand} when the current realm is the POC
 * realm ({@code geowealth-realm}). Any other realm sees parent behavior
 * verbatim, so this provider is safe to ship alongside the existing
 * demo-realm.</p>
 */
public class GeoWealthLoginFormsProvider extends FreeMarkerLoginFormsProvider {

    private static final Logger LOG = Logger.getLogger(GeoWealthLoginFormsProvider.class);

    /**
     * Realm attribute name that opts a realm into GeoWealth white-labeling.
     * The POC seed (keycloak/geowealth-realm-export.json) sets this on
     * {@code geowealth-realm} so the provider lights up there; any realm
     * without the attribute renders with the upstream FreeMarkerLoginFormsProvider
     * verbatim. Attribute-based gating is rename-safe — a realm with a
     * different name still picks up branding as long as the attribute
     * sticks.
     *
     * <p>For backwards compatibility, also matches the legacy
     * {@code "geowealth-realm"} name so a realm export that predates the
     * attribute keeps working.</p>
     */
    private static final String REALM_ATTR_OPT_IN = "geowealthBrandingProvider";

    /** Legacy fallback — realm name match for installs that haven't yet set the realm attribute. */
    private static final String LEGACY_REALM_NAME = "geowealth-realm";

    private static final String ATTR_NAME = "brand";
    private static final String ATTR_FALLBACK = "brandFallback";

    private final BrandingService brandingService;

    public GeoWealthLoginFormsProvider(KeycloakSession session, BrandingService brandingService) {
        super(session);
        this.brandingService = brandingService;
    }

    @Override
    protected Response processTemplate(Theme theme, String templateName, Locale locale) {
        if (realm != null && isOptedIn(realm)) {
            BrandResolution resolved = brandingService.lookupByHostResolved(currentRequestHost());
            Brand brand = resolved.brand();
            setAttribute(ATTR_NAME, brand);
            setAttribute(ATTR_FALLBACK, resolved.fallback());
            if (LOG.isDebugEnabled()) {
                LOG.debugf("Resolved brand for %s template: code=%s fallback=%s",
                    templateName, brand.getCode(), resolved.fallback());
            }
        }
        return super.processTemplate(theme, templateName, locale);
    }

    /**
     * True when the realm has opted into the GeoWealth branding provider.
     * Checks for the {@link #REALM_ATTR_OPT_IN} realm attribute first
     * (production-correct mechanism — rename-safe, environment-portable);
     * falls through to the legacy realm-name string match so an existing
     * deployment that hasn't been re-imported still works during the
     * migration window.
     */
    private static boolean isOptedIn(org.keycloak.models.RealmModel realm) {
        String attr = realm.getAttribute(REALM_ATTR_OPT_IN);
        if (attr != null && Boolean.parseBoolean(attr.trim())) return true;
        return LEGACY_REALM_NAME.equals(realm.getName());
    }

    /**
     * Prefer X-Forwarded-Host when behind a trusted reverse proxy
     * (production architecture; Keycloak set to --proxy-headers=xforwarded).
     * Fall back to Host, then the URI authority. Never throws — any
     * unexpected request-context state defaults to the realm fallback in
     * the BrandingService.
     */
    private String currentRequestHost() {
        try {
            HttpHeaders headers = session.getContext().getHttpRequest().getHttpHeaders();
            String xfh = headers.getHeaderString("X-Forwarded-Host");
            if (xfh != null && !xfh.isBlank()) {
                int comma = xfh.indexOf(',');
                return (comma >= 0 ? xfh.substring(0, comma) : xfh).trim();
            }
            String host = headers.getHeaderString(HttpHeaders.HOST);
            if (host != null && !host.isBlank()) {
                return host.trim();
            }
        } catch (Exception ignored) {
            // Defensive: ensure no headers-related issue breaks login.
        }
        return session.getContext().getUri().getRequestUri().getAuthority();
    }
}
