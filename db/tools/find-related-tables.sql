--------------------------------------------------------------------------------
-- find-related-tables.sql
--
-- Referential-closure discovery: given a HUB key column and a list of key VALUES
-- already present in the seed, find every base table that has that column AND
-- contains at least one of those values. This is how the seed graph is grown one
-- hop at a time (the schema has only ~84 declared FKs, so relationships are found
-- by column name + value match, not just FK constraints).
--
-- Edit the two DEFINEs below, then run AS the schema owner (GP). It can be slow
-- on a big schema (it count-probes every candidate table) — run it in the
-- background and read the spooled output:
--   nohup sql -S gp/<pwd>@host:1521/SID @find-related-tables.sql > /dev/null 2>&1 &
--   ... then read ./related.out
--
-- Output lines: "<TABLE> <match_count>"  (match_count capped at 11 = ">=10").
-- Feed the table names into the extract -> scrub_faker.py -> assemble pipeline
-- (see seed-pipeline.md).
--------------------------------------------------------------------------------

define HUB  = "ENTITY_ID"
-- comma-separated, single-quoted key values from already-seeded rows:
define VALS = "'3CE4F04D5C8C483AA5BAC168B7AB670A','00146D7E90514A39AB16ADC6BC1FBD74'"

set serveroutput on size unlimited lines 200 pagesize 0 feedback off
spool related.out
declare
  c number;
begin
  for t in (
    select table_name tn from user_tables
    where table_name in (select table_name from user_tab_columns where column_name = '&HUB')
      -- skip history / temp / backup / materialized-view shadow tables:
      and table_name not like '%HIST%'
      and table_name not like '%TEMP%'
      and table_name not like '%TMP%'
      and table_name not like '%MVW%'
      and table_name not like '%BACKUP%'
      and table_name not like '%PREV%'
      and table_name not like '%BEFORE%'
      and table_name not like '%AFTER%'
      and not regexp_like(table_name, '_20[0-9]{6}$')   -- dated snapshots
  ) loop
    begin
      execute immediate 'select count(*) from "' || t.tn ||
        '" where &HUB in (&VALS) and rownum <= 11' into c;
      if c > 0 then dbms_output.put_line(t.tn || ' ' || c); end if;
    exception when others then null;   -- skip tables the column can't be probed on
    end;
  end loop;
end;
/
spool off
