package com.gw.authzservice.dao;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure unit tests for the two tricky helpers that must be exactly right for the
 * decision path to resolve the correct entity and intersect the correct ids.
 */
class PolicyRuleDaoStaticTest {

    @Test
    void entityIdFromSub_stripsStorageIdPrefix() {
        // User Storage SPI: sub = "f:<componentId>:<entityId>"
        assertEquals("019E1AE7C9D0738687919D666E4DDE4C",
                PolicyRuleDao.entityIdFromSub("f:1a2b3c4d-5e6f-7788-99aa-bbccddeeff00:019E1AE7C9D0738687919D666E4DDE4C"));
    }

    @Test
    void entityIdFromSub_passesThroughNonFederated() {
        assertEquals("plain-sub", PolicyRuleDao.entityIdFromSub("plain-sub"));
    }

    @Test
    void entityIdFromSub_nullAndBlank() {
        assertNull(PolicyRuleDao.entityIdFromSub(null));
        assertNull(PolicyRuleDao.entityIdFromSub("   "));
    }

    @Test
    void normalizeUuid_stripsDashesUppercases() {
        assertEquals("019E1AE7C9D0738687919D666E4DDE4C",
                PolicyRuleDao.normalizeUuid("019e1ae7-c9d0-7386-8791-9d666e4dde4c"));
        assertEquals("019E1AE7C9D0738687919D666E4DDE4C",
                PolicyRuleDao.normalizeUuid("  019E1AE7C9D0738687919D666E4DDE4C  "));
    }

    @Test
    void normalizeUuid_rejectsNonHexAndWrongLength() {
        assertNull(PolicyRuleDao.normalizeUuid("not-a-uuid"));
        assertNull(PolicyRuleDao.normalizeUuid("ABC"));
        assertNull(PolicyRuleDao.normalizeUuid("019E1AE7C9D0738687919D666E4DDE4G")); // G is not hex
        assertNull(PolicyRuleDao.normalizeUuid(null));
    }
}
