-- V19 — adviser suitability config for the seeded advisers.
--
-- Why: P1 maps NEntity 1:1 to SUITABILITY_ADVISER_CONFIGURATION_TBL
-- (com.geowealth.model.suitability.AdviserSuitabilityConfiguration). On login the
-- advisor's entity is loaded and Hibernate eagerly fetches this row; when it is
-- missing the login dies with `ObjectNotFoundException: No row ... #<entityId>`
-- ("System error occurred"). The prod/host DB had these rows; the Flyway seed did
-- not — this migration closes that gap so every seeded advisor (firms 1/3/5/40)
-- gets the standard config. Values mirror the host's uniform default (3/3/3/2).
--
-- Idempotent: inserts only for advisor entities (ENTITY_TYPE_CD=4) that lack a row.
INSERT INTO SUITABILITY_ADVISER_CONFIGURATION_TBL
  ( ENTITY_ID,
    SUITABILITY_FIELDS_LEVEL_CD,
    EDIT_ACCOUNT_FIELDS_LEVEL_CD,
    EDIT_MODEL_RISKS_LEVEL_CD,
    COMPLIANCE_CENTER_LEVEL_CD,
    CREATED_DATE,
    CREATED_BY )
-- ENTITY_TBL.ENTITY_ID is CHAR(32) holding the hex string directly (not RAW), so
-- use it as-is.
-- CREATED_BY is read back as an entity UUID, so it must be a valid 32-hex id
-- (NOT a free-text marker) — use the advisor's own entity id.
SELECT e.ENTITY_ID, 3, 3, 3, 2, SYSTIMESTAMP, e.ENTITY_ID
FROM   ENTITY_TBL e
WHERE  e.ENTITY_TYPE_CD = 4            -- advisor
AND    e.FIRM_CD IN (1, 3, 5, 40)
AND    NOT EXISTS ( SELECT 1
                    FROM   SUITABILITY_ADVISER_CONFIGURATION_TBL s
                    WHERE  s.ENTITY_ID = e.ENTITY_ID );

COMMIT;
