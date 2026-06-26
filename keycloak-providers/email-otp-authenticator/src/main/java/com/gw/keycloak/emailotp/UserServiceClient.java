package com.gw.keycloak.emailotp;

import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Minimal HTTP client used by the email-OTP authenticator. Lives in this
 * module (and not as a shared dep on user-storage-spi/UserServiceClient)
 * because keeping each provider JAR self-contained means redeploying one
 * doesn't risk pulling in changes from the other.
 */
public class UserServiceClient {

    private static final Logger LOG = Logger.getLogger(UserServiceClient.class);

    private final String baseUrl;
    private final HttpClient http;

    public UserServiceClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    /** Returns the plain-text 6-digit token + the destination email, or empty on failure. */
    public Optional<IssuedToken> issue(String entityId) {
        String url = baseUrl + "/users/" + enc(entityId) + "/mfa-token";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return Optional.empty();
            String token = matchString(r.body(), "\"token\"");
            String to = matchString(r.body(), "\"tokenSentTo\"");
            if (token == null) return Optional.empty();
            return Optional.of(new IssuedToken(token, to));
        } catch (Exception e) {
            LOG.warnf(e, "mfa-token issue failed: %s", url);
            return Optional.empty();
        }
    }

    public boolean verify(String entityId, String submitted) {
        String url = baseUrl + "/users/" + enc(entityId) + "/mfa-token/verify";
        String body = "{\"token\":" + jsonString(submitted) + "}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 && r.body().contains("\"valid\":true");
        } catch (Exception e) {
            LOG.warnf(e, "mfa-token verify failed: %s", url);
            return false;
        }
    }

    static String matchString(String json, String key) {
        int i = json.indexOf(key);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + key.length());
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public record IssuedToken(String token, String sentTo) {}
}
