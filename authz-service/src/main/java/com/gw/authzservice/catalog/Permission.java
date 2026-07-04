package com.gw.authzservice.catalog;

import java.util.Set;

/**
 * Permission catalog — mirrors GeoWealth master
 * {@code com.geowealth.model.authorisation.Permission} exactly
 * (PERMISSION_TBL codes). This is the authoritative copy for the demo; the
 * {@code bff-core} constants mirror it for compile-time call sites (alignment
 * plan D5).
 */
public final class Permission {

    private Permission() {
    }

    public static final int VIEW = 1;
    public static final int MODIFY = 2;
    public static final int CREATE = 3;
    public static final int DELETE = 4;
    public static final int EXECUTE = 5;

    /**
     * The permissions the login-time capability map ships to the SPA — mirrors
     * master {@code loadCreateExecutePermissions} ("VIEW, CREATE and EXECUTE
     * ONLY"). MODIFY/DELETE are deliberately excluded and enforced server-side
     * per object (alignment §4.4).
     */
    public static final Set<Integer> CAPABILITY_MAP_PERMISSIONS = Set.of(VIEW, CREATE, EXECUTE);
}
