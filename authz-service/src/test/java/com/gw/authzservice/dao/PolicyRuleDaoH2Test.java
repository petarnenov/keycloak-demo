package com.gw.authzservice.dao;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2-backed tests for the PolicyRule decision SQL: capabilities (role join),
 * canDo (section + single object), refine (UUID-only intersect), fail-closed.
 */
@MicronautTest(environments = "test")
class PolicyRuleDaoH2Test {

    private static final String E1 = "019E1AE7C9D0738687919D666E4DDE4C"; // has rules
    private static final String E2 = "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"; // no rules
    private static final String INV_A = "0000000000000000000000000000000A";
    private static final String INV_B = "0000000000000000000000000000000B";
    private static final String INV_C = "0000000000000000000000000000000C"; // NOT granted to E1

    @Inject
    DataSource dataSource;

    @Inject
    PolicyRuleDao dao;

    @BeforeEach
    void schema() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String tbl : new String[]{
                    "ENTITY_ROLE_TBL", "ROLE_PERMISSION_TBL", "OBJECTTYPE_PERMISSION_TBL", "POLICY_RULE_TBL"}) {
                s.execute("DROP TABLE IF EXISTS " + tbl);
            }
            s.execute("CREATE TABLE ENTITY_ROLE_TBL (ENTITY_ID CHAR(32), ROLE_CD NUMBER(5))");
            s.execute("CREATE TABLE ROLE_PERMISSION_TBL (ROLE_CD NUMBER(5), OBJECTTYPE_PERMISSION_CD NUMBER(5))");
            s.execute("CREATE TABLE OBJECTTYPE_PERMISSION_TBL (OBJECTTYPE_PERMISSION_CD NUMBER(5), OBJECT_TYPE_CD NUMBER(4), PERMISSION_CD NUMBER(2))");
            s.execute("CREATE TABLE POLICY_RULE_TBL (POLICY_RULE_KEY CHAR(84), ENTITY_ID CHAR(32), PERMISSION_CD NUMBER(2), OBJECT_ID CHAR(32), OBJECT_TYPE_CD NUMBER(4), OBJECT_CD NUMBER(11), FIRM_CD NUMBER(8))");

            // E1 has role 100
            s.execute("INSERT INTO ENTITY_ROLE_TBL VALUES ('" + E1 + "', 100)");
            // role 100 grants objecttype-permission 500(59,5 EXECUTE), 501(9101,1 VIEW), 502(9101,2 MODIFY)
            s.execute("INSERT INTO ROLE_PERMISSION_TBL VALUES (100, 500)");
            s.execute("INSERT INTO ROLE_PERMISSION_TBL VALUES (100, 501)");
            s.execute("INSERT INTO ROLE_PERMISSION_TBL VALUES (100, 502)");
            s.execute("INSERT INTO OBJECTTYPE_PERMISSION_TBL VALUES (500, 59, 5)");
            s.execute("INSERT INTO OBJECTTYPE_PERMISSION_TBL VALUES (501, 9101, 1)");
            s.execute("INSERT INTO OBJECTTYPE_PERMISSION_TBL VALUES (502, 9101, 2)");
            // POLICY_RULE: section EXECUTE (null object) + two invoice VIEW rows (A,B not C)
            s.execute("INSERT INTO POLICY_RULE_TBL VALUES ('k1', '" + E1 + "', 5, NULL, 59, NULL, 1)");
            s.execute("INSERT INTO POLICY_RULE_TBL VALUES ('k2', '" + E1 + "', 1, '" + INV_A + "', 9101, NULL, 1)");
            s.execute("INSERT INTO POLICY_RULE_TBL VALUES ('k3', '" + E1 + "', 1, '" + INV_B + "', 9101, NULL, 1)");
        }
    }

    @Test
    void capabilities_roleLevelViewCreateExecuteOnly() {
        Map<String, Boolean> caps = dao.loadCreateExecutePermissions(E1);
        assertTrue(caps.getOrDefault("59_5", false), "BILLING_CENTER EXECUTE present");
        assertTrue(caps.getOrDefault("9101_1", false), "INVOICE VIEW present");
        assertFalse(caps.containsKey("9101_2"), "MODIFY excluded from capability map");
    }

    @Test
    void capabilities_emptyForUnknownEntity() {
        assertTrue(dao.loadCreateExecutePermissions(E2).isEmpty());
    }

    @Test
    void canDo_sectionGate_nullObjectId() {
        assertTrue(dao.canDo(E1, 59, 5, null), "section EXECUTE granted");
        assertFalse(dao.canDo(E1, 59, 1, null), "no VIEW rule on section");
        assertFalse(dao.canDo(E2, 59, 5, null), "unknown entity denied");
    }

    @Test
    void canDo_singleObject() {
        assertTrue(dao.canDo(E1, 9101, 1, INV_A), "invoice A granted");
        assertTrue(dao.canDo(E1, 9101, 1, "0000000000000000000000000000000a"), "case-insensitive match");
        assertFalse(dao.canDo(E1, 9101, 1, INV_C), "invoice C not granted");
        assertFalse(dao.canDo(E1, 9101, 1, "garbage"), "unparseable id denied");
    }

    @Test
    void refine_intersectsUuidOnly() {
        List<String> allowed = dao.refine(E1, 9101, 1,
                List.of(INV_A, "0000000000000000000000000000000b", INV_C, "not-a-uuid"));
        // A granted; b (lowercase B) granted and returned in ORIGINAL form; C not granted; garbage dropped
        assertEquals(2, allowed.size());
        assertTrue(allowed.contains(INV_A));
        assertTrue(allowed.contains("0000000000000000000000000000000b"));
        assertFalse(allowed.contains(INV_C));
    }

    @Test
    void refine_emptyCandidatesFailClosed() {
        assertTrue(dao.refine(E1, 9101, 1, List.of()).isEmpty());
        assertTrue(dao.refine(E2, 9101, 1, List.of(INV_A)).isEmpty());
    }
}
