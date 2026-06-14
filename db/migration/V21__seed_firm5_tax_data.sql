--------------------------------------------------------------------------------
-- V21__seed_firm5_tax_data.sql
--
-- Seeds the ENTITY_TAX_DATA shared-PK one-to-one row for the firm-5 (CreativeOne
-- / c1wealth) entities. Same fix as V9/V11, extended to firm 5: `NEntity.toUser()`
-- dereferences the `taxData` proxy (`getTaxBudgetPermissionLevel()`), and if the
-- row is absent Hibernate throws
-- `ObjectNotFoundException: [NEntityTaxData#<entityId>]`, which surfaces here as a
-- `usermanager` ServiceException when KeycloakClientRedirectRegistrar / the
-- whitelabel firm-switch resolves a firm-5 account to a User
-- (OidcCallbackAction.switchToFirmAccount → UserManager.lookupByUserUUID).
--
-- V17 (firm-5 c1wealth data) and V18 (firm-5 john link) created these entities
-- but not their tax-data rows, so the "open the firm-5 whitelabel host → switch
-- to john" flow failed: switchToFirmAccount caught the lookup exception, returned
-- null, and the gwAdmin fallback kept the login identity (tim1) instead of
-- becoming john.
--
-- ENTITY_TAX_DATA_TBL is keyed by ENTITY_ID; the rows carry NULL permission
-- levels (toUser() then falls back to the firm-level tax data), so each row only
-- needs to EXIST. Idempotent: NOT EXISTS guard so a re-run / partial prior seed
-- is safe.
--
-- Generated : 2026-06-14. Applies on top of V17 (firm 5) and V18 (firm-5 john).
--------------------------------------------------------------------------------

-- bavis — firm-5 advisor (V17)
INSERT INTO ENTITY_TAX_DATA_TBL (ENTITY_ID,TE_PERMISSION_LEVEL_CD,TLH_PERMISSION_LEVEL_CD,TAX_TRANSITION_PERMISSION_LEVEL_CD)
SELECT '09E101E6719E41AA8D8283F6909AE47B',NULL,NULL,NULL FROM dual
WHERE NOT EXISTS (SELECT 1 FROM ENTITY_TAX_DATA_TBL WHERE ENTITY_ID='09E101E6719E41AA8D8283F6909AE47B');

-- john — firm-5 advisor linked to firm-1 tim1 (V18); the whitelabel firm-switch target
INSERT INTO ENTITY_TAX_DATA_TBL (ENTITY_ID,TE_PERMISSION_LEVEL_CD,TLH_PERMISSION_LEVEL_CD,TAX_TRANSITION_PERMISSION_LEVEL_CD)
SELECT '019E8978F9AE7676915751838956A526',NULL,NULL,NULL FROM dual
WHERE NOT EXISTS (SELECT 1 FROM ENTITY_TAX_DATA_TBL WHERE ENTITY_ID='019E8978F9AE7676915751838956A526');

-- firm-5 client (V17)
INSERT INTO ENTITY_TAX_DATA_TBL (ENTITY_ID,TE_PERMISSION_LEVEL_CD,TLH_PERMISSION_LEVEL_CD,TAX_TRANSITION_PERMISSION_LEVEL_CD)
SELECT '60A80D957437415886BEE723B725ED22',NULL,NULL,NULL FROM dual
WHERE NOT EXISTS (SELECT 1 FROM ENTITY_TAX_DATA_TBL WHERE ENTITY_ID='60A80D957437415886BEE723B725ED22');

-- firm-5 entity (V17)
INSERT INTO ENTITY_TAX_DATA_TBL (ENTITY_ID,TE_PERMISSION_LEVEL_CD,TLH_PERMISSION_LEVEL_CD,TAX_TRANSITION_PERMISSION_LEVEL_CD)
SELECT 'D9E9447EAA1641F8B7D297981595AD7B',NULL,NULL,NULL FROM dual
WHERE NOT EXISTS (SELECT 1 FROM ENTITY_TAX_DATA_TBL WHERE ENTITY_ID='D9E9447EAA1641F8B7D297981595AD7B');
