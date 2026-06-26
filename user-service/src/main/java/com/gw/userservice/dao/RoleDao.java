package com.gw.userservice.dao;

import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Singleton
public class RoleDao {

    private final DataSource dataSource;

    public RoleDao(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public List<String> findRoleNamesByEntityId(String entityId) {
        String sql = """
                SELECT R.NAME
                  FROM ENTITY_ROLE_TBL ER
                  JOIN ROLE_TBL R ON R.ROLE_CD = ER.ROLE_CD
                 WHERE ER.ENTITY_ID = ?
                 ORDER BY R.NAME
                """;
        List<String> roles = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) roles.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("findRoleNamesByEntityId failed", e);
        }
        return roles;
    }
}
