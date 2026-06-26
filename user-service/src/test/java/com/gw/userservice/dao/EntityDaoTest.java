package com.gw.userservice.dao;

import com.gw.userservice.domain.User;
import com.gw.userservice.security.SHAPassword;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityDaoTest {

    private static DataSource ds;

    @BeforeAll
    static void setup() throws Exception {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:h2:mem:user-service-test;MODE=Oracle;DB_CLOSE_DELAY=-1");
        cfg.setUsername("sa");
        cfg.setMaximumPoolSize(2);
        ds = new HikariDataSource(cfg);
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE ENTITY_TBL (
                      ENTITY_ID CHAR(32) PRIMARY KEY,
                      FIRM_CD NUMBER,
                      LDAP_UID VARCHAR2(64),
                      LDAP_PSWD_HASH VARCHAR2(256),
                      ENTITY_TYPE_CD NUMBER,
                      ENTITY_ACTIVE_FLAG NUMBER(1),
                      LOGIN_INACTIVATED_FLAG NUMBER(1),
                      LOGIN_INACTIVED_REASON_CD NUMBER,
                      GW_ADMIN_FLAG NUMBER(1),
                      LINKED_GW_USER CHAR(32),
                      MFA_TOKEN VARCHAR2(256),
                      MFA_TOKEN_EXPIRATION_DATE TIMESTAMP,
                      MFA_REQUIRED_FLAG NUMBER(1)
                    )""");
            String tim1Hash = new SHAPassword().digest("tim1pass");
            String adminHash = new SHAPassword().digest("adminpass");
            s.execute("INSERT INTO ENTITY_TBL (ENTITY_ID, FIRM_CD, LDAP_UID, LDAP_PSWD_HASH, ENTITY_TYPE_CD, ENTITY_ACTIVE_FLAG, LOGIN_INACTIVATED_FLAG, GW_ADMIN_FLAG, MFA_REQUIRED_FLAG) VALUES ("
                    + "'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 1, 'tim1', '" + tim1Hash + "', 1, 1, 0, 0, 0)");
            s.execute("INSERT INTO ENTITY_TBL (ENTITY_ID, FIRM_CD, LDAP_UID, LDAP_PSWD_HASH, ENTITY_TYPE_CD, ENTITY_ACTIVE_FLAG, LOGIN_INACTIVATED_FLAG, GW_ADMIN_FLAG, MFA_REQUIRED_FLAG) VALUES ("
                    + "'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', 2, 'admin1', '" + adminHash + "', 1, 1, 0, 1, 1)");
            s.execute("INSERT INTO ENTITY_TBL (ENTITY_ID, FIRM_CD, LDAP_UID, LDAP_PSWD_HASH, ENTITY_TYPE_CD, ENTITY_ACTIVE_FLAG, LOGIN_INACTIVATED_FLAG, GW_ADMIN_FLAG, MFA_REQUIRED_FLAG) VALUES ("
                    + "'cccccccccccccccccccccccccccccccc', 1, 'locked1', '" + tim1Hash + "', 1, 1, 1, 0, 0)");
        }
    }

    @AfterAll
    static void teardown() {
        if (ds instanceof HikariDataSource h) h.close();
    }

    @Test
    void findsByUsernameAndFirm() {
        EntityDao dao = new EntityDao(ds);
        Optional<User> u = dao.findByUsernameAndFirm("tim1", 1);
        assertTrue(u.isPresent());
        assertEquals("tim1", u.get().ldapUid());
        assertFalse(u.get().isLocked());
    }

    @Test
    void findsByEntityId() {
        EntityDao dao = new EntityDao(ds);
        assertTrue(dao.findByEntityId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").isPresent());
        assertFalse(dao.findByEntityId("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz").isPresent());
    }

    @Test
    void lockedFlagSurfaces() {
        EntityDao dao = new EntityDao(ds);
        Optional<User> u = dao.findByUsernameAndFirm("locked1", 1);
        assertTrue(u.isPresent());
        assertTrue(u.get().isLocked());
    }

    @Test
    void gwAdminFlagSurfaces() {
        EntityDao dao = new EntityDao(ds);
        Optional<User> u = dao.findByUsernameAndFirm("admin1", 2);
        assertTrue(u.isPresent());
        assertTrue(u.get().gwAdminFlag());
    }

    @Test
    void passwordHashRetrievable() {
        EntityDao dao = new EntityDao(ds);
        assertTrue(dao.findPasswordHash("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").isPresent());
    }
}
