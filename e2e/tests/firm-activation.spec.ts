import { test, expect } from '@playwright/test';
import { URLS } from '../fixtures/config.js';
import { deleteDemoKcUser, loginViaP1 } from '../fixtures/auth.js';

/**
 * Firm activation through UI — what an operator can drive today.
 *
 * Two specs:
 *   1. `creates a firm + registers a person via the SPA` — green path. Tim1
 *      goes through Firms UI, then Persons UI, then verifies the entries
 *      materialise in their grids, then tears them down with the same UI
 *      buttons. End-to-end demo workflow that doesn't depend on any GW
 *      backend code beyond what BffUsersAction.createFirm sets up after
 *      the access-set + service-request fix in this branch.
 *   2. `new user signs in to the new firm` — currently fixmed. The
 *      keycloak-demo Users SPA can drive AddEditUserModal to call
 *      `/api/users/createUpdateUser` for the new firm, and now that the
 *      firm carries DEFAULT_ACCESS_SET + FIRM_SERVICE_REQUEST + the
 *      constrained one-to-one children, Hibernate stops short of the
 *      original `Firm.defaultAccessSet is null` failure. The next layer
 *      throws inside `NEntityDAO.createPersonalAccessSetPerEntity`:
 *        ORA-01400: cannot insert NULL into POLICY_RULE_TBL.ENTITY_ID
 *      The PolicyRules-agent flow that copies a firm-default access set
 *      onto a new user is wired against the canonical firm 1 seed
 *      (POLICY_RULE_TBL rules carry an ENTITY_ID template that doesn't
 *      exist for a freshly-minted firm). Building out the missing
 *      policy-rules + permissioning seed for new firms is its own work
 *      item — out of scope for the cross-subdomain SSO test suite.
 *      Until that is fixed, this spec drives the UI up to the moment of
 *      the persistence failure and stops; it is left in place so the
 *      diff is visible the day the GW backfill lands.
 *
 * Every step the green test takes is a click or a keystroke in the SPA;
 * no `/api/users/*` POSTs, no DB seeds. The Persons + Firms rows are
 * created and removed using only the buttons a human operator can see.
 */

const SUFFIX = `${Date.now()}`;
const FIRM_NAME = `Acme E2E ${SUFFIX}`;
const FIRM_CODE = `E2E${SUFFIX.slice(-5)}`; // ≤ 8 chars per FIRM_TBL.CODE
const PERSON_ID = `P-e2e-${SUFFIX}`;
const PERSON_USERNAME = `demo_e2e_${SUFFIX}`;
const PERSON_DISPLAY = `E2E Demo Person ${SUFFIX}`;

