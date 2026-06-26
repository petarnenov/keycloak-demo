package com.gw.userservice.dao;

import com.gw.userservice.security.SHAPassword;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Singleton
public class MfaTokenDao {

    private static final SecureRandom RNG = new SecureRandom();

    private final DataSource dataSource;

    public MfaTokenDao(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public String issueToken(String entityId, int ttlSeconds) {
        String token = String.format("%06d", RNG.nextInt(1_000_000));
        String hashed;
        try {
            hashed = new SHAPassword().digest(token);
        } catch (Exception e) {
            throw new RuntimeException("digest failed", e);
        }
        Timestamp expiry = Timestamp.from(Instant.now().plusSeconds(ttlSeconds));
        String sql = "UPDATE ENTITY_TBL SET MFA_TOKEN = ?, MFA_TOKEN_EXPIRATION_DATE = ? WHERE ENTITY_ID = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, hashed);
            ps.setTimestamp(2, expiry);
            ps.setString(3, entityId);
            int updated = ps.executeUpdate();
            if (updated == 0) throw new IllegalStateException("entity not found: " + entityId);
        } catch (SQLException e) {
            throw new RuntimeException("issueToken failed", e);
        }
        return token;
    }

    public boolean verifyToken(String entityId, String submitted) {
        String sql = "SELECT MFA_TOKEN, MFA_TOKEN_EXPIRATION_DATE FROM ENTITY_TBL WHERE ENTITY_ID = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, entityId);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                String hash = rs.getString(1);
                Timestamp expiry = rs.getTimestamp(2);
                if (hash == null || expiry == null) return false;
                if (expiry.toInstant().isBefore(Instant.now())) return false;
                return new SHAPassword().check(submitted, hash);
            }
        } catch (Exception e) {
            throw new RuntimeException("verifyToken failed", e);
        }
    }

    public Optional<String> findEmail(String entityId) {
        // Email column doesn't live in ENTITY_TBL in the demo schema — return empty.
        // Real lookup would walk EMAIL_TBL or a custom field; placeholder for the demo.
        return Optional.of("redacted-" + entityId.substring(0, 6) + "@geowealth.local");
    }
}
