package com.gw.userservice.domain;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record VerifyResponse(boolean valid, String reason) {
    public static final String OK = "OK";
    public static final String LOCKED = "LOCKED";
    public static final String BAD_PASSWORD = "BAD_PASSWORD";
    public static final String UNKNOWN_USER = "UNKNOWN_USER";

    public static VerifyResponse ok() {
        return new VerifyResponse(true, OK);
    }

    public static VerifyResponse fail(String reason) {
        return new VerifyResponse(false, reason);
    }
}
