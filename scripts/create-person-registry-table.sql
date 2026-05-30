-- Person Registry — DB-backed replacement for the hardcoded map in
-- geowealth/.../PersonRegistry.java. One row per person; the
-- payload is JSON so the schema can evolve without migrations as the
-- multi-username SSO model grows (additional tenant slots, role
-- annotations, audit metadata, etc.).
--
-- The shape of the PAYLOAD column:
--   {
--     "usernames":  ["tim1", "tim5", "tim10"],
--     "aliases":    { "billing": "tim1",  "trading": "tim5",  "users":  "tim10" },
--     "roles":      { "billing": [...],   "trading": [...],   "users":  [...] }
--   }
--
-- `usernames` replaces the legacy USERNAME_TO_PERSON_ID reverse lookup —
-- every login row in ENTITY_TBL whose LDAP_UID appears here resolves to
-- this PERSON_ID. `aliases` is the (person, tenant) → username table the
-- source document calls out (§ 6 of cross-subdomain-sso-keycloak.md);
-- `roles` is the per-(person, tenant) capability set.

CREATE TABLE PERSON_REGISTRY_TBL (
    PERSON_ID            VARCHAR2(64)  NOT NULL,
    DISPLAY_NAME         VARCHAR2(255),
    PAYLOAD              CLOB          NOT NULL,
    CREATED_DATE         DATE          DEFAULT SYSDATE NOT NULL,
    LAST_MODIFIED_DATE   DATE          DEFAULT SYSDATE NOT NULL,
    LAST_MODIFIED_BY     VARCHAR2(64),
    CONSTRAINT PK_PERSON_REGISTRY PRIMARY KEY (PERSON_ID)
);

COMMENT ON TABLE  PERSON_REGISTRY_TBL          IS 'Cross-subdomain SSO person registry — see cross-subdomain-sso-implementation.md and PersonRegistry.java javadoc.';
COMMENT ON COLUMN PERSON_REGISTRY_TBL.PERSON_ID    IS 'Stable per-person identifier emitted as the SAML NameID and OIDC personId claim.';
COMMENT ON COLUMN PERSON_REGISTRY_TBL.DISPLAY_NAME IS 'Human-friendly label shown in the Demo Users SPA persons grid.';
COMMENT ON COLUMN PERSON_REGISTRY_TBL.PAYLOAD      IS 'JSON: { usernames: [], aliases: { slug: alias }, roles: { slug: [roleName] } }.';

-- Seed the demo persona so the existing SSO flow keeps working on a
-- fresh DB. Re-runnable: ignore duplicate-PK on conflict.
DECLARE
    dup EXCEPTION;
    PRAGMA EXCEPTION_INIT(dup, -1);
BEGIN
    INSERT INTO PERSON_REGISTRY_TBL (PERSON_ID, DISPLAY_NAME, PAYLOAD, LAST_MODIFIED_BY)
    VALUES (
        'P-tim',
        'Tim Arnold — multi-username demo persona',
        '{
  "usernames": ["tim1", "tim5", "tim10"],
  "aliases":   { "billing": "tim1", "trading": "tim5", "users": "tim10" },
  "roles": {
    "billing": ["client", "advisor", "admin", "billing-admin",  "gwAdmin"],
    "trading": ["client", "advisor", "admin", "trading-trader", "gwAdmin"],
    "users":   ["client", "advisor", "admin", "users-admin",    "gwAdmin"]
  }
}',
        'seed'
    );
EXCEPTION WHEN dup THEN NULL;
END;
/

COMMIT;

-- Verification
SET LINES 200 PAGES 100;
COL PERSON_ID FOR A20;
COL DISPLAY_NAME FOR A50;
SELECT PERSON_ID, DISPLAY_NAME, LAST_MODIFIED_BY, LENGTH(PAYLOAD) AS PAYLOAD_LEN
FROM   PERSON_REGISTRY_TBL ORDER BY PERSON_ID;
EXIT;
