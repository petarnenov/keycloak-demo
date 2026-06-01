--------------------------------------------------------------------------------
-- env_objects.sql   (OPTIONAL / ENVIRONMENT-SPECIFIC - apply by hand)
--
-- These objects are deliberately kept OUT of the versioned Flyway baseline
-- (db/migration/V1__baseline_schema.sql) because they are tied to a concrete
-- deployment and almost always need editing before they will work elsewhere.
-- Apply selectively, after the baseline, only on environments that need them.
--
-- Provenance: PROD 192.168.1.42 / ORCL12VM, schema GP, 2026-06-01.
--
-- MANUAL FIX-UPS REQUIRED:
--   * DATABASE LINKS - the dictionary does NOT expose the stored password, so
--     the CREATE DATABASE LINK statements below have no credentials. Add the
--     correct "IDENTIFIED BY <pwd>" and target connect string per environment.
--   * DBMS_SCHEDULER JOBS - review schedules / enabled state before running;
--     they may fire immediately.
--   * MATERIALIZED VIEWS - depend on base tables from the baseline; create them
--     only after V1 has been applied. Initial refresh can be heavy.
--
-- NOT INCLUDED (cannot be reproduced from DDL):
--   * 21 Java CLASS objects + 1 Java RESOURCE were loaded as COMPILED binaries;
--     there is no JAVA SOURCE in the dictionary to extract. Reload them with
--     loadjava from the original .jar/.class artifacts.
--------------------------------------------------------------------------------


--------------------------------------------------------------------------------
-- SECTION: DATABASE LINKS (passwords missing - see header)
--------------------------------------------------------------------------------


  CREATE DATABASE LINK "NETFLQA.NETFOLIO.COM"
   CONNECT TO "NF_MAIN" IDENTIFIED BY VALUES ':1'
   USING 'netflqa.netfolio.com';


  CREATE DATABASE LINK "NF_MAIN_OPS.NETFOLIO.COM"
   CONNECT TO "NF_MAIN" IDENTIFIED BY VALUES ':1'
   USING 'netflqa.netfolio.com';


  CREATE DATABASE LINK "PROD.NETFOLIO.COM"
   CONNECT TO "NF_MAIN" IDENTIFIED BY VALUES ':1'
   USING 'netfprd.breakaway.com';



--------------------------------------------------------------------------------
-- SECTION: DBMS_SCHEDULER JOBS
--------------------------------------------------------------------------------



BEGIN
dbms_scheduler.create_job('"ADV_SEGMENTADV_125997"',
job_type=>'PLSQL_BLOCK', job_action=>
'DECLARE
 taskname varchar2(100);
 BEGIN
 taskname := ''SEGMENTADV_125997'';
 dbms_advisor.reset_task(taskname);
 dbms_advisor.execute_task(taskname);
 END;'
, number_of_arguments=>0,
start_date=>TO_TIMESTAMP_TZ('25-NOV-2012 01.07.25.914139000 PM EST5EDT','DD-MON-RRRR HH.MI.SSXFF AM TZR','NLS_DATE_LANGUAGE=english'), repeat_interval=>
NULL
, end_date=>NULL,
job_class=>'"DEFAULT_JOB_CLASS"', enabled=>FALSE, auto_drop=>FALSE,comments=>
NULL
);
sys.dbms_scheduler.set_attribute('"ADV_SEGMENTADV_125997"','NLS_ENV','NLS_LANGUAGE=''AMERICAN'' NLS_TERRITORY=''AMERICA'' NLS_CURRENCY=''$'' NLS_ISO_CURRENCY=''AMERICA'' NLS_NUMERIC_CHARACTERS=''.,'' NLS_CALENDAR=''GREGORIAN'' NLS_DATE_FORMAT=''DD-MON-RR'' NLS_DATE_LANGUAGE=''AMERICAN'' NLS_SORT=''BINARY'' NLS_TIME_FORMAT=''HH.MI.SSXFF AM'' NLS_TIMESTAMP_FORMAT=''DD-MON-RR HH.MI.SSXFF AM'' NLS_TIME_TZ_FORMAT=''HH.MI.SSXFF AM TZR'' NLS_TIMESTAMP_TZ_FORMAT=''DD-MON-RR HH.MI.SSXFF AM TZR'' NLS_DUAL_CURRENCY=''$'' NLS_COMP=''BINARY'' NLS_LENGTH_SEMANTICS=''BYTE'' NLS_NCHAR_CONV_EXCP=''FALSE''');
dbms_scheduler.set_attribute('"ADV_SEGMENTADV_125997"','logging_level',DBMS_SCHEDULER.LOGGING_RUNS);
COMMIT;
END;
/



BEGIN
dbms_scheduler.create_job('"DELETECLOSEDACCOUNTS_EODCOMPONENT_VALUE_JOB"',
job_type=>'PLSQL_BLOCK', job_action=>
'
declare
l_rows NUMBER;
l_begin_time timestamp;
l_end_time   timestamp;
begin
select systimestamp into l_begin_time from dual;
delete from GP.EOD_COMPONENT_VALUE_TBL eod
where exists (select 1 from EQIS_CLOSE_ACCOUNT_TNP z where z.NF_ACCOUNT_ID = eod.NF_ACCOUNT_ID) and rownum <500001;
l_rows := SQL%ROWCOUNT;
insert into COMMON_JOB_LOG_TBL values ( ''DeleteClosedAccounts_EodComponent_Value_job'', l_begin_time, systimestamp , systimestamp - l_begin_time , l_rows, ''SID: '' || to_char( sys_context (''userenv'',''SID'') )  );
commit;
exception when  NO_DATA_FOUND then
   null;
end;
'
, number_of_arguments=>0,
start_date=>TO_TIMESTAMP_TZ('24-AUG-2023 04.35.22.672690000 AM AMERICA/NEW_YORK','DD-MON-RRRR HH.MI.SSXFF AM TZR','NLS_DATE_LANGUAGE=english'), repeat_interval=>
'FREQ = DAILY; BYHOUR = 23; BYMINUTE = 10'
, end_date=>NULL,
job_class=>'"DEFAULT_JOB_CLASS"', enabled=>FALSE, auto_drop=>FALSE,comments=>
'Delete closed accounts from EOD_COMPONENT_VALUE_TBL Job'
);
sys.dbms_scheduler.set_attribute('"DELETECLOSEDACCOUNTS_EODCOMPONENT_VALUE_JOB"','NLS_ENV','NLS_LANGUAGE=''ENGLISH'' NLS_TERRITORY=''UNITED KINGDOM'' NLS_CURRENCY=''£'' NLS_ISO_CURRENCY=''UNITED KINGDOM'' NLS_NUMERIC_CHARACTERS=''.,'' NLS_CALENDAR=''GREGORIAN'' NLS_DATE_FORMAT=''DD-MON-RR'' NLS_DATE_LANGUAGE=''ENGLISH'' NLS_SORT=''BINARY'' NLS_TIME_FORMAT=''HH24.MI.SSXFF'' NLS_TIMESTAMP_FORMAT=''DD-MON-RR HH24.MI.SSXFF'' NLS_TIME_TZ_FORMAT=''HH24.MI.SSXFF TZR'' NLS_TIMESTAMP_TZ_FORMAT=''DD-MON-RR HH24.MI.SSXFF TZR'' NLS_DUAL_CURRENCY=''€'' NLS_COMP=''BINARY'' NLS_LENGTH_SEMANTICS=''BYTE'' NLS_NCHAR_CONV_EXCP=''FALSE''');
dbms_scheduler.enable('"DELETECLOSEDACCOUNTS_EODCOMPONENT_VALUE_JOB"');
COMMIT;
END;
/



BEGIN
dbms_scheduler.create_job('"DELETE_TLH_JOB"',
job_type=>'PLSQL_BLOCK', job_action=>
'
declare
l_rows NUMBER;
l_begin_time timestamp;
l_end_time   timestamp;
begin
l_rows :=0;
select systimestamp into l_begin_time from dual;
-- Delete SQL operator
DELETE FROM TLH_INIT_DAILY_ACCOUNT_SUMMARY_TBL WHERE ASOFDATE < ADD_MONTHS(SYSDATE, -6);
l_rows := l_rows + SQL%ROWCOUNT;
DELETE FROM TLH_INIT_DAILY_ACCOUNT_SUMMARY_DETAIL_TBL a WHERE NOT EXISTS ( SELECT 1 FROM TLH_INIT_DAILY_ACCOUNT_SUMMARY_TBL b  WHERE b.TLH_INIT_DAILY_ACCOUNT_SUMMARY_ID = a.TLH_INIT_DAILY_ACCOUNT_SUMMARY_ID );
l_rows := l_rows + SQL%ROWCOUNT;
DELETE FROM TLH_RETURN_DAILY_ACCOUNT_SUMMARY_TBL WHERE ASOFDATE < ADD_MONTHS(SYSDATE, -6);
l_rows := l_rows + SQL%ROWCOUNT;
DELETE FROM TLH_RETURN_DAILY_ACCOUNT_SUMMARY_DETAIL_TBL a WHERE NOT EXISTS ( SELECT 1 FROM TLH_RETURN_DAILY_ACCOUNT_SUMMARY_TBL b WHERE b.TLH_RETURN_DAILY_ACCOUNT_SUMMARY_ID = a.TLH_RETURN_DAILY_ACCOUNT_SUMMARY_ID );
l_rows := l_rows + SQL%ROWCOUNT;
--
insert into COMMON_JOB_LOG_TBL values ( ''Delete_TLH_job'', l_begin_time, systimestamp , systimestamp - l_begin_time , l_rows, ''SID: '' || to_char( sys_context (''userenv'',''SID'') )  );
commit;
exception when  NO_DATA_FOUND then
   null;
end;
'
, number_of_arguments=>0,
start_date=>TO_TIMESTAMP_TZ('25-APR-2025 07.04.42.473991000 AM AMERICA/NEW_YORK','DD-MON-RRRR HH.MI.SSXFF AM TZR','NLS_DATE_LANGUAGE=english'), repeat_interval=>
'FREQ = MONTHLY; BYMONTHDAY = 20; BYHOUR = 5; BYMINUTE = 12'
, end_date=>NULL,
job_class=>'"DEFAULT_JOB_CLASS"', enabled=>FALSE, auto_drop=>FALSE,comments=>
'Delete old records from TLH tables Job'
);
sys.dbms_scheduler.set_attribute('"DELETE_TLH_JOB"','NLS_ENV','NLS_LANGUAGE=''AMERICAN'' NLS_TERRITORY=''AMERICA'' NLS_CURRENCY=''$'' NLS_ISO_CURRENCY=''AMERICA'' NLS_NUMERIC_CHARACTERS=''.,'' NLS_CALENDAR=''GREGORIAN'' NLS_DATE_FORMAT=''DD-MON-RR'' NLS_DATE_LANGUAGE=''AMERICAN'' NLS_SORT=''BINARY'' NLS_TIME_FORMAT=''HH.MI.SSXFF AM'' NLS_TIMESTAMP_FORMAT=''DD-MON-RR HH.MI.SSXFF AM'' NLS_TIME_TZ_FORMAT=''HH.MI.SSXFF AM TZR'' NLS_TIMESTAMP_TZ_FORMAT=''DD-MON-RR HH.MI.SSXFF AM TZR'' NLS_DUAL_CURRENCY=''$'' NLS_COMP=''BINARY'' NLS_LENGTH_SEMANTICS=''BYTE'' NLS_NCHAR_CONV_EXCP=''FALSE''');
dbms_scheduler.enable('"DELETE_TLH_JOB"');
COMMIT;
END;
/



