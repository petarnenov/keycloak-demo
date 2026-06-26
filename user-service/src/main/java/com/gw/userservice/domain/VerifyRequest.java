package com.gw.userservice.domain;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;

@Serdeable
public record VerifyRequest(@NotBlank String password) {
}
