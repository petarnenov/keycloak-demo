package com.gw.userservice.domain;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record MfaTokenIssued(String tokenSentTo, int ttlSeconds) {
}
