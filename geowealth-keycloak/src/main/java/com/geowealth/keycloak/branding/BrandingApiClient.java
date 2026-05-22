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
        return getAsJson(uri, BrandDto.class)
            .map(BrandDto::toBrand)
            .filter(b -> b != null);
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
        HttpRequest req = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(3))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + bearerToken)
            .GET()
            .build();
        try {
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                if (LOG.isDebugEnabled()) {
                    LOG.debugf("BrandingApi %s -> HTTP %d", uri, resp.statusCode());
                }
                return Optional.empty();
            }
            return Optional.ofNullable(json.readValue(resp.body(), type));
        } catch (java.net.http.HttpTimeoutException e) {
            LOG.warnf("BrandingApi timeout on %s", uri);
            return Optional.empty();
        } catch (Exception e) {
            // Catch-all is deliberate: any failure must NOT throw past this
            // method. BrandingService handles fallback; logging here is the
            // only signal we get.
            if (LOG.isDebugEnabled()) {
                LOG.debugf(e, "BrandingApi error on %s", uri);
            } else {
                LOG.warnf("BrandingApi error on %s: %s", uri, e.getClass().getSimpleName());
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
