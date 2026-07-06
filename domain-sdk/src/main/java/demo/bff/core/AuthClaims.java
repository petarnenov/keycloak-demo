package demo.bff.core;

import io.micronaut.security.authentication.Authentication;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Small read helpers for the JWT claims the BFFs echo back into responses,
 * extracted from the per-controller copies so the wire shape stays consistent.
 */
public final class AuthClaims {

    /**
     * Delimiter used to pack the multi-valued {@code memberships} fact into a
     * single String attribute. micronaut-security's session-stored
     * {@code Authentication} reliably round-trips scalar (String) attributes
     * but drops {@code List} attributes, so the mapper joins memberships into
     * one String and this helper splits it back. A newline never appears in a
     * {@code "<firmCd>:<ldapUid>"} value, so it is a safe separator.
     */
    public static final String MEMBERSHIPS_DELIM = "\n";

    private AuthClaims() {
    }

    /** Tenant code from the JWT, as a String (null when absent). */
    public static String firmCd(Authentication authentication) {
        Object v = authentication.getAttributes().get("firmCd");
        return v == null ? null : v.toString();
    }

    /**
     * The firms the person has an account in, as {@code "<firmCd>:<ldapUid>"}
     * entries. Stored as a delimited String in the session-backed
     * {@code Authentication} (see {@link #MEMBERSHIPS_DELIM}); tolerates a raw
     * {@code List} too for safety. Never null.
     */
    public static List<String> memberships(Authentication authentication) {
        Object v = authentication.getAttributes().get("memberships");
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o != null) {
                    out.add(o.toString());
                }
            }
            return out;
        }
        if (v instanceof String s) {
            return s.isEmpty() ? List.of() : Arrays.asList(s.split(MEMBERSHIPS_DELIM));
        }
        return List.of();
    }
}
