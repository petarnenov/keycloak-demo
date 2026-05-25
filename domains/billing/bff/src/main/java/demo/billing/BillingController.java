package demo.billing;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stub billing endpoints. Returns deterministic fake data scoped (loosely)
 * to the authenticated subject so the FE has something realistic to render
 * without touching a database. The shape of the responses is what would
 * eventually come back from a real billing service; the values are mock.
 *
 * Authorization model (see sso-role-mapping.md):
 *   - read endpoints: any of billing-admin / billing-viewer / admin
 *   - write endpoints (not present in this stub): billing-admin / admin
 * `firmCd` is read from the JWT and echoed back so the FE / downstream can
 * verify the tenant scoping that any real service would enforce.
 */
@Controller("/api")
@Produces(MediaType.APPLICATION_JSON)
public class BillingController {

    private final String source;
    private final P1AuthzClient authz;

    public BillingController(@Value("${app.source:billing-bff}") String source, P1AuthzClient authz) {
        this.source = source;
        this.authz = authz;
    }

    @Get("/summary")
    @Secured({"billing-admin", "billing-viewer", "admin"})   // tier 1: can this user reach billing
    public Map<String, Object> summary(HttpRequest<?> request, Authentication authentication) {
        // tier 2: can this user VIEW invoices specifically (opt-in; see P1AuthzClient)
        requirePermission(request, authentication, DemoAuthz.INVOICE, DemoAuthz.PERM_VIEW);
        Map<String, Object> body = new HashMap<>();
        body.put("source", source);
        body.put("username", authentication.getName());
        body.put("firmCd", firmCd(authentication));
        body.put("accountId", "ACCT-90217");
        body.put("plan", "Professional");
        body.put("planRenewsOn", LocalDate.now().plusDays(18).toString());
        body.put("currency", "USD");
        body.put("currentBalance", 1284.50);
        body.put("nextInvoiceAmount", 499.00);
        body.put("nextInvoiceDate", LocalDate.now().plusDays(18).toString());
        body.put("paymentMethod", Map.of(
                "brand", "Visa",
                "last4", "4242",
                "expires", "11/2028"
        ));
        return body;
    }

    @Get("/invoices")
    @Secured({"billing-admin", "billing-viewer", "admin"})   // tier 1
    public Map<String, Object> invoices(HttpRequest<?> request, Authentication authentication) {
        requirePermission(request, authentication, DemoAuthz.INVOICE, DemoAuthz.PERM_VIEW);   // tier 2

        List<Map<String, Object>> invoices = new ArrayList<>();
        invoices.add(invoice("INV-2026-005", LocalDate.now().minusDays(2),  499.00, "open"));
        invoices.add(invoice("INV-2026-004", LocalDate.now().minusDays(31), 499.00, "paid"));
        invoices.add(invoice("INV-2026-003", LocalDate.now().minusDays(61), 499.00, "paid"));
        invoices.add(invoice("INV-2026-002", LocalDate.now().minusDays(92), 449.00, "paid"));
        invoices.add(invoice("INV-2026-001", LocalDate.now().minusDays(120), 449.00, "paid"));

        // tier 3 (lists): refine the page to the invoices this user may VIEW — the
        // loadCustomerViewableAccounts pattern (one /refine call, P1 intersects).
        invoices = refineByObjectAccess(request, authentication, invoices, DemoAuthz.INVOICE, DemoAuthz.PERM_VIEW);

        Map<String, Object> body = new HashMap<>();
        body.put("source", source);
        body.put("username", authentication.getName());
        body.put("firmCd", firmCd(authentication));
        body.put("invoices", invoices);
        return body;
    }

    @Get("/usage")
    @Secured({"billing-admin", "billing-viewer", "admin"})
    public Map<String, Object> usage(Authentication authentication) {
        List<Map<String, Object>> lines = new ArrayList<>();
        lines.add(Map.of("metric", "API requests",    "included", 100_000, "used", 42_318, "unit", "calls"));
        lines.add(Map.of("metric", "Active seats",    "included", 25,      "used", 17,     "unit", "users"));
        lines.add(Map.of("metric", "Stored documents","included", 5_000,   "used", 1_240,  "unit", "files"));
        lines.add(Map.of("metric", "Email sends",     "included", 10_000,  "used", 3_104,  "unit", "emails"));

        Map<String, Object> body = new HashMap<>();
        body.put("source", source);
        body.put("username", authentication.getName());
        body.put("firmCd", firmCd(authentication));
        body.put("periodStart", LocalDate.now().withDayOfMonth(1).toString());
        body.put("periodEnd",   LocalDate.now().withDayOfMonth(1).plusMonths(1).minusDays(1).toString());
        body.put("lines", lines);
        return body;
    }

    private static String firmCd(Authentication authentication) {
        Object v = authentication.getAttributes().get("firmCd");
        return v == null ? null : v.toString();
    }

    /** Tier 2 gate: 403 unless the user holds (objectType, permission) in P1. No-op when fine checks are off or gw-superadmin. */
    private void requirePermission(HttpRequest<?> request, Authentication authentication, int objectType, int permission) {
        if (!authz.fineEnabled() || isGwSuperadmin(authentication)) {
            return; // opt-in; coarse @Secured already applied; gw-superadmin overrides (gwAdmin || canX)
        }
        String bearer = bearer(request);
        if (bearer == null || !authz.hasPermission(bearer, sub(authentication), objectType, permission)) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "fine permission denied");
        }
    }

    /** Tier 3 list gate: keep only the items the user may act on, via P1's refine. No-op when off or gw-superadmin. */
    private List<Map<String, Object>> refineByObjectAccess(HttpRequest<?> request, Authentication authentication,
                                                           List<Map<String, Object>> items,
                                                           int objectType, int permission) {
        if (!authz.fineEnabled() || isGwSuperadmin(authentication)) {
            return items; // gw-superadmin sees every row (gwAdmin || canX)
        }
        String bearer = bearer(request);
        if (bearer == null) {
            return List.of(); // fail closed
        }
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> it : items) {
            Object id = it.get("number");
            if (id != null) {
                ids.add(id.toString());
            }
        }
        java.util.Set<String> allowed = new java.util.HashSet<>(authz.refine(bearer, objectType, permission, ids));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> it : items) {
            Object id = it.get("number");
            if (id != null && allowed.contains(id.toString())) {
                out.add(it);
            }
        }
        return out;
    }

    private static String bearer(HttpRequest<?> request) {
        return request.getHeaders().get(HttpHeaders.AUTHORIZATION);
    }

    private static String sub(Authentication authentication) {
        Object v = authentication.getAttributes().get("sub");
        return v == null ? authentication.getName() : v.toString();
    }

    /** Global cross-firm override carried as the gw-superadmin realm role (gwAdminFlag). */
    private static boolean isGwSuperadmin(Authentication authentication) {
        return authentication.getRoles().contains("gw-superadmin");
    }

    private static Map<String, Object> invoice(String number, LocalDate issued, double amount, String status) {
        Map<String, Object> inv = new HashMap<>();
        inv.put("number", number);
        inv.put("issuedOn", issued.toString());
        inv.put("dueOn", issued.plusDays(14).toString());
        inv.put("amount", amount);
        inv.put("currency", "USD");
        inv.put("status", status);
        return inv;
    }
}
