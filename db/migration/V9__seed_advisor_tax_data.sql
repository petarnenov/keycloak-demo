--------------------------------------------------------------------------------
-- V9__seed_advisor_tax_data.sql
--
-- Seeds the ENTITY_TAX_DATA shared-PK one-to-one row for the seeded login
-- advisors. `NEntity.toUser()` runs on EVERY login (direct or SAML-brokered) and
-- dereferences the `taxData` proxy (`getTaxBudgetPermissionLevel()`); if the row
-- is absent Hibernate throws `ObjectNotFoundException: [NEntityTaxData#<entityId>]`
-- and the login dies with a generic "General Error". V2/V3 seeded the advisor
-- ENTITY but not its tax-data row.
--
-- ENTITY_TAX_DATA_TBL is keyed by ENTITY_ID (= the entity's own id). The source
-- rows carry NULL permission levels, so the row only needs to EXIST. tim40
-- (firm 40) already got its row from the firm-40 seed; tim1 (firm 1) and tim3
-- (firm 3) were missing.
--
-- Generated : 2026-06-01. Applies on top of V2 (firm 1) and V3 (firm 3).
--------------------------------------------------------------------------------

-- tim1 (firm 1) — entity_id matches V2's seeded advisor
INSERT INTO ENTITY_TAX_DATA_TBL (ENTITY_ID,TE_PERMISSION_LEVEL_CD,TLH_PERMISSION_LEVEL_CD,TAX_TRANSITION_PERMISSION_LEVEL_CD)
VALUES ('6459DFB4414B47DE9BFE9AC06205BD43',NULL,NULL,NULL);

-- tim3 (firm 3) — entity_id matches V3's seeded advisor
INSERT INTO ENTITY_TAX_DATA_TBL (ENTITY_ID,TE_PERMISSION_LEVEL_CD,TLH_PERMISSION_LEVEL_CD,TAX_TRANSITION_PERMISSION_LEVEL_CD)
VALUES ('019E1AE7C9D0738687919D666E4DDE4C',NULL,NULL,NULL);

COMMIT;
