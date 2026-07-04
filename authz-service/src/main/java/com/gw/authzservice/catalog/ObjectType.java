package com.gw.authzservice.catalog;

import java.util.Set;

/**
 * ObjectType catalog — the demo-relevant subset of GeoWealth master
 * {@code com.geowealth.model.authorisation.ObjectType}, plus the demo-only
 * REAL_OBJECT codes the sample domains need. Authoritative copy (alignment D5);
 * {@code bff-core} mirrors the constants for compile-time call sites.
 *
 * <p>Master partitions object types into REAL_OBJECTS (concrete rows keyed by a
 * UUID {@code object_id}) and WEB_SECTION_OBJECTS (UI sections gated by EXECUTE
 * with a null object). The demo keeps the same split so the three check kinds
 * map faithfully (alignment §4.2/§4.3).</p>
 */
public final class ObjectType {

    private ObjectType() {
    }

    // --- master codes (com.geowealth.model.authorisation.ObjectType) ---
    public static final int ACCOUNT = 3;               // REAL
    public static final int CLIENT = 4;                // REAL
    public static final int TRADE = 5;                 // WEB_SECTION gate for the trading domain
    public static final int BILLING_CENTER = 59;       // WEB_SECTION gate for billing/portfolio/custodian

    // --- demo-only REAL_OBJECTS (master has no invoice/order object type) ---
    /** Demo REAL_OBJECT: billing invoices (row-level refine target). */
    public static final int INVOICE = 9101;
    /** Demo REAL_OBJECT: trading orders (row-level refine target). */
    public static final int ORDER = 9201;

    /**
     * Concrete data rows keyed by a UUID {@code object_id}. {@code refineUUIDs}
     * and single-object {@code can(objectId)} operate on these.
     */
    public static final Set<Integer> REAL_OBJECTS = Set.of(ACCOUNT, CLIENT, INVOICE, ORDER);

    /**
     * UI sections/features gated by EXECUTE with a null object id — the section
     * capability check ({@code loadPolicyRules(user, SECTION, EXECUTE)} non-empty).
     */
    public static final Set<Integer> WEB_SECTION_OBJECTS = Set.of(TRADE, BILLING_CENTER);

    public static boolean isRealObject(int objectTypeCd) {
        return REAL_OBJECTS.contains(objectTypeCd);
    }

    public static boolean isWebSection(int objectTypeCd) {
        return WEB_SECTION_OBJECTS.contains(objectTypeCd);
    }
}
