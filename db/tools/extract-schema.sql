--------------------------------------------------------------------------------
-- extract-schema.sql
--
-- Reproduces the Flyway baseline (../migration/V1__baseline_schema.sql) and the
-- optional env file (../optional/env_objects.sql) by extracting DDL from a live
-- Oracle schema with DBMS_METADATA.
--
-- Run it AS the schema owner (GP) with SQLcl or SQL*Plus, e.g.:
--   sql -S gp/<pwd>@192.168.1.42:1521:ORCL12VM @extract-schema.sql
--
-- It writes one file per object category into ./out/ . Re-assemble in the order
-- listed in db/README.md (the section banners in V1 follow the same order).
--
-- POST-EXTRACTION NORMALIZATION applied to the committed V1 (do it again if you
-- regenerate):
--   * Strip system-generated constraint names so a fresh DB assigns its own and
--     does not hit ORA-02264:
--       sed -E 's/CONSTRAINT "SYS_C[0-9]+" //g'  on 03_tables.sql / 05_ref_constraints.sql
--   * SQLTERMINATOR=true (set below) must stay on for EVERY section, including
--     sequences - without the trailing ';' SQL*Plus silently drops them.
--
-- TARGET PDB must be MAX_STRING_SIZE=EXTENDED (32767 VARCHAR2 + COLLATE), else
-- ORA-43929 on import. See V1 header for the one-time conversion steps.
--------------------------------------------------------------------------------

set long 2000000000 longchunksize 100000 pagesize 0 heading off feedback off verify off trimspool on linesize 32767 termout off echo off

-- Portable, owner-agnostic, physical-storage-free DDL.
begin
  dbms_metadata.set_transform_param(dbms_metadata.session_transform,'EMIT_SCHEMA',false);
  dbms_metadata.set_transform_param(dbms_metadata.session_transform,'SEGMENT_ATTRIBUTES',false);
  dbms_metadata.set_transform_param(dbms_metadata.session_transform,'STORAGE',false);
  dbms_metadata.set_transform_param(dbms_metadata.session_transform,'TABLESPACE',false);
  dbms_metadata.set_transform_param(dbms_metadata.session_transform,'CONSTRAINTS',true);
  dbms_metadata.set_transform_param(dbms_metadata.session_transform,'REF_CONSTRAINTS',false);  -- FKs emitted separately
  dbms_metadata.set_transform_param(dbms_metadata.session_transform,'PRETTY',true);
  dbms_metadata.set_transform_param(dbms_metadata.session_transform,'SQLTERMINATOR',true);
end;
/

-- ===== baseline (db/migration/V1__baseline_schema.sql) =====

spool out/00_types.sql
select dbms_metadata.get_ddl('TYPE', type_name) from user_types order by type_name;
spool off

spool out/01_type_bodies.sql
select dbms_metadata.get_ddl('TYPE_BODY', type_name) from user_types
 where exists (select 1 from user_source s where s.name = user_types.type_name and s.type = 'TYPE BODY')
 order by type_name;
spool off

-- ISEQ$$ identity sequences are auto-created by IDENTITY columns - skip them.
spool out/02_sequences.sql
select dbms_metadata.get_ddl('SEQUENCE', sequence_name) from user_sequences
 where sequence_name not like 'ISEQ$$%' order by sequence_name;
spool off

spool out/03_tables.sql
select dbms_metadata.get_ddl('TABLE', table_name) from user_tables
 where table_name not like 'BIN$%' and nested = 'NO' and temporary = 'N'
 order by table_name;
spool off

-- Exclude constraint-backed indexes (auto-created by PK/UNIQUE) and LOB indexes.
spool out/04_indexes.sql
select dbms_metadata.get_ddl('INDEX', index_name) from user_indexes
 where index_type not in ('LOB') and table_name not like 'BIN$%'
   and index_name not in (select index_name from user_constraints where index_name is not null)
 order by index_name;
spool off

spool out/05_ref_constraints.sql
select dbms_metadata.get_ddl('REF_CONSTRAINT', constraint_name) from user_constraints
 where constraint_type = 'R' order by table_name, constraint_name;
spool off

spool out/06_views.sql
select dbms_metadata.get_ddl('VIEW', view_name) from user_views order by view_name;
spool off

spool out/07_functions.sql
select dbms_metadata.get_ddl('FUNCTION', object_name) from user_objects
 where object_type = 'FUNCTION' order by object_name;
spool off

spool out/08_procedures.sql
select dbms_metadata.get_ddl('PROCEDURE', object_name) from user_objects
 where object_type = 'PROCEDURE' order by object_name;
spool off

-- 'PACKAGE' emits spec + body together.
spool out/09_packages.sql
select dbms_metadata.get_ddl('PACKAGE', object_name) from user_objects
 where object_type = 'PACKAGE' order by object_name;
spool off

spool out/11_triggers.sql
select dbms_metadata.get_ddl('TRIGGER', trigger_name) from user_triggers order by trigger_name;
spool off

spool out/12_synonyms.sql
select dbms_metadata.get_ddl('SYNONYM', synonym_name) from user_synonyms order by synonym_name;
spool off

-- ===== optional / env-specific (db/optional/env_objects.sql) =====

spool out/90_dblinks.sql
select dbms_metadata.get_ddl('DB_LINK', db_link) from user_db_links order by db_link;
spool off

spool out/91_jobs.sql
select dbms_metadata.get_ddl('PROCOBJ', object_name) from user_objects
 where object_type = 'JOB' order by object_name;
spool off

spool out/93_mviews.sql
select dbms_metadata.get_ddl('MATERIALIZED_VIEW', mview_name) from user_mviews order by mview_name;
spool off

-- NOTE: Java is loaded as compiled classes (no JAVA SOURCE), so it cannot be
-- extracted as DDL here - reload it with loadjava from the original jar.

exit
