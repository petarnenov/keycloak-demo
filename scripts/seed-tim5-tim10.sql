-- Seed tim5 + tim10 as real P1 users in geo-oracle (FREEPDB1).
--
-- Companion to cross-subdomain-sso-multi-username-analysis.md and the
-- demo PersonRegistry in geowealth/.../PersonRegistry.java. Before this
-- script ran the registry referenced tim5/tim10 symbolically — only
-- tim1 existed as a real ENTITY_TBL row. After it ran:
--
--   PersonRegistry.USERNAME_TO_PERSON_ID  // unchanged: 3 → P-tim
--   PersonRegistry.PERSON_TENANT_ALIASES  // unchanged
--   ENTITY_TBL                            // now has tim1 + tim5 + tim10
--
-- The seed clones tim1's row verbatim where it would be benign (firm 1,
-- ENTITY_TYPE_CD=4 employee, same DEFAULT_ROLE_CD=528, same
-- PERSONAL_ACCESS_SET_CD=4358, MFA off, gwAdmin off) and changes only
-- LDAP_UID, names, and the identifier columns.
--
-- All three users share the SAME email (tim.a@geo.com) so KC's
-- broker-link table joins them onto the SAME federated user via the
-- person-stable NameID (= P-tim) the multi-username refactor introduced;
-- KC's first-broker-login email auto-link is the safety net for new
-- realm imports. The personId resolution path in BffUsersAction picks
-- tim1 as the canonical row (via PersonRegistry.resolvePrimaryUsername),
-- so cross-firm reads continue to consult tim1's policy/role data; tim5
-- and tim10 are listed in the Users grid but the Tier-2/3 actor for
-- write ops is still tim1.
--
-- Re-runnable: each INSERT is wrapped in BEGIN/EXCEPTION to no-op on
-- duplicate-PK so the script is safe to apply twice.

-- ============================================================================
-- tim5 — ENTITY_ID = 50000000000000000000000000000005
-- ============================================================================
DECLARE
  dup EXCEPTION;
  PRAGMA EXCEPTION_INIT(dup, -1);
BEGIN
  INSERT INTO ENTITY_TBL (
    ENTITY_ID, ENTITY_TYPE_CD, LDAP_UID, LDAP_PSWD_HASH,
    ENTITY_ACTIVE_FLAG, LOGIN_INACTIVATED_FLAG, PROSPECT_ONLY_FLAG,
    CREATED_DATE, CREATED_BY, LAST_UPDATE_DATE, LAST_UPDATE_BY,
    FIRM_CD, PERSONAL_ACCESS_SET_CD, DEFAULT_ROLE_CD,
    SHOW_PORTFOLIO_CLPORTAL_FLAG, INSURANCE_AGENT_FLAG,
    SHOW_PORTFOLIOS_ADVPORTAL_FLAG, ENABLE_BALANCE_SHEET,
    GW_ADMIN_FLAG, TRADE_HOLD_FLAG, MFA_REQUIRED_FLAG,
    NO_EMAIL_FLAG, NO_MAIL_FLAG, NO_PHONE_FLAG, NO_TEXTMESSAGE_FLAG,
    NO_IM_FLAG, NO_FAX_FLAG,
    DOC_PRIVATE_FLAG, DOC_NOTIFICATION_FLAG, PRIMARY_CONTACT_FLAG,
    SHOW_SLEEVE_ALLOCATION_TAB_CL_PORTAL_FLAG,
    SHOW_CLIENTS_TAB_CLIENT_PORTAL,
    USE_CUSTODIAN_DATA_AS_PRIMARY_FLAG,
    USE_PRIMARY_CLIENT_DATA_AS_PRIMARY_FLAG,
    LOGIN_STATUS_CD
  ) VALUES (
    '50000000000000000000000000000005', 4, 'tim5', '{SHA}Ih9xbw2j6OxaOp1gkQ1QigvWtmk=',
    1, 0, 0,
    SYSDATE, '50000000000000000000000000000005', SYSDATE, '50000000000000000000000000000005',
    1, 4358, 528,
    1, 0,
    1, 0,
    0, 0, 0,
    0, 0, 0, 0,
    0, 0,
    0, 0, 0,
    0,
    0,
    0,
    0,
    3
  );