BEGIN
dbms_scheduler.create_job('"GRANT_CROSS_PROC_JOB"',
job_type=>'PLSQL_BLOCK', job_action=>
'
DECLARE
i_granted number;
i_err     number ;
BEGIN
   i_granted:=0;
   i_err:=0;
   -- grant only missing ones
   FOR rec IN (
     (
     SELECT table_name o_name FROM dba_tables where owner=''GP''
     union
     SELECT view_name o_name FROM dba_views where owner=''GP''
     )
     minus
     select table_name o_name from dba_tab_privs  where owner=''GP'' and grantee=''EXT_LIEBERMANE'' and privilege=''SELECT'' and table_name not like ''BIN$%'' order by o_name
     ) LOOP
  begin
   --dbms_output.put_line( ''grant select on '' || ''GP."'' || rec.o_name || ''" to '' || ''GP_READER'' );
   EXECUTE IMMEDIATE ''grant select on '' || ''GP."'' || rec.o_name || ''" to '' || ''EXT_LIEBERMANE'';
   i_granted:=i_granted+1;
   EXCEPTION
   WHEN OTHERS THEN DBMS_OUTPUT.PUT_LINE(SQLERRM);
   i_err:=i_err+1;
  end;
   END LOOP;
insert into COMMON_JOB_LOG_TBL values ( ''Grant_Cross_Proc_job'', systimestamp, systimestamp , systimestamp - systimestamp , i_err , '' Errors. Granted select on : '' || i_granted || '' GP tables''   );
commit;
---------
-- AP schema
   i_granted:=0;
   i_err:=0;
   -- grant only missing ones
   FOR rec IN (
     SELECT table_name FROM dba_tables where owner=''AP''
     minus
     select table_name from role_tab_privs where owner=''AP'' and role=''AP_READER'' and privilege=''SELECT'' and table_name not like ''BIN$%'' order by  table_name
     ) LOOP
  begin
   --dbms_output.put_line( ''grant select on '' || ''AP."'' || rec.table_name || ''" to '' || ''AP_READER'' );
   EXECUTE IMMEDIATE ''grant select on '' || ''AP."'' || rec.table_name || ''" to '' || ''AP_READER'';
   EXECUTE IMMEDIATE ''grant select on '' || ''AP."'' || rec.table_name || ''" to '' || ''EXT_LIEBERMANE, RYAN_FADDEN, ALEX_STEWART'';
   i_granted:=i_granted+1;
   EXCEPTION
   WHEN OTHERS THEN DBMS_OUTPUT.PUT_LINE(SQLERRM);
   i_err:=i_err+1;
  end;
   END LOOP;
insert into COMMON_JOB_LOG_TBL values ( ''Grant_Cross_Proc_job'', systimestamp, systimestamp , systimestamp - systimestamp , i_err , '' Errors. Granted select on : '' || i_granted || '' AP tables''   );
commit;
-- CP schema
---------------------
   i_granted:=0;
   i_err:=0;
   -- grant only missing ones
   FOR rec IN (
     (
     SELECT table_name o_name FROM dba_tables where owner=''CP''
     union
     SELECT view_name o_name FROM dba_views where owner=''CP''
     )
     minus
     select table_name o_name from role_tab_privs where owner=''CP'' and role=''CP_READER'' and privilege=''SELECT'' and table_name not like ''BIN$%'' order by  o_name
     ) LOOP
  begin
   --dbms_output.put_line( ''grant select on '' || ''CP."'' || rec.o_name || ''" to '' || ''CP_READER'' );
   EXECUTE IMMEDIATE ''grant select on '' || ''CP."'' || rec.o_name || ''" to '' || ''CP_READER'';
   EXECUTE IMMEDIATE ''grant select on '' || ''CP."'' || rec.o_name || ''" to '' || ''EXT_LIEBERMANE, RYAN_FADDEN ,ALEX_STEWART'';
   i_granted:=i_granted+1;
   EXCEPTION
   WHEN OTHERS THEN DBMS_OUTPUT.PUT_LINE(SQLERRM);
   i_err:=i_err+1;
  end;
   END LOOP;
insert into COMMON_JOB_LOG_TBL values ( ''Grant_Cross_Proc_job'', systimestamp, systimestamp , systimestamp - systimestamp , i_err , '' Errors. Granted select on : '' || i_granted || '' CP tables''   );
commit;
END;
'
, number_of_arguments=>0,
start_date=>TO_TIMESTAMP_TZ('16-JAN-2025 04.52.23.642972000 AM AMERICA/NEW_YORK','DD-MON-RRRR HH.MI.SSXFF AM TZR','NLS_DATE_LANGUAGE=english'), repeat_interval=>
'FREQ = DAILY; BYHOUR = 23; BYMINUTE = 20'
, end_date=>NULL,
job_class=>'"DEFAULT_JOB_CLASS"', enabled=>FALSE, auto_drop=>FALSE,comments=>
'Grant select on GP tables to GP_READER role'
);
sys.dbms_scheduler.set_attribute('"GRANT_CROSS_PROC_JOB"','NLS_ENV','NLS_LANGUAGE=''AMERICAN'' NLS_TERRITORY=''AMERICA'' NLS_CURRENCY=''$'' NLS_ISO_CURRENCY=''AMERICA'' NLS_NUMERIC_CHARACTERS=''.,'' NLS_CALENDAR=''GREGORIAN'' NLS_DATE_FORMAT=''DD-MON-RR'' NLS_DATE_LANGUAGE=''AMERICAN'' NLS_SORT=''BINARY'' NLS_TIME_FORMAT=''HH.MI.SSXFF AM'' NLS_TIMESTAMP_FORMAT=''DD-MON-RR HH.MI.SSXFF AM'' NLS_TIME_TZ_FORMAT=''HH.MI.SSXFF AM TZR'' NLS_TIMESTAMP_TZ_FORMAT=''DD-MON-RR HH.MI.SSXFF AM TZR'' NLS_DUAL_CURRENCY=''$'' NLS_COMP=''BINARY'' NLS_LENGTH_SEMANTICS=''BYTE'' NLS_NCHAR_CONV_EXCP=''FALSE''');
COMMIT;
END;
/



BEGIN
dbms_scheduler.create_job('"GRANT_DEVELOPER_READER_ROLE_JOB"',
job_type=>'PLSQL_BLOCK', job_action=>
'
DECLARE
i_granted number;
i_err     number ;
BEGIN
   i_granted:=0;
   i_err:=0;
   -- grant only missing ones
   FOR rec IN (
        select obj.* from (
        SELECT
            ''grant ''
            || decode(object_type, ''PROCEDURE'', ''EXECUTE on "'', ''FUNCTION'', ''EXECUTE on "'', ''PACKAGE'', ''EXECUTE on "'', ''TABLE'', ''SELECT on "'', ''VIEW'',
                      ''SELECT on "'', ''SEQUENCE'', ''SELECT on "'') || owner || ''"."'' || object_name || ''" to DEVELOPER_READER'' str_grant ,
            decode(object_type, ''PROCEDURE'', ''EXECUTE'', ''FUNCTION'', ''EXECUTE'', ''PACKAGE'', ''EXECUTE'', ''TABLE'', ''SELECT'', ''VIEW'',
                      ''SELECT'', ''SEQUENCE'', ''SELECT'') str_privs, owner str_owner , object_name str_name
        FROM
            dba_objects do
        WHERE
                upper(object_name) = object_name  AND object_type in ( ''PROCEDURE'', ''FUNCTION'', ''PACKAGE'', ''TABLE'', ''VIEW'', ''SEQUENCE'' ) and owner in ( ''GP'', ''CP'', ''QP'',''AP'' )
        ) obj
        where not exists (
            select privs.* from
            ( select rtp.table_name o_name, rtp.owner o_owner, listagg( rtp.privilege, '','') WITHIN GROUP (ORDER BY rtp.privilege) AS o_privs from role_tab_privs rtp where owner=''GP'' and role=''DEVELOPER_READER'' group by rtp.owner,rtp.table_name) privs
            where privs.o_owner=obj.str_owner and privs.o_name=obj.str_name and privs.o_privs=obj.str_privs
        )
   ) LOOP
   begin
           --dbms_output.put_line( rec.str_grant || '';'' ); --|| ''   ''  || rec.str_privs  || rec.str_name  );
           execute immediate  rec.str_grant;
   i_granted:=i_granted+1;
   EXCEPTION
   WHEN OTHERS THEN DBMS_OUTPUT.PUT_LINE(SQLERRM);
   i_err:=i_err+1;
  END;
  END LOOP;
insert into COMMON_JOB_LOG_TBL values ( ''Grant_DEVELOPER_READER_ROLE_job'', systimestamp, systimestamp , systimestamp - systimestamp , i_err , '' Errors. Granted select on : '' || i_granted || '' GP tables''   );
commit;
END;
---------
'
, number_of_arguments=>0,
start_date=>TO_TIMESTAMP_TZ('18-DEC-2025 04.13.45.810040000 AM AMERICA/NEW_YORK','DD-MON-RRRR HH.MI.SSXFF AM TZR','NLS_DATE_LANGUAGE=english'), repeat_interval=>
'FREQ = DAILY; BYHOUR = 23; BYMINUTE = 28'
, end_date=>NULL,
job_class=>'"DEFAULT_JOB_CLASS"', enabled=>FALSE, auto_drop=>FALSE,comments=>
'Grant Reader access for GP tables to DEVELOPER_READER role'
);
sys.dbms_scheduler.set_attribute('"GRANT_DEVELOPER_READER_ROLE_JOB"','NLS_ENV','NLS_LANGUAGE=''AMERICAN'' NLS_TERRITORY=''AMERICA'' NLS_CURRENCY=''$'' NLS_ISO_CURRENCY=''AMERICA'' NLS_NUMERIC_CHARACTERS=''.,'' NLS_CALENDAR=''GREGORIAN'' NLS_DATE_FORMAT=''DD-MON-RR'' NLS_DATE_LANGUAGE=''AMERICAN'' NLS_SORT=''BINARY'' NLS_TIME_FORMAT=''HH.MI.SSXFF AM'' NLS_TIMESTAMP_FORMAT=''DD-MON-RR HH.MI.SSXFF AM'' NLS_TIME_TZ_FORMAT=''HH.MI.SSXFF AM TZR'' NLS_TIMESTAMP_TZ_FORMAT=''DD-MON-RR HH.MI.SSXFF AM TZR'' NLS_DUAL_CURRENCY=''$'' NLS_COMP=''BINARY'' NLS_LENGTH_SEMANTICS=''BYTE'' NLS_NCHAR_CONV_EXCP=''FALSE''');
dbms_scheduler.enable('"GRANT_DEVELOPER_READER_ROLE_JOB"');
COMMIT;
END;
/



BEGIN
dbms_scheduler.create_job('"GRANT_DEVELOPER_WRITER_ROLE_JOB"',
job_type=>'PLSQL_BLOCK', job_action=>
'
DECLARE
i_granted number;
i_err     number ;
BEGIN
   i_granted:=0;
   i_err:=0;
   -- grant only missing ones
   FOR rec IN (
        select obj.* from (
        SELECT
            ''grant ''
            || decode(object_type, ''PROCEDURE'', ''EXECUTE on "'', ''FUNCTION'', ''EXECUTE on "'', ''PACKAGE'', ''EXECUTE on "'',
            ''TABLE'', ''DELETE,INSERT,SELECT,UPDATE on "'',
            ''VIEW'',  ''SELECT on "'', ''SEQUENCE'', ''SELECT on "'') || owner || ''"."'' || object_name || ''" to DEVELOPER_WRITER'' str_grant ,
            decode(object_type, ''PROCEDURE'', ''EXECUTE'', ''FUNCTION'', ''EXECUTE'', ''PACKAGE'', ''EXECUTE'', ''TABLE'', ''DELETE,INSERT,SELECT,UPDATE'', ''VIEW'',
                      ''SELECT'', ''SEQUENCE'', ''SELECT'') str_privs, owner str_owner , object_name str_name, status str_status
        FROM
            dba_objects do
        WHERE
                upper(object_name) = object_name  AND object_type in ( ''PROCEDURE'', ''FUNCTION'', ''PACKAGE'', ''TABLE'', ''VIEW'', ''SEQUENCE'' ) and owner in (  ''GP'',''CP'', ''QP'',''AP'' )
                and status!=''INVALID''
--                and rownum < 501
        ) obj
        where not exists (
            select privs.* from
            ( select rtp.table_name o_name, rtp.owner o_owner, listagg( rtp.privilege, '','') WITHIN GROUP (ORDER BY rtp.privilege) AS o_privs from role_tab_privs rtp where owner=obj.str_owner and role=''DEVELOPER_WRITER'' group by rtp.owner,rtp.table_name) privs
            where privs.o_owner=obj.str_owner and privs.o_name=obj.str_name and privs.o_privs=obj.str_privs
        )
   ) LOOP
   begin
           --dbms_output.put_line( rec.str_grant || '';'' ); --|| ''   ''  || rec.str_privs  || rec.str_name  );
           execute immediate  rec.str_grant;
   i_granted:=i_granted+1;
   EXCEPTION
   WHEN OTHERS THEN DBMS_OUTPUT.PUT_LINE(SQLERRM);
   i_err:=i_err+1;
  END;
  END LOOP;
insert into COMMON_JOB_LOG_TBL values ( ''Grant_DEVELOPER_WRITER_ROLE_job'', systimestamp, systimestamp , systimestamp - systimestamp , i_err , '' Errors. Granted select_And_DML on : '' || i_granted || '' GP tables''   );
commit;
END;
---------
'
, number_of_arguments=>0,
start_date=>TO_TIMESTAMP_TZ('18-DEC-2025 04.11.09.717141000 AM AMERICA/NEW_YORK','DD-MON-RRRR HH.MI.SSXFF AM TZR','NLS_DATE_LANGUAGE=english'), repeat_interval=>
'FREQ = DAILY; BYHOUR = 23; BYMINUTE = 25'
, end_date=>NULL,
job_class=>'"DEFAULT_JOB_CLASS"', enabled=>FALSE, auto_drop=>FALSE,comments=>
'Grant Writer access for GP tables to DEVELOPER_WRITER role'
);
sys.dbms_scheduler.set_attribute('"GRANT_DEVELOPER_WRITER_ROLE_JOB"','NLS_ENV','NLS_LANGUAGE=''AMERICAN'' NLS_TERRITORY=''AMERICA'' NLS_CURRENCY=''$'' NLS_ISO_CURRENCY=''AMERICA'' NLS_NUMERIC_CHARACTERS=''.,'' NLS_CALENDAR=''GREGORIAN'' NLS_DATE_FORMAT=''DD-MON-RR'' NLS_DATE_LANGUAGE=''AMERICAN'' NLS_SORT=''BINARY'' NLS_TIME_FORMAT=''HH.MI.SSXFF AM'' NLS_TIMESTAMP_FORMAT=''DD-MON-RR HH.MI.SSXFF AM'' NLS_TIME_TZ_FORMAT=''HH.MI.SSXFF AM TZR'' NLS_TIMESTAMP_TZ_FORMAT=''DD-MON-RR HH.MI.SSXFF AM TZR'' NLS_DUAL_CURRENCY=''$'' NLS_COMP=''BINARY'' NLS_LENGTH_SEMANTICS=''BYTE'' NLS_NCHAR_CONV_EXCP=''FALSE''');
dbms_scheduler.enable('"GRANT_DEVELOPER_WRITER_ROLE_JOB"');
COMMIT;
END;
/



