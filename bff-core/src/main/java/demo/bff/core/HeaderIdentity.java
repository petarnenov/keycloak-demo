package demo.bff.core;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.Authentication;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds an {@link Authentication} from the {@code X-Auth-*} request headers that
 * the Token Handler's {@code /auth/verify} emits and nginx injects (forward-auth).
 * Lets an auth-UNAWARE data BFF keep using {@link PolicyRuleGate} / {@link AuthClaims}
 * for its per-endpoint Tier 2/3 checks without resolving a session itself — the
 * session, refresh and coarse/subdomain authz all happened in the Token Handler.
 *
 * <p>The data BFF receives these requests only via nginx (which gates them on a
 * 200 from {@code /auth/verify}); the session cookie is deliberately NOT forwarded,
 * so no session is resolved and the BFF's auth filters no-op.</p>
 */
public final class HeaderIdentity {

    private HeaderIdentity() {
    }

    /** True when forward-auth identity headers are present on the request. */
    public static boolean isPresent(HttpRequest<?> request) {
        return request.getHeaders().get("X-Auth-Username") != null;
    }

    /** Build the {@link Authentication} from {@code X-Auth-*} headers. */
    public static Authentication from(HttpRequest<?> request) {
        HttpHeaders h = request.getHeaders();
        String username = h.get("X-Auth-Username");
        List<String> roles = split(h.get("X-Auth-Roles"));
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("sub", h.get("X-Auth-Sub"));
        attrs.put("personId", h.get("X-Auth-Person-Id"));
        attrs.put("firmCd", h.get("X-Auth-Firm-Cd"));
        attrs.put("accessToken", h.get("X-Auth-Access-Token"));
        // /auth/verify comma-joins memberships; AuthClaims expects MEMBERSHIPS_DELIM.
        String m = h.get("X-Auth-Memberships");
        attrs.put("memberships", m == null ? "" : m.replace(",", AuthClaims.MEMBERSHIPS_DELIM));
        return Authentication.build(username != null ? username : "anonymous", roles, attrs);
    }

    private static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
