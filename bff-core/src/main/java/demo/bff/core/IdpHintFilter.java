package demo.bff.core;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.http.filter.ServerFilterPhase;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

/**
 * Forces a fixed {@code kc_idp_hint} onto the Keycloak authorize URL the BFF
 * builds when a SPA starts login via {@code /oauth/login/keycloak}.
 *
 * <p>This demo realm has no native users — every login MUST be brokered through
 * the {@code p1} SAML IdP. Without this hint Keycloak shows its own login screen
 * with a "Sign in with P1" button, which is dead-end UX. Pre-BFF, the SPA called
 * {@code keycloak.login({ idpHint: 'p1' })} via keycloak-js and the hint was
 * supplied there; in the BFF / Token Handler model the BFF builds the authorize
 * URL itself, so the hint has to be added on the BFF side.</p>
 *
 * <p>The filter is a thin Location-rewriter: micronaut-security returns
 * {@code 302 Location: https://auth.geowealth.int:.../auth?...} and we append
 * {@code &kc_idp_hint=p1}. Idempotent — if the hint is already present (e.g.
 * because P1's own sidebar built the URL with it) we leave the response
 * untouched.</p>
 */
@Filter("/oauth/login/keycloak")
public class IdpHintFilter implements HttpServerFilter {

    private final String idpHint;

    public IdpHintFilter(@Value("${app.kc-idp-hint:p1}") String idpHint) {
        this.idpHint = idpHint;
    }

    @Override
    public int getOrder() {
        // Run after micronaut-security has built the redirect to KC.
        return ServerFilterPhase.LAST.order();
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        // Capture whether this login attempt is the silent-first variant —
        // SilentLoginController sets ?silent=1 on the request before
        // bouncing it through /oauth/login/keycloak. Read it in request scope
        // so we can apply it when rewriting the response Location.
        boolean silent = request.getParameters()
                .get("silent", String.class)
                .map(v -> v.equals("1") || v.equalsIgnoreCase("true"))
                .orElse(false);
        return Flux.from(chain.proceed(request)).map(response -> {
            int code = response.getStatus().getCode();
            if (code < 300 || code >= 400) {
                return response;
            }
            String location = response.getHeaders().get(HttpHeaders.LOCATION);
            if (location == null) {
                return response;
            }
            if (idpHint != null && !idpHint.isBlank() && !location.contains("kc_idp_hint=")) {
                location = appendParam(location, "kc_idp_hint", idpHint);
            }
            // Silent-first variant: tell Keycloak "do not interact with the
            // user". If the realm SSO session is alive KC emits a code; if
            // not, KC redirects back with error=login_required, which is
            // routed through redirect.login-failure → /auth/login-failed and
            // upgraded to an interactive login. Redirect-based (not iframe)
            // by design — see cross-subdomain-sso-implementation.md § 7.
            if (silent && !location.contains("prompt=")) {
                location = appendParam(location, "prompt", "none");
            }
            response.getHeaders().set(HttpHeaders.LOCATION, location);
            return response;
        });
    }

    private static String appendParam(String url, String key, String value) {
        String sep = url.indexOf('?') >= 0 ? "&" : "?";
        return url + sep + key + "=" + value;
    }
}
