package demo.bff.core;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Thin Java-side client over P1's {@code BrandingApiServlet}
 * ({@code BrandingApiServlet.java:196-402}). Drives:
 * <ul>
 *   <li>{@code GET /branding-api/keycloak/whitelabel/lookup?firmCd={N}}
 *       (added in the P1 servlet alongside the existing host branch) —
 *       resolves a firmCd to a wlcode without the token-handler having to
 *       synthesise a host string;</li>
 *   <li>{@code GET /branding-api/keycloak/whitelabel/{code}} — returns the
 *       brand JSON (displayName, optional firmCd/supportEmail/phone/website,
 *       optional {@code assets.{loginLogo,favicon}.url}, and a
 *       {@code cssVariables} object whose keys start with {@code --}).</li>
 * </ul>
 *
 * <p>Auth is a bearer token configured on P1 as
 * {@code POC_BRANDING_API_TOKEN} and on this service as
 * {@code app.branding.token}. {@link BrandingApiAuthFilter} enforces the
 * filter — missing / mismatched token → 401, rate limit → 429.</p>
 *
 * <p>The client is intentionally not opinionated about the result shape
 * (returns raw {@code Map<String, Object>}); the consumer ({@link BrandResolver})
 * adapts the payload into a {@link BrandConfig} and applies the same
 * CSS-variable key normalisation it does for the inline config path so the
 * SPA contract stays identical regardless of which backing store served the
 * request.</p>
 */
@Singleton
public class BrandingApiClient {

    private static final Logger LOG = LoggerFactory.getLogger(BrandingApiClient.class);

    private final HttpClient http;
    @Nullable private final String token;
    @Nullable private final String basePath;

    public BrandingApiClient(@Client("branding") HttpClient http,
                             @Value("${app.branding.token:}") @Nullable String token,
                             @Value("${app.branding.base-path:/branding-api/keycloak/whitelabel}")
                                 @Nullable String basePath) {
        this.http = http;
        this.token = (token == null || token.isBlank()) ? null : token;
        this.basePath = (basePath == null || basePath.isBlank())
                ? "/branding-api/keycloak/whitelabel"
                : basePath;
    }

    /** True when a bearer token is configured — gates whether the resolver should call P1 at all. */
    public boolean isEnabled() { return token != null; }

