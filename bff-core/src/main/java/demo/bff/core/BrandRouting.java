package demo.bff.core;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.util.Collections;
import java.util.Map;

/**
 * Two flat, optional config knobs the {@link BrandResolver} needs:
 * <ul>
 *   <li>{@code app.firm-to-wlcode.<firmCd>: <wlcode>} — the demo-side mirror of
 *       {@code WHITELABEL_TBL.firmCd}, used to pick a brand from the logged-in
 *       user's firm claim.</li>
 *   <li>{@code app.default-wlcode: <wlcode>} — the brand the resolver falls
 *       back to when no firm mapping matches and no configured brand-by-host
 *       hint applies.</li>
 * </ul>
 *
 * <p>Lifted out of the resolver into a dedicated {@code @ConfigurationProperties}
 * bean so Micronaut's binder can handle the optional map cleanly — an inline
 * {@code @Value("${app.firm-to-wlcode:}") Map<String,String>} can't safely
 * default to "empty" when the key is absent.</p>
 */
@ConfigurationProperties("app")
public class BrandRouting {

    private Map<String, String> firmToWlcode = Collections.emptyMap();
    private String defaultWlcode = "geowealth";
    private String assetBase = "";
    private long cacheTtlMillis = 60_000L;

    public Map<String, String> getFirmToWlcode() { return firmToWlcode; }
    public void setFirmToWlcode(Map<String, String> firmToWlcode) {
        this.firmToWlcode = firmToWlcode == null ? Collections.emptyMap() : firmToWlcode;
    }

    public String getDefaultWlcode() { return defaultWlcode; }
    public void setDefaultWlcode(String defaultWlcode) {
        this.defaultWlcode = defaultWlcode == null ? "geowealth" : defaultWlcode;
    }

    /**
     * Optional base URL the resolver substitutes in front of server-relative
     * asset URLs returned by P1's BrandingApiServlet (which emits paths like
     * {@code /branding-api/keycloak/whitelabel/cca/asset/logo-login}). Set to
     * the SPA-visible Token Handler base (e.g. {@code /auth/brand/upstream})
     * to keep asset traffic same-origin and the bearer token on the server.
     * Blank → asset URLs are returned to the SPA verbatim from upstream.
     */
    public String getAssetBase() { return assetBase; }
    public void setAssetBase(String assetBase) {
        this.assetBase = assetBase == null ? "" : assetBase;
    }

    /**
     * TTL for the in-process wlcode and brand caches. Default 60 s — keeps
     * the steady-state {@code /auth/me} path off the wire while still picking
     * up an admin-edited whitelabel row within a minute.
     */
    public long getCacheTtlMillis() { return cacheTtlMillis; }
    public void setCacheTtlMillis(long cacheTtlMillis) {
        this.cacheTtlMillis = cacheTtlMillis;
    }
}
