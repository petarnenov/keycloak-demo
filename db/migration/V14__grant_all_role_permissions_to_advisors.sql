--------------------------------------------------------------------------------
-- V14__grant_all_role_permissions_to_advisors.sql
--
-- The REAL capability grant. The portal builds a logged user's permission map
-- (LoggedUserJTO -> PolicyRuleManager.loadCreateExecutePermissions ->
-- PolicyRuleManagerTrait/LoadCreateExecutePermissions) purely from the user's
-- ROLES and each role's ObjectTypePermissions:
--     roles = authDao.loadRolesForEntity(user)              -- ENTITY_ROLE_TBL
--     map   = roles.flatMap(Role::getObjectTypePermissions) -- ROLE_PERMISSION_TBL
-- It does NOT read ENTITY_PERMISSION_OVERRIDE_TBL (that table, granted in V13, is
-- only the "Adviser Permissioning" per-entity override and does not feed this map).
-- The seeded advisors had NO entity-role at all, so the map was empty and every
-- capability feature flag (PowerAdvisor, ModifyDashboard, CanExecute*, 55-IP, ...)
-- was false.
--
-- Steps:
--   0. seed the firm 3 / firm 40 admin+all-employees roles (firm 1's 528/529 came
--      from V7); they are the targets of FIRM_TBL.ADMINS_ROLE for those firms and
--      were never seeded (V3/V4 seed people, not ROLE_TBL).
--   1. assign each advisor their firm's admins role (firm1->528, firm3->554,
--      firm40->672) via ENTITY_ROLE_TBL (ENTITY_ROLE_SEQ for the PK).
--   2. grant every catalog (object type x verb) permission (208) to those admin
--      roles via ROLE_PERMISSION_TBL.
-- All idempotent (NOT EXISTS), driven by INSERT ... SELECT over the V12 catalog.
-- Requires V7 (firm1 roles) and V12 (catalog).
--
-- Generated : 2026-06-01.
--------------------------------------------------------------------------------

-- 0. seed firm 3 / firm 40 roles (FIRM_CD -> FIRM_TBL already present from V3/V4)
INSERT INTO ROLE_TBL (ROLE_CD,FIRM_CD,NAME,DESCRIPTION,DEFAULT_FLAG)
SELECT 554,3,'Admins','Firm Administrators',0 FROM dual WHERE NOT EXISTS (SELECT 1 FROM role_tbl WHERE role_cd=554);
INSERT INTO ROLE_TBL (ROLE_CD,FIRM_CD,NAME,DESCRIPTION,DEFAULT_FLAG)
SELECT 555,3,'All Employees','All employees of the firm has this role',1 FROM dual WHERE NOT EXISTS (SELECT 1 FROM role_tbl WHERE role_cd=555);
INSERT INTO ROLE_TBL (ROLE_CD,FIRM_CD,NAME,DESCRIPTION,DEFAULT_FLAG)
SELECT 672,40,'Admins','Firm Administrators',0 FROM dual WHERE NOT EXISTS (SELECT 1 FROM role_tbl WHERE role_cd=672);
INSERT INTO ROLE_TBL (ROLE_CD,FIRM_CD,NAME,DESCRIPTION,DEFAULT_FLAG)
SELECT 673,40,'All Employees','All employees of the firm has this role',1 FROM dual WHERE NOT EXISTS (SELECT 1 FROM role_tbl WHERE role_cd=673);

-- 1. assign each advisor their firm's admins role
INSERT INTO ENTITY_ROLE_TBL (ENTITY_ROLE_CD, ROLE_CD, ENTITY_ID, CREATED_DATE, LAST_UPDATED_DATE)
SELECT ENTITY_ROLE_SEQ.NEXTVAL, f.admins_role, e.entity_id, SYSDATE, SYSDATE
  FROM entity_tbl e
  JOIN firm_tbl f ON f.firm_cd = e.firm_cd
 WHERE e.ldap_uid IN ('tim1','tim3','tim40')
   AND e.firm_cd IN (1,3,40)
   AND f.admins_role IS NOT NULL
   AND NOT EXISTS (
        SELECT 1 FROM entity_role_tbl er
         WHERE er.entity_id = e.entity_id AND er.role_cd = f.admins_role);

-- 2. give those admin roles every (object type x verb) permission in the catalog
INSERT INTO ROLE_PERMISSION_TBL (ROLE_CD, OBJECTTYPE_PERMISSION_CD, CREATED_DATE, LAST_UPDATED_DATE)
SELECT r.role_cd, otp.objecttype_permission_cd, SYSDATE, SYSDATE
  FROM (SELECT 528 role_cd FROM dual
        UNION ALL SELECT 554 FROM dual
        UNION ALL SELECT 672 FROM dual) r
 CROSS JOIN objecttype_permission_tbl otp
 WHERE NOT EXISTS (
        SELECT 1 FROM role_permission_tbl rp
         WHERE rp.role_cd = r.role_cd
           AND rp.objecttype_permission_cd = otp.objecttype_permission_cd);

COMMIT;
