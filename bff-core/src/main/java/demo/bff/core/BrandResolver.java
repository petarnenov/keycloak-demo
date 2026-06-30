package demo.bff.core;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a {@code wlcode} + brand for the active session. The class is the
 * SPA-facing contract for theming; the SPA only ever talks to
 * {@link BrandController}, which calls {@link #resolve(Integer, String)} —
 * the body of {@code resolve} can be swapped between data sources without
 * changing anything visible to the browser.
 *
 * <h3>Data sources, in priority order</h3>
 * <ol>
 *   <li><b>P1's {@code BrandingApiServlet}</b> via {@link BrandingApiClient}
 *       — the source of truth in production. Two calls:
 *       <ul>
 *         <li>{@code lookup?firmCd=N} → {@code wlcode} (single call from a
 *             KC firmCd claim, no host synthesis required);</li>
 *         <li>{@code /whitelabel/{code}} → the full brand JSON (displayName,
 *             logo/favicon URLs, css variables).</li>
 *       </ul>
 *       Enabled when {@code app.branding.token} is set. Results cached
 *       in-process for {@code app.branding.cache-ttl-millis} (default 60 s)
 *       so a steady-state browser session doesn't hit P1 on every
 *       {@code /auth/me} or {@code /auth/brand}.</li>
 *   <li><b>Inline config</b> ({@code app.brands.*} + {@code app.firm-to-wlcode.*})
 *       — used when the Branding API is disabled, or as the fallback when
 *       the upstream lookup misses / errors. Keeps the local-dev compose
 *       and the unit-test path self-contained.</li>
 *   <li><b>Default brand</b> ({@code app.default-wlcode}, default
 *       {@code geowealth}) — last-resort, mirrors P1's
 *       {@code WhiteLabeler.getLabelFor} default behaviour
 *       ({@code WhiteLabeler.java:55-58}).</li>
 * </ol>
 *
 * <p>Cache + fallback live here, not in {@link BrandingApiClient}, so the
 * client stays a thin HTTP wrapper and a future Redis-backed cache lifts
 * cleanly out.</p>
 */
@Singleton
public class BrandResolver {

    private static final Logger LOG = LoggerFactory.getLogger(BrandResolver.class);
    private static final long DEFAULT_CACHE_TTL_MILLIS = 60_000L;

    private final Map<String, BrandConfig> byWlcode;
    private final Map<Integer, String> firmToWlcode;
    private final String defaultWlcode;
    private final BrandingApiClient client;
    @Nullable private final String assetBase;
    private final long cacheTtlMillis;

    private final Map<Integer, CachedString> wlcodeByFirmCache = new ConcurrentHashMap<>();
    private final Map<String, CachedBrand> brandByWlcodeCache = new ConcurrentHashMap<>();

    public BrandResolver(List<BrandConfig> brands,
                         BrandRouting routing,
                         BrandingApiClient client) {
        Map<String, BrandConfig> m = new HashMap<>();
        for (BrandConfig b : brands) {
            if (b.getWlcode() != null) {
                m.put(b.getWlcode().toLowerCase(), b);
            }
        }
        this.byWlcode = Map.copyOf(m);
        this.defaultWlcode = routing.getDefaultWlcode().toLowerCase();
        this.firmToWlcode = parseFirmMap(routing.getFirmToWlcode());
        this.client = client;
        this.assetBase = routing.getAssetBase();
        this.cacheTtlMillis = routing.getCacheTtlMillis() > 0
                ? routing.getCacheTtlMillis()
                : DEFAULT_CACHE_TTL_MILLIS;
        LOG.info("BrandResolver ready: brandingApi={}, defaultWlcode={}, configBrands={}",
                client.isEnabled() ? "enabled" : "disabled (config-only)",
                defaultWlcode, byWlcode.keySet());
    }

    /** Resolve to a concrete brand, falling back to the configured default. Never {@code null}. */
    public BrandConfig resolve(@Nullable Integer firmCd, @Nullable String host) {
        String wl = resolveWlcode(firmCd, host);
        BrandConfig b = brandFor(wl);
        if (b != null) return b;
        BrandConfig fb = brandFor(defaultWlcode);
        if (fb != null) return fb;
        BrandConfig empty = new BrandConfig(defaultWlcode);
        empty.setDisplayName("GeoWealth");
        return empty;
    }

    /** Resolve just the wlcode string. Always returns a non-null lower-case slug. */
    public String resolveWlcode(@Nullable Integer firmCd, @Nullable String host) {
        // 1. Try the upstream Branding API. The lookup is cached so the
        //    steady-state /auth/me path doesn't trip a network round-trip.
        if (firmCd != null && client.isEnabled()) {
            String fromApi = cachedWlcodeByFirm(firmCd);
            if (fromApi != null && !fromApi.isBlank()) return fromApi.toLowerCase();
        }
        // 2. Inline firmCd → wlcode config (covers offline dev + unit tests).
        if (firmCd != null) {
            String wl = firmToWlcode.get(firmCd);
            if (wl != null) return wl;
        }
        return defaultWlcode;
    }

    /** Direct lookup by wlcode (case-insensitive); {@code empty} when unknown to either source. */
    public Optional<BrandConfig> byWlcode(@Nullable String wlcode) {
        if (wlcode == null) return Optional.empty();
        return Optional.ofNullable(brandFor(wlcode.toLowerCase()));
    }

    @Nullable
    private BrandConfig brandFor(String wlcode) {
        // Upstream first, fall back to inline config when API disabled / 404.
        if (client.isEnabled()) {
            BrandConfig fromApi = cachedBrandByWlcode(wlcode);
            if (fromApi != null) return fromApi;
        }
        return byWlcode.get(wlcode);
    }

    @Nullable
    private String cachedWlcodeByFirm(int firmCd) {
        long now = System.currentTimeMillis();
        CachedString hit = wlcodeByFirmCache.get(firmCd);
        if (hit != null && hit.expiresAt > now) return hit.value;
        String fresh = client.lookupByFirmCd(firmCd).orElse(null);
        wlcodeByFirmCache.put(firmCd, new CachedString(fresh, now + cacheTtlMillis));
        return fresh;
    }

    @Nullable
    private BrandConfig cachedBrandByWlcode(String wlcode) {
        long now = System.currentTimeMillis();
        CachedBrand hit = brandByWlcodeCache.get(wlcode);
        if (hit != null && hit.expiresAt > now) return hit.value;
        Map<String, Object> upstream = client.getByCode(wlcode);
        BrandConfig fresh = upstream.isEmpty() ? null
                : BrandingApiClient.toBrand(wlcode, upstream, assetBase);
        brandByWlcodeCache.put(wlcode, new CachedBrand(fresh, now + cacheTtlMillis));
        return fresh;
    }

    private static Map<Integer, String> parseFirmMap(@Nullable Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<Integer, String> out = new HashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            try {
                out.put(Integer.valueOf(e.getKey()), e.getValue().toLowerCase());
            } catch (NumberFormatException ignored) {
                // skip non-numeric firmCd keys; config typo, don't blow up boot
            }
        }
        return Map.copyOf(out);
    }

    private record CachedString(@Nullable String value, long expiresAt) {}
    private record CachedBrand(@Nullable BrandConfig value, long expiresAt) {}
}
