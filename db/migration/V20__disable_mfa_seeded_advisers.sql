-- V20 — disable email-OTP MFA for the seeded demo advisers.
--
-- Why: P1 enforces MFA_REQUIRED_FLAG=1 on advisors, but a seeded demo / automated
-- E2E has no inbox to fetch the OTP from, so MFA blocks login by design (the suite
-- otherwise runs e2e/scripts/disable-mfa-for-tim1.sh as a one-off DB tweak). This
-- migration makes that part of the reproducible seed: the demo advisors in the
-- seeded firms log in with username+password only. NOT for production.
UPDATE ENTITY_TBL
SET    MFA_REQUIRED_FLAG = 0
WHERE  ENTITY_TYPE_CD = 4              -- advisor
AND    FIRM_CD IN (1, 3, 5, 40)
AND    NVL(MFA_REQUIRED_FLAG, 0) <> 0;

COMMIT;
