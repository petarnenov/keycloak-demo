package demo.billing;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.security.authentication.Authentication;

/**
 * RFC 9470 step-up gate for sensitive endpoints. Sessions minted via the
 * cross-domain linked-identity silent-swap path carry the
 * {@code linkedIdentityAcr} attribute set to the well-known URI
 * {@code urn:geowealth:ac:classes:linked-identity-from-prior-session}
 * (see {@code keycloak-demo/cross-domain-sso.md} §3.4). For endpoints the
 * firm classifies as sensitive (financial ops, write paths, admin) we want
 * a fresh credential presentation, not the swap's inherited assertion.
 *
 * <p>{@link #requireFreshAuthOr401(Authentication)} returns {@code null} when
 * the session was minted by fresh authentication, or a populated 401 Response
 * carrying the RFC 9470 {@code WWW-Authenticate: Bearer
 * error="insufficient_user_authentication"} header otherwise. The SPA reads
 * that header on a 401 and starts a new {@code keycloak.login({ prompt: 'login' })}
 * authorize flow.</p>
 *
 * <p>Out of scope for Phase 4: the {@code mfa_required} flag in the binding
 * row. When set, even the silent swap should have already MFA-challenged
 * the user upstream at P1; the BFF will then see a different {@code acr}
 * class and let the request through.</p>
 */
public final class StepUpGuard {

    /** Canonical URI emitted by P1 when a session originates from a linked-identity swap. */
    public static final String LINKED_IDENTITY_ACR =
            "urn:geowealth:ac:classes:linked-identity-from-prior-session";

    /**
     * What the WWW-Authenticate header asks the SPA to do. SAML
     * {@code PasswordProtectedTransport} corresponds to a normal fresh
     * password login at P1; firms wanting MFA can swap this for the
     * {@code MultiFactor} class.
     */
    private static final String REQUIRED_ACR_VALUES =
            "urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport";

    private StepUpGuard() {}

    /**
     * Returns {@code null} when the session is acceptable for sensitive
     * endpoints; otherwise returns a 401 with the RFC 9470 header set so the
     * SPA can step up the user. Callers do
     * {@code MutableHttpResponse<?> r = StepUpGuard.requireFreshAuthOr401(auth); if (r != null) return r;}.
     */
    public static MutableHttpResponse<?> requireFreshAuthOr401(Authentication authentication) {
        Object acr = authentication.getAttributes().get("linkedIdentityAcr");
        if (acr == null || acr.toString().isBlank()) {
            return null; // fresh authn — let it through
        }
        if (!LINKED_IDENTITY_ACR.equals(acr.toString())) {
            // Unknown linked-identity class — fail closed; treat as if step-up
            // were required so a misconfiguration cannot silently bypass.
            return unauthorizedStepUp();
        }
        return unauthorizedStepUp();
    }

    private static MutableHttpResponse<?> unauthorizedStepUp() {
        // RFC 9470 §3 — 401 with WWW-Authenticate carrying error +
        // acr_values asking the client for a stronger assertion.
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
