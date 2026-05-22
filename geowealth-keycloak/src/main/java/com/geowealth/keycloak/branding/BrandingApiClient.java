package com.geowealth.keycloak.branding;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * HTTP client to the GeoWealth branding API.
 *
 * Endpoints consumed (per geowealth-keycloak-whitelabel-sync.md §4.1):
 *   GET {base}/branding-api/keycloak/whitelabel/{code}
 *   GET {base}/branding-api/keycloak/whitelabel/lookup?host={host}
 *
 * Auth: static bearer token, configured via SPI scope at provider init time
 * (see {@code GeoWealthLoginFormsProviderFactory.init}). Constant-time
 * comparison happens on the server side; the client just attaches the
 * header.
 *
 * Failure handling: every method returns {@code Optional} — never throws —
 * so the calling {@link BrandingService} can keep login flowing through
 * the fail-open fallback chain on network errors, 5xx responses, or
 * malformed JSON.
 *
 * Timeouts: aggressive (connect 2 s, request 3 s) to keep the login
 * latency bounded when GeoWealth is slow or down. A cold-cache login hits
 * two of these (lookup + fetch) so the worst-case adds ≤ 6 s — and only
 * to the very first per-host login per cache TTL.
 */
public final class BrandingApiClient {

    private static final Logger LOG = Logger.getLogger(BrandingApiClient.class);

    private final URI baseUri;
    private final String bearerToken;
    private final HttpClient http;
    private final ObjectMapper json;

    public BrandingApiClient(String baseUrl, String bearerToken) {
        this.baseUri = URI.create(baseUrl);
        this.bearerToken = bearerToken;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        this.json = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** GET /whitelabel/{code} — returns the parsed Brand or empty on any failure. */
    public Optional<Brand> fetchBrand(String code) {
        if (code == null || code.isEmpty()) return Optional.empty();
        URI uri = baseUri.resolve("/branding-api/keycloak/whitelabel/" + urlEncode(code));
        Optional<BrandDto> dto = getAsJson(uri, BrandDto.class);
        if (dto.isEmpty()) return Optional.empty();
        Brand brand = dto.get().toBrand();
        if (brand == null) {
            // 200 OK but the payload is missing fields the Keycloak side
            // can't render with. That's a contract violation — distinct
            // from a transport failure or a parse failure — and an
            // operator looking at the logs deserves to know.
            LOG.errorf(
                "BrandingApi contract violation on %s: response was valid JSON but missing required fields (code/cssVariables); falling back",
                uri
            );
            return Optional.empty();
        }
        return Optional.of(brand);
    }

    /** GET /whitelabel/lookup?host={host} — returns firm code, empty on failure. */
    public Optional<String> lookupHost(String host) {
        if (host == null || host.isEmpty()) return Optional.empty();
        URI uri = baseUri.resolve(
            "/branding-api/keycloak/whitelabel/lookup?host=" + urlEncode(host));
        return getAsJson(uri, LookupResponse.class)
            .map(r -> r != null && r.code != null && !r.code.isEmpty() ? r.code : null);
    }

    private <T> Optional<T> getAsJson(URI uri, Class<T> type) {
        long started = System.nanoTime();
        HttpRequest req = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(3))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + bearerToken)
            .GET()
            .build();
        try {
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            long latencyMs = (System.nanoTime() - started) / 1_000_000L;
            if (resp.statusCode() != 200) {
                LOG.warnf("BrandingApi %s -> HTTP %d after %d ms", uri, resp.statusCode(), latencyMs);
                return Optional.empty();
            }
            try {
                return Optional.ofNullable(json.readValue(resp.body(), type));
            } catch (com.fasterxml.jackson.core.JsonProcessingException jpe) {
                // Server returned 200 with a body Jackson can't parse —
                // contract violation, not a transport flap. Bump to ERROR
                // so it's separable in the log from intermittent 5xx /
                // timeouts that don't need a human's attention.
                long parseLatencyMs = (System.nanoTime() - started) / 1_000_000L;
                LOG.errorf(
                    "BrandingApi JSON parse failure on %s after %d ms: %s",
                    uri, parseLatencyMs, jpe.getOriginalMessage()
                );
                return Optional.empty();
            }
        } catch (java.net.http.HttpTimeoutException e) {
            long latencyMs = (System.nanoTime() - started) / 1_000_000L;
            LOG.warnf("BrandingApi timeout on %s after %d ms (limit 3000 ms)", uri, latencyMs);
            return Optional.empty();
        } catch (Exception e) {
            // Catch-all is deliberate: any failure must NOT throw past this
            // method. BrandingService handles fallback; logging here is the
            // only signal we get. Transport errors stay at WARN — flapping
            // is expected in real ops.
            long latencyMs = (System.nanoTime() - started) / 1_000_000L;
            if (LOG.isDebugEnabled()) {
                LOG.debugf(e, "BrandingApi error on %s after %d ms", uri, latencyMs);
            } else {
                LOG.warnf("BrandingApi error on %s after %d ms: %s",
                    uri, latencyMs, e.getClass().getSimpleName());
            }
            return Optional.empty();
        }
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Inner class for the /lookup endpoint's tiny JSON response. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static final class LookupResponse {
        public String code;
    }
}
