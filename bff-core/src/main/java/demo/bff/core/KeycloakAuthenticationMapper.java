package demo.bff.core;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.security.authentication.AuthenticationResponse;
import io.micronaut.security.oauth2.endpoint.authorization.state.State;
import io.micronaut.security.oauth2.endpoint.token.response.DefaultOpenIdAuthenticationMapper;
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdAuthenticationMapper;
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdClaims;
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdTokenResponse;
import com.nimbusds.jwt.SignedJWT;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Token Handler / BFF pattern (IETF "OAuth 2.0 for Browser-Based Apps").
 *
 * <p>After the BFF completes the authorization-code exchange, this mapper turns
 * the OIDC tokens + claims into the {@code Authentication} that micronaut-security
 * stores in the <b>server-side session</b>. The browser only ever receives the
 * httpOnly session cookie — the access / refresh / id tokens never leave
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

    private static final Logger LOG = LoggerFactory.getLogger(KeycloakAuthenticationMapper.class);

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

        // Subdomain-agnostic person facts (see person-identity-via-existing-linkdelink.md):
        //   personId    — stable P1 person id; same on every subdomain.
        //   memberships — multi-valued "<firmCd>:<ldapUid>" — the firms the person
        //                 has an account in. Each subdomain's BFF authorizes access
        //                 from this (firm-bound subdomain → is its firm present?).
        // NB: OpenIdClaims.get() reliably surfaces scalar claims (personId, firmCd)
        // but NOT multi-valued array claims like `memberships`, so read it straight
        // from the raw JWT payload. The id_token passed to this mapper is not always
        // populated by micronaut-security at the code-exchange step, so fall back to
        // the access token (always present here — it carries the same claim and is
        // forwarded to P1 for Tier 2/3 authz).
        List<String> memberships = membershipsFromJwt(tokenResponse.getIdToken());
        String source = "id_token";
        if (memberships.isEmpty()) {
            memberships = membershipsFromJwt(tokenResponse.getAccessToken());
            source = "access_token";
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("memberships parsed from {} (idToken present={}): count={}",
                    source, tokenResponse.getIdToken() != null, memberships.size());
        }

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("sub", claims.getSubject());
        attrs.put("email", str(claims.get("email")));
        attrs.put("firmCd", str(claims.get("firmCd")));
        attrs.put("sid", str(claims.get("sid")));
        attrs.put("personId", str(claims.get("personId")));
        // Stored as a single delimited String (not a List): micronaut-security's
        // session-backed Authentication round-trips scalar attributes but drops
        // List attributes. AuthClaims.memberships() splits it back on read.
        attrs.put("memberships", String.join(AuthClaims.MEMBERSHIPS_DELIM, memberships));
        attrs.put("accessToken", tokenResponse.getAccessToken());
        attrs.put("refreshToken", tokenResponse.getRefreshToken());
        attrs.put("idToken", tokenResponse.getIdToken());

        return Publishers.just(AuthenticationResponse.success(username, roles, attrs));
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /**
     * Extract the multi-valued {@code memberships} claim ("&lt;firmCd&gt;:&lt;ldapUid&gt;")
     * from a raw JWT payload (id_token or access_token). {@code OpenIdClaims.get("memberships")}
     * returns null for array claims in this Micronaut version, so we parse the JWT
     * with Nimbus and read the typed string-list claim — robust against value
     * content / claim ordering (the previous regex broke on any {@code ]} or quote
     * inside a value). Best-effort: empty on failure.
     */
    private static List<String> membershipsFromJwt(String jwt) {
        List<String> out = new ArrayList<>();
        if (jwt == null) {
            return out;
        }
        try {
            List<String> claim = SignedJWT.parse(jwt).getJWTClaimsSet().getStringListClaim("memberships");
            if (claim != null) {
                for (String m : claim) {
                    if (m != null && !m.isBlank()) {
                        out.add(m);
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through to empty
        }
        return out;
    }
}
