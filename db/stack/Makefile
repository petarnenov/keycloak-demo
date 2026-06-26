# gw-stack — provision a fresh local GeoWealth Oracle from the Flyway migrations.
#
# Why this exists: `flyway migrate` ALONE cannot build the schema. V1 is a
# type=BASELINE migration (Flyway OSS cannot parse its PL/SQL), so the 1792-table
# schema must be loaded with sqlplus and only RECORDED with Flyway, after which
# V2..V15 run normally. The login password hash lives in the gitignored
# ../local/R__local_login_hash.sql (the hash is a credential) and is applied last.
#
# One-shot from an empty/new DB:   make provision
# Guaranteed clean slate (wipes):  make fresh
# Verify the result:               make verify
#
# Override the engine if needed:   make CE=podman DC="podman compose" provision
#
# NOTE: written for macOS's GNU Make 3.81 — no .ONESHELL / .SHELLFLAGS; each SQL
# block is a single logical line (printf | sqlplus) so it runs in one shell.

SHELL := /bin/bash

# --- knobs (no inline comments — they would leak trailing spaces into values) ---
CE        ?= docker
DC        ?= docker compose
ORA       ?= gw-oracle
SYS_PW    ?= oracle_sys_pw
APP_USER  ?= GP
APP_PW    ?= gp123
PDB       ?= FREEPDB1

FW_URL    := jdbc:oracle:thin:@//oracle:1521/$(PDB)
MIG_DIR   := $(CURDIR)/../migration
LOCAL_DIR := $(CURDIR)/../local
LOCAL_SQL := $(LOCAL_DIR)/R__local_login_hash.sql

# flyway one-shot helper (the compose service already bind-mounts ../migration)
FLYWAY = $(DC) run --rm flyway -url=$(FW_URL) -user=$(APP_USER) -password=$(APP_PW)
# sqlplus as the app user / as sysdba, reading SQL from stdin
SQLPLUS    = $(CE) exec -i $(ORA) sqlplus -s $(APP_USER)/$(APP_PW)@//localhost:1521/$(PDB)
SQLPLUS_PDB_SYS = $(CE) exec -i $(ORA) sqlplus -s sys/$(SYS_PW)@//localhost:1521/$(PDB) as sysdba
SQLPLUS_CDB_SYS = $(CE) exec -i $(ORA) sqlplus -s sys/$(SYS_PW)@//localhost:1521/FREE as sysdba

.PHONY: provision fresh up down down-wipe wait-healthy prepare-oracle \
        load-baseline record-baseline migrate local-hash info update verify sql help \
        seeded bake bake-up bake-down bake-wipe

# --- baked image flow ---------------------------------------------------------
# Provisions schema+seeds ONCE into a Docker image, then any container started
# from that image is ready in ~30s on first run (one-time copy of datafiles
# from the image's baked snapshot into the named volume) and ~10s on every
# restart (the volume is already populated). Compare to `provision` which is
# ~2-3 min EVERY time on an empty volume.
#
#   make seeded      # build image (once) + start + verify — the whole story
#   make bake-down   # stop the container, keep the volume (restart is ~10s)
#   make bake-wipe   # stop AND drop the volume (next bake-up re-copies)

IMAGE_BAKED ?= keycloak-demo-db:seeded
DB_CTX      := $(realpath $(CURDIR)/..)
BAKED_VOL   ?= gw-oracle-baked-data

help: ## Show targets
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	  | sort | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

# ------------------------------------------------------------------------------
# Headline: build a working DB on top of an EMPTY/new Oracle volume.
# ------------------------------------------------------------------------------
provision: up wait-healthy prepare-oracle load-baseline record-baseline migrate local-hash verify ## Full provision on an empty DB
	@echo ""
	@echo ">>> DONE. Local GeoWealth DB is provisioned (schema + seeds V2..V15 + login hash)."

# Wipe the Oracle volume first, then provision — guarantees a clean slate.
fresh: down-wipe provision ## Wipe volumes then provision from scratch

# ------------------------------------------------------------------------------
# Building blocks
# ------------------------------------------------------------------------------
up: ## Start oracle + elasticsearch
	$(DC) up -d oracle elasticsearch

down: ## Stop services (keep data volume)
	$(DC) down

down-wipe: ## Stop services AND delete the Oracle data volume
	$(DC) down -v

wait-healthy: ## Block until the Oracle container reports healthy
	@echo ">>> Waiting for $(ORA) to become healthy (Oracle init takes ~1-2 min)..."
	@for i in $$(seq 1 60); do \
	  status=$$($(CE) inspect --format '{{.State.Health.Status}}' $(ORA) 2>/dev/null || echo missing); \
	  if [ "$$status" = healthy ]; then echo ">>> $(ORA) healthy."; exit 0; fi; \
	  printf '    [%2d/60] %s\r' "$$i" "$$status"; sleep 5; \
	done; \
	echo ">>> TIMEOUT waiting for $(ORA) to be healthy."; exit 1

