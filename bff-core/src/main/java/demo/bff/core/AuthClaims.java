package demo.bff.core;

import io.micronaut.security.authentication.Authentication;

/**
 * Small read helpers for the JWT claims the BFFs echo back into responses,
 * extracted from the per-controller copies so the wire shape stays consistent.
 */
public final class AuthClaims {

    private AuthClaims() {
    }

    /** Tenant code from the JWT, as a String (null when absent). */
    public static String firmCd(Authentication authentication) {
        Object v = authentication.getAttributes().get("firmCd");
        return v == null ? null : v.toString();
    }
}
