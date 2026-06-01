--------------------------------------------------------------------------------
-- V16__seed_firm3_firm40_firm_closure.sql
--
-- Makes firms 3 (CF Inc) and 40 (ClearPath) appear in the "Select Firm" picker
-- on /#platformOne/firmAdmin/users and load without crashing when selected.
--
-- Root cause: the picker is fed by /react/getWhitelabelFirmsList.do ->
-- WhitelabelAction.getFirmsList -> UserManager.loadFirmsForList, whose projection
-- query is:
--     select ... from Firm f JOIN f.firmServiceRequest fsr
--      where f.code is not null and f.inactiveFlag = false
-- The INNER JOIN on firmServiceRequest silently drops any firm without a
-- FIRM_SERVICE_REQUEST_TBL row. Firms 1 and 5 got their closure from V7/V8, so
-- only they showed; firms 3 and 40 (seeded by V3/V4) never got the firm-config
-- closure, so they were filtered out. Beyond the picker, selecting a firm runs
-- UserManager.loadFirm -> session.refresh(firm), which cascades (cascade="all")
-- to Firm.hbm.xml's eager many-to-ones — defaultAccessSet (DEFAULT_ACCESS_SET,
-- not-null), businessAddress, firmTaxData, firmSSO — so those are seeded here too
-- or the firm would fail to load exactly like firm 1 did before V7.
--
-- firm_tbl already points at these rows (firm 3: access_set 3162, address
-- 7256…; firm 40: access_set 4412, address 934D…); only the referenced rows
-- were missing. adminsRole / allEmployeesRole (554/555, 672/673) already exist
-- from V14, so they are not re-seeded here.
--
-- Provenance : config rows (ACCESS_SET / FIRM_SERVICE_REQUEST / FIRM_TAX_DATA)
--              copied verbatim from the real qa source (qa4db / ORCL12VM, GP).
--              The business addresses are SYNTHETIC (the qa source already held
--              scrubbed '********' placeholders; replaced here with realistic
--              values). FIRM_SSO is scrubbed to ('off', NULL): firm 3's real
--              config carried a live Azure AD SAML certificate + tenant id, which
--              must not land in a public repo (same treatment as firm 5's
--              DocuSign key in V8); firm 40 was already 'off'/NULL upstream.
--              Audit BY columns nulled (they reference entities not seeded here).
-- Idempotent : every INSERT is NOT EXISTS-guarded, so `make update` re-runs are
--              safe. Requires V3/V4 (firm rows) and V14 (firm 3/40 roles).
-- Generated  : 2026-06-01.
--------------------------------------------------------------------------------

-- defaultAccessSet (not-null eager assoc): firm 3 -> 3162, firm 40 -> 4412
INSERT INTO ACCESS_SET_TBL (ACCESS_SET_CD,NAME,DEFAULT_FLAG,DESCRIPTION,FIRM_CD,CREATED_BY,CREATED_DATE,LAST_UPDATED_BY,LAST_UPDATED_DATE)
SELECT 3162,'Default Firm AccessSet',1,'Default Firm AccessSet',3,NULL,NULL,NULL,TO_DATE('2015-04-24 05:26:12','YYYY-MM-DD HH24:MI:SS')
  FROM dual WHERE NOT EXISTS (SELECT 1 FROM access_set_tbl WHERE access_set_cd=3162);
INSERT INTO ACCESS_SET_TBL (ACCESS_SET_CD,NAME,DEFAULT_FLAG,DESCRIPTION,FIRM_CD,CREATED_BY,CREATED_DATE,LAST_UPDATED_BY,LAST_UPDATED_DATE)
SELECT 4412,'Default Firm AccessSet',1,'Default Firm AccessSet',40,NULL,NULL,NULL,TO_DATE('2025-09-04 15:45:56','YYYY-MM-DD HH24:MI:SS')
  FROM dual WHERE NOT EXISTS (SELECT 1 FROM access_set_tbl WHERE access_set_cd=4412);

-- businessAddress (BUSINESS_ADDRESS_ID on firm 3 / firm 40) — SYNTHETIC values
INSERT INTO ENTITY_ADDRESS_TBL (ADDRESS_ID,ENTITY_ID,ADDRESS_TYPE_CD,ADDRESS_LINE1,ADDRESS_LINE2,ADDRESS_LINE3,CITY,STATE,COUNTRY,POSTAL_CODE,PRIMARY_ADDRESS_FLAG,COUNTY,CREATED_DATE,CREATED_BY,LAST_UPDATE_DATE,LAST_UPDATE_BY,CRM_ID)
SELECT '72561638784249908DF4DC1A71323566',NULL,10,'120 N LaSalle St',NULL,NULL,'Chicago','IL','US','60602',0,'Cook',NULL,NULL,TO_DATE('2026-03-18 18:49:12','YYYY-MM-DD HH24:MI:SS'),NULL,445927
  FROM dual WHERE NOT EXISTS (SELECT 1 FROM entity_address_tbl WHERE address_id='72561638784249908DF4DC1A71323566');
