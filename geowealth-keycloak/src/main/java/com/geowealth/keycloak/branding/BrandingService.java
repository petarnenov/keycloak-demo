package com.geowealth.keycloak.branding;

import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Optional;

/**
 * Facade over the branding sources. Composes:
 *   - BrandingCache (in-memory TTL) — hot path
 *   - BrandingApiClient (HTTP to GeoWealth) — on cache miss
 *   - BrandRegistry (hardcoded fallback) — on any API failure
 *
 * The {@link #lookupByHost(String)} contract is identical to
 * {@link BrandRegistry#lookupByHost(String)} so the LoginFormsProvider
 * call-site is unchanged between Phase 2.a (registry-only) and Phase 2.b
 * (API + cache).
 *
 * Fail-open guarantee: this method never returns null. If everything else
 * fails (no cache, API unreachable, registry empty for the firm), the
 * hardcoded default brand is returned. Login is never blocked by a
 * branding outage.
 *
 * Disabled mode: if {@code apiClient} is null, the facade short-circuits
 * to the registry — Phase 2.a behavior. This lets POC operators run with
 * GeoWealth unreachable indefinitely without configuration changes.
 */
public final class BrandingService {

    private static final Logger LOG = Logger.getLogger(BrandingService.class);

    private final BrandingApiClient apiClient;   // may be null = disabled
    private final BrandingCache<String> hostCache;
    private final BrandingCache<Brand> brandCache;
    private final BrandRegistry fallback;

    public BrandingService(BrandingApiClient apiClient, Duration ttl, BrandRegistry fallback) {
        this.apiClient = apiClient;
        this.hostCache = new BrandingCache<>(ttl);
        this.brandCache = new BrandingCache<>(ttl);
        this.fallback = fallback;
    }

    public Brand lookupByHost(String host) {
        String code = resolveCode(host);
        Brand brand = resolveBrand(code);
        if (LOG.isDebugEnabled()) {
            LOG.debugf("Branding: host=%s -> code=%s -> brand=%s",
                host, code, brand.getCode());
        }
        return brand;
    }

    private String resolveCode(String host) {
        if (host == null || host.isEmpty()) return BrandRegistry.DEFAULT_CODE;

        // 1) Cache hit.
        Optional<String> cached = hostCache.get(host);
        if (cached.isPresent()) return cached.get();

        // 2) API lookup, if enabled.
        if (apiClient != null) {
            Optional<String> fromApi = apiClient.lookupHost(host);
            if (fromApi.isPresent()) {
                hostCache.put(host, fromApi.get());
                return fromApi.get();
            }
        }

        // 3) Fallback: derive from hostname locally. Mirrors GeoWealth's
        //    identifyFirmByUrl() rough behavior so the demo keeps working
        //    without the API.
        String derived = BrandRegistry.firmCodeFromHost(host);
        // Cache the derived value too — short-circuits subsequent lookups
        // when GeoWealth is intermittently unreachable.
        hostCache.put(host, derived);
        return derived;
    }

    private Brand resolveBrand(String code) {
        if (code == null) code = BrandRegistry.DEFAULT_CODE;

        // 1) Cache hit.
        Optional<Brand> cached = brandCache.get(code);
        if (cached.isPresent()) return cached.get();

        // 2) API fetch.
        if (apiClient != null) {
            Optional<Brand> fromApi = apiClient.fetchBrand(code);
            if (fromApi.isPresent()) {
                brandCache.put(code, fromApi.get());
                return fromApi.get();
            }
        }

        // 3) Hardcoded fallback. BrandRegistry has the demo default plus
        //    ChangePath so the visual continues to differentiate even
        //    without GeoWealth.
        Brand fb = fallback.lookupByCode(code);
        // Cache the fallback too so we don't hammer the API every request
        // while it's down.
        brandCache.put(code, fb);
        return fb;
    }
}
