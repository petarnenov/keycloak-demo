package demo.billing;

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
        return Flux.from(chain.proceed(request)).map(response -> {
            if (idpHint == null || idpHint.isBlank()) {
                return response;
            }
            int code = response.getStatus().getCode();
            if (code < 300 || code >= 400) {
                return response;
            }
            String location = response.getHeaders().get(HttpHeaders.LOCATION);
            if (location == null || location.contains("kc_idp_hint=")) {
                return response;
            }
            String sep = location.indexOf('?') >= 0 ? "&" : "?";
            response.getHeaders().set(HttpHeaders.LOCATION, location + sep + "kc_idp_hint=" + idpHint);
            return response;
        });
    }
}
