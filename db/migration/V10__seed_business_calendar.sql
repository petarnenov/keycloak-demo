--------------------------------------------------------------------------------
-- V10__seed_business_calendar.sql
--
-- Seeds BUSINESS_DAY_TBL (the platform business calendar). The post-login portal
-- bootstrap (`PortalCacheControler.getNomenclatures` ->
-- `generateNomenclatureMeta`) looks up "today"'s BusinessDay and calls
-- `getWorkDayFlag()` on it; with an empty calendar `today` is null ->
-- NullPointerException -> `GetNomenclaturesMsg failed` -> the React dashboard
-- hangs on the loading spinner after an otherwise-successful login.
--
-- The baseline schema creates the table but the calendar is operational data, not
-- captured by the firm seeds. We generate a plain Mon-Fri working calendar for a
-- wide static range so "today" resolves for any near-term demo run. Real market
-- holidays are not modelled (not needed for the demo's nomenclature bootstrap).
--
-- Generated : 2026-06-01.
--------------------------------------------------------------------------------

DECLARE
  d DATE := DATE '2024-01-01';
  e DATE := DATE '2030-12-31';
  wd NUMBER;
BEGIN
  WHILE d <= e LOOP
    wd := CASE WHEN TO_CHAR(d,'DY','NLS_DATE_LANGUAGE=ENGLISH') IN ('SAT','SUN') THEN 0 ELSE 1 END;
    INSERT INTO BUSINESS_DAY_TBL (CALENDAR_DATE, WORK_DAY_FLAG, BANK_WORKING_DAY_FLAG)
    VALUES (d, wd, wd);
    d := d + 1;
  END LOOP;
  COMMIT;
END;
/