BEGIN
dbms_scheduler.create_job('"GRANT_GP_READER_JOB"',
job_type=>'PLSQL_BLOCK', job_action=>
'
DECLARE
i_granted number;
i_err     number ;
BEGIN
   i_granted:=0;
   i_err:=0;
   -- grant only missing ones
   FOR rec IN (
     SELECT table_name FROM dba_tables where owner=''GP''
     minus
     select table_name from role_tab_privs where owner=''GP'' and role=''GP_READER'' and privilege=''SELECT'' and table_name not like ''BIN$%'' order by  table_name
     ) LOOP
  begin
   --dbms_output.put_line( ''grant select on '' || ''GP."'' || rec.table_name || ''" to '' || ''GP_READER'' );
   EXECUTE IMMEDIATE ''grant select on '' || ''GP."'' || rec.table_name || ''" to '' || ''GP_READER'';
   EXECUTE IMMEDIATE ''grant select on '' || ''GP."'' || rec.table_name || ''" to '' || ''EXT_LIEBERMANE'';
   i_granted:=i_granted+1;
   EXCEPTION
   WHEN OTHERS THEN DBMS_OUTPUT.PUT_LINE(SQLERRM);
   i_err:=i_err+1;
  end;
   END LOOP;
insert into COMMON_JOB_LOG_TBL values ( ''Grant_GP_READER_job'', systimestamp, systimestamp , systimestamp - systimestamp , i_err , '' Errors. Granted select on : '' || i_granted || '' GP tables''   );
commit;
---------
-- AP schema
   i_granted:=0;
   i_err:=0;
   -- grant only missing ones
   FOR rec IN (
     SELECT table_name FROM dba_tables where owner=''AP''
     minus
     select table_name from role_tab_privs where owner=''AP'' and role=''AP_READER'' and privilege=''SELECT'' and table_name not like ''BIN$%'' order by  table_name
     ) LOOP
  begin
   --dbms_output.put_line( ''grant select on '' || ''AP."'' || rec.table_name || ''" to '' || ''AP_READER'' );
   EXECUTE IMMEDIATE ''grant select on '' || ''AP."'' || rec.table_name || ''" to '' || ''AP_READER'';
   EXECUTE IMMEDIATE ''grant select on '' || ''AP."'' || rec.table_name || ''" to '' || ''EXT_LIEBERMANE, RYAN_FADDEN, ALEX_STEWART'';
   i_granted:=i_granted+1;
   EXCEPTION
   WHEN OTHERS THEN DBMS_OUTPUT.PUT_LINE(SQLERRM);
   i_err:=i_err+1;
  end;
   END LOOP;
insert into COMMON_JOB_LOG_TBL values ( ''Grant_GP_READER_job'', systimestamp, systimestamp , systimestamp - systimestamp , i_err , '' Errors. Granted select on : '' || i_granted || '' AP tables''   );
commit;
-- CP schema
---------------------
   i_granted:=0;
   i_err:=0;
   -- grant only missing ones
   FOR rec IN (
     SELECT table_name FROM dba_tables where owner=''CP''
     minus
     select table_name from role_tab_privs where owner=''CP'' and role=''CP_READER'' and privilege=''SELECT'' and table_name not like ''BIN$%'' order by  table_name
     ) LOOP
  begin
   --dbms_output.put_line( ''grant select on '' || ''CP."'' || rec.table_name || ''" to '' || ''CP_READER'' );
   EXECUTE IMMEDIATE ''grant select on '' || ''CP."'' || rec.table_name || ''" to '' || ''CP_READER'';
   EXECUTE IMMEDIATE ''grant select on '' || ''CP."'' || rec.table_name || ''" to '' || ''EXT_LIEBERMANE, RYAN_FADDEN ,ALEX_STEWART'';
   i_granted:=i_granted+1;
   EXCEPTION
   WHEN OTHERS THEN DBMS_OUTPUT.PUT_LINE(SQLERRM);
   i_err:=i_err+1;
  end;
   END LOOP;
insert into COMMON_JOB_LOG_TBL values ( ''Grant_GP_READER_job'', systimestamp, systimestamp , systimestamp - systimestamp , i_err , '' Errors. Granted select on : '' || i_granted || '' CP tables''   );
commit;
   dbms_output.put_line( ''Granted select to102   '' || to_char(i_granted) || '' tables '');
   dbms_output.put_line( ''Errors '' || to_char(i_err) );
END;
'
, number_of_arguments=>0,
start_date=>TO_TIMESTAMP_TZ('15-JAN-2025 08.02.12.318606000 AM AMERICA/NEW_YORK','DD-MON-RRRR HH.MI.SSXFF AM TZR','NLS_DATE_LANGUAGE=english'), repeat_interval=>
'FREQ = DAILY; BYHOUR = 23; BYMINUTE = 20'
, end_date=>NULL,
job_class=>'"DEFAULT_JOB_CLASS"', enabled=>FALSE, auto_drop=>FALSE,comments=>
'Grant select on GP tables to GP_READER role'
);
sys.dbms_scheduler.set_attribute('"GRANT_GP_READER_JOB"','NLS_ENV','NLS_LANGUAGE=''AMERICAN'' NLS_TERRITORY=''AMERICA'' NLS_CURRENCY=''$'' NLS_ISO_CURRENCY=''AMERICA'' NLS_NUMERIC_CHARACTERS=''.,'' NLS_CALENDAR=''GREGORIAN'' NLS_DATE_FORMAT=''DD-MON-RR'' NLS_DATE_LANGUAGE=''AMERICAN'' NLS_SORT=''BINARY'' NLS_TIME_FORMAT=''HH.MI.SSXFF AM'' NLS_TIMESTAMP_FORMAT=''DD-MON-RR HH.MI.SSXFF AM'' NLS_TIME_TZ_FORMAT=''HH.MI.SSXFF AM TZR'' NLS_TIMESTAMP_TZ_FORMAT=''DD-MON-RR HH.MI.SSXFF AM TZR'' NLS_DUAL_CURRENCY=''$'' NLS_COMP=''BINARY'' NLS_LENGTH_SEMANTICS=''BYTE'' NLS_NCHAR_CONV_EXCP=''FALSE''');
COMMIT;
END;
/



BEGIN
dbms_scheduler.create_job('"INDEX_REBUILD_DAILY"',
job_type=>'PLSQL_BLOCK', job_action=>
'INDEX_REBUILD;'
, number_of_arguments=>0,
start_date=>TO_TIMESTAMP_TZ('30-JUN-2020 05.58.55.266665000 AM -04:00','DD-MON-RRRR HH.MI.SSXFF AM TZR','NLS_DATE_LANGUAGE=english'), repeat_interval=>
'freq=daily; byhour=03,13,23; byminute=0; bysecond=0'
, end_date=>NULL,
job_class=>'"DEFAULT_JOB_CLASS"', enabled=>FALSE, auto_drop=>TRUE,comments=>
NULL
);
sys.dbms_scheduler.set_attribute('"INDEX_REBUILD_DAILY"','NLS_ENV','NLS_LANGUAGE=''AMERICAN'' NLS_TERRITORY=''AMERICA'' NLS_CURRENCY=''$'' NLS_ISO_CURRENCY=''AMERICA'' NLS_NUMERIC_CHARACTERS=''.,'' NLS_CALENDAR=''GREGORIAN'' NLS_DATE_FORMAT=''DD-MON-RR'' NLS_DATE_LANGUAGE=''AMERICAN'' NLS_SORT=''BINARY'' NLS_TIME_FORMAT=''HH.MI.SSXFF AM'' NLS_TIMESTAMP_FORMAT=''DD-MON-RR HH.MI.SSXFF AM'' NLS_TIME_TZ_FORMAT=''HH.MI.SSXFF AM TZR'' NLS_TIMESTAMP_TZ_FORMAT=''DD-MON-RR HH.MI.SSXFF AM TZR'' NLS_DUAL_CURRENCY=''$'' NLS_COMP=''BINARY'' NLS_LENGTH_SEMANTICS=''BYTE'' NLS_NCHAR_CONV_EXCP=''FALSE''');
dbms_scheduler.enable('"INDEX_REBUILD_DAILY"');
COMMIT;
END;
/



BEGIN
dbms_scheduler.create_job('"PURGE_AT_TO_NON_EXISTING_IOES_JOB"',
job_type=>'PLSQL_BLOCK', job_action=>
'
declare
l_rows NUMBER;
l_begin_time timestamp;
l_end_time   timestamp;
begin
select systimestamp into l_begin_time from dual;
delete from GP.ACCOUNT_TRANSACTION_TBL at
where at.INST_ORDER_EXECUTED_ID is not null and not exists (select 1 from GP.INSTR_ORDER_EXECUTED_TBL ioe where ioe.INST_ORDER_EXECUTED_ID = at.INST_ORDER_EXECUTED_ID)
and at.TRANSACTION_DATE > sysdate - 91
;
l_rows := SQL%ROWCOUNT;
insert into COMMON_JOB_LOG_TBL values ( ''PURGE_AT_TO_NON_EXISTING_IOES_JOB'', l_begin_time, systimestamp , systimestamp - l_begin_time , l_rows, ''SID: '' || to_char( sys_context (''userenv'',''SID'') )  );
commit;
exception when  NO_DATA_FOUND then
   null;
end;
'
, number_of_arguments=>0,
start_date=>TO_TIMESTAMP_TZ('07-JUL-2024 10.06.52.743989000 AM AMERICA/NEW_YORK','DD-MON-RRRR HH.MI.SSXFF AM TZR','NLS_DATE_LANGUAGE=english'), repeat_interval=>
'FREQ = DAILY; BYHOUR = 21; BYMINUTE = 5'
, end_date=>NULL,
job_class=>'"DEFAULT_JOB_CLASS"', enabled=>FALSE, auto_drop=>FALSE,comments=>
'PURGE_AT_TO_NON_EXISTING_IOES_JOB Niki Letter 07.07.2024 Job'
);
sys.dbms_scheduler.set_attribute('"PURGE_AT_TO_NON_EXISTING_IOES_JOB"','NLS_ENV','NLS_LANGUAGE=''AMERICAN'' NLS_TERRITORY=''AMERICA'' NLS_CURRENCY=''$'' NLS_ISO_CURRENCY=''AMERICA'' NLS_NUMERIC_CHARACTERS=''.,'' NLS_CALENDAR=''GREGORIAN'' NLS_DATE_FORMAT=''DD-MON-RR'' NLS_DATE_LANGUAGE=''AMERICAN'' NLS_SORT=''BINARY'' NLS_TIME_FORMAT=''HH.MI.SSXFF AM'' NLS_TIMESTAMP_FORMAT=''DD-MON-RR HH.MI.SSXFF AM'' NLS_TIME_TZ_FORMAT=''HH.MI.SSXFF AM TZR'' NLS_TIMESTAMP_TZ_FORMAT=''DD-MON-RR HH.MI.SSXFF AM TZR'' NLS_DUAL_CURRENCY=''$'' NLS_COMP=''BINARY'' NLS_LENGTH_SEMANTICS=''BYTE'' NLS_NCHAR_CONV_EXCP=''FALSE''');
COMMIT;
END;
/



BEGIN
dbms_scheduler.create_job('"PURGE_EOD_ADVISOR_AUM_TBL"',
job_type=>'PLSQL_BLOCK', job_action=>
'
declare
l_rows NUMBER;
l_begin_time timestamp;
l_end_time   timestamp;
begin
select systimestamp into l_begin_time from dual;
delete from GP.EOD_ADVISOR_AUM_TBL aum where aum.asofdate < trunc(sysdate-370) and rownum <1000001;
l_rows := SQL%ROWCOUNT;
insert into COMMON_JOB_LOG_TBL values ( ''purge_EOD_ADVISOR_AUM_TBL'', l_begin_time, systimestamp , systimestamp - l_begin_time , l_rows, ''SID: '' || to_char( sys_context (''userenv'',''SID'') )  );
commit;
exception when  NO_DATA_FOUND then
   null;
end;
'
, number_of_arguments=>0,
start_date=>TO_TIMESTAMP_TZ('19-OCT-2023 07.55.39.034712000 AM AMERICA/NEW_YORK','DD-MON-RRRR HH.MI.SSXFF AM TZR','NLS_DATE_LANGUAGE=english'), repeat_interval=>
'FREQ = DAILY; BYHOUR = 22; BYMINUTE = 17'
, end_date=>NULL,
job_class=>'"DEFAULT_JOB_CLASS"', enabled=>FALSE, auto_drop=>FALSE,comments=>
'Delete 1 mln oldest rows older than 370 days from GP.EOD_ADVISOR_AUM_TBL by asofdate field Job'
);
sys.dbms_scheduler.set_attribute('"PURGE_EOD_ADVISOR_AUM_TBL"','NLS_ENV','NLS_LANGUAGE=''ENGLISH'' NLS_TERRITORY=''UNITED KINGDOM'' NLS_CURRENCY=''£'' NLS_ISO_CURRENCY=''UNITED KINGDOM'' NLS_NUMERIC_CHARACTERS=''.,'' NLS_CALENDAR=''GREGORIAN'' NLS_DATE_FORMAT=''DD-MON-RR'' NLS_DATE_LANGUAGE=''ENGLISH'' NLS_SORT=''BINARY'' NLS_TIME_FORMAT=''HH24.MI.SSXFF'' NLS_TIMESTAMP_FORMAT=''DD-MON-RR HH24.MI.SSXFF'' NLS_TIME_TZ_FORMAT=''HH24.MI.SSXFF TZR'' NLS_TIMESTAMP_TZ_FORMAT=''DD-MON-RR HH24.MI.SSXFF TZR'' NLS_DUAL_CURRENCY=''€'' NLS_COMP=''BINARY'' NLS_LENGTH_SEMANTICS=''BYTE'' NLS_NCHAR_CONV_EXCP=''FALSE''');
dbms_scheduler.enable('"PURGE_EOD_ADVISOR_AUM_TBL"');
COMMIT;
END;
/



BEGIN
dbms_scheduler.create_job('"PURGE_POLICY_RULE_FROM_306"',
job_type=>'PLSQL_BLOCK', job_action=>
'
declare
l_rows NUMBER;
l_begin_time timestamp;
l_end_time   timestamp;
begin
select systimestamp into l_begin_time from dual;
delete from GP.POLICY_RULE_TBL pr where pr.firm_cd=306 and rownum <1000001;
l_rows := SQL%ROWCOUNT;
insert into COMMON_JOB_LOG_TBL values ( ''purge_policy_rule_from_306'', l_begin_time, systimestamp , systimestamp - l_begin_time , l_rows, ''SID: '' || to_char( sys_context (''userenv'',''SID'') )  );
commit;
exception when  NO_DATA_FOUND then
   null;
end;
'
, number_of_arguments=>0,
start_date=>TO_TIMESTAMP_TZ('24-AUG-2023 05.16.02.987080000 AM AMERICA/NEW_YORK','DD-MON-RRRR HH.MI.SSXFF AM TZR','NLS_DATE_LANGUAGE=english'), repeat_interval=>
'FREQ = DAILY; BYHOUR = 1; BYMINUTE = 2'
, end_date=>NULL,
job_class=>'"DEFAULT_JOB_CLASS"', enabled=>FALSE, auto_drop=>FALSE,comments=>
'Delete rows from GP.POLICY_RULE_TBL where pr.firm_cd=306 Job'
);
sys.dbms_scheduler.set_attribute('"PURGE_POLICY_RULE_FROM_306"','NLS_ENV','NLS_LANGUAGE=''ENGLISH'' NLS_TERRITORY=''UNITED KINGDOM'' NLS_CURRENCY=''£'' NLS_ISO_CURRENCY=''UNITED KINGDOM'' NLS_NUMERIC_CHARACTERS=''.,'' NLS_CALENDAR=''GREGORIAN'' NLS_DATE_FORMAT=''DD-MON-RR'' NLS_DATE_LANGUAGE=''ENGLISH'' NLS_SORT=''BINARY'' NLS_TIME_FORMAT=''HH24.MI.SSXFF'' NLS_TIMESTAMP_FORMAT=''DD-MON-RR HH24.MI.SSXFF'' NLS_TIME_TZ_FORMAT=''HH24.MI.SSXFF TZR'' NLS_TIMESTAMP_TZ_FORMAT=''DD-MON-RR HH24.MI.SSXFF TZR'' NLS_DUAL_CURRENCY=''€'' NLS_COMP=''BINARY'' NLS_LENGTH_SEMANTICS=''BYTE'' NLS_NCHAR_CONV_EXCP=''FALSE''');
dbms_scheduler.enable('"PURGE_POLICY_RULE_FROM_306"');
COMMIT;
END;
/



