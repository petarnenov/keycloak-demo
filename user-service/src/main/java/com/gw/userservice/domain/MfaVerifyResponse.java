package com.gw.userservice.domain;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record MfaVerifyResponse(boolean valid, String reason) {
    public static MfaVerifyResponse ok() {
        return new MfaVerifyResponse(true, "OK");
    }

    public static MfaVerifyResponse fail(String reason) {
        return new MfaVerifyResponse(false, reason);
    }
}
