package demo.bff.core;

import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.security.authentication.Authentication;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Tier23GateTest {

    private static Authentication auth(Set<String> roles, Map<String, Object> attrs) {
        Authentication a = mock(Authentication.class);
        when(a.getRoles()).thenReturn(roles);
        when(a.getAttributes()).thenReturn(attrs);
        return a;
    }

    private static final Function<String, String> ID = s -> s;

    // ---- require (Tier 2) ----

    @Test
    void require_noOpWhenFineDisabled() {
        P1AuthzClient authz = mock(P1AuthzClient.class);
        when(authz.fineEnabled()).thenReturn(false);
        Tier23Gate gate = new Tier23Gate(authz);

        gate.require(auth(Set.of(), Map.of()), 12, 1); // no throw — absence of exception is the contract
    }

    @Test
    void require_noOpForGwAdmin() {
        P1AuthzClient authz = mock(P1AuthzClient.class);
        when(authz.fineEnabled()).thenReturn(true);
        Tier23Gate gate = new Tier23Gate(authz);

        gate.require(auth(Set.of("gwAdmin"), Map.of("accessToken", "t")), 12, 1); // no throw
    }

    @Test
    void require_passesWhenPermissionHeld() {
        P1AuthzClient authz = mock(P1AuthzClient.class);
        when(authz.fineEnabled()).thenReturn(true);
        when(authz.hasPermission(eq("Bearer t"), anyString(), eq(12), eq(1))).thenReturn(true);
        Tier23Gate gate = new Tier23Gate(authz);

        gate.require(auth(Set.of(), Map.of("accessToken", "t", "sub", "u")), 12, 1); // no throw
    }

    @Test
    void require_forbiddenWhenPermissionMissing() {
        P1AuthzClient authz = mock(P1AuthzClient.class);
        when(authz.fineEnabled()).thenReturn(true);
        when(authz.hasPermission(anyString(), anyString(), anyInt(), anyInt())).thenReturn(false);
        Tier23Gate gate = new Tier23Gate(authz);

        assertThrows(HttpStatusException.class,
                () -> gate.require(auth(Set.of(), Map.of("accessToken", "t")), 12, 1));
    }

    @Test
    void require_forbiddenWhenNoBearer() {
        P1AuthzClient authz = mock(P1AuthzClient.class);
        when(authz.fineEnabled()).thenReturn(true);
        Tier23Gate gate = new Tier23Gate(authz);

        assertThrows(HttpStatusException.class,
                () -> gate.require(auth(Set.of(), Map.of()), 12, 1));
    }

    // ---- refine (Tier 3) ----

    @Test
    void refine_returnsAllWhenFineDisabled() {
        P1AuthzClient authz = mock(P1AuthzClient.class);
        when(authz.fineEnabled()).thenReturn(false);
        Tier23Gate gate = new Tier23Gate(authz);

        List<String> items = List.of("a", "b");
        assertSame(items, gate.refine(auth(Set.of(), Map.of()), items, ID, 12, 1));
    }

    @Test
    void refine_returnsAllForGwAdmin() {
        P1AuthzClient authz = mock(P1AuthzClient.class);
        when(authz.fineEnabled()).thenReturn(true);
        Tier23Gate gate = new Tier23Gate(authz);

        List<String> items = List.of("a", "b");
        assertSame(items, gate.refine(auth(Set.of("gwAdmin"), Map.of()), items, ID, 12, 1));
    }

    @Test
    void refine_failsClosedWhenNoBearer() {
        P1AuthzClient authz = mock(P1AuthzClient.class);
        when(authz.fineEnabled()).thenReturn(true);
        Tier23Gate gate = new Tier23Gate(authz);

        assertEquals(List.of(),
                gate.refine(auth(Set.of(), Map.of()), List.of("a"), ID, 12, 1));
    }

    @Test
    void refine_keepsOnlyAllowedIds() {
        P1AuthzClient authz = mock(P1AuthzClient.class);
        when(authz.fineEnabled()).thenReturn(true);
        when(authz.refine(eq("Bearer t"), eq(12), eq(1), any()))
                .thenReturn(List.of("a", "c"));
        Tier23Gate gate = new Tier23Gate(authz);

        List<String> out = gate.refine(
                auth(Set.of(), Map.of("accessToken", "t")),
                List.of("a", "b", "c"), ID, 12, 1);

        assertEquals(List.of("a", "c"), out);
    }

    @Test
    void refine_dropsItemsWithNullId() {
        P1AuthzClient authz = mock(P1AuthzClient.class);
        when(authz.fineEnabled()).thenReturn(true);
        when(authz.refine(anyString(), anyInt(), anyInt(), any()))
                .thenReturn(List.of("a"));
        Tier23Gate gate = new Tier23Gate(authz);

        // idOf returns null for "skip" — that item is never offered to refine and never kept.
        Function<String, String> idOf = s -> s.equals("skip") ? null : s;
        List<String> out = gate.refine(
                auth(Set.of(), Map.of("accessToken", "t")),
                List.of("a", "skip"), idOf, 12, 1);

        assertEquals(List.of("a"), out);
    }
}
