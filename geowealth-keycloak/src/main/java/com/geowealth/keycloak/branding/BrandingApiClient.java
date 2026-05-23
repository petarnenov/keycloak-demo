package com.geowealth.keycloak.branding;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
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

    /**
     * Fetch the brand JSON for {@code code}, follow up with asset GETs
     * (login logo, favicon) per the OpenAPI contract, and return the
     * fully-populated {@link Brand}. Returns {@link Optional#empty()} on
     * any failure of the brand JSON call; asset failures are
     * <em>silently absorbed</em> — a missing or unreachable asset
     * leaves the corresponding data URI null, the template renders
     * gracefully without it.
     */
    public Optional<Brand> fetchBrand(String code) {
        if (code == null || code.isEmpty()) return Optional.empty();
        URI uri = baseUri.resolve("/branding-api/keycloak/whitelabel/" + urlEncode(code));
        Optional<BrandDto> dtoOpt = getAsJson(uri, BrandDto.class);
        if (dtoOpt.isEmpty()) return Optional.empty();
        BrandDto dto = dtoOpt.get();

        // Resolve and fetch the optional asset payloads. Each fetch is
        // best-effort and capped at a tight timeout so a slow asset
        // backend doesn't dominate cold-cache login latency. A failing
        // asset is just absent — Brand.java tolerates nulls, and the
        // template falls back to displayName / theme favicon.
        String loginLogoDataUri      = fetchAssetAsDataUri(dto.assets != null ? dto.assets.loginLogo      : null, "login-logo");
        String loginLogoSmallDataUri = fetchAssetAsDataUri(dto.assets != null ? dto.assets.loginLogoSmall : null, "login-logo-small");
        String faviconDataUri        = fetchAssetAsDataUri(dto.assets != null ? dto.assets.favicon        : null, "favicon");
        String logoIconDataUri       = fetchAssetAsDataUri(dto.assets != null ? dto.assets.logoIcon       : null, "logo-icon");

        Brand brand = dto.toBrand(loginLogoDataUri, loginLogoSmallDataUri, faviconDataUri, logoIconDataUri);
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

    /**
     * GET the asset bytes referenced by {@code ref} (an
     * {@link BrandDto.AssetRef} or {@code null}) and return them as a
     * {@code data:<contentType>;base64,<...>} URI. Returns {@code null}
     * on any failure or a missing reference — caller renders without
     * the asset.
     *
     * <p>Url resolution: {@link BrandDto.AssetRef#url} can be absolute
     * (e.g. {@code https://cdn.example.com/logo.svg}) or API-relative
     * (e.g. {@code /branding-api/keycloak/whitelabel/cca/asset/logo-login}).
     * The relative form is resolved against {@link #baseUri}.</p>
     *
     * <p>Latency budget: a 2 s request timeout, half the brand JSON
     * timeout. Two assets * 2 s + the 3 s brand + 3 s lookup keeps a
     * cold-cache login worst-case under 10 s. Warm cache: zero asset
     * fetches (Brand is the cache value).</p>
     */
    private String fetchAssetAsDataUri(BrandDto.AssetRef ref, String kind) {
        if (ref == null || ref.url == null || ref.url.isEmpty()) return null;
        URI uri;
        try {
            URI parsed = new URI(ref.url);
            uri = parsed.isAbsolute() ? parsed : baseUri.resolve(parsed);
        } catch (URISyntaxException e) {
            LOG.warnf("BrandingApi asset[%s]: invalid URL '%s'", kind, truncate(ref.url, 80));
            return null;
        }

        long started = System.nanoTime();
        HttpRequest req = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(2))
            .header("Authorization", "Bearer " + bearerToken)
            .GET()
            .build();
        try {
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            long latencyMs = (System.nanoTime() - started) / 1_000_000L;
            if (resp.statusCode() != 200) {
                LOG.warnf("BrandingApi asset[%s] %s -> HTTP %d after %d ms",
                    kind, uri, resp.statusCode(), latencyMs);
                return null;
            }
            byte[] body = resp.body();
            if (body == null || body.length == 0) {
                LOG.warnf("BrandingApi asset[%s] %s -> empty body after %d ms",
                    kind, uri, latencyMs);
                return null;
            }
            // Prefer the response Content-Type header — server is the
            // authority on the bytes it just streamed. Fall back to the
            // ref's declared contentType (informative), then a safe
            // default. Brand.java's allowlist will drop anything that
            // doesn't shape up to image/{svg+xml,png,jpeg}.
            String contentType = resp.headers().firstValue("Content-Type")
                .filter(s -> !s.isBlank())
                .orElse(ref.contentType != null ? ref.contentType : "application/octet-stream");
            // Strip a charset parameter if present (e.g. "image/svg+xml; charset=utf-8")
            int semi = contentType.indexOf(';');
            if (semi >= 0) contentType = contentType.substring(0, semi).trim();
            String b64 = Base64.getEncoder().encodeToString(body);
            return "data:" + contentType + ";base64," + b64;
        } catch (java.net.http.HttpTimeoutException e) {
            long latencyMs = (System.nanoTime() - started) / 1_000_000L;
            LOG.warnf("BrandingApi asset[%s] timeout on %s after %d ms (limit 2000 ms)",
                kind, uri, latencyMs);
            return null;
        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - started) / 1_000_000L;
            if (LOG.isDebugEnabled()) {
                LOG.debugf(e, "BrandingApi asset[%s] error on %s after %d ms", kind, uri, latencyMs);
            } else {
                LOG.warnf("BrandingApi asset[%s] error on %s after %d ms: %s",
                    kind, uri, latencyMs, e.getClass().getSimpleName());
            }
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "<null>";
        return s.length() <= max ? s : s.substring(0, max) + "…";
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
