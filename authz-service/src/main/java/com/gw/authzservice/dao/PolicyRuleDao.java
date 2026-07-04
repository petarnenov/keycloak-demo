package com.gw.authzservice.dao;

import com.gw.authzservice.catalog.Permission;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only Oracle DAO for the PolicyRule decision path — the demo's
 * {@code PolicyRuleManager} read surface as a standalone service (alignment
 * Phase A0). It only READS the pre-materialized {@code POLICY_RULE_TBL} (and the
 * role tables for the capability map); the refresh/materialization of those rows
 * stays in P1 / the Flyway seed (alignment §4.6/§4.7).
 *
 * <p>Every method fails closed: a DB error, an empty candidate set, or an
 * unparseable id yields deny/omit, never a leaked authority.</p>
 */
@Singleton
public class PolicyRuleDao {

    private final DataSource dataSource;

    public PolicyRuleDao(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * The capability map — mirrors master {@code loadCreateExecutePermissions}:
     * the role-level {@code (objectType, permission)} pairs the entity's roles
     * grant, VIEW/CREATE/EXECUTE only, keyed {@code "<objectTypeCd>_<permissionCd>"}.
     * Role-level only (no object_id) — this is the client-side UI hint, not the
     * server gate.
     */
    public Map<String, Boolean> loadCreateExecutePermissions(String entityId) {
        String sql = """
                SELECT DISTINCT OTP.OBJECT_TYPE_CD, OTP.PERMISSION_CD
                  FROM ENTITY_ROLE_TBL ER
                  JOIN ROLE_PERMISSION_TBL RP ON RP.ROLE_CD = ER.ROLE_CD
                  JOIN OBJECTTYPE_PERMISSION_TBL OTP ON OTP.OBJECTTYPE_PERMISSION_CD = RP.OBJECTTYPE_PERMISSION_CD
                 WHERE ER.ENTITY_ID = ?
                   AND OTP.PERMISSION_CD IN (?, ?, ?)
                """;
        Map<String, Boolean> out = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, entityId);
            int i = 2;
            for (Integer p : Permission.CAPABILITY_MAP_PERMISSIONS) {
                ps.setInt(i++, p);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getInt(1) + "_" + rs.getInt(2), Boolean.TRUE);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("loadCreateExecutePermissions failed", e);
        }
        return out;
    }

    /**
     * Single-object / section check — mirrors master {@code canUserDoObject}:
     * {@code loadPolicyRules(entity, objectType, permission[, objectId]).size() > 0}.
     * When {@code objectId} is null the query has no object filter, so a non-empty
     * result means the entity holds ANY rule for {@code (objectType, permission)}
     * — the WEB_SECTION EXECUTE capability/section gate.
     */
    public boolean canDo(String entityId, int objectType, int permission, String objectId) {
        StringBuilder sql = new StringBuilder("""
                SELECT 1 FROM POLICY_RULE_TBL
                 WHERE ENTITY_ID = ? AND OBJECT_TYPE_CD = ? AND PERMISSION_CD = ?
                """);
        String normalized = null;
        if (objectId != null) {
            normalized = normalizeUuid(objectId);
            if (normalized == null) {
                return false; // unparseable object id → deny (fail closed)
            }
            sql.append(" AND OBJECT_ID = ?");
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            ps.setString(1, entityId);
            ps.setInt(2, objectType);
            ps.setInt(3, permission);
            if (normalized != null) {
                ps.setString(4, normalized);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("canDo failed", e);
        }
    }

    /**
     * List refine — mirrors master {@code refineUUIDs}: load the entity's
     * {@code (objectType, permission)} rule set ONCE, intersect in memory with the
     * candidate ids. UUID-only (alignment §4.8): candidates that don't parse as a
     * UUID are dropped. Returns the ORIGINAL caller strings for the allowed subset
     * so the BFF can match without worrying about UUID re-formatting.
     */
    public List<String> refine(String entityId, int objectType, int permission, List<String> candidateIds) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            return List.of();
        }
        // original string keyed by its normalized 32-hex form (drop unparseable)
        Map<String, String> byNormalized = new LinkedHashMap<>();
        for (String s : candidateIds) {
            String n = normalizeUuid(s);
            if (n != null) {
                byNormalized.putIfAbsent(n, s);
            }
        }
        if (byNormalized.isEmpty()) {
            return List.of();
        }
        Set<String> allowedNormalized = loadObjectIds(entityId, objectType, permission);
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : byNormalized.entrySet()) {
            if (allowedNormalized.contains(e.getKey())) {
                out.add(e.getValue());
            }
        }
        return out;
    }

    /** The entity's accessible object ids for {@code (objectType, permission)}, normalized. */
    private Set<String> loadObjectIds(String entityId, int objectType, int permission) {
        String sql = """
                SELECT OBJECT_ID FROM POLICY_RULE_TBL
                 WHERE ENTITY_ID = ? AND OBJECT_TYPE_CD = ? AND PERMISSION_CD = ?
                   AND OBJECT_ID IS NOT NULL
                """;
        Set<String> out = new HashSet<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, entityId);
            ps.setInt(2, objectType);
            ps.setInt(3, permission);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String n = normalizeUuid(rs.getString(1));
                    if (n != null) {
                        out.add(n);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("loadObjectIds failed", e);
        }
        return out;
    }

    /**
     * Resolve the KC access-token {@code sub} to a POLICY_RULE {@code ENTITY_ID}.
     * User Storage SPI users have {@code sub = "f:<componentId>:<entityId>"}
     * (KC {@code StorageId}); a native/non-federated sub is used verbatim.
     */
    public static String entityIdFromSub(String sub) {
        if (sub == null || sub.isBlank()) {
            return null;
        }
        String s = sub.trim();
        if (s.startsWith("f:")) {
            int second = s.indexOf(':', 2);
            if (second >= 0 && second + 1 < s.length()) {
                return s.substring(second + 1);
            }
        }
        return s;
    }

    /**
     * Normalize a UUID string to POLICY_RULE.OBJECT_ID form: 32 upper-hex chars,
     * no dashes. Returns null when the input is not 32 hex digits (with or without
     * dashes) — i.e. unparseable → dropped/denied (fail closed, alignment §4.8).
     */
    static String normalizeUuid(String raw) {
        if (raw == null) {
            return null;
        }
        String hex = raw.trim().replace("-", "").toUpperCase();
        if (hex.length() != 32) {
            return null;
        }
        for (int i = 0; i < 32; i++) {
            char ch = hex.charAt(i);
            boolean isHex = (ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'F');
            if (!isHex) {
                return null;
            }
        }
        return hex;
    }

    /** Distinct helper kept for callers that want an ordered view (tests). */
    Set<String> orderedObjectIds(String entityId, int objectType, int permission) {
        return new LinkedHashSet<>(loadObjectIds(entityId, objectType, permission));
    }
}
