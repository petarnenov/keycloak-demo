package com.gw.keycloak.userstorage;

import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Thin HTTP client for the user-service REST contract. Stateless; one instance
 * per UserStorageProvider creation. Uses JDK 17 HttpClient — no Feign / OkHttp
 * dependency to keep the provider JAR small.
 */
public class UserServiceClient {

    private static final Logger LOG = Logger.getLogger(UserServiceClient.class);

    private final String baseUrl;
    private final HttpClient http;

    public UserServiceClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    public Optional<Map<String, Object>> findByUsername(String username) {
        String url = baseUrl + "/users/search?username=" + encode(username);
        return getJson(url);
    }

    public Optional<Map<String, Object>> findById(String entityId) {
        String url = baseUrl + "/users/" + encode(entityId);
        return getJson(url);
    }

    public Optional<Map<String, Object>> attributes(String entityId) {
        String url = baseUrl + "/users/" + encode(entityId) + "/attributes";
        return getJson(url);
    }

    public List<String> roles(String entityId) {
        String url = baseUrl + "/users/" + encode(entityId) + "/roles";
        return getJson(url)
                .map(m -> m.get("_array"))
                .map(o -> (List<?>) o)
                .map(l -> l.stream().map(Object::toString).toList())
                .orElseGet(() -> parseArray(url));
    }

    public boolean verifyCredentials(String entityId, String password) {
        String url = baseUrl + "/users/" + encode(entityId) + "/verify-credentials";
        String body = "{\"password\":" + jsonString(password) + "}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return false;
            return r.body().contains("\"valid\":true");
        } catch (Exception e) {
            LOG.warnf(e, "verify-credentials call failed: %s", url);
            return false;
        }
    }

    public Optional<Map<String, Object>> issueMfaToken(String entityId) {
        String url = baseUrl + "/users/" + encode(entityId) + "/mfa-token";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return Optional.empty();
            return Optional.of(MiniJson.parseObject(r.body()));
        } catch (Exception e) {
            LOG.warnf(e, "issue-mfa-token call failed: %s", url);
            return Optional.empty();
        }
    }

    public boolean verifyMfaToken(String entityId, String token) {
        String url = baseUrl + "/users/" + encode(entityId) + "/mfa-token/verify";
        String body = "{\"token\":" + jsonString(token) + "}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 && r.body().contains("\"valid\":true");
        } catch (Exception e) {
            LOG.warnf(e, "verify-mfa-token call failed: %s", url);
            return false;
        }
    }

    private List<String> parseArray(String url) {
        try {
            HttpResponse<String> r = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return Collections.emptyList();
            return MiniJson.parseStringArray(r.body());
        } catch (Exception e) {
            LOG.warnf(e, "GET array failed: %s", url);
            return Collections.emptyList();
        }
    }

    private Optional<Map<String, Object>> getJson(String url) {
        try {
            HttpResponse<String> r = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 404) return Optional.empty();
            if (r.statusCode() != 200) {
                LOG.warnf("user-service GET %s returned %d", url, r.statusCode());
                return Optional.empty();
            }
            return Optional.of(MiniJson.parseObject(r.body()));
        } catch (Exception e) {
            LOG.warnf(e, "GET failed: %s", url);
            return Optional.empty();
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