    /**
     * {@code GET /lookup?firmCd={N}} → {@code {"code":"..."}}. Returns the
     * resolved wlcode or {@link Optional#empty()} on transport error / 4xx.
     */
    public Optional<String> lookupByFirmCd(int firmCd) {
        if (!isEnabled()) return Optional.empty();
        String path = basePath + "/lookup?firmCd=" + firmCd;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = http.toBlocking().retrieve(
                    HttpRequest.GET(path)
                            .header("Authorization", "Bearer " + token)
                            .accept(MediaType.APPLICATION_JSON),
                    Map.class);
            Object code = body == null ? null : body.get("code");
            return Optional.ofNullable(code).map(Object::toString);
        } catch (HttpClientResponseException e) {
            LOG.warn("Branding API lookup?firmCd={} failed: status={} body={}",
                    firmCd, e.getStatus(), e.getResponse().getBody(String.class).orElse(""));
            return Optional.empty();
        } catch (Throwable t) {
            LOG.warn("Branding API lookup?firmCd=" + firmCd + " transport error", t);
            return Optional.empty();
        }
    }

    /**
     * {@code GET /whitelabel/{code}} → the full brand JSON. Returns an
     * empty map (not {@code null}) on 404 / 4xx / transport failure so the
     * resolver can detect "no upstream data" with a single
     * {@code map.isEmpty()} check.
     */
    public Map<String, Object> getByCode(String code) {
        if (!isEnabled() || code == null || code.isBlank()) return Collections.emptyMap();
        String safe = URLEncoder.encode(code, StandardCharsets.UTF_8);
        String path = basePath + "/" + safe;
        try {
            @SuppressWarnings("unchecked")
            HttpResponse<Map> res = http.toBlocking().exchange(
                    HttpRequest.GET(path)
                            .header("Authorization", "Bearer " + token)
                            .accept(MediaType.APPLICATION_JSON),
                    Map.class);
            if (res.getStatus() == HttpStatus.OK && res.body() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = (Map<String, Object>) res.body();
                return body == null ? Collections.emptyMap() : body;
            }
            return Collections.emptyMap();
        } catch (HttpClientResponseException e) {
            // 404 = unknown wlcode; treat as empty (caller falls back).
            if (e.getStatus() != HttpStatus.NOT_FOUND) {
                LOG.warn("Branding API get {} failed: status={} body={}", code, e.getStatus(),
                        e.getResponse().getBody(String.class).orElse(""));
            }
            return Collections.emptyMap();
        } catch (Throwable t) {
            LOG.warn("Branding API get " + code + " transport error", t);
            return Collections.emptyMap();
        }
    }

    /**
     * Adapt the {@code BrandingApiServlet} JSON payload (see endpoint #3 in the
     * servlet javadoc) into the same {@link BrandConfig} shape the config-backed
     * path produced. Centralised here so the resolver doesn't have to know the
     * upstream key names — only the local interface.
     */
    public static BrandConfig toBrand(String wlcode, Map<String, Object> upstream,
                                      @Nullable String absoluteAssetBase) {
        BrandConfig b = new BrandConfig(wlcode);
        Object displayName = upstream.get("displayName");
        if (displayName != null) b.setDisplayName(displayName.toString());

        // assets.{loginLogo|favicon}.url are SERVER-RELATIVE paths
        // (e.g. /branding-api/keycloak/whitelabel/cca/asset/logo-login). Rewrite
        // them through the SPA-visible proxy on the BFF so the browser doesn't
        // need direct reachability to P1 and the bearer token never leaves the
        // server side.
        Object assetsObj = upstream.get("assets");
        if (assetsObj instanceof Map<?, ?> assets) {
            String loginLogo = assetUrl(assets.get("loginLogo"));
            String favicon = assetUrl(assets.get("favicon"));
            b.setLogoUrl(rewriteAssetUrl(loginLogo, absoluteAssetBase));
            b.setFaviconUrl(rewriteAssetUrl(favicon, absoluteAssetBase));
        }

        Object cssVarsObj = upstream.get("cssVariables");
        if (cssVarsObj instanceof Map<?, ?> cssVars) {
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : cssVars.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                out.put(e.getKey().toString(), e.getValue().toString());
            }
            // The servlet emits keys already prefixed with `--`; setCssVars
            // is idempotent on already-prefixed keys.
            b.setCssVars(out);
        }
        return b;
    }

    @Nullable
    private static String assetUrl(@Nullable Object asset) {
        if (asset instanceof Map<?, ?> m) {
            Object url = m.get("url");
            if (url != null) return url.toString();
        }
        return null;
    }

    /**
     * The SPA fetches asset URLs via a same-origin path served by the
     * Token Handler ({@code /auth/brand/asset/...}); only the resolver knows
     * the canonical P1 path, so rewrite the upstream's
     * {@code /branding-api/...} URL into the BFF-served one. Falls back to
     * the upstream URL when no rewriting base is configured (useful in a
     * dev compose stack where P1 is exposed directly).
     */
    @Nullable
    private static String rewriteAssetUrl(@Nullable String upstreamUrl,
                                          @Nullable String absoluteAssetBase) {
        if (upstreamUrl == null) return null;
        if (absoluteAssetBase == null || absoluteAssetBase.isBlank()) return upstreamUrl;
        return absoluteAssetBase.endsWith("/")
                ? absoluteAssetBase + upstreamUrl.replaceFirst("^/", "")
                : absoluteAssetBase + (upstreamUrl.startsWith("/") ? upstreamUrl : "/" + upstreamUrl);
    }
}
