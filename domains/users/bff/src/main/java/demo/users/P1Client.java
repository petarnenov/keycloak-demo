package demo.users;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.exceptions.HttpStatusException;
import jakarta.inject.Singleton;

import java.util.Map;

/**
 * Thin HTTP client over the P1 monolith's bearer-auth users endpoint
 * ({@code /saml/idp/bff-users.do}, see
 * {@code com.geowealth.bff.BffUsersAction}). The BFF forwards the
 * caller's Keycloak access token verbatim, so authority is always the
 * end-user — no service account, no token exchange.
 *
 * <p>P1 already enforces firm-scope ({@code gwAdmin from firm 1}
 * cross-firm, otherwise same-firm only) and emits HTTP-native
 * statuses (401/403/400/404/500). This client surfaces them by
 * re-throwing as {@link HttpStatusException} so Micronaut's default
 * exception mapping translates them to the same status on the
 * BFF&rarr;web hop.</p>
 */
@Singleton
public class P1Client {

    private final HttpClient http;
    private final String baseUrl;

    public P1Client(@Client HttpClient http,
                    @Value("${p1.base-url:http://host.docker.internal:8080}") String baseUrl) {
        this.http = http;
        this.baseUrl = stripTrailingSlash(baseUrl);
    }

    /** GET /saml/idp/bff-users.do?op=list&firmCd=N */
    public Map<String, Object> list(int firmCd, String bearer) {
        return get("list", "&firmCd=" + firmCd, bearer);
    }

    /** GET /saml/idp/bff-users.do?op=getById&userId=… */
    public Map<String, Object> getById(String userId, String bearer) {
        return get("getById", "&userId=" + urlEncode(userId), bearer);
    }

    /** GET /saml/idp/bff-users.do?op=dropdowns&firmCd=N */
    public Map<String, Object> dropdowns(int firmCd, String bearer) {
        return get("dropdowns", "&firmCd=" + firmCd, bearer);
    }

    /** GET /saml/idp/bff-users.do?op=firms — DB-driven picker list. */
    public Map<String, Object> firms(String bearer) {
        return get("firms", "", bearer);
    }

    /** POST /saml/idp/bff-users.do?op=createFirm  body=JSON({firmCd?, firmName, code?}). */
    public Map<String, Object> createFirm(Map<String, Object> body, String bearer) {
        String url = baseUrl + "/saml/idp/bff-users.do?op=createFirm";
        HttpRequest<Map<String, Object>> req = HttpRequest.POST(url, body)
            .header("Authorization", "Bearer " + bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON);
        return exchange(req);
    }

    /** POST /saml/idp/bff-users.do?op=deactivateFirm&firmCd=N — soft-delete. */
    public Map<String, Object> deactivateFirm(int firmCd, String bearer) {
        String url = baseUrl + "/saml/idp/bff-users.do?op=deactivateFirm&firmCd=" + firmCd;
        return exchange(HttpRequest.POST(url, java.util.Map.of())
            .header("Authorization", "Bearer " + bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON));
    }

    /** POST /saml/idp/bff-users.do?op=createUpdate  body=JSON(GenericUserJTO) */
    public Map<String, Object> createUpdate(Map<String, Object> body, String bearer) {
        String url = baseUrl + "/saml/idp/bff-users.do?op=createUpdate";
        HttpRequest<Map<String, Object>> req = HttpRequest.POST(url, body)
            .header("Authorization", "Bearer " + bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON);
        return exchange(req);
    }

    // ---- person-registry endpoints (Persons tab) -----------------------------

    /** GET /saml/idp/bff-persons.do?op=list */
    public Map<String, Object> listPersons(String bearer) {
        String url = baseUrl + "/saml/idp/bff-persons.do?op=list";
        return exchange(HttpRequest.GET(url)
            .header("Authorization", "Bearer " + bearer)
            .accept(MediaType.APPLICATION_JSON));
    }

    /** GET /saml/idp/bff-persons.do?op=get&personId=… */
    public Map<String, Object> getPerson(String personId, String bearer) {
        String url = baseUrl + "/saml/idp/bff-persons.do?op=get&personId=" + urlEncode(personId);
        return exchange(HttpRequest.GET(url)
            .header("Authorization", "Bearer " + bearer)
            .accept(MediaType.APPLICATION_JSON));
    }

    /** POST /saml/idp/bff-persons.do?op=upsert  body = the person JSON. */
    public Map<String, Object> upsertPerson(Map<String, Object> body, String bearer) {
        String url = baseUrl + "/saml/idp/bff-persons.do?op=upsert";
        return exchange(HttpRequest.POST(url, body)
            .header("Authorization", "Bearer " + bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON));
    }

    /** POST /saml/idp/bff-persons.do?op=delete&personId=… */
    public Map<String, Object> deletePerson(String personId, String bearer) {
        String url = baseUrl + "/saml/idp/bff-persons.do?op=delete&personId=" + urlEncode(personId);
        return exchange(HttpRequest.POST(url, java.util.Map.of())
            .header("Authorization", "Bearer " + bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON));
    }

    // ---- internals -----------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String op, String tail, String bearer) {
        String url = baseUrl + "/saml/idp/bff-users.do?op=" + op + tail;
        HttpRequest<?> req = HttpRequest.GET(url)
            .header("Authorization", "Bearer " + bearer)
            .accept(MediaType.APPLICATION_JSON);
        return exchange(req);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> exchange(HttpRequest<?> req) {
        try {
            HttpResponse<Map> res = http.toBlocking().exchange(req, Map.class);
            Map body = res.body();
            return body == null ? Map.of() : (Map<String, Object>) body;
        } catch (HttpClientResponseException e) {
            // Status translation:
            //   400/404 — about THIS request → pass through verbatim;
            //   403     — authenticated but not authorised at P1 → pass through
            //             (SPA renders an access-denied state, doesn't relogin);
            //   401     — P1 rejected the bearer token → REMAP to 502.
            //             A naive pass-through here would tell the SPA "your
            //             BFF session is gone", but the BFF session is in fact
            //             still valid — what failed is the upstream
            //             validation. The SPA's api.ts treats 401 as
            //             "signed out" and bounces through silent login;
            //             silent login succeeds (BFF session is fine), the
            //             SPA retries the same /api/* call, P1 rejects again,
            //             loop. Mapping to 502 puts the call into the
            //             "downstream error" branch and surfaces a normal
            //             error instead. See
            //             cross-subdomain-sso-multi-username-analysis.md:
            //             this is the integration failure mode the
            //             personId-based BffUsersAction lookup hasn't
            //             implemented yet.
            //   5xx     — pass through verbatim.
            String message = e.getResponse().getBody(String.class).orElse(e.getMessage());
            int code = e.getStatus().getCode();
            if (code == 401) {
                throw new HttpStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "P1 rejected the bearer token (502): " + message);
            }
            throw new HttpStatusException(HttpStatus.valueOf(code), message);
        }
    }

    private static String stripTrailingSlash(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
