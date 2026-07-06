package demo.bff.core;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SilentLoginControllerTest {

    @Test
    void startSilent_redirectsToKeycloakWithSilentMarker() {
        HttpResponse<?> resp = new SilentLoginController().startSilent();

        assertEquals(HttpStatus.SEE_OTHER, resp.getStatus());
        assertEquals("/oauth/login/keycloak?silent=1", resp.getHeaders().get(HttpHeaders.LOCATION));
    }
}