BEGIN
dbms_scheduler.create_job('"PURGE_RECONCILIATION_POSITION_TBL"',
job_type=>'PLSQL_BLOCK', job_action=>
'
declare
l_rows NUMBER;
l_begin_time timestamp;
l_end_time   timestamp;
begin
select systimestamp into l_begin_time from dual;
delete from GP.RECONCILIATION_POSITION_TBL where reconciliation_date < trunc(sysdate - 10) and rownum <1000001;
l_rows := SQL%ROWCOUNT;
insert into COMMON_JOB_LOG_TBL values ( ''purge_RECONCILIATION_POSITION_TBL'', l_begin_time, systimestamp , systimestamp - l_begin_time , l_rows, ''SID: '' || to_char( sys_context (''userenv'',''SID'') )  );
commit;
exception when  NO_DATA_FOUND then
   null;
end;
'
, number_of_arguments=>0,
start_date=>TO_TIMESTAMP_TZ('02-NOV-2023 05.26.44.125649000 AM AMERICA/NEW_YORK','DD-MON-RRRR HH.MI.SSXFF AM TZR','NLS_DATE_LANGUAGE=english'), repeat_interval=>
'FREQ = DAILY; BYHOUR = 22; BYMINUTE = 22'
, end_date=>NULL,
job_class=>'"DEFAULT_JOB_CLASS"', enabled=>FALSE, auto_drop=>FALSE,comments=>
'Delete 1 mln oldest rows older than 10 days from RECONCILIATION_POSITION_TBL by asofdate field Job'
);
sys.dbms_scheduler.set_attribute('"PURGE_RECONCILIATION_POSITION_TBL"','NLS_ENV','NLS_LANGUAGE=''ENGLISH'' NLS_TERRITORY=''UNITED KINGDOM'' NLS_CURRENCY=''£'' NLS_ISO_CURRENCY=''UNITED KINGDOM'' NLS_NUMERIC_CHARACTERS=''.,'' NLS_CALENDAR=''GREGORIAN'' NLS_DATE_FORMAT=''DD-MON-RR'' NLS_DATE_LANGUAGE=''ENGLISH'' NLS_SORT=''BINARY'' NLS_TIME_FORMAT=''HH24.MI.SSXFF'' NLS_TIMESTAMP_FORMAT=''DD-MON-RR HH24.MI.SSXFF'' NLS_TIME_TZ_FORMAT=''HH24.MI.SSXFF TZR'' NLS_TIMESTAMP_TZ_FORMAT=''DD-MON-RR HH24.MI.SSXFF TZR'' NLS_DUAL_CURRENCY=''€'' NLS_COMP=''BINARY'' NLS_LENGTH_SEMANTICS=''BYTE'' NLS_NCHAR_CONV_EXCP=''FALSE''');
dbms_scheduler.enable('"PURGE_RECONCILIATION_POSITION_TBL"');
COMMIT;
END;
/



BEGIN
dbms_scheduler.create_job('"REFRESH_ENT_APP_DISAPP_VAL_MVWS"',
job_type=>'PLSQL_BLOCK', job_action=>
'BEGIN GP.REF_ENTITY_APP_DISAPP_VAL_MVWS; END;'
, number_of_arguments=>0,
start_date=>TO_TIMESTAMP_TZ('30-JUN-2020 06.00.00.000000000 AM EUROPE/HELSINKI','DD-MON-RRRR HH.MI.SSXFF AM TZR','NLS_DATE_LANGUAGE=english'), repeat_interval=>
'FREQ=DAILY;byhour=22;byminute=05; bysecond=00'
, end_date=>NULL,
job_class=>'"DEFAULT_JOB_CLASS"', enabled=>FALSE, auto_drop=>TRUE,comments=>
NULL
);
sys.dbms_scheduler.set_attribute('"REFRESH_ENT_APP_DISAPP_VAL_MVWS"','NLS_ENV','NLS_LANGUAGE=''AMERICAN'' NLS_TERRITORY=''AMERICA'' NLS_CURRENCY=''$'' NLS_ISO_CURRENCY=''AMERICA'' NLS_NUMERIC_CHARACTERS=''.,'' NLS_CALENDAR=''GREGORIAN'' NLS_DATE_FORMAT=''DD-MON-RR'' NLS_DATE_LANGUAGE=''AMERICAN'' NLS_SORT=''BINARY'' NLS_TIME_FORMAT=''HH.MI.SSXFF AM'' NLS_TIMESTAMP_FORMAT=''DD-MON-RR HH.MI.SSXFF AM'' NLS_TIME_TZ_FORMAT=''HH.MI.SSXFF AM TZR'' NLS_TIMESTAMP_TZ_FORMAT=''DD-MON-RR HH.MI.SSXFF AM TZR'' NLS_DUAL_CURRENCY=''$'' NLS_COMP=''BINARY'' NLS_LENGTH_SEMANTICS=''BYTE'' NLS_NCHAR_CONV_EXCP=''FALSE''');
dbms_scheduler.set_attribute('"REFRESH_ENT_APP_DISAPP_VAL_MVWS"','raise_events',332);
dbms_scheduler.add_job_email_notification('"REFRESH_ENT_APP_DISAPP_VAL_MVWS"','nikolay.ivanchev@geowealth.com',NULL,'Oracle Scheduler Job Notification - %job_owner%.%job_name%.%job_subname% %event_type%',
'Job: %job_owner%.%job_name%.%job_subname%
Event: %event_type%
Date: %event_timestamp%
Log id: %log_id%
Job class: %job_class_name%
Run count: %run_count%
Failure count: %failure_count%
Retry count: %retry_count%
Error code: %error_code%
Error message:
%error_message%
'
,'JOB_FAILED',NULL);
dbms_scheduler.add_job_email_notification('"REFRESH_ENT_APP_DISAPP_VAL_MVWS"','nikolay.ivanchev@geowealth.com',NULL,'Oracle Scheduler Job Notification - %job_owner%.%job_name%.%job_subname% %event_type%',
'Job: %job_owner%.%job_name%.%job_subname%
Event: %event_type%
Date: %event_timestamp%
Log id: %log_id%
Job class: %job_class_name%
Run count: %run_count%
Failure count: %failure_count%
Retry count: %retry_count%
Error code: %error_code%
Error message:
%error_message%
'
,'JOB_BROKEN',NULL);
dbms_scheduler.add_job_email_notification('"REFRESH_ENT_APP_DISAPP_VAL_MVWS"','nikolay.ivanchev@geowealth.com',NULL,'Oracle Scheduler Job Notification - %job_owner%.%job_name%.%job_subname% %event_type%',
'Job: %job_owner%.%job_name%.%job_subname%
Event: %event_type%
Date: %event_timestamp%
Log id: %log_id%
Job class: %job_class_name%
Run count: %run_count%
Failure count: %failure_count%
Retry count: %retry_count%
Error code: %error_code%
Error message:
%error_message%
'
,'JOB_SCH_LIM_REACHED',NULL);
dbms_scheduler.add_job_email_notification('"REFRESH_ENT_APP_DISAPP_VAL_MVWS"','nikolay.ivanchev@geowealth.com',NULL,'Oracle Scheduler Job Notification - %job_owner%.%job_name%.%job_subname% %event_type%',
'Job: %job_owner%.%job_name%.%job_subname%
Event: %event_type%
Date: %event_timestamp%
Log id: %log_id%
Job class: %job_class_name%
Run count: %run_count%
Failure count: %failure_count%
Retry count: %retry_count%
Error code: %error_code%
Error message:
%error_message%
'
,'JOB_CHAIN_STALLED',NULL);
dbms_scheduler.add_job_email_notification('"REFRESH_ENT_APP_DISAPP_VAL_MVWS"','nikolay.ivanchev@geowealth.com',NULL,'Oracle Scheduler Job Notification - %job_owner%.%job_name%.%job_subname% %event_type%',
'Job: %job_owner%.%job_name%.%job_subname%
Event: %event_type%
Date: %event_timestamp%
Log id: %log_id%
Job class: %job_class_name%
Run count: %run_count%
Failure count: %failure_count%
Retry count: %retry_count%
Error code: %error_code%
Error message:
%error_message%
'
,'JOB_OVER_MAX_DUR',NULL);
dbms_scheduler.add_job_email_notification('"REFRESH_ENT_APP_DISAPP_VAL_MVWS"','rosen.rankov@geowealth.com',NULL,'Oracle Scheduler Job Notification - %job_owner%.%job_name%.%job_subname% %event_type%',
'Job: %job_owner%.%job_name%.%job_subname%
Event: %event_type%
Date: %event_timestamp%
Log id: %log_id%
Job class: %job_class_name%
Run count: %run_count%
Failure count: %failure_count%
Retry count: %retry_count%
Error code: %error_code%
Error message:
%error_message%
'
,'JOB_SCH_LIM_REACHED',NULL);
dbms_scheduler.add_job_email_notification('"REFRESH_ENT_APP_DISAPP_VAL_MVWS"','rosen.rankov@geowealth.com',NULL,'Oracle Scheduler Job Notification - %job_owner%.%job_name%.%job_subname% %event_type%',
'Job: %job_owner%.%job_name%.%job_subname%
Event: %event_type%
Date: %event_timestamp%
Log id: %log_id%
Job class: %job_class_name%
Run count: %run_count%
Failure count: %failure_count%
Retry count: %retry_count%
Error code: %error_code%
Error message:
%error_message%
'
,'JOB_FAILED',NULL);
dbms_scheduler.add_job_email_notification('"REFRESH_ENT_APP_DISAPP_VAL_MVWS"','rosen.rankov@geowealth.com',NULL,'Oracle Scheduler Job Notification - %job_owner%.%job_name%.%job_subname% %event_type%',
'Job: %job_owner%.%job_name%.%job_subname%
Event: %event_type%
Date: %event_timestamp%
Log id: %log_id%
Job class: %job_class_name%
Run count: %run_count%
Failure count: %failure_count%
Retry count: %retry_count%
Error code: %error_code%
Error message:
%error_message%
'
,'JOB_BROKEN',NULL);
dbms_scheduler.add_job_email_notification('"REFRESH_ENT_APP_DISAPP_VAL_MVWS"','rosen.rankov@geowealth.com',NULL,'Oracle Scheduler Job Notification - %job_owner%.%job_name%.%job_subname% %event_type%',
'Job: %job_owner%.%job_name%.%job_subname%
Event: %event_type%
Date: %event_timestamp%
Log id: %log_id%
Job class: %job_class_name%
Run count: %run_count%
Failure count: %failure_count%
Retry count: %retry_count%
Error code: %error_code%
Error message:
%error_message%
'
,'JOB_OVER_MAX_DUR',NULL);
dbms_scheduler.add_job_email_notification('"REFRESH_ENT_APP_DISAPP_VAL_MVWS"','rosen.rankov@geowealth.com',NULL,'Oracle Scheduler Job Notification - %job_owner%.%job_name%.%job_subname% %event_type%',
'Job: %job_owner%.%job_name%.%job_subname%
Event: %event_type%
Date: %event_timestamp%
Log id: %log_id%
Job class: %job_class_name%
Run count: %run_count%
Failure count: %failure_count%
Retry count: %retry_count%
Error code: %error_code%
Error message:
%error_message%
'
,'JOB_CHAIN_STALLED',NULL);
COMMIT;
END;
/



BEGIN
dbms_scheduler.create_job('"REF_COMP_ALLOC_EBASKET_MVWS"',
job_type=>'PLSQL_BLOCK', job_action=>
'BEGIN GP.COMP_ALLOC_EBASKET_MVWS; END;'
, number_of_arguments=>0,
start_date=>TO_TIMESTAMP_TZ('21-JAN-2022 05.00.00.000000000 PM -05:00','DD-MON-RRRR HH.MI.SSXFF AM TZR','NLS_DATE_LANGUAGE=english'), repeat_interval=>
'FREQ=DAILY;byhour=02;byminute=00; bysecond=00'
, end_date=>NULL,
job_class=>'"DEFAULT_JOB_CLASS"', enabled=>FALSE, auto_drop=>TRUE,comments=>
NULL
);
sys.dbms_scheduler.set_attribute('"REF_COMP_ALLOC_EBASKET_MVWS"','NLS_ENV','NLS_LANGUAGE=''AMERICAN'' NLS_TERRITORY=''AMERICA'' NLS_CURRENCY=''$'' NLS_ISO_CURRENCY=''AMERICA'' NLS_NUMERIC_CHARACTERS=''.,'' NLS_CALENDAR=''GREGORIAN'' NLS_DATE_FORMAT=''DD-MM-YYYY HH24:MI:SS'' NLS_DATE_LANGUAGE=''AMERICAN'' NLS_SORT=''BINARY'' NLS_TIME_FORMAT=''HH.MI.SSXFF AM'' NLS_TIMESTAMP_FORMAT=''DD-MON-RR HH.MI.SSXFF AM'' NLS_TIME_TZ_FORMAT=''HH.MI.SSXFF AM TZR'' NLS_TIMESTAMP_TZ_FORMAT=''DD-MON-RR HH.MI.SSXFF AM TZR'' NLS_DUAL_CURRENCY=''$'' NLS_COMP=''BINARY'' NLS_LENGTH_SEMANTICS=''BYTE'' NLS_NCHAR_CONV_EXCP=''FALSE''');
dbms_scheduler.set_attribute('"REF_COMP_ALLOC_EBASKET_MVWS"','raise_events',332);
dbms_scheduler.add_job_email_notification('"REF_COMP_ALLOC_EBASKET_MVWS"','nikolay.ivanchev@geowealth.com',NULL,'Oracle Scheduler Job Notification - %job_owner%.%job_name%.%job_subname% %event_type%',
'Job: %job_owner%.%job_name%.%job_subname%
Event: %event_type%
Date: %event_timestamp%
Log id: %log_id%
Job class: %job_class_name%
Run count: %run_count%
Failure count: %failure_count%
Retry count: %retry_count%
Error code: %error_code%
Error message:
%error_message%
'
,'JOB_SCH_LIM_REACHED',NULL);
dbms_scheduler.add_job_email_notification('"REF_COMP_ALLOC_EBASKET_MVWS"','nikolay.ivanchev@geowealth.com',NULL,'Oracle Scheduler Job Notification - %job_owner%.%job_name%.%job_subname% %event_type%',
'Job: %job_owner%.%job_name%.%job_subname%
Event: %event_type%
Date: %event_timestamp%
Log id: %log_id%
Job class: %job_class_name%
Run count: %run_count%
Failure count: %failure_count%
Retry count: %retry_count%
Error code: %error_code%
Error message:
%error_message%
'
,'JOB_CHAIN_STALLED',NULL);
dbms_scheduler.add_job_email_notification('"REF_COMP_ALLOC_EBASKET_MVWS"','nikolay.ivanchev@geowealth.com',NULL,'Oracle Scheduler Job Notification - %job_owner%.%job_name%.%job_subname% %event_type%',
'Job: %job_owner%.%job_name%.%job_subname%
Event: %event_type%
Date: %event_timestamp%
Log id: %log_id%
Job class: %job_class_name%
Run count: %run_count%
Failure count: %failure_count%
Retry count: %retry_count%
Error code: %error_code%
Error message:
%error_message%
'
,'JOB_OVER_MAX_DUR',NULL);
dbms_scheduler.add_job_email_notification('"REF_COMP_ALLOC_EBASKET_MVWS"','nikolay.ivanchev@geowealth.com',NULL,'Oracle Scheduler Job Notification - %job_owner%.%job_name%.%job_subname% %event_type%',
'Job: %job_owner%.%job_name%.%job_subname%
Event: %event_type%
Date: %event_timestamp%
Log id: %log_id%
Job class: %job_class_name%
Run count: %run_count%
Failure count: %failure_count%
Retry count: %retry_count%
Error code: %error_code%
Error message:
%error_message%
'
,'JOB_FAILED',NULL);
dbms_scheduler.add_job_email_notification('"REF_COMP_ALLOC_EBASKET_MVWS"','nikolay.ivanchev@geowealth.com',NULL,'Oracle Scheduler Job Notification - %job_owner%.%job_name%.%job_subname% %event_type%',
'Job: %job_owner%.%job_name%.%job_subname%
Event: %event_type%
Date: %event_timestamp%
Log id: %log_id%
Job class: %job_class_name%
Run count: %run_count%
Failure count: %failure_count%
Retry count: %retry_count%
Error code: %error_code%
Error message:
%error_message%
'
,'JOB_BROKEN',NULL);
COMMIT;
END;
/



