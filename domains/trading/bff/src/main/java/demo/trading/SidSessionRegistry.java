package demo.trading;

import io.micronaut.session.SessionStore;
import jakarta.inject.Singleton;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Correlates Keycloak's OIDC {@code sid} (session id, carried in the id_token and
 * in the back-channel logout token) to the BFF's own server-side session id(s).
 *
 * <p>This is what lets an out-of-band logout — e.g. P1 (IdP) logout → KC
 * terminates its SSO session → KC POSTs a back-channel logout token to the BFF —
 * destroy exactly the right BFF session, so the next {@code /auth/me} from the
 * open SPA tab returns 401 and the SPA signs out. No third-party cookies, no
 * front-channel iframes (OIDC Back-Channel Logout 1.0).</p>
 */
@Singleton
public class SidSessionRegistry {

    private final Map<String, Set<String>> sidToSessions = new ConcurrentHashMap<>();
    private final SessionStore<?> sessionStore;

    public SidSessionRegistry(SessionStore<?> sessionStore) {
        this.sessionStore = sessionStore;
    }

    /** Record that this OIDC {@code sid} is backed by this BFF session id. */
    public void register(String sid, String sessionId) {
        if (sid == null || sessionId == null) {
            return;
        }
        sidToSessions.computeIfAbsent(sid, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
    }

    /** Invalidate every BFF session tied to this OIDC {@code sid}. Returns the count. */
    public int invalidateBySid(String sid) {
        if (sid == null) {
            return 0;
        }
        Set<String> ids = sidToSessions.remove(sid);
        if (ids == null) {
            return 0;
        }
        int n = 0;
        for (String id : ids) {
            try {
                sessionStore.deleteSession(id);
                n++;
            } catch (Exception ignored) {
                // best-effort: a session may already be gone
            }
        }
        return n;
    }
}
