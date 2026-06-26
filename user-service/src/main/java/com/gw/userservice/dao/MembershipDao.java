package com.gw.userservice.dao;

import com.gw.userservice.domain.Membership;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Singleton
public class MembershipDao {

    private final DataSource dataSource;

    public MembershipDao(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Mirrors PersonRegistry#loadAccountsByFirm + resolveMemberships from P1.
     * Walks the LINKED_GW_USER graph: if the seed entity has LINKED_GW_USER set,
     * the person root is that linked entity; otherwise the person root is the
     * seed itself. We then list every entity that shares the person root
     * (either as the root or as a child via LINKED_GW_USER), one per firm.
     */
    public List<Membership> findByEntityId(String entityId) {
        String sql = """
                WITH person_root (ENTITY_ID) AS (
                  SELECT NVL(LINKED_GW_USER, ENTITY_ID)
                    FROM ENTITY_TBL
                   WHERE ENTITY_ID = ?
                )
                SELECT E.FIRM_CD, E.LDAP_UID
                  FROM ENTITY_TBL E
                  JOIN person_root P
                    ON E.ENTITY_ID = P.ENTITY_ID OR E.LINKED_GW_USER = P.ENTITY_ID
                 WHERE E.ENTITY_ACTIVE_FLAG = 1
                 ORDER BY E.FIRM_CD
                """;
        List<Membership> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Integer firm = (Integer) rs.getObject(1);
                    String uid = rs.getString(2);
                    if (firm != null && uid != null) out.add(new Membership(firm, uid));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("findByEntityId (memberships) failed", e);
        }
        return out;
    }

    public String findPersonRoot(String entityId) {
        String sql = "SELECT NVL(LINKED_GW_USER, ENTITY_ID) FROM ENTITY_TBL WHERE ENTITY_ID = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : entityId;
            }
        } catch (SQLException e) {
            throw new RuntimeException("findPersonRoot failed", e);
        }
    }
}
