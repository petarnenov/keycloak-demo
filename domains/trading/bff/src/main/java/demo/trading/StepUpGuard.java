package demo.trading;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.security.authentication.Authentication;

/**
 * RFC 9470 step-up gate for sensitive endpoints. Sessions minted via the
 * cross-domain linked-identity silent-swap path carry the
 * {@code linkedIdentityAcr} attribute set to the well-known URI
 * {@code urn:geowealth:ac:classes:linked-identity-from-prior-session}
 * (see {@code keycloak-demo/cross-domain-sso.md} §3.4). For endpoints the
 * firm classifies as sensitive (order placement, write paths) we want a
 * fresh credential presentation, not the swap's inherited assertion.
 *
 * <p>{@link #requireFreshAuthOr401(Authentication)} returns {@code null} when
 * the session was minted by fresh authentication, or a populated 401 Response
 * carrying the RFC 9470 {@code WWW-Authenticate: Bearer
 * error="insufficient_user_authentication"} header otherwise. The SPA reads
 * that header on a 401 and starts a new {@code keycloak.login({ prompt: 'login' })}
 * authorize flow.</p>
 */
public final class StepUpGuard {

    public static final String LINKED_IDENTITY_ACR =
            "urn:geowealth:ac:classes:linked-identity-from-prior-session";

    private static final String REQUIRED_ACR_VALUES =
            "urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport";

    private StepUpGuard() {}

    public static MutableHttpResponse<?> requireFreshAuthOr401(Authentication authentication) {
        Object acr = authentication.getAttributes().get("linkedIdentityAcr");
        if (acr == null || acr.toString().isBlank()) {
            return null;
        }
        if (!LINKED_IDENTITY_ACR.equals(acr.toString())) {
            return unauthorizedStepUp();
        }
        return unauthorizedStepUp();
    }

    private static MutableHttpResponse<?> unauthorizedStepUp() {
        String wwwAuth = "Bearer error=\"insufficient_user_authentication\","
                + " error_description=\"This endpoint requires fresh authentication, "
                + "not a linked-identity assertion.\","
                + " acr_values=\"" + REQUIRED_ACR_VALUES + "\"";
        return HttpResponse.unauthorized()
                .header("WWW-Authenticate", wwwAuth)
                .body(java.util.Map.of(
                        "error", "insufficient_user_authentication",
                        "acr_required", REQUIRED_ACR_VALUES,
                        "acr_current", LINKED_IDENTITY_ACR));
    }
}
