package com.gw.keycloak.userstorage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniJsonTest {

    @Test
    void parsesFlatObject() {
        Map<String, Object> m = MiniJson.parseObject("{\"a\":1,\"b\":\"hello\",\"c\":true,\"d\":null}");
        assertEquals(1, m.get("a"));
        assertEquals("hello", m.get("b"));
        assertEquals(Boolean.TRUE, m.get("c"));
        assertNull(m.get("d"));
    }

    @Test
    void parsesNestedListAndObject() {
        Map<String, Object> m = MiniJson.parseObject("{\"xs\":[1,2,3],\"nested\":{\"k\":\"v\"}}");
        assertEquals(List.of(1, 2, 3), m.get("xs"));
        Map<String, Object> nested = (Map<String, Object>) m.get("nested");
        assertEquals("v", nested.get("k"));
    }

    @Test
    void parsesStringArray() {
        List<String> arr = MiniJson.parseStringArray("[\"a\",\"b\",\"c\"]");
        assertEquals(List.of("a", "b", "c"), arr);
    }

    @Test
    void handlesEscapes() {
        Map<String, Object> m = MiniJson.parseObject("{\"s\":\"line\\nbreak\\ttab\\\"quote\"}");
        assertEquals("line\nbreak\ttab\"quote", m.get("s"));
    }

    @Test
    void parsesMembershipsShape() {
        Map<String, Object> m = MiniJson.parseObject(
                "{\"ldapUid\":\"tim1\",\"firmCd\":1,\"memberships\":[\"1:tim1\",\"2:tim1\"],\"gwAdmin\":false}");
        assertEquals("tim1", m.get("ldapUid"));
        assertEquals(1, m.get("firmCd"));
        assertTrue(m.get("memberships") instanceof List<?>);
        assertEquals(2, ((List<?>) m.get("memberships")).size());
    }
}
