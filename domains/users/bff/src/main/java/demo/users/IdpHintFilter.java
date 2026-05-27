package demo.users;

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
 * builds when a SPA starts login via {@code /oauth/login/keycloak}. See the
 * billing twin for the full rationale.
 */
@Filter("/oauth/login/keycloak")
public class IdpHintFilter implements HttpServerFilter {

    private final String idpHint;

    public IdpHintFilter(@Value("${app.kc-idp-hint:p1}") String idpHint) {
        this.idpHint = idpHint;
    }

    @Override
    public int getOrder() {
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
