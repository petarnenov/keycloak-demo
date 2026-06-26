package com.gw.userservice.domain;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record Membership(int firmCd, String ldapUid) {
    public String toClaim() {
        return firmCd + ":" + ldapUid;
    }
}
