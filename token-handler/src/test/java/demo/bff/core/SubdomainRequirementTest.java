package demo.bff.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubdomainRequirementTest {

    @Test
    void defaults_areNullAndUntyped() {
        SubdomainRequirement r = new SubdomainRequirement();
        assertNull(r.getType());
        assertNull(r.getFirmCd());
        assertNull(r.getObjectType());
        assertNull(r.getPermission());
        assertFalse(r.isFirmType());
        assertFalse(r.isResourceType());
    }

    @Test
    void firmType_isCaseInsensitive() {
        SubdomainRequirement r = new SubdomainRequirement();
        r.setType("FIRM");
        r.setFirmCd(5);
        assertTrue(r.isFirmType());
        assertFalse(r.isResourceType());
        assertEquals(5, r.getFirmCd());
    }

    @Test
    void resourceType_carriesObjectTypeAndPermission() {
        SubdomainRequirement r = new SubdomainRequirement();
        r.setType("resource");
        r.setObjectType(12);
        r.setPermission(1);
        assertTrue(r.isResourceType());
        assertFalse(r.isFirmType());
        assertEquals(12, r.getObjectType());
        assertEquals(1, r.getPermission());
    }
}
