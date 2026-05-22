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
        long started = System.nanoTime();
        Resolved<String> code = resolveCode(host);
        // If the lookup call just failed (host_derived after an API
        // attempt), don't try the brand fetch — we already know the
        // backend is unreachable, so a second 3 s timeout would just
        // double the login latency for no chance of success. Skip
        // straight to the registry fallback.
        boolean skipBrandApi = "host_derived".equals(code.source()) && apiClient != null;
        Resolved<Brand> brand = resolveBrand(code.value(), skipBrandApi);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        // One line per template render with everything an operator needs to
        // grep for: did the cache help, did the API succeed, did we fall
        // back. INFO so it's visible without enabling DEBUG; cheap because
        // a login render is already orders of magnitude more expensive.
        LOG.infof(
            "Branding: host=%s code=%s code_src=%s brand_src=%s latency_ms=%d",
            host == null ? "-" : host,
            brand.value().getCode(),
            code.source(),
            brand.source(),
            elapsedMs
        );
        return brand.value();
    }

    private Resolved<String> resolveCode(String host) {
        if (host == null || host.isEmpty()) {
            return new Resolved<>(BrandRegistry.DEFAULT_CODE, "host_empty");
        }

        // 1) Cache hit.
        Optional<String> cached = hostCache.get(host);
        if (cached.isPresent()) {
            return new Resolved<>(cached.get(), "cache_hit");
        }

        // 2) API lookup, if enabled.
        if (apiClient != null) {
            Optional<String> fromApi = apiClient.lookupHost(host);
            if (fromApi.isPresent()) {
                hostCache.put(host, fromApi.get());
                return new Resolved<>(fromApi.get(), "api_hit");
            }
        }

        // 3) Fallback: derive from hostname locally. Mirrors GeoWealth's
        //    identifyFirmByUrl() rough behavior so the demo keeps working
        //    without the API. Cache the derived value too — short-circuits
        //    subsequent lookups when GeoWealth is intermittently unreachable.
        String derived = BrandRegistry.firmCodeFromHost(host);
        hostCache.put(host, derived);
        return new Resolved<>(derived, "host_derived");
    }

    private Resolved<Brand> resolveBrand(String code, boolean skipApi) {
        if (code == null) code = BrandRegistry.DEFAULT_CODE;

        // 1) Cache hit.
        Optional<Brand> cached = brandCache.get(code);
        if (cached.isPresent()) {
            return new Resolved<>(cached.get(), "cache_hit");
        }

        // 2) API fetch — skipped when the lookup call in the same render
        //    already failed (degraded mode, avoids a redundant 3 s wait).
        if (apiClient != null && !skipApi) {
            Optional<Brand> fromApi = apiClient.fetchBrand(code);
            if (fromApi.isPresent()) {
                brandCache.put(code, fromApi.get());
                return new Resolved<>(fromApi.get(), "api_hit");
            }
        }

        // 3) Hardcoded fallback. BrandRegistry has the demo default plus
        //    ChangePath so the visual continues to differentiate even
        //    without GeoWealth. Cache the fallback too so we don't hammer
        //    the API every request while it's down.
        Brand fb = fallback.lookupByCode(code);
        brandCache.put(code, fb);
        return new Resolved<>(fb, skipApi ? "registry_fallback_skip" : "registry_fallback");
    }

    /**
     * Carries a resolved value alongside the path that produced it so the
     * INFO log can record cache hit vs API hit vs fallback in one shot.
     */
    private record Resolved<T>(T value, String source) {}
}
