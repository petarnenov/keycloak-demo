package demo.billing;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
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
 */
@Controller("/api")
@Produces(MediaType.APPLICATION_JSON)
public class BillingController {

    private final String source;

    public BillingController(@Value("${app.source:billing-bff}") String source) {
        this.source = source;
    }

    @Get("/summary")
    @Secured({"isAuthenticated()"})
    public Map<String, Object> summary(Authentication authentication) {
        Map<String, Object> body = new HashMap<>();
        body.put("source", source);
        body.put("username", authentication.getName());
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
    @Secured({"isAuthenticated()"})
    public Map<String, Object> invoices(Authentication authentication) {
        List<Map<String, Object>> invoices = new ArrayList<>();
        invoices.add(invoice("INV-2026-005", LocalDate.now().minusDays(2),  499.00, "open"));
        invoices.add(invoice("INV-2026-004", LocalDate.now().minusDays(31), 499.00, "paid"));
        invoices.add(invoice("INV-2026-003", LocalDate.now().minusDays(61), 499.00, "paid"));
        invoices.add(invoice("INV-2026-002", LocalDate.now().minusDays(92), 449.00, "paid"));
        invoices.add(invoice("INV-2026-001", LocalDate.now().minusDays(120), 449.00, "paid"));

        Map<String, Object> body = new HashMap<>();
        body.put("source", source);
        body.put("username", authentication.getName());
        body.put("invoices", invoices);
        return body;
    }

    @Get("/usage")
    @Secured({"isAuthenticated()"})
    public Map<String, Object> usage(Authentication authentication) {
        List<Map<String, Object>> lines = new ArrayList<>();
        lines.add(Map.of("metric", "API requests",    "included", 100_000, "used", 42_318, "unit", "calls"));
        lines.add(Map.of("metric", "Active seats",    "included", 25,      "used", 17,     "unit", "users"));
        lines.add(Map.of("metric", "Stored documents","included", 5_000,   "used", 1_240,  "unit", "files"));
        lines.add(Map.of("metric", "Email sends",     "included", 10_000,  "used", 3_104,  "unit", "emails"));

        Map<String, Object> body = new HashMap<>();
        body.put("source", source);
        body.put("username", authentication.getName());
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