INSERT INTO ENTITY_ADDRESS_TBL (ADDRESS_ID,ENTITY_ID,ADDRESS_TYPE_CD,ADDRESS_LINE1,ADDRESS_LINE2,ADDRESS_LINE3,CITY,STATE,COUNTRY,POSTAL_CODE,PRIMARY_ADDRESS_FLAG,COUNTY,CREATED_DATE,CREATED_BY,LAST_UPDATE_DATE,LAST_UPDATE_BY,CRM_ID)
SELECT '934D75B121B64633AEF58DB2AE0E360D',NULL,10,'8000 Maryland Ave',NULL,NULL,'Clayton','MO','US','63105',0,'St. Louis',NULL,NULL,TO_DATE('2026-03-18 18:49:12','YYYY-MM-DD HH24:MI:SS'),NULL,445936
  FROM dual WHERE NOT EXISTS (SELECT 1 FROM entity_address_tbl WHERE address_id='934D75B121B64633AEF58DB2AE0E360D');

-- firmServiceRequest — the row whose INNER JOIN gates the picker (real config)
INSERT INTO FIRM_SERVICE_REQUEST_TBL (FIRM_CD,SR_CENTER_ENABLED_FLAG,OTHER_SERVICE_REQUESTS_FLAG,OTHER_SERVICE_REQUESTS_URL,OTHER_SERVICE_REQUESTS_OA_FLAG,SR_DISCRETIONARY_TRADING_EXECUTION_TYPE_CD,SR_DISCRETIONARY_TRADING_CUT_OFF_HOUR_TYPE,OTHER_SERVICE_REQUESTS_LABEL,REDIRECT_DISCRETIONARY_SRS_TO_FIRM_CD,SKIP_PASSWORD_VERIFICATION_FLAG)
SELECT 3,0,0,NULL,0,0,'THIRTEEN','Other Service Requests',NULL,0
  FROM dual WHERE NOT EXISTS (SELECT 1 FROM firm_service_request_tbl WHERE firm_cd=3);
INSERT INTO FIRM_SERVICE_REQUEST_TBL (FIRM_CD,SR_CENTER_ENABLED_FLAG,OTHER_SERVICE_REQUESTS_FLAG,OTHER_SERVICE_REQUESTS_URL,OTHER_SERVICE_REQUESTS_OA_FLAG,SR_DISCRETIONARY_TRADING_EXECUTION_TYPE_CD,SR_DISCRETIONARY_TRADING_CUT_OFF_HOUR_TYPE,OTHER_SERVICE_REQUESTS_LABEL,REDIRECT_DISCRETIONARY_SRS_TO_FIRM_CD,SKIP_PASSWORD_VERIFICATION_FLAG)
SELECT 40,1,0,NULL,0,2,'SIXTEEN','Other Service Requests',5,0
  FROM dual WHERE NOT EXISTS (SELECT 1 FROM firm_service_request_tbl WHERE firm_cd=40);

-- firmTaxData (constrained one-to-one, real config)
INSERT INTO FIRM_TAX_DATA_TBL (FIRM_CD,TE_PERMISSION_LEVEL_CD,TLH_PERMISSION_LEVEL_CD,TAX_TRANSITION_PERMISSION_LEVEL_CD,NEW_TAX_TIERS_AVAILABLE_FLAG)
SELECT 3,0,'off','off',0
  FROM dual WHERE NOT EXISTS (SELECT 1 FROM firm_tax_data_tbl WHERE firm_cd=3);
INSERT INTO FIRM_TAX_DATA_TBL (FIRM_CD,TE_PERMISSION_LEVEL_CD,TLH_PERMISSION_LEVEL_CD,TAX_TRANSITION_PERMISSION_LEVEL_CD,NEW_TAX_TIERS_AVAILABLE_FLAG)
SELECT 40,0,'off','off',0
  FROM dual WHERE NOT EXISTS (SELECT 1 FROM firm_tax_data_tbl WHERE firm_cd=40);

-- firmSSO (constrained one-to-one) — SCRUBBED to 'off'/NULL (no live SAML certs)
INSERT INTO FIRM_SSO_TBL (FIRM_CD,ENFORCEMENT_TYPE,CONFIG,LAST_UPDATED_DATE,LAST_UPDATED_BY)
SELECT 3,'off',NULL,NULL,NULL
  FROM dual WHERE NOT EXISTS (SELECT 1 FROM firm_sso_tbl WHERE firm_cd=3);
INSERT INTO FIRM_SSO_TBL (FIRM_CD,ENFORCEMENT_TYPE,CONFIG,LAST_UPDATED_DATE,LAST_UPDATED_BY)
SELECT 40,'off',NULL,NULL,NULL
  FROM dual WHERE NOT EXISTS (SELECT 1 FROM firm_sso_tbl WHERE firm_cd=40);

COMMIT;
