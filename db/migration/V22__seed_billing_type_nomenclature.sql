-- Seed BILLING_TYPE_TBL — the firm-dependent billing-type nomenclature P1
-- reads at React app load via /ux/nomenclatures.do. Without it, Hibernate's
-- lazy proxy for NBillingType#1 throws ObjectNotFoundException inside
-- PortalCacheControler.generateFirmDependantNomenclaturesList, the JSON
-- response is broken, and the React app retries the endpoint indefinitely
-- (a visible "endless loop" symptom in the browser).
--
-- The V1 baseline DDL carries an inline comment naming the two canonical
-- codes:
--   BILLING_TYPE_CD = 1 -> "Advisor"
--   BILLING_TYPE_CD = 2 -> "Money Manager"
-- (search V1__baseline_schema.sql for `1 - "Advisor", 2 - "Money Manager"`).
--
-- The seed migrations V2..V21 cover firms / entities / accounts but the
-- nomenclature / lookup tables were not extracted, leaving a data gap that
-- only surfaces when the React UI calls /ux/nomenclatures.do (other flows —
-- login, billing dashboard, household drilldown — don't touch this code
-- path).
--
-- PARENT_BILLING_TYPE_CD is NOT NULL but has no FK constraint; the
-- established convention in this hierarchical-lookup schema is self-reference
-- for root nodes (PARENT = own CD).

INSERT INTO BILLING_TYPE_TBL (BILLING_TYPE_CD, NAME, PARENT_BILLING_TYPE_CD)
  VALUES (1, 'Advisor', 1);

INSERT INTO BILLING_TYPE_TBL (BILLING_TYPE_CD, NAME, PARENT_BILLING_TYPE_CD)
  VALUES (2, 'Money Manager', 2);

COMMIT;
