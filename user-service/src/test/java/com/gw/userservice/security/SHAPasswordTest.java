package com.gw.userservice.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SHAPasswordTest {

    @Test
    void plainSha1RoundTrip() throws Exception {
        SHAPassword sha = new SHAPassword();
        String hash = sha.digest("hunter2");
        assertTrue(hash.startsWith("{SHA}"));
        assertTrue(sha.check("hunter2", hash));
        assertFalse(sha.check("wrong", hash));
    }

    @Test
    void saltedRoundTrip() throws Exception {
        byte[] salt = "deadbeef".getBytes();
        SHAPassword sha = new SHAPassword(salt);
        String hash = sha.digest("hunter2");
        assertTrue(hash.startsWith("{SSHA}"));
        assertTrue(new SHAPassword().check("hunter2", hash));
        assertFalse(new SHAPassword().check("wrong", hash));
    }

    @Test
    void emptyPasswordDigests() throws Exception {
        assertTrue(new SHAPassword().digest("").equals("-no-pswd-set-"));
    }

    @Test
    void checkAcceptsLabelCaseInsensitive() throws Exception {
        String upper = "{SHA}" + new SHAPassword().digest("foo").substring(5);
        String lower = upper.replace("{SHA}", "{sha}");
        assertTrue(new SHAPassword().check("foo", lower));
    }
}
