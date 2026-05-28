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
 * Thin HTTP client over the P1 monolith's bearer-auth linked-identity-admin
 * endpoint ({@code /saml/idp/linked-identity-admin.do}, see
 * {@code com.geowealth.saml.idp.LinkedIdentityAdminAction}). Same forwarding
 * model as {@link P1Client}: the user's Keycloak access token rides verbatim,
 * P1 re-checks gw-admin authorization, and any 4xx surfaces back to the SPA
 * with the same status.
 *
 * <p>This client is gw-admin only — non-gw-admin tokens get 403 from P1 even
 * if the BFF's {@code @Secured} were misconfigured. Defence in depth.</p>
 */
@Singleton
public class LinkedIdentityAdminClient {

    private static final String PATH = "/saml/idp/linked-identity-admin.do";

    private final HttpClient http;
    private final String baseUrl;

    public LinkedIdentityAdminClient(@Client HttpClient http,
                                     @Value("${p1.base-url:http://host.docker.internal:8080}") String baseUrl) {
        this.http = http;
        this.baseUrl = stripTrailingSlash(baseUrl);
    }

    /** {@code GET ?op=list[&sourceUserUuid=...]} — list all or filter by source. */
    public Map<String, Object> list(String sourceUserUuid, String bearer) {
        String tail = (sourceUserUuid == null || sourceUserUuid.isBlank())
                ? ""
                : "&sourceUserUuid=" + urlEncode(sourceUserUuid);
        return getOp("list", tail, bearer);
    }

    /** {@code GET ?op=get&sourceUserUuid=...&targetClient=...} */
    public Map<String, Object> get(String sourceUserUuid, String targetClient, String bearer) {
        String tail = "&sourceUserUuid=" + urlEncode(sourceUserUuid)
                + "&targetClient=" + urlEncode(targetClient);
        return getOp("get", tail, bearer);
    }

    /** {@code POST ?op=upsert} with the binding body. */
    public Map<String, Object> upsert(Map<String, Object> body, String bearer) {
        String url = baseUrl + PATH + "?op=upsert";
        return post(url, body, bearer);
    }

    /** {@code POST ?op=delete} with {@code {sourceUserUuid, targetClient, hard?}}. */
    public Map<String, Object> delete(Map<String, Object> body, String bearer) {
        String url = baseUrl + PATH + "?op=delete";
        return post(url, body, bearer);
    }

    // ---- internals -----------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> getOp(String op, String tail, String bearer) {
        String url = baseUrl + PATH + "?op=" + op + tail;
        HttpRequest<?> req = HttpRequest.GET(url)
                .header("Authorization", "Bearer " + bearer)
                .accept(MediaType.APPLICATION_JSON);
        return exchange(req);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String url, Map<String, Object> body, String bearer) {
        HttpRequest<Map<String, Object>> req = HttpRequest.POST(url, body)
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
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
            // Surface P1's status verbatim so the SPA sees the same 401/403/400/404.
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
