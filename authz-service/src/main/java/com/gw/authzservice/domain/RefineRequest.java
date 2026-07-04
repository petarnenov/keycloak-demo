package com.gw.authzservice.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * {@code POST /policy/refine} body — mirrors master {@code refineUUIDs}. The BFF
 * sends only the page of ids it is about to return; the service intersects them
 * with the entity's rule set.
 */
@Serdeable
public record RefineRequest(
        @NotNull Integer objectType,
        @NotNull Integer permission,
        @Nullable List<String> ids
) {
}
