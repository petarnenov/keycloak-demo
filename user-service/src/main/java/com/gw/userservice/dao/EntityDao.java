package com.gw.userservice.dao;

import com.gw.userservice.domain.User;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

@Singleton
public class EntityDao {

    private static final String SELECT_COLUMNS = """
            SELECT ENTITY_ID, FIRM_CD, LDAP_UID, ENTITY_TYPE_CD,
                   ENTITY_ACTIVE_FLAG, LOGIN_INACTIVATED_FLAG,
                   LOGIN_INACTIVED_REASON_CD, GW_ADMIN_FLAG,
                   LINKED_GW_USER, MFA_REQUIRED_FLAG
              FROM ENTITY_TBL
            """;

    private final DataSource dataSource;

    public EntityDao(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Optional<User> findByUsernameAndFirm(String username, int firmCd) {
        String sql = SELECT_COLUMNS + " WHERE LDAP_UID = ? AND FIRM_CD = ? AND ENTITY_ACTIVE_FLAG = 1";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setInt(2, firmCd);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("findByUsernameAndFirm failed", e);
        }
    }

    public Optional<User> findByEntityId(String entityId) {
        String sql = SELECT_COLUMNS + " WHERE ENTITY_ID = ? AND ENTITY_ACTIVE_FLAG = 1";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("findByEntityId failed", e);
        }
    }

    public Optional<String> findPasswordHash(String entityId) {
        String sql = "SELECT LDAP_PSWD_HASH FROM ENTITY_TBL WHERE ENTITY_ID = ? AND ENTITY_ACTIVE_FLAG = 1";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String h = rs.getString(1);
                return (h == null || h.isEmpty()) ? Optional.empty() : Optional.of(h);
            }
        } catch (SQLException e) {
            throw new RuntimeException("findPasswordHash failed", e);
        }
    }

    public List<User> searchByUsername(String username) {
        String sql = SELECT_COLUMNS + " WHERE LDAP_UID = ? AND ENTITY_ACTIVE_FLAG = 1 ORDER BY FIRM_CD";
        java.util.List<User> out = new java.util.ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("searchByUsername failed", e);
        }
        return out;
    }

    private User mapRow(ResultSet rs) throws SQLException {
        return new User(
                rs.getString("ENTITY_ID"),
                toInt(rs.getObject("FIRM_CD")),
                rs.getString("LDAP_UID"),
                toInt(rs.getObject("ENTITY_TYPE_CD")),
                rs.getInt("ENTITY_ACTIVE_FLAG") == 1,
                rs.getInt("LOGIN_INACTIVATED_FLAG") == 1,
                toInt(rs.getObject("LOGIN_INACTIVED_REASON_CD")),
                rs.getInt("GW_ADMIN_FLAG") == 1,
                rs.getString("LINKED_GW_USER"),
                rs.getObject("MFA_REQUIRED_FLAG") == null ? null : rs.getInt("MFA_REQUIRED_FLAG") == 1,
                null, null, null, null, null,
                new HashMap<>()
        );
    }

    private static Integer toInt(Object o) {
        if (o == null) return null;
        if (o instanceof Integer i) return i;
        if (o instanceof Number n) return n.intValue();
        return Integer.valueOf(o.toString());
    }
}
