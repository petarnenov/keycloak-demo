package demo.custodian;

/**
 * Demo object-type / permission codes for the custodian domain's PolicyRule capability/refine checks.
 *
 * <p>Permission codes mirror P1's {@code Permission} enum
 * ({@code VIEW=1, MODIFY=2, CREATE=3, DELETE=4, EXECUTE=5}). The {@code INVOICE}
 * ObjectType code is a <b>demo placeholder</b> — a real domain maps its resources
 * to the firm-agnostic P1 {@code ObjectType} codes. The keys the BFF builds
 * ({@code "<objectType>_<permission>"}) are exactly P1's wire convention
 * (p1-auth-flow.md §1.7b), so no translation is needed at the gate.</p>
 */
final class DemoAuthz {

    private DemoAuthz() {
    }

    /** Demo ObjectType for custodian invoices (placeholder; real domains use real P1 codes). */
    static final int INVOICE = 9101;

    static final int PERM_VIEW = 1;
    static final int PERM_MODIFY = 2;
}
