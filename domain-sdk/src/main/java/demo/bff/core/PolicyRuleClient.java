package demo.bff.core;

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
 * PolicyRule (capability + refine) authorisation client (p1-auth-flow.md §2.2 / §2.9). The coarse
 * gate ({@code @Secured} on canonical capabilities) answers "can this user reach
 * the domain"; this client answers the finer questions by calling the
 * standalone authz-service (alignment Phase A0 — P1 is off the runtime authz path):
 *
 * <ul>
 *   <li><b>capability check</b> — {@code GET /policy/capabilities} returns the user's
 *       permission map ({@code "<objectTypeCd>_<permissionCd>"} keys). Cached
 *       per {@code sub} for {@code ttl} (≤60s, §2.3): the only network hit on the
 *       hot path.</li>
 *   <li><b>refine</b> — {@code POST /policy/can} (single object) and
 *       {@code /policy/refine} (a page of ids → the allowed subset, the
 *       monolith's {@code refineUUIDs} pattern, §2.9).</li>
 * </ul>
 *
 * <p>The user's own bearer token is forwarded so authority stays user-bound
 * (§2.6 — never an admin token). Every call <b>fails closed</b>: a timeout, a
 * non-2xx, or an unreachable authz-service yields "deny" (empty permissions /
 * empty subset / {@code can=false}), never an exception to the request thread (§2.7).</p>
 *
 * <p>PolicyRule enforcement is always on — matching P1, whose PolicyRuleManager
 * gates on nothing. (The former {@code app.authz.fine-enabled} opt-in switch was
 * a rollout net for the authz-service extraction; it was retired once the service
 * was proven in sync — see {@code docs/plans/2026-07-06-authz-service-p1-sync.md}.)</p>
 */
@Singleton
public class PolicyRuleClient {

    // PolicyRule decision endpoints on authz-service (alignment Phase A0). These
    // supersede the legacy in-monolith authz actions — P1 is off the runtime authz
    // path. Response shapes: {permissions:{...}} / {allowed:bool} / {allowed:[...]}.
    private static final String ME_PATH = "/policy/capabilities";
    private static final String CAN_PATH = "/policy/can";
    private static final String REFINE_PATH = "/policy/refine";

    private final HttpClient http;
    private final long ttlMillis;

    private final ConcurrentHashMap<String, Cached> meCache = new ConcurrentHashMap<>();

    public PolicyRuleClient(@Client(id = "authz") HttpClient http,
                         @Value("${app.authz.cache-ttl-millis:60000}") long ttlMillis) {
        this.http = http;
        this.ttlMillis = ttlMillis;
    }

    /**
     * capability check — does the user hold {@code (objectType, permission)}? Served from the
     * cached {@code /me} permission map. Fail-closed: {@code false} when P1 is
     * unavailable.
     */
    public boolean hasPermission(String authorizationHeader, String sub, int objectType, int permission) {
        Map<String, Object> permissions = permissions(authorizationHeader, sub);
        Object v = permissions.get(objectType + "_" + permission);
        return Boolean.TRUE.equals(v) || "true".equals(String.valueOf(v));
    }

    /** capability check — the user's full permission map (cached). Empty map on failure. */
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

    /** single-object check — {@code canLoggedUserExecuteAccount} analogue. Fail-closed false. */
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
     * list refine — send the page of candidate ids, get back the subset the user
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
