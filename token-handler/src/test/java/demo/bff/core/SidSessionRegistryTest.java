package demo.bff.core;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.micronaut.session.Session;
import io.micronaut.session.SessionStore;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Redis-backed {@link SidSessionRegistry} (B2). Redis is faked
 * with an in-memory map behind a mocked {@link RedisCommands}, so the registry's
 * real SADD/SMEMBERS/DEL logic is exercised without a live Redis.
 */
@SuppressWarnings("unchecked")
class SidSessionRegistryTest {

    /** A mocked StatefulRedisConnection whose sync() commands are backed by maps. */
    private static StatefulRedisConnection<String, String> fakeRedis() {
        Map<String, Set<String>> sets = new HashMap<>();
        Map<String, String> kv = new HashMap<>();
        RedisCommands<String, String> c = mock(RedisCommands.class);

        when(c.sadd(anyString(), any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            Set<String> s = sets.computeIfAbsent(key, k -> new LinkedHashSet<>());
            java.util.List<String> members = tail(inv, 1);
            s.addAll(members);
            return (long) members.size();
        });
        when(c.smembers(anyString())).thenAnswer(inv ->
            new LinkedHashSet<>(sets.getOrDefault(inv.getArgument(0), Set.of())));
        when(c.srem(anyString(), any())).thenAnswer(inv -> {
            Set<String> s = sets.get(inv.getArgument(0));
            if (s == null) return 0L;
            long n = 0;
            for (String m : tail(inv, 1)) if (s.remove(m)) n++;
            return n;
        });
        when(c.del(any())).thenAnswer(inv -> {
            long n = 0;
            for (String k : tail(inv, 0)) {
                if (sets.remove(k) != null || kv.remove(k) != null) n++;
            }
            return n;
        });
        when(c.set(anyString(), anyString())).thenAnswer(inv -> {
            kv.put(inv.getArgument(0), inv.getArgument(1));
            return "OK";
        });
        when(c.get(anyString())).thenAnswer(inv -> kv.get(inv.getArgument(0)));
        when(c.expire(anyString(), anyLong())).thenReturn(true);

        StatefulRedisConnection<String, String> conn = mock(StatefulRedisConnection.class);
        when(conn.sync()).thenReturn(c);
        return conn;
    }

    /** Collect String args from {@code from} onward, flattening a varargs array —
     *  Mockito may deliver {@code String...} either expanded or as a {@code String[]}. */
    private static java.util.List<String> tail(org.mockito.invocation.InvocationOnMock inv, int from) {
        java.util.List<String> out = new java.util.ArrayList<>();
        Object[] args = inv.getArguments();
        for (int i = from; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof String[]) {
                for (String x : (String[]) a) out.add(x);
            } else if (a instanceof String) {
                out.add((String) a);
            }
        }
        return out;
    }

    @Test
    void register_nullArgs_areNoOps() {
        SessionStore<Session> store = mock(SessionStore.class);
        SidSessionRegistry reg = new SidSessionRegistry(store, fakeRedis());

        reg.register(null, "s1");
        reg.register("sid", null);

        assertEquals(0, reg.invalidateBySid("sid"));
        verify(store, never()).deleteSession(anyString());
    }

    @Test
    void invalidateBySid_unknownSid_returnsZero() {
        SessionStore<Session> store = mock(SessionStore.class);
        SidSessionRegistry reg = new SidSessionRegistry(store, fakeRedis());

        assertEquals(0, reg.invalidateBySid("nope"));
        assertEquals(0, reg.invalidateBySid(null));
    }

    @Test
    void register_then_invalidate_deletesAllMappedSessions() {
        SessionStore<Session> store = mock(SessionStore.class);
        SidSessionRegistry reg = new SidSessionRegistry(store, fakeRedis());

        reg.register("sid-1", "bff-a");
        reg.register("sid-1", "bff-b");

        int killed = reg.invalidateBySid("sid-1");

        assertEquals(2, killed);
        verify(store).deleteSession("bff-a");
        verify(store).deleteSession("bff-b");
        // entry removed — a second call finds nothing
        assertEquals(0, reg.invalidateBySid("sid-1"));
    }

    @Test
    void invalidate_isBestEffort_countsOnlySuccessfulDeletes() {
        SessionStore<Session> store = mock(SessionStore.class);
        doThrow(new RuntimeException("already gone")).when(store).deleteSession("dead");
        SidSessionRegistry reg = new SidSessionRegistry(store, fakeRedis());

        reg.register("sid-2", "dead");
        reg.register("sid-2", "live");

        assertEquals(1, reg.invalidateBySid("sid-2"));
    }
}