EXCEPTION WHEN dup THEN NULL;
END;
/

DECLARE dup EXCEPTION; PRAGMA EXCEPTION_INIT(dup, -1); BEGIN
  INSERT INTO USER_DETAIL_TBL (ENTITY_ID, GIVEN_NAME, SURNAME, COMMON_NAME, HIDE_DISABLED_WORKFLOWS)
  VALUES ('50000000000000000000000000000005', 'Tim', 'Arnold (Trading)', 'Arnold, Tim (Trading)', 0);
EXCEPTION WHEN dup THEN NULL; END;
/

DECLARE dup EXCEPTION; PRAGMA EXCEPTION_INIT(dup, -1); BEGIN
  INSERT INTO ENTITY_EMAIL_TBL (EMAIL_ID, ENTITY_ID, EMAIL_TYPE_CD, EMAIL, PRIMARY_EMAIL_FLAG, CREATED_DATE, CREATED_BY)
  VALUES ('50000000000000000000000000000F05', '50000000000000000000000000000005', 30, 'tim.a@geo.com', 1,
          SYSDATE, '50000000000000000000000000000005');
EXCEPTION WHEN dup THEN NULL; END;
/

DECLARE dup EXCEPTION; PRAGMA EXCEPTION_INIT(dup, -1); BEGIN
  INSERT INTO ENTITY_ROLE_TBL (ENTITY_ROLE_CD, ROLE_CD, ENTITY_ID, CREATED_BY, CREATED_DATE)
  VALUES (3846, 528, '50000000000000000000000000000005', '50000000000000000000000000000005', SYSDATE);
EXCEPTION WHEN dup THEN NULL; END;
/

-- ENTITY_TAX_DATA_TBL is reached as a Hibernate one-to-one with
-- constrained="true" — when tim1 is loaded, Hibernate's batch-size=25 fetch
-- on the NEntity subclass slurps tim5/tim10 into the session cache; the
-- toUser() projection then calls getEntityTaxData() and the proxy resolution
-- fires for every row in the batch. Missing tax-data row →
-- ObjectNotFoundException → the whole UserManager.lookupByUsername hangs the
-- BFF. An empty row (only ENTITY_ID populated) is enough: NEntity#toUser
-- falls back to firmTaxData when each column is null.
DECLARE dup EXCEPTION; PRAGMA EXCEPTION_INIT(dup, -1); BEGIN
  INSERT INTO ENTITY_TAX_DATA_TBL (ENTITY_ID) VALUES ('50000000000000000000000000000005');
EXCEPTION WHEN dup THEN NULL; END;
/

-- ============================================================================
-- tim10 — ENTITY_ID = 50000000000000000000000000000010
-- ============================================================================
DECLARE dup EXCEPTION; PRAGMA EXCEPTION_INIT(dup, -1); BEGIN
  INSERT INTO ENTITY_TBL (
    ENTITY_ID, ENTITY_TYPE_CD, LDAP_UID, LDAP_PSWD_HASH,
    ENTITY_ACTIVE_FLAG, LOGIN_INACTIVATED_FLAG, PROSPECT_ONLY_FLAG,
    CREATED_DATE, CREATED_BY, LAST_UPDATE_DATE, LAST_UPDATE_BY,
    FIRM_CD, PERSONAL_ACCESS_SET_CD, DEFAULT_ROLE_CD,
    SHOW_PORTFOLIO_CLPORTAL_FLAG, INSURANCE_AGENT_FLAG,
    SHOW_PORTFOLIOS_ADVPORTAL_FLAG, ENABLE_BALANCE_SHEET,
    GW_ADMIN_FLAG, TRADE_HOLD_FLAG, MFA_REQUIRED_FLAG,
    NO_EMAIL_FLAG, NO_MAIL_FLAG, NO_PHONE_FLAG, NO_TEXTMESSAGE_FLAG,
    NO_IM_FLAG, NO_FAX_FLAG,
    DOC_PRIVATE_FLAG, DOC_NOTIFICATION_FLAG, PRIMARY_CONTACT_FLAG,
    SHOW_SLEEVE_ALLOCATION_TAB_CL_PORTAL_FLAG,
    SHOW_CLIENTS_TAB_CLIENT_PORTAL,
    USE_CUSTODIAN_DATA_AS_PRIMARY_FLAG,
    USE_PRIMARY_CLIENT_DATA_AS_PRIMARY_FLAG,
    LOGIN_STATUS_CD
  ) VALUES (
    '50000000000000000000000000000010', 4, 'tim10', '{SHA}Ih9xbw2j6OxaOp1gkQ1QigvWtmk=',
    1, 0, 0,
    SYSDATE, '50000000000000000000000000000010', SYSDATE, '50000000000000000000000000000010',
    1, 4358, 528,
    1, 0,
    1, 0,
    0, 0, 0,
    0, 0, 0, 0,
    0, 0,
    0, 0, 0,
    0,
    0,
    0,
    0,
    3
  );
