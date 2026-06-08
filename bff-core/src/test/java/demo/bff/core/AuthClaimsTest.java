package demo.bff.core;

import io.micronaut.security.authentication.Authentication;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthClaimsTest {

    private static Authentication authWith(Map<String, Object> attrs) {
        Authentication a = mock(Authentication.class);
        when(a.getAttributes()).thenReturn(attrs);
        return a;
    }

    @Test
    void firmCd_returnsStringValue() {
        assertEquals("5", AuthClaims.firmCd(authWith(Map.of("firmCd", 5))));
        assertEquals("42", AuthClaims.firmCd(authWith(Map.of("firmCd", "42"))));
    }

    @Test
    void firmCd_nullWhenAbsent() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("firmCd", null);
        assertNull(AuthClaims.firmCd(authWith(attrs)));
        assertNull(AuthClaims.firmCd(authWith(new HashMap<>())));
    }

    @Test
    void memberships_fromDelimitedString() {
        String packed = String.join(AuthClaims.MEMBERSHIPS_DELIM, "5:john", "7:johnny");
        List<String> m = AuthClaims.memberships(authWith(Map.of("memberships", packed)));
        assertEquals(List.of("5:john", "7:johnny"), m);
    }

    @Test
    void memberships_emptyStringIsEmptyList() {
        assertTrue(AuthClaims.memberships(authWith(Map.of("memberships", ""))).isEmpty());
    }

    @Test
    void memberships_fromRawListTolerated() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("memberships", Arrays.asList("5:john", null, "7:jane"));
        assertEquals(List.of("5:john", "7:jane"), AuthClaims.memberships(authWith(attrs)));
    }

    @Test
    void memberships_absentOrWrongTypeIsEmptyList() {
        assertTrue(AuthClaims.memberships(authWith(new HashMap<>())).isEmpty());
        assertTrue(AuthClaims.memberships(authWith(Map.of("memberships", 123))).isEmpty());
    }
}
