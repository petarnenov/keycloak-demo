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

class PolicyRuleGateTest {

    private static Authentication auth(Set<String> roles, Map<String, Object> attrs) {
        Authentication a = mock(Authentication.class);
        when(a.getRoles()).thenReturn(roles);
        when(a.getAttributes()).thenReturn(attrs);
        return a;
    }

    private static final Function<String, String> ID = s -> s;

    // ---- require (capability check) ----

    @Test
    void require_noOpForGwAdmin() {
        PolicyRuleClient authz = mock(PolicyRuleClient.class);
        PolicyRuleGate gate = new PolicyRuleGate(authz);

        gate.requireCapability(auth(Set.of("gwAdmin"), Map.of("accessToken", "t")), 12, 1); // no throw
    }

    @Test
    void require_passesWhenPermissionHeld() {
        PolicyRuleClient authz = mock(PolicyRuleClient.class);
        when(authz.hasPermission(eq("Bearer t"), anyString(), eq(12), eq(1))).thenReturn(true);
        PolicyRuleGate gate = new PolicyRuleGate(authz);

        gate.requireCapability(auth(Set.of(), Map.of("accessToken", "t", "sub", "u")), 12, 1); // no throw
    }

    @Test
    void require_forbiddenWhenPermissionMissing() {
        PolicyRuleClient authz = mock(PolicyRuleClient.class);
        when(authz.hasPermission(anyString(), anyString(), anyInt(), anyInt())).thenReturn(false);
        PolicyRuleGate gate = new PolicyRuleGate(authz);

        assertThrows(HttpStatusException.class,
                () -> gate.requireCapability(auth(Set.of(), Map.of("accessToken", "t")), 12, 1));
    }

    @Test
    void require_forbiddenWhenNoBearer() {
        PolicyRuleClient authz = mock(PolicyRuleClient.class);
        PolicyRuleGate gate = new PolicyRuleGate(authz);

        assertThrows(HttpStatusException.class,
                () -> gate.requireCapability(auth(Set.of(), Map.of()), 12, 1));
    }

    // ---- refine (refine) ----

    @Test
    void refine_returnsAllForGwAdmin() {
        PolicyRuleClient authz = mock(PolicyRuleClient.class);
        PolicyRuleGate gate = new PolicyRuleGate(authz);

        List<String> items = List.of("a", "b");
        assertSame(items, gate.refineUUIDs(auth(Set.of("gwAdmin"), Map.of()), items, ID, 12, 1));
    }

    @Test
    void refine_failsClosedWhenNoBearer() {
        PolicyRuleClient authz = mock(PolicyRuleClient.class);
        PolicyRuleGate gate = new PolicyRuleGate(authz);

        assertEquals(List.of(),
                gate.refineUUIDs(auth(Set.of(), Map.of()), List.of("a"), ID, 12, 1));
    }

    @Test
    void refine_keepsOnlyAllowedIds() {
        PolicyRuleClient authz = mock(PolicyRuleClient.class);
        when(authz.refine(eq("Bearer t"), eq(12), eq(1), any()))
                .thenReturn(List.of("a", "c"));
        PolicyRuleGate gate = new PolicyRuleGate(authz);

        List<String> out = gate.refineUUIDs(
                auth(Set.of(), Map.of("accessToken", "t")),
                List.of("a", "b", "c"), ID, 12, 1);

        assertEquals(List.of("a", "c"), out);
    }

    @Test
    void refine_dropsItemsWithNullId() {
        PolicyRuleClient authz = mock(PolicyRuleClient.class);
        when(authz.refine(anyString(), anyInt(), anyInt(), any()))
                .thenReturn(List.of("a"));
        PolicyRuleGate gate = new PolicyRuleGate(authz);

        // idOf returns null for "skip" — that item is never offered to refine and never kept.
        Function<String, String> idOf = s -> s.equals("skip") ? null : s;
        List<String> out = gate.refineUUIDs(
                auth(Set.of(), Map.of("accessToken", "t")),
                List.of("a", "skip"), idOf, 12, 1);

        assertEquals(List.of("a"), out);
    }
}