EXCEPTION WHEN dup THEN NULL; END;
/

DECLARE dup EXCEPTION; PRAGMA EXCEPTION_INIT(dup, -1); BEGIN
  INSERT INTO USER_DETAIL_TBL (ENTITY_ID, GIVEN_NAME, SURNAME, COMMON_NAME, HIDE_DISABLED_WORKFLOWS)
  VALUES ('50000000000000000000000000000010', 'Tim', 'Arnold (Users)', 'Arnold, Tim (Users)', 0);
EXCEPTION WHEN dup THEN NULL; END;
/

DECLARE dup EXCEPTION; PRAGMA EXCEPTION_INIT(dup, -1); BEGIN
  INSERT INTO ENTITY_EMAIL_TBL (EMAIL_ID, ENTITY_ID, EMAIL_TYPE_CD, EMAIL, PRIMARY_EMAIL_FLAG, CREATED_DATE, CREATED_BY)
  VALUES ('50000000000000000000000000000F10', '50000000000000000000000000000010', 30, 'tim.a@geo.com', 1,
          SYSDATE, '50000000000000000000000000000010');
EXCEPTION WHEN dup THEN NULL; END;
/

DECLARE dup EXCEPTION; PRAGMA EXCEPTION_INIT(dup, -1); BEGIN
  INSERT INTO ENTITY_ROLE_TBL (ENTITY_ROLE_CD, ROLE_CD, ENTITY_ID, CREATED_BY, CREATED_DATE)
  VALUES (3847, 528, '50000000000000000000000000000010', '50000000000000000000000000000010', SYSDATE);
EXCEPTION WHEN dup THEN NULL; END;
/

-- ENTITY_TAX_DATA_TBL — same rationale as for tim5 above.
DECLARE dup EXCEPTION; PRAGMA EXCEPTION_INIT(dup, -1); BEGIN
  INSERT INTO ENTITY_TAX_DATA_TBL (ENTITY_ID) VALUES ('50000000000000000000000000000010');
EXCEPTION WHEN dup THEN NULL; END;
/

COMMIT;

-- ============================================================================
-- Verification
-- ============================================================================
SET LINES 200 PAGES 100;
COL LDAP_UID FOR A10
COL GIVEN_NAME FOR A10
COL SURNAME FOR A25
COL EMAIL FOR A20
SELECT e.LDAP_UID, e.ENTITY_ID, e.FIRM_CD, e.GW_ADMIN_FLAG,
       d.GIVEN_NAME, d.SURNAME, m.EMAIL
FROM   ENTITY_TBL e
JOIN   USER_DETAIL_TBL d ON d.ENTITY_ID = e.ENTITY_ID
JOIN   ENTITY_EMAIL_TBL m ON m.ENTITY_ID = e.ENTITY_ID
WHERE  e.LDAP_UID IN ('tim1','tim5','tim10')
ORDER  BY e.LDAP_UID;

EXIT;
