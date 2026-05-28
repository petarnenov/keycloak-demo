package demo.billing;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.security.authentication.AuthenticationResponse;
import io.micronaut.security.oauth2.endpoint.authorization.state.State;
import io.micronaut.security.oauth2.endpoint.token.response.DefaultOpenIdAuthenticationMapper;
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdAuthenticationMapper;
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdClaims;
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdTokenResponse;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Token Handler / BFF pattern (IETF "OAuth 2.0 for Browser-Based Apps").
 *
 * <p>After the BFF completes the authorization-code exchange, this mapper turns
 * the OIDC tokens + claims into the {@code Authentication} that micronaut-security
 * stores in the <b>server-side session</b>. The browser only ever receives the
 * httpOnly {@code BSESSION} cookie — the access / refresh / id tokens never leave
 * the BFF.</p>
 *
 * <p>The OAuth tokens are stashed in the authentication attributes so the
 * controller can forward the user's own access token to P1 (Tier 2/3 authz,
 * §2.6) and so logout can present an {@code id_token_hint}. The OIDC {@code sid}
 * is kept too, to correlate KC's back-channel logout token to this session.</p>
 */
@Singleton
@Replaces(DefaultOpenIdAuthenticationMapper.class)
public class KeycloakAuthenticationMapper implements OpenIdAuthenticationMapper {

    @Override
    @SuppressWarnings("unchecked")
    public Publisher<AuthenticationResponse> createAuthenticationResponse(String providerName,
                                                               OpenIdTokenResponse tokenResponse,
                                                               OpenIdClaims claims,
                                                               @Nullable State state) {
        Object rolesClaim = claims.get("roles");
        List<String> roles = (rolesClaim instanceof List) ? (List<String>) rolesClaim : List.of();

        Object preferredUsername = claims.get("preferred_username");
        String username = preferredUsername != null ? preferredUsername.toString() : claims.getSubject();

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("sub", claims.getSubject());
        attrs.put("email", str(claims.get("email")));
        attrs.put("firmCd", str(claims.get("firmCd")));
        attrs.put("sid", str(claims.get("sid")));
        // Cross-domain linked-identity ACR (cross-domain-sso.md §3.4 / §4.4).
        // Non-null only when this session was minted via the silent-swap path
        // AND not MFA-elevated — BFF endpoints refuse sensitive operations
        // until the user re-authenticates with a stronger assertion
        // (RFC 9470 step-up).
        attrs.put("linkedIdentityAcr", str(claims.get("linked_identity_acr")));
        // Source identity's UUID on the swap path. Non-null on ANY swap
        // (including MFA-elevated ones) — independent of step-up state.
        // Controls per-audience logout (§8.5): swap-origin sessions skip the
        // P1 SLO chain so logging out of one identity does not cascade-kill
        // the other.
        attrs.put("linkedIdentitySource", str(claims.get("linked_identity_source")));
        attrs.put("accessToken", tokenResponse.getAccessToken());
        attrs.put("refreshToken", tokenResponse.getRefreshToken());
        attrs.put("idToken", tokenResponse.getIdToken());

        return Publishers.just(AuthenticationResponse.success(username, roles, attrs));
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
