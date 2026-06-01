--------------------------------------------------------------------------------
-- V13__grant_all_permissions_to_advisors.sql
--
-- Grants ABSOLUTELY ALL permissions to the demo advisors tim1 (firm 1), tim3
-- (firm 3) and tim40 (firm 40): every (object type x verb) entry in the
-- OBJECTTYPE_PERMISSION_TBL catalog (208 of them) is granted to each advisor via
-- ENTITY_PERMISSION_OVERRIDE_TBL with ON_OFF_FLAG=1 (explicit ON) — the exact
-- mechanism the "Adviser Permissioning" admin screen writes. 208 x 3 = 624 grants.
--
-- INSERT ... SELECT over the catalog (not 624 literals) so it always grants the
-- full catalog; NOT EXISTS makes it idempotent against a reseed. Requires V12
-- (the catalog) to be present.
--
-- Generated : 2026-06-01.
--------------------------------------------------------------------------------

INSERT INTO ENTITY_PERMISSION_OVERRIDE_TBL (ENTITY_ID, ON_OFF_FLAG, OBJECTTYPE_PERMISSION_CD)
SELECT e.entity_id, 1, otp.objecttype_permission_cd
  FROM entity_tbl e
 CROSS JOIN objecttype_permission_tbl otp
 WHERE e.ldap_uid IN ('tim1','tim3','tim40')
   AND e.firm_cd IN (1,3,40)
   AND NOT EXISTS (
        SELECT 1 FROM entity_permission_override_tbl o
         WHERE o.entity_id = e.entity_id
           AND o.objecttype_permission_cd = otp.objecttype_permission_cd);

COMMIT;
