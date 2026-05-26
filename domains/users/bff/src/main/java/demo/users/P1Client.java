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

    /** POST /saml/idp/bff-users.do?op=createUpdate  body=JSON(GenericUserJTO) */
    public Map<String, Object> createUpdate(Map<String, Object> body, String bearer) {
        String url = baseUrl + "/saml/idp/bff-users.do?op=createUpdate";
        HttpRequest<Map<String, Object>> req = HttpRequest.POST(url, body)
            .header("Authorization", "Bearer " + bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON);
        return exchange(req);
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
            // Surface P1's status verbatim so the web client sees the same
            // 401/403/400/404 instead of an opaque 500 from the BFF.
            String message = e.getResponse().getBody(String.class).orElse(e.getMessage());
            throw new HttpStatusException(HttpStatus.valueOf(e.getStatus().getCode()), message);
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
