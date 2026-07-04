package demo.trading;

import demo.bff.core.AuthClaims;
import demo.bff.core.HeaderIdentity;
import demo.bff.core.PolicyRuleGate;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.context.annotation.Value;
import io.micronaut.security.authentication.Authentication;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stub trading endpoints. Returns deterministic fake data so the FE has
 * a realistic dashboard to render without a real market data provider.
 * Shape mirrors what a real trading service would expose; numbers are
 * mock and pinned per-call.
 *
 * Authorization model (see sso-role-mapping.md):
 *   - Tier 1 (coarse role): the {@code @Secured} lists below.
 *   - PolicyRule capability/refine (fine, opt-in): {@link PolicyRuleGate}, keyed by this domain's
 *     {@link DemoAuthz} ObjectType codes.
 * `firmCd` is read from the JWT and echoed back so the FE / downstream can
 * verify the tenant scoping that any real service would enforce.
 */
@Controller("/api")
@Produces(MediaType.APPLICATION_JSON)
public class TradingController {

    private final String source;
    private final PolicyRuleGate gate;

    public TradingController(@Value("${app.source:trading-bff}") String source, PolicyRuleGate gate) {
        this.source = source;
        this.gate = gate;
    }

    @Get("/portfolio")
    public Map<String, Object> portfolio(HttpRequest<?> request) {
        // Auth-unaware: identity from the X-Auth-* headers (forward-auth). The
        // Token Handler's /auth/verify already did session + coarse + subdomain authz.
        Authentication authentication = HeaderIdentity.from(request);
        Map<String, Object> body = new HashMap<>();
        body.put("source", source);
        body.put("username", authentication.getName());
        body.put("firmCd", AuthClaims.firmCd(authentication));
        body.put("accountId", "TRD-44219");
        body.put("currency", "USD");
        body.put("marketValue",   1_247_812.55);
        body.put("cashAvailable",    82_104.10);
        body.put("dayPnl",            3_421.18);
        body.put("dayPnlPct",            0.27);
        body.put("ytdPnl",          184_902.40);
        body.put("ytdPnlPct",            17.40);
        body.put("asOf", LocalDate.now().toString() + "T" + LocalTime.of(16, 0).toString() + "Z");
        return body;
    }

    @Get("/positions")
    public Map<String, Object> positions(HttpRequest<?> request) {
        Authentication authentication = HeaderIdentity.from(request);
        List<Map<String, Object>> positions = new ArrayList<>();
        positions.add(position("AAPL", "Apple Inc.",          240, 198.42, 212.85));
        positions.add(position("MSFT", "Microsoft Corp.",     180, 412.10, 438.20));
        positions.add(position("NVDA", "NVIDIA Corp.",        160, 612.50, 728.40));
        positions.add(position("AMZN", "Amazon.com Inc.",     100, 183.20, 195.75));
        positions.add(position("GOOG", "Alphabet Inc.",       120, 165.40, 172.10));
        positions.add(position("BRK.B","Berkshire Hathaway B", 80, 412.55, 419.20));
        positions.add(position("VOO",  "Vanguard S&P 500 ETF",300, 478.90, 502.30));

        Map<String, Object> body = new HashMap<>();
        body.put("source", source);
        body.put("username", authentication.getName());
        body.put("firmCd", AuthClaims.firmCd(authentication));
        body.put("positions", positions);
        return body;
    }

    @Get("/orders")
    public Map<String, Object> orders(HttpRequest<?> request) {
        Authentication authentication = HeaderIdentity.from(request);

        List<Map<String, Object>> orders = new ArrayList<>();
        orders.add(order("ORD-91204", "AAPL", "buy",  100, "limit", 211.50, "filled",  LocalDate.now()));
        orders.add(order("ORD-91205", "VOO",  "buy",   50, "market",   null, "filled",  LocalDate.now()));
        orders.add(order("ORD-91206", "NVDA", "sell",  20, "limit", 730.00, "working", LocalDate.now()));
        orders.add(order("ORD-91203", "MSFT", "buy",   40, "limit", 432.10, "filled",  LocalDate.now().minusDays(1)));
        orders.add(order("ORD-91202", "AMZN", "sell",  25, "market",   null, "filled",  LocalDate.now().minusDays(1)));
        orders.add(order("ORD-91198", "GOOG", "buy",   60, "limit", 168.00, "cancelled", LocalDate.now().minusDays(3)));

        // list refine: refine to the orders this user may EXECUTE — P1's refine
        // pattern. Rows are keyed by "id".
        orders = gate.refineUUIDs(authentication, orders,
                o -> { Object id = o.get("id"); return id == null ? null : id.toString(); },
                DemoAuthz.ORDER, DemoAuthz.PERM_EXECUTE);

        Map<String, Object> body = new HashMap<>();
        body.put("source", source);
        body.put("username", authentication.getName());
        body.put("firmCd", AuthClaims.firmCd(authentication));
        body.put("orders", orders);
        return body;
    }

    private static Map<String, Object> position(String symbol, String name, int qty, double avgCost, double last) {
        double marketValue = qty * last;
        double unrealized  = qty * (last - avgCost);
        double pctChange   = ((last - avgCost) / avgCost) * 100.0;
        Map<String, Object> p = new HashMap<>();
        p.put("symbol", symbol);
        p.put("name", name);
        p.put("quantity", qty);
        p.put("avgCost", avgCost);
        p.put("lastPrice", last);
        p.put("marketValue", round2(marketValue));
        p.put("unrealized", round2(unrealized));
        p.put("changePct", round2(pctChange));
        return p;
    }

    private static Map<String, Object> order(String id, String symbol, String side, int qty,
                                             String type, Double limit, String status, LocalDate placedOn) {
        Map<String, Object> o = new HashMap<>();
        o.put("id", id);
        o.put("symbol", symbol);
        o.put("side", side);
        o.put("quantity", qty);
        o.put("type", type);
        o.put("limitPrice", limit);
        o.put("status", status);
        o.put("placedOn", placedOn.toString());
        return o;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