prepare-oracle: ## One-time: MAX_STRING_SIZE=EXTENDED + grant DBMS_CRYPTO to GP
	@echo ">>> Enabling EXTENDED string size (required by the baseline schema)..."
	@printf '%s\n' \
	  'WHENEVER SQLERROR CONTINUE' \
	  'ALTER PLUGGABLE DATABASE $(PDB) CLOSE IMMEDIATE;' \
	  'ALTER PLUGGABLE DATABASE $(PDB) OPEN UPGRADE;' \
	  'ALTER SESSION SET CONTAINER=$(PDB);' \
	  'ALTER SYSTEM SET MAX_STRING_SIZE=EXTENDED;' \
	  '@?/rdbms/admin/utl32k.sql' \
	  'ALTER SESSION SET CONTAINER=CDB$$ROOT;' \
	  'ALTER PLUGGABLE DATABASE $(PDB) CLOSE IMMEDIATE;' \
	  'ALTER PLUGGABLE DATABASE $(PDB) OPEN;' \
	  'ALTER PLUGGABLE DATABASE $(PDB) SAVE STATE;' \
	  'EXIT' \
	  | $(SQLPLUS_CDB_SYS)
	@echo ">>> Granting EXECUTE ON SYS.DBMS_CRYPTO to $(APP_USER)..."
	@printf '%s\n' \
	  'WHENEVER SQLERROR CONTINUE' \
	  'GRANT EXECUTE ON SYS.DBMS_CRYPTO TO $(APP_USER);' \
	  'EXIT' \
	  | $(SQLPLUS_PDB_SYS)

load-baseline: ## Load V1 (1792-table schema) via sqlplus — NOT runnable by Flyway
	@echo ">>> Loading V1 baseline schema via sqlplus (~1792 tables)..."
	$(CE) cp $(MIG_DIR)/V1__baseline_schema.sql $(ORA):/tmp/V1.sql
	@printf '%s\n' \
	  'SET DEFINE OFF' \
	  'WHENEVER SQLERROR CONTINUE' \
	  '@/tmp/V1.sql' \
	  'EXIT' \
	  | $(SQLPLUS)
	@echo ">>> V1 loaded. Object summary:"
	@printf '%s\n' \
	  'SET HEADING OFF FEEDBACK OFF' \
	  "SELECT 'tables=' || COUNT(*) FROM user_tables;" \
	  "SELECT 'valid='  || COUNT(*) FROM user_objects WHERE status='VALID';" \
	  "SELECT 'invalid='|| COUNT(*) FROM user_objects WHERE status='INVALID';" \
	  'EXIT' \
	  | $(SQLPLUS)

record-baseline: ## Drop the empty history table V1 created, then `flyway baseline`
	@echo ">>> Dropping the empty flyway_schema_history that V1's DDL created..."
	@printf '%s\n' \
	  'WHENEVER SQLERROR CONTINUE' \
	  'DROP TABLE "flyway_schema_history" CASCADE CONSTRAINTS PURGE;' \
	  'EXIT' \
	  | $(SQLPLUS)
	@echo ">>> Recording V1 as the Flyway baseline..."
	$(FLYWAY) -baselineVersion=1 -baselineDescription="baseline_schema" baseline

migrate: ## Run the seed migrations V2..V15 (skips the V1 baseline)
	@echo ">>> Applying seed migrations V2..V15..."
	$(FLYWAY) -locations=filesystem:/flyway/sql migrate

local-hash: ## Apply the gitignored login-password hash (../local/R__...)
	@if [ ! -f "$(LOCAL_SQL)" ]; then \
	  echo ">>> WARNING: $(LOCAL_SQL) not found (gitignored)."; \
	  echo ">>>          Schema is complete but NO user can log in until the"; \
	  echo ">>>          LDAP_PSWD_HASH is set. Create ../local/R__local_login_hash.sql."; \
	  exit 0; \
	fi; \
	echo ">>> Applying local login-password hash (R__) ..."; \
	$(DC) run --rm -v "$(LOCAL_DIR):/flyway/local" flyway \
	  -url=$(FW_URL) -user=$(APP_USER) -password=$(APP_PW) \
	  -locations=filesystem:/flyway/sql,filesystem:/flyway/local migrate

