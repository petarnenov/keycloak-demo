package com.geowealth.keycloak.branding;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tiny TTL cache for Brand and host→code lookups.
 *
 * Deliberately no Caffeine dependency for Phase 2.b — Caffeine is ~700 KB
 * of jar weight and adds a non-trivial relocation surface. The POC needs
 * at most ~89 entries (one per firm); a plain ConcurrentHashMap with
 * Instant-based expiry is fast enough and ships with the JDK.
 *
 * Semantics:
 *   - Hard TTL: expireAfterWrite. After the deadline, get() returns empty
 *     and the entry is dropped on next access.
 *   - No background refresh in this implementation; callers (BrandingService)
 *     run inside the request thread on a miss. Acceptable because login
 *     latency dominates the lookup, and login itself is bounded by
 *     BrandingApiClient's 3 s request timeout.
 *
 * Concurrency: ConcurrentHashMap. Reads are lock-free; writes coalesce
 * via {@code compute}/{@code put}. Two requests for the same key on a cold
 * cache may both fetch — that's a (rare) double-call to GeoWealth, not a
 * correctness problem.
 */
public final class BrandingCache<V> {

    private static final class Entry<V> {
        final V value;
        final Instant deadline;
        Entry(V value, Instant deadline) {
            this.value = value;
            this.deadline = deadline;
        }
    }

    private final ConcurrentHashMap<String, Entry<V>> map = new ConcurrentHashMap<>();
    private final Duration ttl;

    public BrandingCache(Duration ttl) {
        this.ttl = ttl;
    }

    public Optional<V> get(String key) {
        Entry<V> e = map.get(key);
        if (e == null) return Optional.empty();
        if (Instant.now().isAfter(e.deadline)) {
            // Drop expired entry so the map stays bounded. Doing it here
            // (lazy eviction) is simpler than a background sweeper.
            map.remove(key, e);
            return Optional.empty();
        }
        return Optional.of(e.value);
    }

    public void put(String key, V value) {
        if (key == null || value == null) return;
        map.put(key, new Entry<>(value, Instant.now().plus(ttl)));
    }

    public void invalidate(String key) {
        map.remove(key);
    }

    /** Drop every entry. Used by the admin cache-invalidation endpoint. */
    public void clear() {
        map.clear();
    }

    /**
     * Drop every entry whose cached value equals {@code value}. Used to
     * keep host→code resolutions consistent after a brand row change:
     * if {@code BrandingService.invalidate("c1wealth")} fires, every
     * cached {@code host → "c1wealth"} mapping should also drop so the
     * next request re-asks the API which host owns the code.
     */
    public void removeWhereValueEquals(V value) {
        if (value == null) return;
        map.entrySet().removeIf(e -> value.equals(e.getValue().value));
    }

    public int size() {
        return map.size();
    }
}
