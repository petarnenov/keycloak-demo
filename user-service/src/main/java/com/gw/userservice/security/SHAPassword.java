package com.gw.userservice.security;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Verbatim port of com.netfolio.util.SHAPassword from the P1 codebase
 * (nodejs/geowealth/src/main/java/com/netfolio/util/SHAPassword.java).
 * Only deviation: uses java.util.Base64 instead of the legacy
 * com.netfolio.util.Base64{En,De}coder. Both implement RFC 1521 / RFC 4648
 * Base64 with the standard alphabet — output bytes are identical for the
 * 20-byte-hash + N-byte-salt inputs we see in ENTITY_TBL.LDAP_PSWD_HASH.
 */
public class SHAPassword {

    protected byte[] salt = {};

    public SHAPassword(byte[] salt) {
        this.salt = salt;
    }

    public SHAPassword() {
    }

    public String digest(String pwd) throws IOException, NoSuchAlgorithmException {
        if (pwd == null || pwd.length() == 0) {
            return "-no-pswd-set-";
        }
        String label = (salt.length > 0) ? "{SSHA}" : "{SHA}";
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        sha.reset();
        sha.update(pwd.getBytes());
        sha.update(salt);
        byte[] pwhash = sha.digest();
        return label + Base64.getEncoder().encodeToString(concatenate(pwhash, salt));
    }

    public boolean check(String pwd, String digest) throws IOException, NoSuchAlgorithmException {
        if (digest.regionMatches(true, 0, "{SHA}", 0, 5)) {
            digest = digest.substring(5);
        } else if (digest.regionMatches(true, 0, "{SSHA}", 0, 6)) {
            digest = digest.substring(6);
        }
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        byte[][] hs = split(Base64.getMimeDecoder().decode(digest), 20);
        byte[] hash = hs[0];
        byte[] salt = hs[1];
        sha.reset();
        sha.update(pwd.getBytes());
        sha.update(salt);
        byte[] pwhash = sha.digest();
        return MessageDigest.isEqual(hash, pwhash);
    }

    private static byte[] concatenate(byte[] l, byte[] r) {
        byte[] b = new byte[l.length + r.length];
        System.arraycopy(l, 0, b, 0, l.length);
        System.arraycopy(r, 0, b, l.length, r.length);
        return b;
    }

    private static byte[][] split(byte[] src, int n) {
        byte[] l, r;
        if (src.length <= n) {
            l = src;
            r = new byte[0];
        } else {
            l = new byte[n];
            r = new byte[src.length - n];
            System.arraycopy(src, 0, l, 0, n);
            System.arraycopy(src, n, r, 0, r.length);
        }
        return new byte[][]{l, r};
    }
}
