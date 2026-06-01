--------------------------------------------------------------------------------
-- V7__seed_firm1_firm_closure.sql
--
-- Completes firm 1's FIRM-CONFIG closure so the full Hibernate `Firm#1` entity
-- load succeeds. V2 seeded firm 1's people/accounts/positions but NOT the
-- firm-level config rows that Firm.hbm.xml eager-loads. The React bootstrap
-- (`ReactJsonIndexAction.indexCommonAsJSON` -> `BasicAction.getFirmKeyword` ->
-- `UserManager.loadFirm` -> `session.refresh(firm)`) refreshes the whole Firm
-- graph, and `refresh` cascades (cascade="all") to the eager many-to-ones:
--   businessAddress (BUSINESS_ADDRESS_ID -> ENTITY_ADDRESS_TBL)
--   adminsRole      (ADMINS_ROLE        -> ROLE_TBL)
--   allEmployeesRole(ALL_EMPLOYEES_ROLE -> ROLE_TBL)
--   defaultAccessSet(DEFAULT_ACCESS_SET -> ACCESS_SET_TBL, not-null="true")
-- If any target row is missing, the firm reload returns no row and Hibernate
-- throws `UnresolvableObjectException: [Firm#1]` -> indexCommonAsJSON returns an
-- empty body -> the SPA never renders the login screen. The hard blocker was the
-- missing DEFAULT_ACCESS_SET (305); the roles and business address are seeded
-- here too so a fresh `flyway migrate` reproduces a login-capable firm 1.
-- The constrained one-to-ones (firmServiceRequest / firmTaxData / firmSSO) are
-- included for completeness.
--
-- Provenance : extracted from a GP prod-shape source (firm 1 = GeoWealth), then
--              PII-SCRUBBED for this public repo (codes/relationships real; the
--              business address is synthetic). No FK ordering trick is needed:
--              FIRM_TBL has no outgoing DB-level FK on these columns (they are
--              Hibernate-only associations), and ROLE/ACCESS_SET/SR/TAX/SSO all
--              point back to FIRM_CD=1, which V2 already created.
-- Generated  : 2026-06-01. Applies on top of V1 (baseline) and V2 (firm 1).
--------------------------------------------------------------------------------

-- adminsRole (528) + allEmployeesRole (529) for firm 1
INSERT INTO ROLE_TBL (ROLE_CD,FIRM_CD,NAME,DESCRIPTION,DEFAULT_FLAG,CREATED_BY,CREATED_DATE,LAST_UPDATED_BY,LAST_UPDATED_DATE)
VALUES (528,1,'Admins','Firm Administrators',0,NULL,TO_DATE('2012-03-30 12:07:09','YYYY-MM-DD HH24:MI:SS'),NULL,TO_DATE('2026-05-29 19:14:21','YYYY-MM-DD HH24:MI:SS'));
INSERT INTO ROLE_TBL (ROLE_CD,FIRM_CD,NAME,DESCRIPTION,DEFAULT_FLAG,CREATED_BY,CREATED_DATE,LAST_UPDATED_BY,LAST_UPDATED_DATE)
VALUES (529,1,'All Employees','All employees of the firm has this role',1,NULL,TO_DATE('2012-03-30 12:07:09','YYYY-MM-DD HH24:MI:SS'),NULL,TO_DATE('2025-01-03 09:55:31','YYYY-MM-DD HH24:MI:SS'));

-- defaultAccessSet (305) — the not-null eager assoc that was the hard blocker
INSERT INTO ACCESS_SET_TBL (ACCESS_SET_CD,NAME,DEFAULT_FLAG,DESCRIPTION,FIRM_CD,CREATED_BY,CREATED_DATE,LAST_UPDATED_BY,LAST_UPDATED_DATE)
VALUES (305,'Default Firm AccessSet',1,'Default Firm AccessSet',1,NULL,TO_DATE('2012-03-30 15:14:35','YYYY-MM-DD HH24:MI:SS'),NULL,TO_DATE('2026-05-29 19:14:21','YYYY-MM-DD HH24:MI:SS'));

-- businessAddress (BUSINESS_ADDRESS_ID on FIRM_TBL firm 1) — synthetic values
INSERT INTO ENTITY_ADDRESS_TBL (ADDRESS_ID,ENTITY_ID,ADDRESS_TYPE_CD,ADDRESS_LINE1,ADDRESS_LINE2,ADDRESS_LINE3,CITY,STATE,COUNTRY,POSTAL_CODE,PRIMARY_ADDRESS_FLAG,COUNTY,CREATED_DATE,CREATED_BY,LAST_UPDATE_DATE,LAST_UPDATE_BY,CRM_ID)
VALUES ('2C9F36C970164C198B6CB6F4FD3A5D24',NULL,10,'200 W Madison St',NULL,NULL,'Chicago','IL','US','60606',0,'Cook',NULL,NULL,TO_DATE('2026-03-18 18:49:12','YYYY-MM-DD HH24:MI:SS'),NULL,445912);

-- constrained one-to-ones referenced by Firm.hbm.xml (firm 1)
INSERT INTO FIRM_SERVICE_REQUEST_TBL (FIRM_CD,SR_CENTER_ENABLED_FLAG,OTHER_SERVICE_REQUESTS_FLAG,OTHER_SERVICE_REQUESTS_URL,OTHER_SERVICE_REQUESTS_OA_FLAG,SR_DISCRETIONARY_TRADING_EXECUTION_TYPE_CD,SR_DISCRETIONARY_TRADING_CUT_OFF_HOUR_TYPE,OTHER_SERVICE_REQUESTS_LABEL,REDIRECT_DISCRETIONARY_SRS_TO_FIRM_CD,SKIP_PASSWORD_VERIFICATION_FLAG)
VALUES (1,0,0,NULL,0,0,'THIRTEEN','Other Service Requests',NULL,0);
INSERT INTO FIRM_TAX_DATA_TBL (FIRM_CD,TE_PERMISSION_LEVEL_CD,TLH_PERMISSION_LEVEL_CD,TAX_TRANSITION_PERMISSION_LEVEL_CD,NEW_TAX_TIERS_AVAILABLE_FLAG)
VALUES (1,1,'enabled','off',0);
INSERT INTO FIRM_SSO_TBL (FIRM_CD,ENFORCEMENT_TYPE,CONFIG,LAST_UPDATED_DATE,LAST_UPDATED_BY)
VALUES (1,'off',NULL,NULL,NULL);

COMMIT;
