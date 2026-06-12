package demo.bff.core;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.session.SessionStore;
import io.micronaut.session.event.SessionDestroyedEvent;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Correlates Keycloak's OIDC {@code sid} (session id, carried in the id_token and
 * in the back-channel logout token) to the BFF's own server-side session id(s).
 *
 * <p>This is what lets an out-of-band logout — e.g. P1 (IdP) logout → KC
 * terminates its SSO session → KC POSTs a back-channel logout token to the BFF —
 * destroy exactly the right BFF session, so the next {@code /auth/me} from the
 * open SPA tab returns 401 and the SPA signs out.</p>
 *
 * <p><b>B2 — shared store.</b> The mapping lives in Redis, not in this JVM's
 * memory, for two reasons: (1) the BFF session store is also Redis-backed, so a
 * {@code deleteSession} from ANY replica removes the real session; (2) a
 * back-channel logout POST lands on a single replica, and it must be able to tear
 * down a session created on a different replica. With both in Redis, one POST
 * invalidates the session globally. Two keys per session:
 * <ul>
 *   <li>{@code bff:sid:<sid>} — a SET of BFF session ids for that OIDC sid;</li>
 *   <li>{@code bff:sess:<sessionId>} — the reverse pointer, so a locally-destroyed
 *       session (idle expiry / explicit delete) can prune itself from its sid set.</li>
 * </ul>
 * Both carry a TTL as a backstop against leaks; the authoritative lifecycle is
 * {@link #invalidateBySid} and the session-destroyed listener.</p>
 */
@Singleton
public class SidSessionRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(SidSessionRegistry.class);

    private static final String SID_KEY_PREFIX = "bff:sid:";
    private static final String SESS_KEY_PREFIX = "bff:sess:";

    /** TTL backstop for the mapping keys (12h ≥ the SSO max lifespan). */
    private static final long MAPPING_TTL_SECONDS = 12 * 60 * 60;

    private final SessionStore<?> sessionStore;
    private final RedisCommands<String, String> redis;

    public SidSessionRegistry(SessionStore<?> sessionStore,
                              StatefulRedisConnection<String, String> connection) {
        this.sessionStore = sessionStore;
        this.redis = connection.sync();
    }

    /** Record that this OIDC {@code sid} is backed by this BFF session id. */
    public void register(String sid, String sessionId) {
        if (sid == null || sessionId == null) {
            return;
        }
        try {
            String sidKey = SID_KEY_PREFIX + sid;
            redis.sadd(sidKey, sessionId);
            redis.expire(sidKey, MAPPING_TTL_SECONDS);
            String sessKey = SESS_KEY_PREFIX + sessionId;
            redis.set(sessKey, sid);
            redis.expire(sessKey, MAPPING_TTL_SECONDS);
        } catch (Exception e) {
            LOG.warn("SidSessionRegistry.register failed (sid={}): {}", sid, e.toString());
        }
    }

    /** Invalidate every BFF session tied to this OIDC {@code sid}. Returns the count. */
    public int invalidateBySid(String sid) {
        if (sid == null) {
            return 0;
        }
        String sidKey = SID_KEY_PREFIX + sid;
        Set<String> ids;
        try {
            ids = redis.smembers(sidKey);
        } catch (Exception e) {
            LOG.warn("SidSessionRegistry.invalidateBySid lookup failed (sid={}): {}", sid, e.toString());
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
            try {
                redis.del(SESS_KEY_PREFIX + id);
            } catch (Exception ignored) {
                // best-effort
            }
        }
        try {
            redis.del(sidKey);
        } catch (Exception ignored) {
            // best-effort
        }
        return n;
    }

    /**
     * Prune mappings when a BFF session is destroyed (idle-expired or deleted), so
     * the sid set doesn't accumulate stale ids. Sessions ended via
     * {@link #invalidateBySid} already removed their reverse key; this catches every
     * other teardown path. Uses the reverse pointer to avoid scanning all sid sets.
     */
    @EventListener
    void onSessionDestroyed(SessionDestroyedEvent event) {
        String sessionId = event.getSource().getId();
        if (sessionId == null) {
            return;
        }
        try {
            String sessKey = SESS_KEY_PREFIX + sessionId;
            String sid = redis.get(sessKey);
            if (sid != null) {
                redis.srem(SID_KEY_PREFIX + sid, sessionId);
            }
            redis.del(sessKey);
        } catch (Exception e) {
            LOG.debug("SidSessionRegistry.onSessionDestroyed cleanup failed: {}", e.toString());
        }
    }
}
