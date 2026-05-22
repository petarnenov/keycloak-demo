package com.geowealth.keycloak.branding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production whitelabel resolution matrix, executed against a running
 * dev stack. Mirrors the matrix in {@code TEST-SCENARIOS.md} —
 * direct probes hit the GeoWealth branding servlet at
 * {@code BE_URL} (default {@code http://127.0.0.1:8080}); end-to-end
 * probes drive Keycloak at {@code KC_URL} (default
 * {@code http://localhost:8898}) and inspect the rendered login HTML.
 *
 * <p>The suite is opt-in by environment: with no
 * {@code POC_BRANDING_API_TOKEN} env var it skips cleanly so a fresh
 * clone's {@code ./gradlew build} stays green. Pass the token (the
 * same value Keycloak's SPI uses, kept in {@code .envrc}) to run.
 *
 * <p>Override the hosts via {@code BE_URL} / {@code KC_URL} env vars
 * when the stack lives elsewhere — useful for CI hitting an
 * ephemeral preview environment.
 */
@Tag("integration")
@DisplayName("Whitelabel resolution — production scenarios")
class WhitelabelScenariosIT {

    private static String beUrl;
    private static String kcUrl;
    private static String token;
    // Redirect URI must be in the geowealth-realm client whitelist (see
    // keycloak/geowealth-realm-export.json). Default port :5174 is
    // already wired for default.localhost.
    private static final String REDIRECT_URI_ENC =
        "http%3A%2F%2Fdefault.localhost%3A5174%2F";
    private static HttpClient http;
    private static ObjectMapper json;

    @BeforeAll
    static void setUpAll() {
        token = System.getenv("POC_BRANDING_API_TOKEN");
        Assumptions.assumeTrue(
            token != null && !token.isBlank(),
            "POC_BRANDING_API_TOKEN unset — source .envrc to enable the integration suite"
        );
        beUrl = envOrDefault("BE_URL", "http://127.0.0.1:8080");
        kcUrl = envOrDefault("KC_URL", "http://localhost:8898");
        http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NORMAL)
            // HTTP/2 forbids the Host header (it uses :authority instead);
            // Keycloak responds with RST_STREAM PROTOCOL_ERROR when we
            // override Host on an HTTP/2 stream. Pin to 1.1 so the
            // KeycloakE2E section's Host-override tests work end-to-end.
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        json = new ObjectMapper();
    }

    private static String envOrDefault(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    // -------------------------------------------------------------
    //  Pass 1 — FIRM.SYSTEM_BASE_URL substring match (advisor portal)
    // -------------------------------------------------------------

    @Nested
    @DisplayName("Pass 1 — FIRM.SYSTEM_BASE_URL")
    class Pass1Lookup {
        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
            "c1wealth.geowealth.com,            c1wealth",
            "c1securities.geowealth.com,        c1securities",
            "bcj.geowealth.com,                 bcj",
            "riverwaterpartners.geowealth.com,  riverwaterpartners",
            "smithandcox.geowealth.com,         smithandcox",
            "wisewealthkc.geowealth.com,        wisewealthkc"
        })
        void lookupResolvesToFirmCode(String host, String expectedCode) {
            assertThat(lookup(host)).isEqualTo(expectedCode);
        }
    }

    // -------------------------------------------------------------
    //  Pass 2 — FIRM.CLIENT_PORTAL_BASE_URL substring match
    // -------------------------------------------------------------

    @Nested
    @DisplayName("Pass 2 — FIRM.CLIENT_PORTAL_BASE_URL")
    class Pass2Lookup {
        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
            "c1wealthclient.geowealth.int,     c1wealth",
            "bcjclient.geowealth.int,          bcj",
            "smithandcoxclient.geowealth.int,  smithandcox"
        })
        void clientPortalUrlResolvesToFirmCode(String host, String expectedCode) {
            assertThat(lookup(host)).isEqualTo(expectedCode);
        }
    }

    // -------------------------------------------------------------
    //  Pass 5 — topSubDomain matches FIRM.CODE
    // -------------------------------------------------------------

    @Nested
    @DisplayName("Pass 5 — topSubDomain == FIRM.CODE")
    class Pass5Lookup {
        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
            "bcj.localhost,       bcj",
            "c1wealth.localhost,  c1wealth"
        })
        void topSubDomainMatchesActiveFirm(String host, String expectedCode) {
            assertThat(lookup(host)).isEqualTo(expectedCode);
        }
    }

    // -------------------------------------------------------------
    //  Pass 3 / Pass 4 — advisor-level URL overrides
    //
    //  Seed (ENTITY_TBL + USER_DETAIL_TBL):
    //    ENTITY_ID                 = 80C84BDACDEF43A092C71F7CCE10969E (any
    //                                valid 32-char hex string; the column
    //                                is parsed via com.netfolio.util.UUID
    //                                so non-hex characters cause Hibernate
    //                                to throw inside the actor and silently
    //                                break passes 3-5 — that's how this
    //                                seed got debugged the first time)
    //    ENTITY_TYPE_CD            = 4   (NEmployeeDetail discriminator)
    //    FIRM_CD                   = 7   (wisewealthkc, no WL row of its own)
    //    SYSTEM_BASE_URL           = wisewealthkcadv.geowealth.com
    //    CLIENT_PORTAL_BASE_URL    = wisewealthkcadvclient.geowealth.int
    //    CP_WHITELABEL_KEYWORD     = c1wealth   (override → fetches the
    //                                c1wealth WL row, not the firm's cca
    //                                fallback)
    // -------------------------------------------------------------

    @Nested
    @DisplayName("Pass 3 / 4 — advisor URL overrides")
    class Pass3And4Lookup {
        @org.junit.jupiter.api.Test
        @DisplayName("advisor SYSTEM_BASE_URL → cpWhitelabelKeyword override (c1wealth)")
        void advisorSystemBaseUrlOverridesFirm() {
            assertThat(lookup("wisewealthkcadv.geowealth.com")).isEqualTo("c1wealth");
        }

        @org.junit.jupiter.api.Test
        @DisplayName("advisor CLIENT_PORTAL_BASE_URL → cpWhitelabelKeyword override (c1wealth)")
        void advisorClientPortalUrlOverridesFirm() {
            assertThat(lookup("wisewealthkcadvclient.geowealth.int")).isEqualTo("c1wealth");
        }
    }

    // -------------------------------------------------------------
    //  Default — unmatched URL falls through to GeoWealth (cca)
    // -------------------------------------------------------------

    @Nested
    @DisplayName("Default — GeoWealth fallback")
    class DefaultLookup {
        @ParameterizedTest(name = "{0} -> cca")
        @ValueSource(strings = {
            "unknown.foo.com",
            "localhost",
            // mca and changepath have WL rows but no FIRM/advisor binding,
            // so production-correct resolution defaults them to cca.
            "mca.localhost",
            "changepath.localhost"
        })
        void unmatchedUrlsDefaultToCca(String host) {
            assertThat(lookup(host)).isEqualTo("cca");
        }
    }

    // -------------------------------------------------------------
    //  Brand JSON shape — /whitelabel/{code}
    // -------------------------------------------------------------

    @Nested
    @DisplayName("Brand JSON shape — /whitelabel/{code}")
    class BrandJson {
        @ParameterizedTest(name = "{0} carries assets + supportEmail")
        @ValueSource(strings = {
            "cca", "changepath", "c1wealth", "c1securities",
            "bcj", "riverwaterpartners", "smithandcox"
        })
        void brandedRowHasAssetsAndContact(String code) throws Exception {
            HttpResponse<String> resp = getBrand(code);
            assertThat(resp.statusCode()).isEqualTo(200);
            JsonNode body = json.readTree(resp.body());
            assertThat(body.path("code").asText()).isEqualTo(code);
            assertThat(body.path("displayName").asText()).isNotBlank();
            assertThat(body.path("cssVariables").isObject()).isTrue();
            assertThat(body.path("assets").path("loginLogo").path("url").asText())
                .as("loginLogo asset url for %s", code)
                .startsWith("/branding-api/keycloak/whitelabel/" + code + "/asset/");
            assertThat(body.path("assets").path("favicon").path("url").asText())
                .as("favicon asset url for %s", code)
                .startsWith("/branding-api/keycloak/whitelabel/" + code + "/asset/");
            assertThat(body.path("supportEmail").asText()).contains("@");
        }

        @ParameterizedTest(name = "/whitelabel/{0} -> 404")
        @ValueSource(strings = { "wisewealthkc", "unknownfirm42" })
        void unseededCodesReturn404(String code) throws Exception {
            HttpResponse<String> resp = getBrand(code);
            assertThat(resp.statusCode()).isEqualTo(404);
        }
    }

    // -------------------------------------------------------------
    //  Asset binary streaming — /whitelabel/{code}/asset/{kind}
    // -------------------------------------------------------------

    @Nested
    @DisplayName("Asset endpoint — /whitelabel/{code}/asset/{kind}")
    class AssetStreaming {
        @ParameterizedTest(name = "/asset/{0}/{1}")
        @CsvSource({
            "c1wealth,    logo-login",
            "c1wealth,    favicon",
            "bcj,         logo-login",
            "bcj,         favicon",
            "smithandcox, logo-login",
            "smithandcox, favicon"
        })
        void assetReturns200WithImageContentType(String code, String kind) throws Exception {
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                    beUrl + "/branding-api/keycloak/whitelabel/" + code + "/asset/" + kind))
                .timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer " + token)
                .GET().build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.headers().firstValue("Content-Type"))
                .hasValueSatisfying(ct ->
                    assertThat(ct.toLowerCase()).startsWith("image/"));
            assertThat(resp.body().length).isGreaterThan(0);
        }

        @ParameterizedTest(name = "/asset/{0}/{1} -> 400 invalid kind")
        @CsvSource({
            "cca,  not-a-kind",
            "cca,  ../passwd"
        })
        void unknownAssetKindReturns400(String code, String kind) throws Exception {
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                    beUrl + "/branding-api/keycloak/whitelabel/" + code + "/asset/" + kind))
                .timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer " + token)
                .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            // Anything outside SAFE_ASSET_KINDS = {logo-login, favicon} is
            // either 400 (rejected at the path layer) or 404 (path not
            // matched at all — for traversal payloads). Either is fine —
            // both are non-2xx and don't leak data.
            assertThat(resp.statusCode())
                .as("asset kind %s/%s should be rejected, got body %s", code, kind, resp.body())
                .isIn(400, 404);
        }
    }

    // -------------------------------------------------------------
    //  Keycloak end-to-end — login HTML carries the right brand
    // -------------------------------------------------------------

    @Nested
    @DisplayName("Keycloak E2E — login HTML brand markers")
    class KeycloakE2E {
        @ParameterizedTest(name = "host {0} renders logo + no banner")
        @ValueSource(strings = {
            "c1wealth.geowealth.com:8898",
            "c1securities.geowealth.com:8898",
            "bcj.geowealth.com:8898",
            "riverwaterpartners.geowealth.com:8898",
            "smithandcox.geowealth.com:8898",
            "bcj.localhost:8898",
            "unknown.foo.com:8898",
            "localhost:8898"
        })
        void brandedFirmsRenderLogo(String host) throws Exception {
            String html = driveLoginRender(host);
            assertThat(html).contains("geowealth-brand-logo");
            assertThat(html).doesNotContain("Branding fallback active");
        }

        @org.junit.jupiter.api.Test
        @DisplayName("wisewealthkc — firm matched, no WL row → banner shows")
        void wisewealthkcShowsFallbackBanner() throws Exception {
            String html = driveLoginRender("wisewealthkc.geowealth.com:8898");
            assertThat(html)
                .as("registry fallback path must still render a logo (BrandRegistry placeholder)")
                .contains("geowealth-brand-logo");
            assertThat(html)
                .as("registry fallback path must surface the operator-facing banner")
                .contains("Branding fallback active");
        }
    }

    // -------------------------------------------------------------
    //  Probe helpers
    // -------------------------------------------------------------

    private static String lookup(String host) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                    beUrl + "/branding-api/keycloak/whitelabel/lookup?host=" + host))
                .timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer " + token)
                .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new AssertionError(
                    "lookup host=" + host + " -> HTTP " + resp.statusCode()
                        + " body=" + resp.body());
            }
            return json.readTree(resp.body()).path("code").asText();
        } catch (Exception e) {
            throw new RuntimeException("lookup failed for host=" + host, e);
        }
    }

    private static HttpResponse<String> getBrand(String code) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(
                beUrl + "/branding-api/keycloak/whitelabel/" + code))
            .timeout(Duration.ofSeconds(3))
            .header("Authorization", "Bearer " + token)
            .GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Drive Keycloak's OIDC auth endpoint with the given {@code Host}
     * header and return the rendered login page HTML. We talk to the
     * container port via {@link #kcUrl} but override Host so the SPI
     * sees the firm's branded subdomain — same shape as
     * {@code test-whitelabel-scenarios.sh}.
     *
     * <p>A fresh random {@code state} on every call defeats any
     * accidental caching at intermediate proxies; Keycloak's SPI brand
     * cache is keyed on the Host header, not state.
     */
    private static String driveLoginRender(String host) throws Exception {
        String state = "junit-" + Long.toHexString(System.nanoTime());
        URI uri = URI.create(
            kcUrl + "/realms/geowealth-realm/protocol/openid-connect/auth"
                + "?client_id=geowealth-poc-client"
                + "&response_type=code"
                + "&redirect_uri=" + REDIRECT_URI_ENC
                + "&scope=openid"
                + "&state=" + state);
        HttpRequest req = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(10))
            .header("Host", host)
            .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new AssertionError(
                "Keycloak login render host=" + host + " -> HTTP " + resp.statusCode());
        }
        return resp.body();
    }
}
