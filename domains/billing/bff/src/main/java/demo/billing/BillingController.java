package demo.billing;

import demo.bff.core.AuthClaims;
import demo.bff.core.HeaderIdentity;
import demo.bff.core.PolicyRuleGate;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
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
 *   - Tier 1 (coarse role): the {@code @Secured} lists below.
 *   - Tier 2/3 (fine, opt-in): {@link PolicyRuleGate}, keyed by this domain's
 *     {@link DemoAuthz} ObjectType codes.
 * `firmCd` is read from the JWT and echoed back so the FE / downstream can
 * verify the tenant scoping that any real service would enforce.
 */
@Controller("/api")
@Produces(MediaType.APPLICATION_JSON)
public class BillingController {

    private final String source;
    private final PolicyRuleGate gate;

    public BillingController(@Value("${app.source:billing-bff}") String source, PolicyRuleGate gate) {
        this.source = source;
        this.gate = gate;
    }

    @Get("/summary")
    public Map<String, Object> summary(HttpRequest<?> request) {
        // Auth-unaware: identity comes from the X-Auth-* headers the Token Handler's
        // /auth/verify emitted and nginx injected (forward-auth). Coarse + subdomain
        // authz already happened in /auth/verify; this is the per-endpoint Tier 2.
        Authentication authentication = HeaderIdentity.from(request);
        gate.requireCapability(authentication, DemoAuthz.INVOICE, DemoAuthz.PERM_VIEW);   // tier 2: can this user VIEW invoices specifically
        Map<String, Object> body = new HashMap<>();
        body.put("source", source);
        body.put("username", authentication.getName());
        body.put("firmCd", AuthClaims.firmCd(authentication));
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
    public Map<String, Object> invoices(HttpRequest<?> request) {
        Authentication authentication = HeaderIdentity.from(request);
        gate.requireCapability(authentication, DemoAuthz.INVOICE, DemoAuthz.PERM_VIEW);   // tier 2

        List<Map<String, Object>> invoices = new ArrayList<>();
        invoices.add(invoice("INV-2026-005", LocalDate.now().minusDays(2),  499.00, "open"));
        invoices.add(invoice("INV-2026-004", LocalDate.now().minusDays(31), 499.00, "paid"));
        invoices.add(invoice("INV-2026-003", LocalDate.now().minusDays(61), 499.00, "paid"));
        invoices.add(invoice("INV-2026-002", LocalDate.now().minusDays(92), 449.00, "paid"));
        invoices.add(invoice("INV-2026-001", LocalDate.now().minusDays(120), 449.00, "paid"));

        // tier 3 (lists): refine the page to the invoices this user may VIEW — P1's
        // refine pattern (one call, P1 intersects). Rows are keyed by "number".
        invoices = gate.refineUUIDs(authentication, invoices,
                inv -> { Object id = inv.get("number"); return id == null ? null : id.toString(); },
                DemoAuthz.INVOICE, DemoAuthz.PERM_VIEW);

        Map<String, Object> body = new HashMap<>();
        body.put("source", source);
        body.put("username", authentication.getName());
        body.put("firmCd", AuthClaims.firmCd(authentication));
        body.put("invoices", invoices);
        return body;
    }

    @Get("/usage")
    public Map<String, Object> usage(HttpRequest<?> request) {
        Authentication authentication = HeaderIdentity.from(request);
        List<Map<String, Object>> lines = new ArrayList<>();
        lines.add(Map.of("metric", "API requests",    "included", 100_000, "used", 42_318, "unit", "calls"));
        lines.add(Map.of("metric", "Active seats",    "included", 25,      "used", 17,     "unit", "users"));
        lines.add(Map.of("metric", "Stored documents","included", 5_000,   "used", 1_240,  "unit", "files"));
        lines.add(Map.of("metric", "Email sends",     "included", 10_000,  "used", 3_104,  "unit", "emails"));

        Map<String, Object> body = new HashMap<>();
        body.put("source", source);
        body.put("username", authentication.getName());
        body.put("firmCd", AuthClaims.firmCd(authentication));
        body.put("periodStart", LocalDate.now().withDayOfMonth(1).toString());
        body.put("periodEnd",   LocalDate.now().withDayOfMonth(1).plusMonths(1).minusDays(1).toString());
        body.put("lines", lines);
        return body;
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