BEGIN
dbms_scheduler.create_job('"REF_CRNT_TAXLOT_NF_ACCOUNT_MVWS"',
job_type=>'PLSQL_BLOCK', job_action=>
'BEGIN GP.REFRESH_CRNT_MVWS; END;'
, number_of_arguments=>0,
start_date=>TO_TIMESTAMP_TZ('30-JUN-2020 06.00.00.000000000 AM EUROPE/HELSINKI','DD-MON-RRRR HH.MI.SSXFF AM TZR','NLS_DATE_LANGUAGE=english'), repeat_interval=>
'FREQ=DAILY;byhour=02;byminute=30; bysecond=00'
, end_date=>NULL,
job_class=>'"DEFAULT_JOB_CLASS"', enabled=>FALSE, auto_drop=>TRUE,comments=>
NULL
);
sys.dbms_scheduler.set_attribute('"REF_CRNT_TAXLOT_NF_ACCOUNT_MVWS"','NLS_ENV','NLS_LANGUAGE=''AMERICAN'' NLS_TERRITORY=''AMERICA'' NLS_CURRENCY=''$'' NLS_ISO_CURRENCY=''AMERICA'' NLS_NUMERIC_CHARACTERS=''.,'' NLS_CALENDAR=''GREGORIAN'' NLS_DATE_FORMAT=''DD-MON-RR'' NLS_DATE_LANGUAGE=''AMERICAN'' NLS_SORT=''BINARY'' NLS_TIME_FORMAT=''HH.MI.SSXFF AM'' NLS_TIMESTAMP_FORMAT=''DD-MON-RR HH.MI.SSXFF AM'' NLS_TIME_TZ_FORMAT=''HH.MI.SSXFF AM TZR'' NLS_TIMESTAMP_TZ_FORMAT=''DD-MON-RR HH.MI.SSXFF AM TZR'' NLS_DUAL_CURRENCY=''$'' NLS_COMP=''BINARY'' NLS_LENGTH_SEMANTICS=''BYTE'' NLS_NCHAR_CONV_EXCP=''FALSE''');
dbms_scheduler.set_attribute('"REF_CRNT_TAXLOT_NF_ACCOUNT_MVWS"','raise_events',332);
dbms_scheduler.add_job_email_notification('"REF_CRNT_TAXLOT_NF_ACCOUNT_MVWS"','nikolay.ivanchev@geowealth.com',NULL,'Oracle Scheduler Job Notification - %job_owner%.%job_name%.%job_subname% %event_type%',
'Job: %job_owner%.%job_name%.%job_subname%
Event: %event_type%
Date: %event_timestamp%
Log id: %log_id%
Job class: %job_class_name%
Run count: %run_count%
Failure count: %failure_count%
Retry count: %retry_count%
Error code: %error_code%
Error message:
%error_message%
'
,'JOB_FAILED',NULL);
dbms_scheduler.add_job_email_notification('"REF_CRNT_TAXLOT_NF_ACCOUNT_MVWS"','nikolay.ivanchev@geowealth.com',NULL,'Oracle Scheduler Job Notification - %job_owner%.%job_name%.%job_subname% %event_type%',
'Job: %job_owner%.%job_name%.%job_subname%
Event: %event_type%
Date: %event_timestamp%
Log id: %log_id%
Job class: %job_class_name%
Run count: %run_count%
Failure count: %failure_count%
Retry count: %retry_count%
Error code: %error_code%
Error message:
%error_message%
'
,'JOB_BROKEN',NULL);
dbms_scheduler.add_job_email_notification('"REF_CRNT_TAXLOT_NF_ACCOUNT_MVWS"','nikolay.ivanchev@geowealth.com',NULL,'Oracle Scheduler Job Notification - %job_owner%.%job_name%.%job_subname% %event_type%',
'Job: %job_owner%.%job_name%.%job_subname%
Event: %event_type%
Date: %event_timestamp%
Log id: %log_id%
Job class: %job_class_name%
Run count: %run_count%
Failure count: %failure_count%
Retry count: %retry_count%
Error code: %error_code%
Error message:
%error_message%
'
,'JOB_SCH_LIM_REACHED',NULL);
dbms_scheduler.add_job_email_notification('"REF_CRNT_TAXLOT_NF_ACCOUNT_MVWS"','nikolay.ivanchev@geowealth.com',NULL,'Oracle Scheduler Job Notification - %job_owner%.%job_name%.%job_subname% %event_type%',
'Job: %job_owner%.%job_name%.%job_subname%
Event: %event_type%
Date: %event_timestamp%
Log id: %log_id%
Job class: %job_class_name%
Run count: %run_count%
Failure count: %failure_count%
Retry count: %retry_count%
Error code: %error_code%
Error message:
%error_message%
'
,'JOB_CHAIN_STALLED',NULL);
dbms_scheduler.add_job_email_notification('"REF_CRNT_TAXLOT_NF_ACCOUNT_MVWS"','nikolay.ivanchev@geowealth.com',NULL,'Oracle Scheduler Job Notification - %job_owner%.%job_name%.%job_subname% %event_type%',
'Job: %job_owner%.%job_name%.%job_subname%
Event: %event_type%
Date: %event_timestamp%
Log id: %log_id%
Job class: %job_class_name%
Run count: %run_count%
Failure count: %failure_count%
Retry count: %retry_count%
Error code: %error_code%
Error message:
%error_message%
'
,'JOB_OVER_MAX_DUR',NULL);
dbms_scheduler.add_job_email_notification('"REF_CRNT_TAXLOT_NF_ACCOUNT_MVWS"','rosen.rankov@geowealth.com',NULL,'Oracle Scheduler Job Notification - %job_owner%.%job_name%.%job_subname% %event_type%',
'Job: %job_owner%.%job_name%.%job_subname%
Event: %event_type%
Date: %event_timestamp%
Log id: %log_id%
Job class: %job_class_name%
Run count: %run_count%
Failure count: %failure_count%
Retry count: %retry_count%
Error code: %error_code%
Error message:
%error_message%
'
,'JOB_SCH_LIM_REACHED',NULL);
dbms_scheduler.add_job_email_notification('"REF_CRNT_TAXLOT_NF_ACCOUNT_MVWS"','rosen.rankov@geowealth.com',NULL,'Oracle Scheduler Job Notification - %job_owner%.%job_name%.%job_subname% %event_type%',
'Job: %job_owner%.%job_name%.%job_subname%
Event: %event_type%
Date: %event_timestamp%
Log id: %log_id%
Job class: %job_class_name%
Run count: %run_count%
Failure count: %failure_count%
Retry count: %retry_count%
Error code: %error_code%
Error message:
%error_message%
'
,'JOB_FAILED',NULL);
dbms_scheduler.add_job_email_notification('"REF_CRNT_TAXLOT_NF_ACCOUNT_MVWS"','rosen.rankov@geowealth.com',NULL,'Oracle Scheduler Job Notification - %job_owner%.%job_name%.%job_subname% %event_type%',
'Job: %job_owner%.%job_name%.%job_subname%
Event: %event_type%
Date: %event_timestamp%
Log id: %log_id%
Job class: %job_class_name%
Run count: %run_count%
Failure count: %failure_count%
Retry count: %retry_count%
Error code: %error_code%
Error message:
%error_message%
'
,'JOB_BROKEN',NULL);
dbms_scheduler.add_job_email_notification('"REF_CRNT_TAXLOT_NF_ACCOUNT_MVWS"','rosen.rankov@geowealth.com',NULL,'Oracle Scheduler Job Notification - %job_owner%.%job_name%.%job_subname% %event_type%',
'Job: %job_owner%.%job_name%.%job_subname%
Event: %event_type%
Date: %event_timestamp%
Log id: %log_id%
Job class: %job_class_name%
Run count: %run_count%
Failure count: %failure_count%
Retry count: %retry_count%
Error code: %error_code%
Error message:
%error_message%
'
,'JOB_CHAIN_STALLED',NULL);
dbms_scheduler.add_job_email_notification('"REF_CRNT_TAXLOT_NF_ACCOUNT_MVWS"','rosen.rankov@geowealth.com',NULL,'Oracle Scheduler Job Notification - %job_owner%.%job_name%.%job_subname% %event_type%',
'Job: %job_owner%.%job_name%.%job_subname%
Event: %event_type%
Date: %event_timestamp%
Log id: %log_id%
Job class: %job_class_name%
Run count: %run_count%
Failure count: %failure_count%
Retry count: %retry_count%
Error code: %error_code%
Error message:
%error_message%
'
,'JOB_OVER_MAX_DUR',NULL);
COMMIT;
END;
/



BEGIN
dbms_scheduler.create_job('"RPT_ETL_CONTROL_PROCESS_PENDING_ITEMS_JOB_1"',
job_type=>'STORED_PROCEDURE', job_action=>
'RPT_ETL_CONTROL_PROCESS_PENDING_ITEMS'
, number_of_arguments=>0,
start_date=>TO_TIMESTAMP_TZ('01-APR-2025 10.00.00.000000000 AM EUROPE/KIEV','DD-MON-RRRR HH.MI.SSXFF AM TZR','NLS_DATE_LANGUAGE=english'), repeat_interval=>
'FREQ=MINUTELY; INTERVAL=5'
, end_date=>NULL,
job_class=>'"DEFAULT_JOB_CLASS"', enabled=>FALSE, auto_drop=>TRUE,comments=>
NULL
);
sys.dbms_scheduler.set_attribute('"RPT_ETL_CONTROL_PROCESS_PENDING_ITEMS_JOB_1"','NLS_ENV','NLS_LANGUAGE=''AMERICAN'' NLS_TERRITORY=''AMERICA'' NLS_CURRENCY=''$'' NLS_ISO_CURRENCY=''AMERICA'' NLS_NUMERIC_CHARACTERS=''.,'' NLS_CALENDAR=''GREGORIAN'' NLS_DATE_FORMAT=''DD-MON-RR'' NLS_DATE_LANGUAGE=''AMERICAN'' NLS_SORT=''BINARY'' NLS_TIME_FORMAT=''HH.MI.SSXFF AM'' NLS_TIMESTAMP_FORMAT=''DD-MON-RR HH.MI.SSXFF AM'' NLS_TIME_TZ_FORMAT=''HH.MI.SSXFF AM TZR'' NLS_TIMESTAMP_TZ_FORMAT=''DD-MON-RR HH.MI.SSXFF AM TZR'' NLS_DUAL_CURRENCY=''$'' NLS_COMP=''BINARY'' NLS_LENGTH_SEMANTICS=''BYTE'' NLS_NCHAR_CONV_EXCP=''FALSE''');
dbms_scheduler.enable('"RPT_ETL_CONTROL_PROCESS_PENDING_ITEMS_JOB_1"');
COMMIT;
END;
/



BEGIN
dbms_scheduler.create_job('"RPT_ETL_CONTROL_PROCESS_PENDING_ITEMS_JOB_2"',
job_type=>'STORED_PROCEDURE', job_action=>
'RPT_ETL_CONTROL_PROCESS_PENDING_ITEMS'
, number_of_arguments=>0,
start_date=>TO_TIMESTAMP_TZ('01-APR-2025 10.00.10.000000000 AM EUROPE/KIEV','DD-MON-RRRR HH.MI.SSXFF AM TZR','NLS_DATE_LANGUAGE=english'), repeat_interval=>
'FREQ=MINUTELY; INTERVAL=5'
, end_date=>NULL,
job_class=>'"DEFAULT_JOB_CLASS"', enabled=>FALSE, auto_drop=>TRUE,comments=>
NULL
);
sys.dbms_scheduler.set_attribute('"RPT_ETL_CONTROL_PROCESS_PENDING_ITEMS_JOB_2"','NLS_ENV','NLS_LANGUAGE=''AMERICAN'' NLS_TERRITORY=''AMERICA'' NLS_CURRENCY=''$'' NLS_ISO_CURRENCY=''AMERICA'' NLS_NUMERIC_CHARACTERS=''.,'' NLS_CALENDAR=''GREGORIAN'' NLS_DATE_FORMAT=''DD-MON-RR'' NLS_DATE_LANGUAGE=''AMERICAN'' NLS_SORT=''BINARY'' NLS_TIME_FORMAT=''HH.MI.SSXFF AM'' NLS_TIMESTAMP_FORMAT=''DD-MON-RR HH.MI.SSXFF AM'' NLS_TIME_TZ_FORMAT=''HH.MI.SSXFF AM TZR'' NLS_TIMESTAMP_TZ_FORMAT=''DD-MON-RR HH.MI.SSXFF AM TZR'' NLS_DUAL_CURRENCY=''$'' NLS_COMP=''BINARY'' NLS_LENGTH_SEMANTICS=''BYTE'' NLS_NCHAR_CONV_EXCP=''FALSE''');
dbms_scheduler.enable('"RPT_ETL_CONTROL_PROCESS_PENDING_ITEMS_JOB_2"');
COMMIT;
END;
/



