package demo.users;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.serde.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Users / Firm Admin endpoints. Replicates the wire contract the
 * GeoWealth P1 UI consumes from
 * {@code FirmAdmin/UsersAndAccess/services/usersServices.js} (with
 * {@code .do} dropped and grouped under {@code /api}), but the reads
 * and writes are real — they forward to the P1 monolith via
 * {@link P1Client}, so a user created here shows up in P1's
 * Operations &rarr; Firm Admin &rarr; Users grid (and vice versa).
 *
 * <p>BFF-side {@code @Secured} acts as a coarse role gate
 * (defence in depth); P1 reapplies the firm-scope rule
 * ({@code isAllowedFor} in {@code BffUsersAction}) so a token
 * forged with extra roles still cannot reach another firm.</p>
 *
 * <p>BFF / Token Handler model: the SPA carries no token — it talks to
 * the BFF over the {@code USESSION} httpOnly cookie. The controller takes
 * the user's access token from the server-side session
 * ({@link Authentication#getAttributes()}) and forwards it to P1, so
 * authority stays user-bound (§2.6).</p>
 *
 * <p>Bulk-CSV upload stays BFF-local: the CSV is parsed here and
 * each parsed row is created via {@link P1Client#createUpdate} one
 * by one. Keeps the P1 surface narrow (no multipart-CSV ingestion
 * to add to {@code BffUsersAction}).</p>
 */
@Controller("/api")
@Produces(MediaType.APPLICATION_JSON)
@ExecuteOn(TaskExecutors.BLOCKING)
public class UsersController {

    private final P1Client p1;
    private final ObjectMapper json;
    private final String source;

    public UsersController(P1Client p1, ObjectMapper json, @Value("${app.source:users-bff}") String source) {
        this.p1 = p1;
        this.json = json;
        this.source = source;
    }

    // ---- READS ---------------------------------------------------------------

    /** Mirrors {@code /platformOne/getUsers.do?firmCd=N}. */
    @Get("/getUsers")
    @Secured({"users-admin", "users-viewer", "admin", "gwAdmin"})
    public Map<String, Object> getUsers(@QueryValue("firmCd") int firmCd,
                                        Authentication authentication) {
        enforceFirmScope(authentication, firmCd);
        Map<String, Object> upstream = p1.list(firmCd, bearer(authentication));

        // upstream is the GsonUtil-serialised RowsJTO from
        // UserManager.getActiveUsers — re-export under the
        // {rows, firmSSO, source} envelope the existing SPA expects.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", upstream.getOrDefault("rows", List.of()));
        body.put("firmSSO", Map.of("enforcementType", "OPTIONAL"));
        body.put("source", source);
        return body;
    }

    /** Mirrors {@code /platformOne/getEmployeeById.do?userId=…}. */
    @Get("/getEmployeeById")
    @Secured({"users-admin", "users-viewer", "admin", "gwAdmin"})
    public Map<String, Object> getEmployeeById(@QueryValue("userId") String userId,
                                               Authentication authentication) {
        Map<String, Object> u = p1.getById(userId, bearer(authentication));
        // P1 has already checked firm-scope, but apply defence-in-depth here too.
        Object firmCdObj = u.get("firmCd");
        if (firmCdObj instanceof Number n) {
            enforceFirmScope(authentication, n.intValue());
        }
        return u;
    }

    /** Mirrors {@code /platformOne/getManageUsersDropdownsByFirm.do?firmCd=N}. */
    @Get("/getManageUsersDropdownsByFirm")
    @Secured({"users-admin", "users-viewer", "admin", "gwAdmin"})
    public Map<String, Object> getDropdowns(@QueryValue("firmCd") int firmCd,
                                            Authentication authentication) {
        enforceFirmScope(authentication, firmCd);
        return p1.dropdowns(firmCd, bearer(authentication));
    }

    /**
     * Returns the firms the caller is allowed to administer — drives the
     * firm picker.
     *
     * <p>Previously a hardcoded list ("Demo Firm" / "Atlas Capital" /
     * "Northwind Advisors") that didn't match what was actually in
     * {@code FIRM_TBL}: firm 2 didn't exist at all, firm 3's real name was
     * "CF Inc", and switching the picker to a non-existent firm broke
     * downstream calls. Now forwards to P1's {@code bff-users.do?op=firms},
     * which returns active firms straight out of the DB; gwAdmin from
     * firm 1 still sees all of them, everyone else only their own.</p>
     */
    @Get("/firms")
    @Secured({"users-admin", "users-viewer", "admin", "gwAdmin"})
    public Map<String, Object> firms(Authentication authentication) {
        return p1.firms(bearer(authentication));
    }

    // ---- WRITES --------------------------------------------------------------

    /**
     * Mirrors {@code /platformOne/createUpdateUser.do}.
     *
     * <p>Two shapes are accepted: the legacy multipart {@code q=&lt;JSON&gt;} that
     * the P1 SPA still posts, and a plain {@code application/json} body. Both
     * are forwarded to P1 as JSON.</p>
     */
    @Post(value = "/createUpdateUser", consumes = MediaType.MULTIPART_FORM_DATA)
    @Secured({"users-admin", "admin", "gwAdmin"})
    public Map<String, Object> createUpdateUserMultipart(@Part("q") String q,
                                                         Authentication authentication) {
        Map<String, Object> incoming = readJson(q);
        return doCreateUpdate(incoming, authentication);
    }

    @Post(value = "/createUpdateUser", consumes = MediaType.APPLICATION_JSON)
    @Secured({"users-admin", "admin", "gwAdmin"})
    public Map<String, Object> createUpdateUserJson(@Body Map<String, Object> body,
                                                    Authentication authentication) {
        return doCreateUpdate(body, authentication);
    }

    private Map<String, Object> doCreateUpdate(Map<String, Object> incoming,
                                               Authentication authentication) {
        if (incoming == null || incoming.isEmpty()) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "missing payload");
        }
        Number firmCdNum = (Number) incoming.get("firmCd");
        if (firmCdNum == null) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "firmCd is required");
        }
        enforceFirmScope(authentication, firmCdNum.intValue());
        Map<String, Object> p1Response = p1.createUpdate(incoming, bearer(authentication));

        // P1 returns {status, userId}; preserve and add the wire field the SPA expects.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", p1Response.getOrDefault("status", "ok"));
        body.put("userId", p1Response.get("userId"));
        body.put("user", incoming);
        return body;
    }

    /** Mirrors {@code /platformOne/redoPolicyRules.do?userId=…}. Demo no-op (no P1 hop for it yet). */
    @Post("/redoPolicyRules")
    @Secured({"users-admin", "admin", "gwAdmin"})
    public Map<String, Object> redoPolicyRules(@QueryValue("userId") String userId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("userId", userId);
        body.put("message", "policy rules refresh not wired to P1 yet (demo no-op)");
        return body;
    }

    /**
     * Mirrors {@code /platformOne/uploadBulkEmployeesFile.do?firmCd=N} — parses
     * the CSV and returns the same {@code results: [{ genericUserJTO, errors }]}
     * envelope the real endpoint returns. Parsing only — no P1 hop. The FE
     * collects validated rows and POSTs them to {@code bulkCreateEmployees}.
     */
    @Post(value = "/uploadBulkEmployeesFile", consumes = MediaType.MULTIPART_FORM_DATA)
    @Secured({"users-admin", "admin", "gwAdmin"})
    public Map<String, Object> uploadBulkUsersFile(@QueryValue("firmCd") int firmCd,
                                                   @Part("bulkCreateEmployeesFile") CompletedFileUpload file,
                                                   Authentication authentication) throws IOException {
        enforceFirmScope(authentication, firmCd);

        String csv = new String(file.getBytes(), StandardCharsets.UTF_8);
        List<Map<String, Object>> rows = parseCsv(csv, firmCd);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("results", rows);
        return body;
    }

    /** Mirrors {@code /platformOne/bulkCreateEmployees.do} — POSTs each row to P1 in turn. */
    @Post(value = "/bulkCreateEmployees", consumes = MediaType.MULTIPART_FORM_DATA)
    @Secured({"users-admin", "admin", "gwAdmin"})
    @SuppressWarnings("unchecked")
    public Map<String, Object> bulkCreateUsers(@Part("q") String q,
                                               Authentication authentication) {
        Object parsed = readJsonAny(q);
        List<Map<String, Object>> users;
        if (parsed instanceof List<?>) {
            users = (List<Map<String, Object>>) parsed;
        } else {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "expected JSON array");
        }

        String bearer = bearer(authentication);
        List<Map<String, Object>> failed = new ArrayList<>();
        int created = 0;
        for (Map<String, Object> u : users) {
            Number firmCdNum = (Number) u.get("firmCd");
            if (firmCdNum == null) {
                failed.add(failure(u, "firmCd", "missing firmCd"));
                continue;
            }
            try {
                enforceFirmScope(authentication, firmCdNum.intValue());
            } catch (HttpStatusException denied) {
                failed.add(failure(u, "firmCd", "not allowed for this firm"));
                continue;
            }
            String username = (String) u.get("username");
            if (username == null || username.isBlank()) {
                failed.add(failure(u, "username", "username is required"));
                continue;
            }
            try {
                p1.createUpdate(u, bearer);
                created++;
            } catch (HttpStatusException p1err) {
                failed.add(failure(u, "p1", p1err.getMessage()));
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("failedRecords", failed);
        body.put("createdCount", created);
        return body;
    }

    // ---- helpers -------------------------------------------------------------

    /**
     * The user's own access token from the server-side session (Token Handler
     * model). Forwarded to P1 so authority stays user-bound (§2.6) — never an
     * inbound Authorization header (the SPA doesn't carry one in this model).
     */
    private static String bearer(Authentication authentication) {
        Object token = authentication.getAttributes().get("accessToken");
        if (token == null) {
            throw new HttpStatusException(HttpStatus.UNAUTHORIZED, "missing access token in session");
        }
        return token.toString();
    }

    /** gwAdmin or admin can cross firms; everyone else is pinned to their own firmCd. */
    private void enforceFirmScope(Authentication authentication, int requestedFirmCd) {
        if (isGwAdmin(authentication)) return;
        Integer own = firmCdInt(authentication);
        if (own != null && own == requestedFirmCd) return;
        throw new HttpStatusException(HttpStatus.FORBIDDEN, "not allowed for firmCd=" + requestedFirmCd);
    }

    private static Integer firmCdInt(Authentication authentication) {
        Object v = authentication.getAttributes().get("firmCd");
        if (v == null) return null;
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    private static boolean isGwAdmin(Authentication authentication) {
        return authentication.getRoles().contains("gwAdmin");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String q) {
        Object parsed = readJsonAny(q);
        if (parsed instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new HttpStatusException(HttpStatus.BAD_REQUEST, "expected JSON object");
    }

    private Object readJsonAny(String q) {
        try {
            return json.readValue(q, Object.class);
        } catch (IOException e) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "invalid JSON: " + e.getMessage());
        }
    }

    private static Map<String, Object> failure(Map<String, Object> u, String field, String error) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("genericUserJTO", u);
        List<Map<String, Object>> errors = new ArrayList<>();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("field", field);
        e.put("error", error);
        errors.add(e);
        rec.put("errors", errors);
        return rec;
    }

    /**
     * Minimal CSV parser tolerant of the formats the demo Bulk Upload screen
     * accepts. Header row is required; whitespace around cells is trimmed; no
     * quoted-field support.
     */
    private List<Map<String, Object>> parseCsv(String csv, int firmCd) {
        if (csv == null || csv.isBlank()) {
            return Collections.emptyList();
        }
        String[] lines = csv.split("\\r?\\n");
        if (lines.length < 2) return Collections.emptyList();

        String[] headers = splitCsvLine(lines[0]);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) continue;
            String[] cells = splitCsvLine(line);
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("firmCd", firmCd);
            for (int c = 0; c < headers.length && c < cells.length; c++) {
                String h = headers[c].trim();
                String v = cells[c].trim();
                if ("rolesCds".equalsIgnoreCase(h)) {
                    List<Integer> roles = new ArrayList<>();
                    if (!v.isEmpty()) {
                        for (String s : v.split("\\|")) {
                            try { roles.add(Integer.parseInt(s.trim())); } catch (NumberFormatException ignored) {}
                        }
                    }
                    u.put("rolesCds", roles);
                } else if ("gwAdminFlag".equalsIgnoreCase(h) || "mfaEnabledFlag".equalsIgnoreCase(h)
                        || "sendInviteFlag".equalsIgnoreCase(h)) {
                    u.put(h, Boolean.parseBoolean(v));
                } else if ("contactTypeCd".equalsIgnoreCase(h) || "defaultRoleCd".equalsIgnoreCase(h)) {
                    try { u.put(h, Integer.parseInt(v)); } catch (NumberFormatException ignored) {}
                } else {
                    u.put(h, v);
                }
            }
            List<Map<String, Object>> errors = validateBulkRow(u);
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("genericUserJTO", u);
            if (!errors.isEmpty()) {
                rec.put("errors", errors);
            } else {
                rec.put("errors", Collections.emptyList());
            }
            out.add(rec);
        }
        return out;
    }

    private static String[] splitCsvLine(String line) {
        return line.split(",", -1);
    }

    private static List<Map<String, Object>> validateBulkRow(Map<String, Object> u) {
        List<Map<String, Object>> errors = new ArrayList<>();
        if (isBlank(u.get("username")))     errors.add(error("username", "username is required"));
        if (isBlank(u.get("firstName")))    errors.add(error("firstName", "firstName is required"));
        if (isBlank(u.get("emailAddress"))) {
            errors.add(error("emailAddress", "emailAddress is required"));
        } else if (!u.get("emailAddress").toString().contains("@")) {
            errors.add(error("emailAddress", "invalid email"));
        }
        return errors;
    }

    private static boolean isBlank(Object v) {
        return v == null || v.toString().isBlank();
    }

    private static Map<String, Object> error(String field, String message) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("field", field);
        e.put("error", message);
        return e;
    }
}
