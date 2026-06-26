package com.gw.userservice.domain;

import io.micronaut.serde.annotation.Serdeable;

import java.util.List;
import java.util.Map;

@Serdeable
public record User(
        String entityId,
        Integer firmCd,
        String ldapUid,
        Integer entityTypeCd,
        boolean entityActiveFlag,
        boolean loginInactivatedFlag,
        Integer loginInactivedReasonCd,
        boolean gwAdminFlag,
        String linkedGwUser,
        Boolean mfaRequiredFlag,
        String email,
        String firstName,
        String lastName,
        String personId,
        List<String> memberships,
        Map<String, Object> extras
) {
    public boolean isLocked() {
        return loginInactivatedFlag;
    }
}