BEGIN
dbms_scheduler.create_job('"RPT_ETL_CONTROL_PROCESS_PENDING_ITEMS_JOB_3"',
job_type=>'STORED_PROCEDURE', job_action=>
'RPT_ETL_CONTROL_PROCESS_PENDING_ITEMS'
, number_of_arguments=>0,
start_date=>TO_TIMESTAMP_TZ('01-APR-2025 10.00.20.000000000 AM EUROPE/KIEV','DD-MON-RRRR HH.MI.SSXFF AM TZR','NLS_DATE_LANGUAGE=english'), repeat_interval=>
'FREQ=MINUTELY; INTERVAL=5'
, end_date=>NULL,
job_class=>'"DEFAULT_JOB_CLASS"', enabled=>FALSE, auto_drop=>TRUE,comments=>
NULL
);
sys.dbms_scheduler.set_attribute('"RPT_ETL_CONTROL_PROCESS_PENDING_ITEMS_JOB_3"','NLS_ENV','NLS_LANGUAGE=''AMERICAN'' NLS_TERRITORY=''AMERICA'' NLS_CURRENCY=''$'' NLS_ISO_CURRENCY=''AMERICA'' NLS_NUMERIC_CHARACTERS=''.,'' NLS_CALENDAR=''GREGORIAN'' NLS_DATE_FORMAT=''DD-MON-RR'' NLS_DATE_LANGUAGE=''AMERICAN'' NLS_SORT=''BINARY'' NLS_TIME_FORMAT=''HH.MI.SSXFF AM'' NLS_TIMESTAMP_FORMAT=''DD-MON-RR HH.MI.SSXFF AM'' NLS_TIME_TZ_FORMAT=''HH.MI.SSXFF AM TZR'' NLS_TIMESTAMP_TZ_FORMAT=''DD-MON-RR HH.MI.SSXFF AM TZR'' NLS_DUAL_CURRENCY=''$'' NLS_COMP=''BINARY'' NLS_LENGTH_SEMANTICS=''BYTE'' NLS_NCHAR_CONV_EXCP=''FALSE''');
dbms_scheduler.enable('"RPT_ETL_CONTROL_PROCESS_PENDING_ITEMS_JOB_3"');
COMMIT;
END;
/



BEGIN
dbms_scheduler.create_job('"RPT_ETL_CONTROL_PROCESS_PENDING_ITEMS_JOB_4"',
job_type=>'STORED_PROCEDURE', job_action=>
'RPT_ETL_CONTROL_PROCESS_PENDING_ITEMS'
, number_of_arguments=>0,
start_date=>TO_TIMESTAMP_TZ('01-APR-2025 10.00.30.000000000 AM EUROPE/KIEV','DD-MON-RRRR HH.MI.SSXFF AM TZR','NLS_DATE_LANGUAGE=english'), repeat_interval=>
'FREQ=MINUTELY; INTERVAL=5'
, end_date=>NULL,
job_class=>'"DEFAULT_JOB_CLASS"', enabled=>FALSE, auto_drop=>TRUE,comments=>
NULL
);
sys.dbms_scheduler.set_attribute('"RPT_ETL_CONTROL_PROCESS_PENDING_ITEMS_JOB_4"','NLS_ENV','NLS_LANGUAGE=''AMERICAN'' NLS_TERRITORY=''AMERICA'' NLS_CURRENCY=''$'' NLS_ISO_CURRENCY=''AMERICA'' NLS_NUMERIC_CHARACTERS=''.,'' NLS_CALENDAR=''GREGORIAN'' NLS_DATE_FORMAT=''DD-MON-RR'' NLS_DATE_LANGUAGE=''AMERICAN'' NLS_SORT=''BINARY'' NLS_TIME_FORMAT=''HH.MI.SSXFF AM'' NLS_TIMESTAMP_FORMAT=''DD-MON-RR HH.MI.SSXFF AM'' NLS_TIME_TZ_FORMAT=''HH.MI.SSXFF AM TZR'' NLS_TIMESTAMP_TZ_FORMAT=''DD-MON-RR HH.MI.SSXFF AM TZR'' NLS_DUAL_CURRENCY=''$'' NLS_COMP=''BINARY'' NLS_LENGTH_SEMANTICS=''BYTE'' NLS_NCHAR_CONV_EXCP=''FALSE''');
dbms_scheduler.enable('"RPT_ETL_CONTROL_PROCESS_PENDING_ITEMS_JOB_4"');
COMMIT;
END;
/



BEGIN
dbms_scheduler.create_job('"SET_STRATEGYID_IOE_TBL_JOB"',
job_type=>'PLSQL_BLOCK', job_action=>
'
declare
l_rows NUMBER;
l_begin_time timestamp;
l_end_time   timestamp;
begin
update instr_order_executed_tbl ioe
set ioe.underlying_strategy_id = (select c.strategy_id from saa_sleeve_component_tbl c where c.saa_sleeve_component_id = ioe.component_id)
where ioe.underlying_strategy_id is null
and ioe.component_id is not null and ioe.position_opener_flag = 1 and ioe.closed_date is null
and exists (select c.strategy_id from saa_sleeve_component_tbl c where c.saa_sleeve_component_id = ioe.component_id and c.strategy_id is not null);
l_rows := SQL%ROWCOUNT;
insert into COMMON_JOB_LOG_TBL values ( ''Set_StrategyId_ioe_tbl_job'', l_begin_time, systimestamp , systimestamp - l_begin_time , l_rows, ''SID: '' || to_char( sys_context (''userenv'',''SID'') )  );
commit;
exception when  NO_DATA_FOUND then
   null;
end;
'
, number_of_arguments=>0,
start_date=>TO_TIMESTAMP_TZ('23-AUG-2023 05.11.02.700705000 AM -04:00','DD-MON-RRRR HH.MI.SSXFF AM TZR','NLS_DATE_LANGUAGE=english'), repeat_interval=>
'FREQ = DAILY; BYHOUR = 23; BYMINUTE = 01'
, end_date=>NULL,
job_class=>'"DEFAULT_JOB_CLASS"', enabled=>FALSE, auto_drop=>FALSE,comments=>
'Set Strategy Id in Rinstr_order_executed_tbl Job'
);
sys.dbms_scheduler.set_attribute('"SET_STRATEGYID_IOE_TBL_JOB"','NLS_ENV','NLS_LANGUAGE=''ENGLISH'' NLS_TERRITORY=''UNITED KINGDOM'' NLS_CURRENCY=''£'' NLS_ISO_CURRENCY=''UNITED KINGDOM'' NLS_NUMERIC_CHARACTERS=''.,'' NLS_CALENDAR=''GREGORIAN'' NLS_DATE_FORMAT=''DD-MON-RR'' NLS_DATE_LANGUAGE=''ENGLISH'' NLS_SORT=''BINARY'' NLS_TIME_FORMAT=''HH24.MI.SSXFF'' NLS_TIMESTAMP_FORMAT=''DD-MON-RR HH24.MI.SSXFF'' NLS_TIME_TZ_FORMAT=''HH24.MI.SSXFF TZR'' NLS_TIMESTAMP_TZ_FORMAT=''DD-MON-RR HH24.MI.SSXFF TZR'' NLS_DUAL_CURRENCY=''€'' NLS_COMP=''BINARY'' NLS_LENGTH_SEMANTICS=''BYTE'' NLS_NCHAR_CONV_EXCP=''FALSE''');
dbms_scheduler.enable('"SET_STRATEGYID_IOE_TBL_JOB"');
COMMIT;
END;
/



BEGIN
dbms_scheduler.create_job('"SET_STRATEGYID_IOE_TYPE_2_EBASKET_JOB"',
job_type=>'PLSQL_BLOCK', job_action=>
'
declare
l_rows NUMBER;
l_begin_time timestamp;
l_end_time   timestamp;
begin
select systimestamp into l_begin_time from dual;
---
merge into GP.INSTR_ORDER_EXECUTED_TBL ioet
using (select ioe.INST_ORDER_EXECUTED_ID, e.INITIAL_BASED_ON_STRATEGY_ID
        from GP.IOE_TAXLOT_VW ioe
        join ebasket_tbl e on (e.ebasket_id = ioe.EBASKET_ID)
        where ioe.EBASKET_TYPE_CD=2 and ioe.UNDERLYING_STRATEGY_ID is null
        and e.LIQUIDATION_DATE is null
        and ioe.ACTUAL_QTY_ORIG !=0) ioef
   on (ioet.INST_ORDER_EXECUTED_ID = ioef.INST_ORDER_EXECUTED_ID )
 when matched then update set ioet.UNDERLYING_STRATEGY_ID = ioef.INITIAL_BASED_ON_STRATEGY_ID;
---
l_rows := SQL%ROWCOUNT;
insert into COMMON_JOB_LOG_TBL values ( ''SET_STRATEGYID_IOE_TYPE_2_EBASKET_JOB'', l_begin_time, systimestamp , systimestamp - l_begin_time , l_rows, ''SID: '' || to_char( sys_context (''userenv'',''SID'') )  );
commit;
exception when  NO_DATA_FOUND then
   null;
end;
'
, number_of_arguments=>0,
start_date=>TO_TIMESTAMP_TZ('25-JUN-2024 04.09.03.906212000 AM AMERICA/NEW_YORK','DD-MON-RRRR HH.MI.SSXFF AM TZR','NLS_DATE_LANGUAGE=english'), repeat_interval=>
'FREQ = DAILY; BYHOUR = 23; BYMINUTE = 15'
, end_date=>NULL,
job_class=>'"DEFAULT_JOB_CLASS"', enabled=>FALSE, auto_drop=>FALSE,comments=>
'SET_STRATEGYID_IOE_TYPE_2_EBASKET_JOB Job'
);
sys.dbms_scheduler.set_attribute('"SET_STRATEGYID_IOE_TYPE_2_EBASKET_JOB"','NLS_ENV','NLS_LANGUAGE=''AMERICAN'' NLS_TERRITORY=''AMERICA'' NLS_CURRENCY=''$'' NLS_ISO_CURRENCY=''AMERICA'' NLS_NUMERIC_CHARACTERS=''.,'' NLS_CALENDAR=''GREGORIAN'' NLS_DATE_FORMAT=''DD-MON-RR'' NLS_DATE_LANGUAGE=''AMERICAN'' NLS_SORT=''BINARY'' NLS_TIME_FORMAT=''HH.MI.SSXFF AM'' NLS_TIMESTAMP_FORMAT=''DD-MON-RR HH.MI.SSXFF AM'' NLS_TIME_TZ_FORMAT=''HH.MI.SSXFF AM TZR'' NLS_TIMESTAMP_TZ_FORMAT=''DD-MON-RR HH.MI.SSXFF AM TZR'' NLS_DUAL_CURRENCY=''$'' NLS_COMP=''BINARY'' NLS_LENGTH_SEMANTICS=''BYTE'' NLS_NCHAR_CONV_EXCP=''FALSE''');
COMMIT;
END;
/



BEGIN
dbms_scheduler.create_job('"SQLSCRIPT_3984175"',
job_type=>'PLSQL_BLOCK', job_action=>
'
 begin
 EXECUTE IMMEDIATE ''alter index "AP"."SYS_C0017841" shrink space'';
 EXECUTE IMMEDIATE ''alter index "GP"."SYS_C0018984" shrink space'';
 EXECUTE IMMEDIATE ''alter table "GP"."EOD_COMPONENT_VALUE_TBL" enable row movement'';
 EXECUTE IMMEDIATE ''alter table "GP"."EOD_COMPONENT_VALUE_TBL" shrink space'';
 end; '
, number_of_arguments=>0,
start_date=>TO_TIMESTAMP_TZ('25-NOV-2012 11.15.26.835631000 AM AMERICA/NEW_YORK','DD-MON-RRRR HH.MI.SSXFF AM TZR','NLS_DATE_LANGUAGE=english'), repeat_interval=>
NULL
, end_date=>NULL,
job_class=>'"DEFAULT_JOB_CLASS"', enabled=>FALSE, auto_drop=>FALSE,comments=>
NULL
);
sys.dbms_scheduler.set_attribute('"SQLSCRIPT_3984175"','NLS_ENV','NLS_LANGUAGE=''AMERICAN'' NLS_TERRITORY=''AMERICA'' NLS_CURRENCY=''$'' NLS_ISO_CURRENCY=''AMERICA'' NLS_NUMERIC_CHARACTERS=''.,'' NLS_CALENDAR=''GREGORIAN'' NLS_DATE_FORMAT=''DD-MON-RR'' NLS_DATE_LANGUAGE=''AMERICAN'' NLS_SORT=''BINARY'' NLS_TIME_FORMAT=''HH.MI.SSXFF AM'' NLS_TIMESTAMP_FORMAT=''DD-MON-RR HH.MI.SSXFF AM'' NLS_TIME_TZ_FORMAT=''HH.MI.SSXFF AM TZR'' NLS_TIMESTAMP_TZ_FORMAT=''DD-MON-RR HH.MI.SSXFF AM TZR'' NLS_DUAL_CURRENCY=''$'' NLS_COMP=''BINARY'' NLS_LENGTH_SEMANTICS=''BYTE'' NLS_NCHAR_CONV_EXCP=''FALSE''');
dbms_scheduler.set_attribute('"SQLSCRIPT_3984175"','logging_level',DBMS_SCHEDULER.LOGGING_RUNS);
COMMIT;
END;
/



