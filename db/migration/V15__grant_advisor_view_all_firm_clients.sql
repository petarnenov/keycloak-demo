--------------------------------------------------------------------------------
-- V15__grant_advisor_view_all_firm_clients.sql
--
-- Client/entity VISIBILITY is a separate authorization layer from the capability
-- matrix (V12-V14). The portal gates "open this client" with
-- PolicyRuleManager.canLoggedUserViewEntity -> canUserDoObject(user,
-- ObjectType.CLIENT(4), Permission.VIEW(1), entityId) -> a POLICY_RULE_TBL row
-- keyed by (ENTITY_ID, PERMISSION_CD, OBJECT_TYPE_CD, OBJECT_ID). gwAdmin does NOT
-- bypass it.
--
-- The seeds (V2/V3/V4) already gave each advisor VIEW grants for the specific
-- clients they seeded, so today this migration is effectively a no-op. It exists
-- as a FUTURE-PROOF, idempotent safety net: every demo advisor (tim1/tim3/tim40)
-- gets a VIEW(CLIENT) policy rule for every non-employee entity in their own firm,
-- so any client added later is automatically visible. POLICY_RULE_KEY is a fresh
-- hex GUID (the column is a 32-char CHAR, no sequence).
--
-- NOTE this only grants directory VISIBILITY. It does not seed the per-client
-- chart/performance data, so opening a seeded client can still return a generic
-- "System error" until that data exists — a separate gap, out of scope here.
--
-- Generated : 2026-06-01.
--------------------------------------------------------------------------------

INSERT INTO POLICY_RULE_TBL (POLICY_RULE_KEY, ENTITY_ID, PERMISSION_CD, OBJECT_ID, OBJECT_TYPE_CD, FIRM_CD)
SELECT RAWTOHEX(SYS_GUID()), adv.entity_id, 1 /*VIEW*/, cli.entity_id, 4 /*CLIENT*/, adv.firm_cd
  FROM entity_tbl adv
  JOIN entity_tbl cli
    ON cli.firm_cd = adv.firm_cd
   AND cli.entity_type_cd <> 4          -- exclude employees/advisors
   AND cli.entity_id <> adv.entity_id
 WHERE adv.ldap_uid IN ('tim1','tim3','tim40')
   AND adv.firm_cd IN (1,3,40)
   AND NOT EXISTS (
        SELECT 1 FROM policy_rule_tbl pr
         WHERE pr.entity_id      = adv.entity_id
           AND pr.object_id      = cli.entity_id
           AND pr.permission_cd  = 1
           AND pr.object_type_cd = 4);

COMMIT;
