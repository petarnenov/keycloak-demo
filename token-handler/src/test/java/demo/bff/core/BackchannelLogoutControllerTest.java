package demo.bff.core;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackchannelLogoutControllerTest {

    @Test
    void nullToken_returnsBadRequest() {
        LogoutTokenValidator v = mock(LogoutTokenValidator.class);
        SidSessionRegistry r = mock(SidSessionRegistry.class);
        HttpResponse<?> resp = new BackchannelLogoutController(v, r).logout(null);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatus());
        verify(r, never()).invalidateBySid(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void blankToken_returnsBadRequest() {
        LogoutTokenValidator v = mock(LogoutTokenValidator.class);
        SidSessionRegistry r = mock(SidSessionRegistry.class);
        HttpResponse<?> resp = new BackchannelLogoutController(v, r).logout("   ");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatus());
    }

    @Test
    void invalidToken_returnsBadRequest() {
        LogoutTokenValidator v = mock(LogoutTokenValidator.class);
        when(v.validateAndGetSid("bad")).thenReturn(null);
        SidSessionRegistry r = mock(SidSessionRegistry.class);

        HttpResponse<?> resp = new BackchannelLogoutController(v, r).logout("bad");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatus());
        verify(r, never()).invalidateBySid(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void validToken_invalidatesSessionsAndReturnsOk() {
        LogoutTokenValidator v = mock(LogoutTokenValidator.class);
        when(v.validateAndGetSid("good")).thenReturn("sid-123");
        SidSessionRegistry r = mock(SidSessionRegistry.class);
        when(r.invalidateBySid("sid-123")).thenReturn(2);

        HttpResponse<?> resp = new BackchannelLogoutController(v, r).logout("good");

        assertEquals(HttpStatus.OK, resp.getStatus());
        assertEquals("no-store", resp.getHeaders().get("Cache-Control"));
        verify(r).invalidateBySid("sid-123");
    }
}