BEGIN
dbms_scheduler.create_job('"SQLSCRIPT_4640149"',
job_type=>'PLSQL_BLOCK', job_action=>
'
 begin
 EXECUTE IMMEDIATE ''alter table "GP"."EOD_COMPONENT_VALUE_TBL" enable row movement'';
 EXECUTE IMMEDIATE ''alter table "GP"."EOD_COMPONENT_VALUE_TBL" shrink space'';
 end; '
, number_of_arguments=>0,
start_date=>TO_TIMESTAMP_TZ('25-NOV-2012 11.17.26.483239000 AM AMERICA/NEW_YORK','DD-MON-RRRR HH.MI.SSXFF AM TZR','NLS_DATE_LANGUAGE=english'), repeat_interval=>
NULL
, end_date=>NULL,
job_class=>'"DEFAULT_JOB_CLASS"', enabled=>FALSE, auto_drop=>FALSE,comments=>
NULL
);
sys.dbms_scheduler.set_attribute('"SQLSCRIPT_4640149"','NLS_ENV','NLS_LANGUAGE=''AMERICAN'' NLS_TERRITORY=''AMERICA'' NLS_CURRENCY=''$'' NLS_ISO_CURRENCY=''AMERICA'' NLS_NUMERIC_CHARACTERS=''.,'' NLS_CALENDAR=''GREGORIAN'' NLS_DATE_FORMAT=''DD-MON-RR'' NLS_DATE_LANGUAGE=''AMERICAN'' NLS_SORT=''BINARY'' NLS_TIME_FORMAT=''HH.MI.SSXFF AM'' NLS_TIMESTAMP_FORMAT=''DD-MON-RR HH.MI.SSXFF AM'' NLS_TIME_TZ_FORMAT=''HH.MI.SSXFF AM TZR'' NLS_TIMESTAMP_TZ_FORMAT=''DD-MON-RR HH.MI.SSXFF AM TZR'' NLS_DUAL_CURRENCY=''$'' NLS_COMP=''BINARY'' NLS_LENGTH_SEMANTICS=''BYTE'' NLS_NCHAR_CONV_EXCP=''FALSE''');
dbms_scheduler.set_attribute('"SQLSCRIPT_4640149"','logging_level',DBMS_SCHEDULER.LOGGING_RUNS);
COMMIT;
END;
/



BEGIN
dbms_scheduler.create_job('"SQLSCRIPT_736040"',
job_type=>'PLSQL_BLOCK', job_action=>
'
 begin
 EXECUTE IMMEDIATE ''alter index "AP"."XIF76EOD_INSTRUMENT_PRICE_TBL" shrink space'';
 EXECUTE IMMEDIATE ''alter index "AP"."XIE1EOD_INSTRUMENT_PRICE_TBL" shrink space'';
 EXECUTE IMMEDIATE ''alter index "AP"."XIF1EOD_INSTRUMENT_PRICE_TBL" shrink space'';
 EXECUTE IMMEDIATE ''alter index "AP"."XIF81EOD_INSTRUMENT_PRICE_TBL" shrink space'';
 EXECUTE IMMEDIATE ''alter index "AP"."XIF82EOD_INSTRUMENT_PRICE_TBL" shrink space'';
 EXECUTE IMMEDIATE ''alter index "AP"."XIE9EOD_INSTRUMENT_PRICE_TBL" shrink space'';
 end; '
, number_of_arguments=>0,
start_date=>TO_TIMESTAMP_TZ('25-NOV-2012 10.52.22.618085000 AM AMERICA/NEW_YORK','DD-MON-RRRR HH.MI.SSXFF AM TZR','NLS_DATE_LANGUAGE=english'), repeat_interval=>
NULL
, end_date=>NULL,
job_class=>'"DEFAULT_JOB_CLASS"', enabled=>FALSE, auto_drop=>FALSE,comments=>
NULL
);
sys.dbms_scheduler.set_attribute('"SQLSCRIPT_736040"','NLS_ENV','NLS_LANGUAGE=''AMERICAN'' NLS_TERRITORY=''AMERICA'' NLS_CURRENCY=''$'' NLS_ISO_CURRENCY=''AMERICA'' NLS_NUMERIC_CHARACTERS=''.,'' NLS_CALENDAR=''GREGORIAN'' NLS_DATE_FORMAT=''DD-MON-RR'' NLS_DATE_LANGUAGE=''AMERICAN'' NLS_SORT=''BINARY'' NLS_TIME_FORMAT=''HH.MI.SSXFF AM'' NLS_TIMESTAMP_FORMAT=''DD-MON-RR HH.MI.SSXFF AM'' NLS_TIME_TZ_FORMAT=''HH.MI.SSXFF AM TZR'' NLS_TIMESTAMP_TZ_FORMAT=''DD-MON-RR HH.MI.SSXFF AM TZR'' NLS_DUAL_CURRENCY=''$'' NLS_COMP=''BINARY'' NLS_LENGTH_SEMANTICS=''BYTE'' NLS_NCHAR_CONV_EXCP=''FALSE''');
dbms_scheduler.set_attribute('"SQLSCRIPT_736040"','logging_level',DBMS_SCHEDULER.LOGGING_RUNS);
COMMIT;
END;
/



BEGIN
dbms_scheduler.create_job('"SQLSCRIPT_8655033"',
job_type=>'PLSQL_BLOCK', job_action=>
'
 begin
 EXECUTE IMMEDIATE ''alter index "GP"."XIE2SCHWAB_POSITION_TBL" shrink space'';
 end; '
, number_of_arguments=>0,
start_date=>TO_TIMESTAMP_TZ('25-NOV-2012 01.02.59.875888000 PM AMERICA/NEW_YORK','DD-MON-RRRR HH.MI.SSXFF AM TZR','NLS_DATE_LANGUAGE=english'), repeat_interval=>
NULL
, end_date=>NULL,
job_class=>'"DEFAULT_JOB_CLASS"', enabled=>FALSE, auto_drop=>FALSE,comments=>
NULL
);
sys.dbms_scheduler.set_attribute('"SQLSCRIPT_8655033"','NLS_ENV','NLS_LANGUAGE=''AMERICAN'' NLS_TERRITORY=''AMERICA'' NLS_CURRENCY=''$'' NLS_ISO_CURRENCY=''AMERICA'' NLS_NUMERIC_CHARACTERS=''.,'' NLS_CALENDAR=''GREGORIAN'' NLS_DATE_FORMAT=''DD-MON-RR'' NLS_DATE_LANGUAGE=''AMERICAN'' NLS_SORT=''BINARY'' NLS_TIME_FORMAT=''HH.MI.SSXFF AM'' NLS_TIMESTAMP_FORMAT=''DD-MON-RR HH.MI.SSXFF AM'' NLS_TIME_TZ_FORMAT=''HH.MI.SSXFF AM TZR'' NLS_TIMESTAMP_TZ_FORMAT=''DD-MON-RR HH.MI.SSXFF AM TZR'' NLS_DUAL_CURRENCY=''$'' NLS_COMP=''BINARY'' NLS_LENGTH_SEMANTICS=''BYTE'' NLS_NCHAR_CONV_EXCP=''FALSE''');
dbms_scheduler.set_attribute('"SQLSCRIPT_8655033"','logging_level',DBMS_SCHEDULER.LOGGING_RUNS);
COMMIT;
END;
/



--------------------------------------------------------------------------------
-- SECTION: MATERIALIZED VIEWS
--------------------------------------------------------------------------------


  CREATE MATERIALIZED VIEW "ACCOUNT_APPEAR_DISAPPEAR_VALUE_MVW" ("NF_ACCOUNT_ID", "ASOFDATE", "VALUE")
  DEFAULT COLLATION "USING_NLS_COMP"  SEGMENT CREATION IMMEDIATE
  ORGANIZATION HEAP PCTFREE 10 PCTUSED 40 INITRANS 1 MAXTRANS 255
 NOCOMPRESS LOGGING
  STORAGE(INITIAL 65536 NEXT 1048576 MINEXTENTS 1 MAXEXTENTS 2147483645
  PCTINCREASE 0 FREELISTS 1 FREELIST GROUPS 1
  BUFFER_POOL DEFAULT FLASH_CACHE DEFAULT CELL_FLASH_CACHE DEFAULT)
  TABLESPACE "GP_TBS"
  BUILD IMMEDIATE
  USING INDEX
  REFRESH FORCE ON DEMAND
  USING DEFAULT LOCAL ROLLBACK SEGMENT
  USING ENFORCED CONSTRAINTS DISABLE ON QUERY COMPUTATION DISABLE QUERY REWRITE
  AS select NF_ACCOUNT_ID,
       ASOFDATE,
       sum(value) as value
from (
         SELECT e.NF_ACCOUNT_ID,
                ASOFDATE,
                SUM(eod.LONG_VALUE - eod.SHORT_VALUE + eod.ACCRUED_INTEREST) AS value
         FROM EOD_COMPONENT_VALUE_TBL eod,
              (select NF_ACCOUNT_ID,
                      EBASKET_ID,
                      coalesce(ee.INVESTMENT_INCEPTION_DATE, CREATED_DATE,
                               (select nvl(PERFORMANCE_INCEPTION_DATE, OPEN_date)
                                from NF_ACCOUNT_TBL n
                                where n.NF_ACCOUNT_ID = ee.NF_ACCOUNT_ID)) -
                      1 as appear_date
               from EBASKET_TBL ee) e
         WHERE e.EBASKET_ID = eod.EBASKET_ID
           and e.appear_date is not null
           and eod.ASOFDATE = e.appear_date
           AND eod.ASOFDATE <= CURRENT_DATE
           AND eod.TRANSFERS + eod.CASH_INFLOW - eod.CASH_OUTFLOW + eod.ASSET_INFLOW -
               eod.ASSET_OUTFLOW = 0
         GROUP BY e.NF_ACCOUNT_ID,
                  ASOFDATE
         union all
         select acc.NF_ACCOUNT_ID,
                ASOFDATE,
                SUM(eod.LONG_VALUE - eod.SHORT_VALUE + eod.ACCRUED_INTEREST) * (-1) as value
         from EOD_COMPONENT_VALUE_TBL eod,
              EBASKET_TBL acc
         where eod.EBASKET_ID = acc.EBASKET_ID
           and eod.ASOFDATE = acc.LIQUIDATION_DATE
           and eod.ASOFDATE <= CURRENT_DATE
         group by acc.NF_ACCOUNT_ID,
                  ASOFDATE
     )
group by NF_ACCOUNT_ID,
         ASOFDATE;


  CREATE MATERIALIZED VIEW "COMPONENT_ALLOCATION_BY_EBASKET_MVW" ("EBASKET_ID", "STRATEGY_ID")
  DEFAULT COLLATION "USING_NLS_COMP"  SEGMENT CREATION IMMEDIATE
  ORGANIZATION HEAP PCTFREE 10 PCTUSED 40 INITRANS 1 MAXTRANS 255
 NOCOMPRESS LOGGING
  STORAGE(INITIAL 65536 NEXT 1048576 MINEXTENTS 1 MAXEXTENTS 2147483645
  PCTINCREASE 0 FREELISTS 1 FREELIST GROUPS 1
  BUFFER_POOL DEFAULT FLASH_CACHE DEFAULT CELL_FLASH_CACHE DEFAULT)
  TABLESPACE "GP_TBS"
  BUILD IMMEDIATE
  USING INDEX
  REFRESH FORCE ON DEMAND
  USING DEFAULT LOCAL ROLLBACK SEGMENT
  USING ENFORCED CONSTRAINTS DISABLE ON QUERY COMPUTATION DISABLE QUERY REWRITE
  AS select x.EBASKET_ID, cx.STRATEGY_ID
from EBASKET_TBL x
join GP.SAA_SLEEVE_TBL sx ON (sx.SAA_ID = x.SAA_ID)
join GP.SAA_SLEEVE_COMPONENT_TBL cx ON (cx.SAA_SLEEVE_ID = sx.SAA_SLEEVE_ID)
where x.EBASKET_TYPE_CD = 3
and x.MULTI_EBASKET_ID is null
and (select count(*)
        from (
                select ax.SAA_ID from EBASKET_TBL ax where ax.EBASKET_ID = x.EBASKET_ID
                union
                select bx.SAA_ID from EBASKET_HISTORY_TBL bx where EBASKET_ID = x.EBASKET_ID
             )
    ) > 1;


  CREATE MATERIALIZED VIEW "ENTITY_APPEAR_VALUE_MVW" ("ENTITY_ID", "FIRM_CD", "APPEAR_DATE", "VALUE")
  DEFAULT COLLATION "USING_NLS_COMP"  SEGMENT CREATION IMMEDIATE
  ORGANIZATION HEAP PCTFREE 10 PCTUSED 40 INITRANS 1 MAXTRANS 255
 NOCOMPRESS LOGGING
  STORAGE(INITIAL 65536 NEXT 1048576 MINEXTENTS 1 MAXEXTENTS 2147483645
  PCTINCREASE 0 FREELISTS 1 FREELIST GROUPS 1
  BUFFER_POOL DEFAULT FLASH_CACHE DEFAULT CELL_FLASH_CACHE DEFAULT)
  TABLESPACE "GP_TBS"
  BUILD IMMEDIATE
  USING INDEX
  REFRESH FORCE ON DEMAND
  USING DEFAULT LOCAL ROLLBACK SEGMENT
  USING ENFORCED CONSTRAINTS DISABLE ON QUERY COMPUTATION DISABLE QUERY REWRITE
  AS SELECT
    ear.ENTITY_ID AS entity_id,
    ent.FIRM_CD AS firm_cd,
    eod.ASOFDATE AS appear_date,
    SUM(eod.LONG_VALUE - eod.SHORT_VALUE + eod.ACCRUED_INTEREST) AS value
FROM
    EOD_COMPONENT_VALUE_TBL eod
    join NF_ACCOUNT_TBL acc on (acc.NF_ACCOUNT_ID = eod.NF_ACCOUNT_ID)
    LEFT JOIN ENTITY_ACCOUNT_ROLE_TBL ear on (eod.NF_ACCOUNT_ID = ear.NF_ACCOUNT_ID)
    LEFT JOIN ENTITY_TBL ent on (ear.ENTITY_ID = ent.ENTITY_ID)
    LEFT JOIN FIRM_TBL firm on (ent.FIRM_CD = firm.FIRM_CD)
WHERE
    eod.ASOFDATE = acc.PERFORMANCE_INCEPTION_DATE - 1
    AND   ear.ACCOUNT_ROLE_CD IN (1,5,7)
    AND   eod.ASOFDATE <= CURRENT_DATE
    AND   eod.TRANSFERS + eod.CASH_INFLOW - eod.CASH_OUTFLOW + eod.ASSET_INFLOW - eod.ASSET_OUTFLOW = 0
    AND   firm.INACTIVE = 0