info: ## Check status: which migrations are applied vs still pending
	@echo ">>> Migration status:"
	@if [ -f "$(LOCAL_SQL)" ]; then \
	  $(DC) run --rm -v "$(LOCAL_DIR):/flyway/local" flyway \
	    -url=$(FW_URL) -user=$(APP_USER) -password=$(APP_PW) \
	    -locations=filesystem:/flyway/sql,filesystem:/flyway/local info; \
	else \
	  $(FLYWAY) -locations=filesystem:/flyway/sql info; \
	fi

update: ## Check for & apply any new/pending migrations to the RUNNING DB (idempotent)
	@echo ">>> State before:"
	@$(MAKE) --no-print-directory info
	@if [ -f "$(LOCAL_SQL)" ]; then \
	  echo ">>> Applying pending versioned migrations (+ repeatable local hash)..."; \
	  $(DC) run --rm -v "$(LOCAL_DIR):/flyway/local" flyway \
	    -url=$(FW_URL) -user=$(APP_USER) -password=$(APP_PW) \
	    -locations=filesystem:/flyway/sql,filesystem:/flyway/local migrate; \
	else \
	  echo ">>> Applying pending versioned migrations (db/local hash file absent)..."; \
	  $(FLYWAY) -locations=filesystem:/flyway/sql migrate; \
	fi
	@echo ">>> State after:"
	@$(MAKE) --no-print-directory verify

verify: ## Smoke-check schema + seeds
	@echo ">>> Verifying..."
	@printf '%s\n' \
	  "SET HEADING OFF FEEDBACK OFF PAGESIZE 0" \
	  "SELECT 'flyway_at=v'   || \"version\" FROM (SELECT \"version\" FROM \"flyway_schema_history\" WHERE \"success\"=1 AND \"version\" IS NOT NULL ORDER BY \"installed_rank\" DESC) WHERE ROWNUM=1;" \
	  "SELECT 'tables='       || COUNT(*) FROM user_tables;" \
	  "SELECT 'firms='        || COUNT(*) FROM firm_tbl WHERE firm_cd IN (1,3,5,40);" \
	  "SELECT 'login_users='  || COUNT(*) FROM entity_tbl WHERE ldap_uid IN ('tim1','tim3','tim40');" \
	  "SELECT 'hashes_set='   || COUNT(*) FROM entity_tbl WHERE ldap_pswd_hash IS NOT NULL AND firm_cd IN (1,3,5,40);" \
	  "SELECT 'objtype_perms='|| COUNT(*) FROM objecttype_permission_tbl;" \
	  "SELECT 'role_perms='   || COUNT(*) FROM role_permission_tbl WHERE role_cd IN (528,554,672);" \
	  "SELECT 'view_rules='   || COUNT(*) FROM policy_rule_tbl WHERE object_type_cd=4 AND permission_cd=1;" \
	  "EXIT" \
	  | $(SQLPLUS)

sql: ## Open an interactive sqlplus as GP
	$(CE) exec -it $(ORA) sqlplus $(APP_USER)/$(APP_PW)@//localhost:1521/$(PDB)

# ------------------------------------------------------------------------------
# Baked-image flow: one-shot seeded DB ready in seconds.
# ------------------------------------------------------------------------------

seeded: bake bake-up ## Build the seeded image (cached after first build) + start ready-to-use

bake: ## Build the baked-DB image (~3-5 min the first time; cached re-build is instant)
	@echo ">>> Building $(IMAGE_BAKED) from $(DB_CTX)/Dockerfile ..."
	$(CE) build -t $(IMAGE_BAKED) -f $(DB_CTX)/Dockerfile $(DB_CTX)

bake-up: ## Start a container from the baked image: first start ~30-60s, restart ~10s
	-$(CE) rm -f $(ORA) >/dev/null 2>&1
	@echo ">>> Starting $(ORA) from $(IMAGE_BAKED) (named volume: $(BAKED_VOL))..."
	$(CE) run -d --name $(ORA) \
	  -p 127.0.0.1:1521:1521 \
	  -v $(BAKED_VOL):/opt/oracle/oradata \
	  $(IMAGE_BAKED)
	@$(MAKE) --no-print-directory wait-healthy
	@$(MAKE) --no-print-directory verify
	@echo ""
	@echo ">>> DONE. Seeded DB is up at //127.0.0.1:1521/$(PDB) ($(APP_USER)/$(APP_PW))."

bake-down: ## Stop+remove the container (keep $(BAKED_VOL) -> restart is ~10s)
	-$(CE) rm -f $(ORA)

bake-wipe: ## Stop+remove the container AND drop $(BAKED_VOL) (next bake-up re-seeds)
	-$(CE) rm -f $(ORA)
	-$(CE) volume rm $(BAKED_VOL)
