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
 * Rewrites the Keycloak authorize URL the BFF builds when a SPA starts login
 * via {@code /oauth/login/keycloak}. Its live job is the <em>silent-first</em>
 * {@code prompt=none} rewrite; the {@code kc_idp_hint} append is now off by
 * default and kept only as a config seam.
 *
 * <p><b>Post-auth-extraction (Phase 5): no {@code kc_idp_hint}.</b> The demo
 * realm no longer federates to a {@code p1} SAML IdP — {@code identityProviders}
 * is empty. Users are loaded from {@code user-service} via the User Storage SPI,
 * and Keycloak renders <em>its own</em> login form (the {@code geowealth} theme)
 * which delegates the credential check to that SPI. So the intended login UI IS
 * the KC form; there is nothing to hint at. Forcing {@code kc_idp_hint=p1} here
 * was a SAML-era behaviour that only "worked" because KC silently ignores an
 * unknown hint — but if a {@code p1} IdP ever reappears (a stale realm import or
 * drift) the hint would broker every login, including the silent {@code
 * prompt=none} probe, into a dead SAML flow and the user lands on a login splash
 * instead of a silent SSO. {@code app.kc-idp-hint} therefore defaults to empty
 * and the append is skipped.</p>
 *
 * <p>The filter is a thin Location-rewriter: micronaut-security returns
 * {@code 302 Location: https://auth.geowealth.int:.../auth?...} and, for a
 * silent attempt, we append {@code &prompt=none}. If {@code app.kc-idp-hint} is
 * set to a non-blank value the (legacy) {@code kc_idp_hint} append is still
 * available. Idempotent — an already-present param is left untouched.</p>
 */
@Filter("/oauth/login/keycloak")
public class IdpHintFilter implements HttpServerFilter {

    private final String idpHint;

    public IdpHintFilter(@Value("${app.kc-idp-hint:}") String idpHint) {
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
