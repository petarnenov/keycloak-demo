package demo.bff.core;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;

import java.util.HashMap;
import java.util.Map;

/**
 * SPA-facing brand endpoint. Returns the resolved whitelabel for the active
 * session — the demo-side equivalent of P1's
 * {@code GET /whitelabel/<code>/<code>_color_theme.json}, only same-origin
 * (no separate static asset host) and computed per-session rather than
 * per-host.
 *
 * <p>Payload shape:</p>
 * <pre>
 *   {
 *     "wlcode": "cca",
 *     "displayName": "Creative One",
 *     "logoUrl": "/brand-assets/cca/logo.svg",
 *     "faviconUrl": "/brand-assets/cca/favicon.ico",
 *     "cssVars": { "--accent": "#d04a02", ... }
 *   }
 * </pre>
 *
 * <p>The SPA's {@code BrandProvider} reads {@code cssVars} and writes each entry
 * onto {@code document.documentElement.style}, then swaps {@code Layout}'s
 * brand markup to use {@code displayName} + {@code logoUrl}. Matches the
 * pattern of P1's {@code App.updateWhiteLabel()}
 * ({@code App.js:295-417}).</p>
 */
@Controller("/auth/brand")
@ExecuteOn(TaskExecutors.BLOCKING)
public class BrandController {

    private final BrandResolver brands;

    public BrandController(BrandResolver brands) {
        this.brands = brands;
    }

    @Get(produces = MediaType.APPLICATION_JSON)
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<Map<String, Object>> brand(Authentication authentication,
                                                   HttpRequest<?> request) {
        Integer firmCd = parseFirm(authentication.getAttributes().get("firmCd"));
        BrandConfig b = brands.resolve(firmCd, forwardedHost(request));
        return HttpResponse.ok(toPayload(b));
    }

    private static Map<String, Object> toPayload(BrandConfig b) {
        Map<String, Object> out = new HashMap<>();
        out.put("wlcode", b.getWlcode());
        out.put("displayName", b.getDisplayName());
        out.put("logoUrl", b.getLogoUrl());
        out.put("faviconUrl", b.getFaviconUrl());
        out.put("cssVars", b.getCssVars());
        return out;
    }

    @Nullable
    private static String forwardedHost(HttpRequest<?> request) {
        String h = request.getHeaders().get("X-Forwarded-Host");
        return (h != null && !h.isBlank()) ? h : request.getHeaders().get(HttpHeaders.HOST);
    }

    @Nullable
    private static Integer parseFirm(Object firmCd) {
        if (firmCd == null) return null;
        try { return Integer.valueOf(firmCd.toString()); } catch (NumberFormatException e) { return null; }
    }
}
