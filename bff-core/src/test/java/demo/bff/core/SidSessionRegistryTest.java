package demo.bff.core;

import io.micronaut.session.Session;
import io.micronaut.session.SessionStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SuppressWarnings("unchecked")
class SidSessionRegistryTest {

    @Test
    void register_nullArgs_areNoOps() {
        SessionStore<Session> store = mock(SessionStore.class);
        SidSessionRegistry reg = new SidSessionRegistry(store);

        reg.register(null, "s1");
        reg.register("sid", null);

        assertEquals(0, reg.invalidateBySid("sid"));
        verify(store, never()).deleteSession(anyString());
    }

    @Test
    void invalidateBySid_unknownSid_returnsZero() {
        SessionStore<Session> store = mock(SessionStore.class);
        SidSessionRegistry reg = new SidSessionRegistry(store);

        assertEquals(0, reg.invalidateBySid("nope"));
        assertEquals(0, reg.invalidateBySid(null));
    }

    @Test
    void register_then_invalidate_deletesAllMappedSessions() {
        SessionStore<Session> store = mock(SessionStore.class);
        SidSessionRegistry reg = new SidSessionRegistry(store);

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
        SidSessionRegistry reg = new SidSessionRegistry(store);

        reg.register("sid-2", "dead");
        reg.register("sid-2", "live");

        assertEquals(1, reg.invalidateBySid("sid-2"));
    }
}
