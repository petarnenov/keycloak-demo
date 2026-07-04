package com.gw.authzservice.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;

/**
 * {@code POST /policy/can} body — mirrors master {@code canUserDoObject}. A null
 * {@code objectId} means the section/capability form (loadPolicyRules non-empty).
 */
@Serdeable
public record CanRequest(
        @NotNull Integer objectType,
        @NotNull Integer permission,
        @Nullable String objectId
) {
}
