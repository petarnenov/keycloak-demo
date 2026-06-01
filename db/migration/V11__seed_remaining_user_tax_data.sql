--------------------------------------------------------------------------------
-- V11__seed_remaining_user_tax_data.sql
--
-- Extends V9 to the remaining seeded login users so EVERY user across the current
-- firms can complete `NEntity.toUser()` (which derefs the ENTITY_TAX_DATA shared-PK
-- proxy) and log in like tim1. After V9 only `rjohnson` (firm 40) was still
-- missing its tax-data row. Permission levels are NULL — the row just needs to
-- exist. The matching password hashes live in the gitignored db/local migration
-- (credentials are never committed).
--
-- Generated : 2026-06-01. Applies on top of V4 (firm 40) and V9.
--------------------------------------------------------------------------------

-- rjohnson (firm 40)
INSERT INTO ENTITY_TAX_DATA_TBL (ENTITY_ID,TE_PERMISSION_LEVEL_CD,TLH_PERMISSION_LEVEL_CD,TAX_TRANSITION_PERMISSION_LEVEL_CD)
VALUES ('52AEF2136D404AA18541CD4446E8F2C1',NULL,NULL,NULL);

COMMIT;