GROUP BY
    ent.FIRM_CD,
    ear.ENTITY_ID,
    eod.ASOFDATE;


  CREATE MATERIALIZED VIEW "ENTITY_APPEAR_VALUE_MVW2" ("ENTITY_ID", "FIRM_CD", "APPEAR_DATE", "VALUE")
  DEFAULT COLLATION "USING_NLS_COMP"  SEGMENT CREATION IMMEDIATE
  ORGANIZATION HEAP PCTFREE 10 PCTUSED 40 INITRANS 1 MAXTRANS 255
 NOCOMPRESS LOGGING
  STORAGE(INITIAL 65536 NEXT 1048576 MINEXTENTS 1 MAXEXTENTS 2147483645
  PCTINCREASE 0 FREELISTS 1 FREELIST GROUPS 1
  BUFFER_POOL DEFAULT FLASH_CACHE DEFAULT CELL_FLASH_CACHE DEFAULT)
  TABLESPACE "GP_TBS"
  BUILD IMMEDIATE
  USING INDEX
  REFRESH FORCE ON DEMAND
  USING DEFAULT LOCAL ROLLBACK SEGMENT
  USING ENFORCED CONSTRAINTS DISABLE ON QUERY COMPUTATION DISABLE QUERY REWRITE
  AS SELECT
    ear.ENTITY_ID AS entity_id,
    ent.FIRM_CD AS firm_cd,
    eod.ASOFDATE AS appear_date,
    SUM(eod.LONG_VALUE - eod.SHORT_VALUE + eod.ACCRUED_INTEREST) AS value
FROM
    EOD_COMPONENT_VALUE_TBL eod
    join NF_ACCOUNT_TBL acc on (acc.NF_ACCOUNT_ID = eod.NF_ACCOUNT_ID)
    LEFT JOIN ENTITY_ACCOUNT_ROLE_TBL ear on (eod.NF_ACCOUNT_ID = ear.NF_ACCOUNT_ID)
    LEFT JOIN ENTITY_TBL ent on (ear.ENTITY_ID = ent.ENTITY_ID)
    LEFT JOIN FIRM_TBL firm on (ent.FIRM_CD = firm.FIRM_CD)
WHERE
    eod.ASOFDATE = acc.PERFORMANCE_INCEPTION_DATE
    AND   ear.ACCOUNT_ROLE_CD IN (1,5,7)
    AND   eod.ASOFDATE <= CURRENT_DATE
    AND   eod.TRANSFERS + eod.CASH_INFLOW - eod.CASH_OUTFLOW + eod.ASSET_INFLOW - eod.ASSET_OUTFLOW = 0
    AND   firm.INACTIVE = 0
GROUP BY
    ent.FIRM_CD,
    ear.ENTITY_ID,
    eod.ASOFDATE;


  CREATE MATERIALIZED VIEW "ENTITY_DISAPPEAR_VALUE_MVW" ("ENTITY_ID", "FIRM_CD", "DISAPPEAR_DATE", "VALUE")
  DEFAULT COLLATION "USING_NLS_COMP"  SEGMENT CREATION IMMEDIATE
  ORGANIZATION HEAP PCTFREE 10 PCTUSED 40 INITRANS 1 MAXTRANS 255
 NOCOMPRESS LOGGING
  STORAGE(INITIAL 65536 NEXT 1048576 MINEXTENTS 1 MAXEXTENTS 2147483645
  PCTINCREASE 0 FREELISTS 1 FREELIST GROUPS 1
  BUFFER_POOL DEFAULT FLASH_CACHE DEFAULT CELL_FLASH_CACHE DEFAULT)
  TABLESPACE "GP_TBS"
  BUILD IMMEDIATE
  USING INDEX
  REFRESH FORCE ON DEMAND
  USING DEFAULT LOCAL ROLLBACK SEGMENT
  USING ENFORCED CONSTRAINTS DISABLE ON QUERY COMPUTATION DISABLE QUERY REWRITE
  AS select
     ear.ENTITY_ID as entity_id,
     ent.FIRM_CD as firm_cd,
     eod.ASOFDATE as disappear_date,
     SUM(eod.LONG_VALUE - eod.SHORT_VALUE + eod.ACCRUED_INTEREST) as value
from EOD_COMPONENT_VALUE_TBL eod
left join NF_ACCOUNT_TBL acc on ((eod.NF_ACCOUNT_ID = acc.NF_ACCOUNT_ID)
and (eod.ASOFDATE = acc.CLOSED_DATE))
left join ENTITY_ACCOUNT_ROLE_TBL ear on (eod.NF_ACCOUNT_ID =
ear.NF_ACCOUNT_ID)
left join ENTITY_TBL ent on (ear.ENTITY_ID = ent.ENTITY_ID)
where acc.CLOSED_DATE = eod.ASOFDATE
   and eod.ASOFDATE <= CURRENT_DATE
   and ear.ACCOUNT_ROLE_CD in (1,5,7)
group by
     ent.FIRM_CD,
     ear.ENTITY_ID,
     eod.ASOFDATE
;


  CREATE MATERIALIZED VIEW "EOD_VALUE_ACCOUNT_MVW" ("NF_ACCOUNT_ID", "ASOFDATE", "CASH_FLOW")
  DEFAULT COLLATION "USING_NLS_COMP"  SEGMENT CREATION IMMEDIATE
  ORGANIZATION HEAP PCTFREE 10 PCTUSED 40 INITRANS 1 MAXTRANS 255
 NOCOMPRESS LOGGING
  STORAGE(INITIAL 65536 NEXT 1048576 MINEXTENTS 1 MAXEXTENTS 2147483645
  PCTINCREASE 0 FREELISTS 1 FREELIST GROUPS 1
  BUFFER_POOL DEFAULT FLASH_CACHE DEFAULT CELL_FLASH_CACHE DEFAULT)
  TABLESPACE "GP_TBS"
  BUILD IMMEDIATE
  USING INDEX
  REFRESH FORCE ON DEMAND
  USING DEFAULT LOCAL ROLLBACK SEGMENT
  USING ENFORCED CONSTRAINTS DISABLE ON QUERY COMPUTATION DISABLE QUERY REWRITE
  AS select /*+ parallel(4) */ af.NF_ACCOUNT_ID, af.ASOFDATE, af.CASH_FLOW from GP.EOD_VALUE_ACCOUNT_VW af where round(af.CASH_FLOW,2) != 0;


  CREATE MATERIALIZED VIEW "EOD_VALUE_CLIENT_MVW" ("ENTITY_ID", "ASOFDATE", "CASH_FLOW")
  DEFAULT COLLATION "USING_NLS_COMP"  SEGMENT CREATION IMMEDIATE
  ORGANIZATION HEAP PCTFREE 10 PCTUSED 40 INITRANS 1 MAXTRANS 255
 NOCOMPRESS LOGGING
  STORAGE(INITIAL 65536 NEXT 1048576 MINEXTENTS 1 MAXEXTENTS 2147483645
  PCTINCREASE 0 FREELISTS 1 FREELIST GROUPS 1
  BUFFER_POOL DEFAULT FLASH_CACHE DEFAULT CELL_FLASH_CACHE DEFAULT)
  TABLESPACE "GP_TBS"
  BUILD IMMEDIATE
  USING INDEX
  REFRESH FORCE ON DEMAND
  USING DEFAULT LOCAL ROLLBACK SEGMENT
  USING ENFORCED CONSTRAINTS DISABLE ON QUERY COMPUTATION DISABLE QUERY REWRITE
  AS select /*+ parallel(4) */ af.ENTITY_ID, af.ASOFDATE, af.CASH_FLOW from GP.EOD_VALUE_CLIENT_VW af where round(af.CASH_FLOW,2) != 0;


  CREATE MATERIALIZED VIEW "EOD_VALUE_EBASKET_MVW" ("EBASKET_ID", "ASOFDATE", "CASH_FLOW")
  DEFAULT COLLATION "USING_NLS_COMP"  SEGMENT CREATION IMMEDIATE
  ORGANIZATION HEAP PCTFREE 10 PCTUSED 40 INITRANS 1 MAXTRANS 255
 NOCOMPRESS LOGGING
  STORAGE(INITIAL 65536 NEXT 1048576 MINEXTENTS 1 MAXEXTENTS 2147483645
  PCTINCREASE 0 FREELISTS 1 FREELIST GROUPS 1
  BUFFER_POOL DEFAULT FLASH_CACHE DEFAULT CELL_FLASH_CACHE DEFAULT)
  TABLESPACE "GP_TBS"
  BUILD IMMEDIATE
  USING INDEX
  REFRESH FORCE ON DEMAND
  USING DEFAULT LOCAL ROLLBACK SEGMENT
  USING ENFORCED CONSTRAINTS DISABLE ON QUERY COMPUTATION DISABLE QUERY REWRITE
  AS select /*+ parallel(4) */ af.EBASKET_ID, af.ASOFDATE, af.CASH_FLOW from GP.EOD_VALUE_EBASKET_VW af where round(af.CASH_FLOW,2) != 0;


  CREATE MATERIALIZED VIEW "INSTRUMENT_STYLE_WEIGHT_MVW" ("INSTRUMENT_MASTER_ID", "ASSET_CLASS_CD", "WEIGHT", "STYLE_CD", "CAPITALIZATION_CD", "EFFECTIVE_DATE", "INACTIVE_DATE", "FIRM_CD", "PREF_LEVEL")
  DEFAULT COLLATION "USING_NLS_COMP"  SEGMENT CREATION IMMEDIATE
  ORGANIZATION HEAP PCTFREE 10 PCTUSED 40 INITRANS 1 MAXTRANS 255
 NOCOMPRESS LOGGING
  STORAGE(INITIAL 65536 NEXT 1048576 MINEXTENTS 1 MAXEXTENTS 2147483645
  PCTINCREASE 0 FREELISTS 1 FREELIST GROUPS 1
  BUFFER_POOL DEFAULT FLASH_CACHE DEFAULT CELL_FLASH_CACHE DEFAULT)
  TABLESPACE "GP_TBS"
  BUILD IMMEDIATE
  USING INDEX
  REFRESH FORCE ON DEMAND
  USING DEFAULT LOCAL ROLLBACK SEGMENT
  USING ENFORCED CONSTRAINTS DISABLE ON QUERY COMPUTATION DISABLE QUERY REWRITE
  AS select
        isw.INSTRUMENT_MASTER_ID,
        isw.ASSET_CLASS_CD,
        isw.WEIGHT,
        isw.STYLE_CD,
        isw.CAPITALIZATION_CD,
        nvl(isw.EFFECTIVE_DATE,to_date ('01-01-1920', 'DD-MM-YYYY')) as effective_date,
        to_date('3000-01-01', 'YYYY-MM-DD') as inactive_date,
        isw.FIRM_CD,
        1 as pref_level
    from
        INSTRUMENT_STYLE_WEIGHT_TBL isw
union all
    select
        iswh.INSTRUMENT_MASTER_ID,
        iswh.ASSET_CLASS_CD,
        iswh.WEIGHT,
        iswh.STYLE_CD,
        iswh.CAPITALIZATION_CD,
        iswh.EFFECTIVE_DATE,
        iswh.INACTIVE_DATE,
        iswh.FIRM_CD,
        1 as pref_level
    from
        INSTRUMENT_STYLE_WEIGHT_HIST_TBL iswh
union all
    select
        isw.INSTRUMENT_MASTER_ID,
        isw.ASSET_CLASS_CD,
        1 as WEIGHT,
        isw.STYLE_CD,
        isw.CAPITALIZATION_CD,
        nvl(isw.CREATED_DATE,to_date ('01-01-1920', 'DD-MM-YYYY')) as effective_date,
        to_date('3000-01-01', 'YYYY-MM-DD') as inactive_date,
        isw.FIRM_CD,
        2 as pref_level
    from
        INSTRUMENT_SINGLE_CLASSIFICATION_TBL isw
union all
    select
        isw.INSTRUMENT_MASTER_ID,
        isw.ASSET_CLASS_CD,
        1 as WEIGHT,
        isw.STYLE_CD,
        isw.CAPITALIZATION_CD,
        nvl(isw.CREATED_DATE,to_date ('01-01-1920', 'DD-MM-YYYY')) as effective_date,
        isw.system_date as inactive_date,
        isw.FIRM_CD,
        2 as pref_level
    from
        GP.INSTRUMENT_SINGLE_CLASSIFICATION_HIST_TBL isw;


  CREATE MATERIALIZED VIEW "TOLERANCE_BAND_RAW_MVW" ("EBASKET_ID", "PORTFOLIO_VALIE", "PRICE", "TICKER_SYMBOL", "DESCRIPTION", "QTY", "TOTAL", "TARGET_WEIGHT", "ACTUAL_WEIGHT", "UNDERWEIGHT", "OVERWEIGHT", "VIOLAE")
  DEFAULT COLLATION "USING_NLS_COMP"  SEGMENT CREATION IMMEDIATE
  ORGANIZATION HEAP PCTFREE 10 PCTUSED 40 INITRANS 1 MAXTRANS 255
 NOCOMPRESS LOGGING
  STORAGE(INITIAL 65536 NEXT 1048576 MINEXTENTS 1 MAXEXTENTS 2147483645
  PCTINCREASE 0 FREELISTS 1 FREELIST GROUPS 1
  BUFFER_POOL DEFAULT FLASH_CACHE DEFAULT CELL_FLASH_CACHE DEFAULT)
  TABLESPACE "GP_TBS"
  BUILD IMMEDIATE
  USING INDEX
  REFRESH FORCE ON DEMAND
  USING DEFAULT LOCAL ROLLBACK SEGMENT
  USING ENFORCED CONSTRAINTS DISABLE ON QUERY COMPUTATION DISABLE QUERY REWRITE
  AS SELECT * FROM GP.tolerance_band_raw_vw;


  CREATE MATERIALIZED VIEW "TRADE_BALANCE_MVW" ("NF_ACCOUNT_ID", "TRADE_BALANCE", "SOD_CASH_AVAILABLE", "TRADE_DATE", "ACCOUNT_TRADING_TYPE_CD", "CASH_AVAILABLE", "BUYING_POWER")
  DEFAULT COLLATION "USING_NLS_COMP"  SEGMENT CREATION IMMEDIATE
  ORGANIZATION HEAP PCTFREE 10 PCTUSED 40 INITRANS 1 MAXTRANS 255
 NOCOMPRESS LOGGING
  STORAGE(INITIAL 65536 NEXT 1048576 MINEXTENTS 1 MAXEXTENTS 2147483645
  PCTINCREASE 0 FREELISTS 1 FREELIST GROUPS 1
  BUFFER_POOL DEFAULT FLASH_CACHE DEFAULT CELL_FLASH_CACHE DEFAULT)
  TABLESPACE "GP_TBS"
   CACHE
  BUILD IMMEDIATE
  USING INDEX
  REFRESH FORCE ON DEMAND
  USING DEFAULT LOCAL ROLLBACK SEGMENT
  USING ENFORCED CONSTRAINTS DISABLE ON QUERY COMPUTATION DISABLE QUERY REWRITE
  AS SELECT V.* FROM TRADE_BALANCE_VW V;