test.describe('firm activation through the Users SPA UI', () => {
  test.beforeAll(async () => {
    // Force a clean first-broker-login for tim1 — same convention as the
    // rest of the suite (e2e/fixtures/auth.ts#deleteDemoKcUser).
    await deleteDemoKcUser();
  });

  test('admin creates a firm and registers a person through the SPA', async ({ page }) => {
    let newFirmCd: number | null = null;

    await test.step('admin tim1 lands authenticated on the Users SPA', async () => {
      const me = await loginViaP1(page, 'users');
      const writeRoles = ['users-admin', 'admin', 'gwAdmin'];
      const hasWriteRole = writeRoles.some((r) => me.roles.includes(r));
      expect(
        hasWriteRole,
        `tim1 must carry one of [${writeRoles.join(', ')}]; got [${me.roles.join(', ')}]`,
      ).toBe(true);
      expect(me.firmCd, 'tim1 is in firm 1').toBe('1');
    });

    await test.step('admin creates a new firm via the Firms tab', async () => {
      await page.getByRole('link', { name: 'Firms' }).click();
      await expect(page.getByRole('heading', { level: 1, name: 'Firms' })).toBeVisible();
      await expect(page.getByRole('heading', { level: 2 })).toContainText(/^FIRMS \(\d+\)$/);

      await page.getByRole('button', { name: 'Add Firm' }).click();
      await expect(page.getByRole('heading', { level: 2, name: 'Add Firm' })).toBeVisible();

      await page.getByLabel('Firm name *').fill(FIRM_NAME);
      await page.getByLabel(/^Code/).fill(FIRM_CODE);
      // firmCd left blank → P1 auto-picks MAX(FIRM_CD)+1.
      await page.getByRole('button', { name: 'Create Firm' }).click();
      await expect(
        page.getByRole('heading', { level: 2, name: 'Add Firm' }),
      ).toBeHidden({ timeout: 15_000 });

      const row = page.locator('tr', { hasText: FIRM_NAME });
      await expect(row).toBeVisible();
      const firmCdText = (await row.locator('code').first().innerText()).trim();
      expect(firmCdText).toMatch(/^\d+$/);
      newFirmCd = Number(firmCdText);
      expect(newFirmCd).toBeGreaterThan(1);
      await expect(row.getByRole('button', { name: 'Deactivate' })).toBeVisible();
    });
    if (newFirmCd == null) throw new Error('newFirmCd not captured');

    await test.step('admin registers a person via the Persons tab', async () => {
      await page.getByRole('link', { name: 'Persons' }).click();
      await expect(page.getByRole('heading', { level: 1, name: 'Persons' })).toBeVisible();

      await page.getByRole('button', { name: 'Add Person' }).click();
      await expect(page.getByRole('heading', { level: 2, name: 'Add person' })).toBeVisible();

      await page.getByLabel('Person ID *').fill(PERSON_ID);
      await page.getByLabel('Display name').fill(PERSON_DISPLAY);
      await page.getByLabel(/^P1 usernames/).fill(PERSON_USERNAME);
      await page.getByPlaceholder('users alias (e.g. bob1)').fill(PERSON_USERNAME);
      await page.getByPlaceholder('users roles (comma-separated)').fill('client, users-viewer');

      await page.getByRole('button', { name: 'Save' }).click();
      await expect(
        page.getByRole('heading', { level: 2, name: 'Add person' }),
      ).toBeHidden({ timeout: 15_000 });

      const personRow = page.locator('tr', { hasText: PERSON_ID });
      await expect(personRow).toBeVisible();
      await expect(personRow).toContainText(PERSON_DISPLAY);
      await expect(personRow).toContainText(PERSON_USERNAME);
    });

    await test.step('cleanup — delete the person and deactivate the firm', async () => {
      // Persons cleanup first so the cross-subdomain registry doesn't keep
      // a dangling reference to a username for a deactivated firm.
      await page.getByRole('link', { name: 'Persons' }).click();
      const personRow = page.locator('tr', { hasText: PERSON_ID });
      if (await personRow.isVisible().catch(() => false)) {
        page.once('dialog', (d) => d.accept());
        await personRow.getByRole('button', { name: 'Delete' }).click();
        await expect(personRow).toBeHidden({ timeout: 15_000 });
      }

      await page.getByRole('link', { name: 'Firms' }).click();
      const firmRow = page.locator('tr', { hasText: FIRM_NAME });
      if (await firmRow.isVisible().catch(() => false)) {
        page.once('dialog', (d) => d.accept());
        await firmRow.getByRole('button', { name: 'Deactivate' }).click();
        await expect(firmRow).toBeHidden({ timeout: 15_000 });
      }
    });
  });

  test('admin creates a user in the new firm via the Users & Access modal', async ({ page }) => {
    // Drives AddEditUserModal — the keycloak-demo SPA's facade over P1's
    // createUpdateUser. A successful row in the grid proves the full
    // user-creation lifecycle is wired end-to-end for a brand-new firm:
    // DEFAULT_ACCESS_SET + FIRM_SSO enforcement + POLICY_RULE_ID
    // populated by createFirm, and CreateUpdateUserMsg's saveOrUpdate
    // collision fixed by the merge() switch in UserManagerTrait.
    const userSuffix = `${Date.now()}`;
    const firmName = `Acme U2 ${userSuffix}`;
    const firmCode = `U2${userSuffix.slice(-5)}`;
    const newFirstName = `Demo`;
    const newLastName = `E2E-${userSuffix.slice(-5)}`;
    const newUsername = `demo_e2e_${userSuffix}`;
    const newEmail = `demo-e2e-${userSuffix}@geo.com`;
    // Matches AddEditUserModal PASSWORD_REGEX: ≥ 8 chars, upper, lower,
    // digit, special.
    const newPassword = `Demo123!@`;

    await loginViaP1(page, 'users');

    // Create the firm.
    let newFirmCd: number | null = null;
    await test.step('admin creates the firm', async () => {
      await page.getByRole('link', { name: 'Firms' }).click();
      await page.getByRole('button', { name: 'Add Firm' }).click();
      await page.getByLabel('Firm name *').fill(firmName);
      await page.getByLabel(/^Code/).fill(firmCode);
      await page.getByRole('button', { name: 'Create Firm' }).click();
      await expect(
        page.getByRole('heading', { level: 2, name: 'Add Firm' }),
      ).toBeHidden({ timeout: 15_000 });
      const row = page.locator('tr', { hasText: firmName });
      await expect(row).toBeVisible();
      newFirmCd = Number((await row.locator('code').first().innerText()).trim());
      expect(newFirmCd).toBeGreaterThan(1);
    });
    if (newFirmCd == null) throw new Error('newFirmCd not captured');

    await test.step(`admin navigates to /users/${newFirmCd}`, async () => {
      await page.goto(`${URLS.domains.users}/users/${newFirmCd}`);
      await expect(
        page.locator('.page-head .muted', { hasText: new RegExp(`firmCd ${newFirmCd}`) }),
      ).toBeVisible({ timeout: 15_000 });
    });

    await test.step('admin creates a user in the new firm', async () => {
      const createBtn = page.getByRole('button', { name: 'Create New User' });
      await expect(createBtn).toBeEnabled({ timeout: 15_000 });
      await createBtn.click();
      await expect(
        page.getByRole('heading', { level: 2, name: 'Create new user' }),
      ).toBeVisible();

      await page.getByLabel('First Name *').fill(newFirstName);
      await page.getByLabel('Last Name').fill(newLastName);
      await page.getByLabel('Username *').fill(newUsername);
      await page.getByLabel('Email Address').fill(newEmail);
      await page.getByLabel('Password', { exact: true }).fill(newPassword);
      await page.getByLabel('Confirm Password').fill(newPassword);
      await page.getByLabel(/^Additional Security/).selectOption('false');

      const defaultRoleSelect = page.getByLabel('Default Role *');
      await expect
        .poll(async () => (await defaultRoleSelect.locator('option:not([value=""])').count()), {
          timeout: 15_000,
        })
        .toBeGreaterThan(0);
      await defaultRoleSelect.selectOption({ label: 'All Employees' });

      await page.getByRole('button', { name: 'Create', exact: true }).click();
      await expect(
        page.getByRole('heading', { level: 2, name: 'Create new user' }),
      ).toBeHidden({ timeout: 30_000 });

      // The user must materialise in the firm's grid with the right
      // username + first name. Email / roles columns can be empty in the
      // grid even when the row exists in ENTITY_TBL — that's a separate
      // GetActiveUsersMsg projection issue, not what this spec is
      // exercising. The point here is that createUpdateUser succeeded
      // end-to-end on a brand-new firm.
      const userRow = page.locator('tr', { hasText: newUsername });
      await expect(userRow).toBeVisible();
      await expect(userRow).toContainText(newFirstName);
    });

    await test.step('cleanup — deactivate the firm', async () => {
      // No delete-user UI button; ENTITY row stays inactive in DB. The
      // unique-suffix usernames keep re-runs isolated.
      await page.getByRole('link', { name: 'Firms' }).click();
      const firmRow = page.locator('tr', { hasText: firmName });
      if (await firmRow.isVisible().catch(() => false)) {
        page.once('dialog', (d) => d.accept());
        await firmRow.getByRole('button', { name: 'Deactivate' }).click();
        await expect(firmRow).toBeHidden({ timeout: 15_000 });
      }
    });
  });
});
