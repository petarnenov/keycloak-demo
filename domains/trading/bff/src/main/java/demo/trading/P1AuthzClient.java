package demo.trading;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import jakarta.inject.Singleton;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tier 2 / Tier 3 authorisation client (p1-auth-flow.md §2.2 / §2.9). The coarse
 * gate ({@code @Secured} on canonical capabilities) answers "can this user reach
 * the domain"; this client answers the finer questions by calling P1:
 *
 * <ul>
 *   <li><b>Tier 2</b> — {@code GET /saml/idp/p1-authz-me.do} returns the user's
 *       permission map ({@code "<objectTypeCd>_<permissionCd>"} keys). Cached
 *       per {@code sub} for {@code ttl} (≤60s, §2.3): the only network hit on the
 *       hot path.</li>
 *   <li><b>Tier 3</b> — {@code POST .../p1-authz-can.do} (single object) and
 *       {@code .../p1-authz-refine.do} (a page of ids → the allowed subset, the
 *       monolith's {@code refineUUIDs} pattern, §2.9).</li>
 * </ul>
 *
 * <p>The user's own bearer token is forwarded so authority stays user-bound
 * (§2.6 — never an admin token). Every call <b>fails closed</b>: a timeout, a
 * non-2xx, or an unreachable P1 yields "deny" (empty permissions / empty subset /
 * {@code can=false}), never an exception to the request thread (§2.7).</p>
 *
 * <p>Fine enforcement is opt-in via {@code app.authz.fine-enabled} (default
 * {@code false}) so the coarse-only demo keeps its verified behaviour until P1's
 * authz endpoints are deployed and reachable.</p>
 */
@Singleton
public class P1AuthzClient {

    private static final String ME_PATH = "/saml/idp/p1-authz-me.do";
    private static final String CAN_PATH = "/saml/idp/p1-authz-can.do";
    private static final String REFINE_PATH = "/saml/idp/p1-authz-refine.do";
    /**
     * Caller-scoped linked-identity discovery (cross-domain-sso.md §8.6) —
     * Bearer-auth, no role required. Co-hosted with the Tier 2/3 surface
     * because the same HTTP client + base URL covers all P1 bearer-auth
     * endpoints.
     */
    private static final String LINKED_IDENTITY_DISCOVERY_PATH = "/saml/idp/linked-identity-discovery.do";

    private final HttpClient http;
    private final boolean fineEnabled;
    private final long ttlMillis;

    private final ConcurrentHashMap<String, Cached> meCache = new ConcurrentHashMap<>();

    public P1AuthzClient(@Client(id = "p1authz") HttpClient http,
                         @Value("${app.authz.fine-enabled:false}") boolean fineEnabled,
                         @Value("${app.authz.cache-ttl-millis:60000}") long ttlMillis) {
        this.http = http;
        this.fineEnabled = fineEnabled;
        this.ttlMillis = ttlMillis;
    }

    /** Whether controllers should enforce the fine (Tier 2/3) checks at all. */
    public boolean fineEnabled() {
        return fineEnabled;
    }

    /**
     * Tier 2 — does the user hold {@code (objectType, permission)}? Served from the
     * cached {@code /me} permission map. Fail-closed: {@code false} when P1 is
     * unavailable.
     */
    public boolean hasPermission(String authorizationHeader, String sub, int objectType, int permission) {
        Map<String, Object> permissions = permissions(authorizationHeader, sub);
        Object v = permissions.get(objectType + "_" + permission);
        return Boolean.TRUE.equals(v) || "true".equals(String.valueOf(v));
    }

    /** Tier 2 — the user's full permission map (cached). Empty map on failure. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> permissions(String authorizationHeader, String sub) {
        Cached cached = meCache.get(sub);
        long now = System.currentTimeMillis();
        if (cached != null && now < cached.expiresAt) {
            return cached.permissions;
        }
        Map<String, Object> permissions = Collections.emptyMap();
        try {
            Map<String, Object> me = exchange(HttpRequest.GET(ME_PATH).header("Authorization", authorizationHeader));
            Object p = me == null ? null : me.get("permissions");
            if (p instanceof Map) {
                permissions = (Map<String, Object>) p;
            }
        } catch (Exception e) {
            // fail closed — leave permissions empty
        }
        meCache.put(sub, new Cached(permissions, now + ttlMillis));
        return permissions;
    }

    /** Tier 3 (single object) — {@code canLoggedUserExecuteAccount} analogue. Fail-closed false. */
    public boolean can(String authorizationHeader, int objectType, int permission, String objectId) {
        try {
            Map<String, Object> body = Map.of(
                    "objectType", objectType, "permission", permission, "objectId", objectId);
            Map<String, Object> resp = exchange(HttpRequest.POST(CAN_PATH, body).header("Authorization", authorizationHeader));
            return resp != null && Boolean.TRUE.equals(resp.get("allowed"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Tier 3 (lists) — send the page of candidate ids, get back the subset the user
     * may act on (the monolith's refine, done P1-side). Fail-closed: empty subset.
     */
    @SuppressWarnings("unchecked")
    public List<String> refine(String authorizationHeader, int objectType, int permission, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            Map<String, Object> body = Map.of(
                    "objectType", objectType, "permission", permission, "ids", ids);
            Map<String, Object> resp = exchange(HttpRequest.POST(REFINE_PATH, body).header("Authorization", authorizationHeader));
            Object allowed = resp == null ? null : resp.get("allowed");
            if (allowed instanceof List) {
                return (List<String>) allowed;
            }
            return Collections.emptyList();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * Caller-scoped linked-identity targets — list of OIDC client IDs the
     * authenticated user has active bindings to. Used by the SPA at boot time
     * to pre-flight-hide cross-domain links whose audience the user is not
     * provisioned for ({@code cross-domain-sso.md} §8.6).
     *
     * <p>Fail-closed: returns an empty list when P1 is unreachable or
     * returns a non-2xx. This means a transient P1 outage <i>hides</i> the
     * cross-domain link rather than showing it broken — clicking a link the
     * user can't follow is worse UX than missing one momentarily.</p>
     */
    @SuppressWarnings("unchecked")
    public List<String> linkedTargets(String authorizationHeader) {
        try {
            Map<String, Object> resp = exchange(
                    HttpRequest.GET(LINKED_IDENTITY_DISCOVERY_PATH)
                            .header("Authorization", authorizationHeader));
            Object t = resp == null ? null : resp.get("targets");
            if (t instanceof List) {
                return (List<String>) t;
            }
        } catch (Exception e) {
            // fail closed — empty list hides the link
        }
        return Collections.emptyList();
    }

    private Map<String, Object> exchange(HttpRequest<?> request) {
        return http.toBlocking().retrieve(request, Argument.mapOf(String.class, Object.class));
    }

    private static final class Cached {
        final Map<String, Object> permissions;
        final long expiresAt;

        Cached(Map<String, Object> permissions, long expiresAt) {
            this.permissions = permissions;
            this.expiresAt = expiresAt;
        }
    }
}
