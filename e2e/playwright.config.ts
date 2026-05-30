import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright config for the cross-subdomain SSO E2E suite.
 *
 * Drives the live `docker compose` stack — the tests assume billing/trading/users
 * are reachable at their mkcert-signed `*.geowealth.int` hosts, and that P1 is
 * running on `http://localhost:8888`. See `e2e/README.md` for the boot order.
 *
 * `ignoreHTTPSErrors` is on because the demo's certs are mkcert-signed — fine
 * locally, not something you'd ship for a public test environment.
 */
export default defineConfig({
  testDir: './tests',
  timeout: 90_000,
  expect: { timeout: 15_000 },
  fullyParallel: false, // SSO tests share KC/P1 server state; serialize.
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    ignoreHTTPSErrors: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    actionTimeout: 10_000,
    navigationTimeout: 30_000,
    // Each test runs in a fresh context (Playwright default). Per-test login
    // is fine because the demo runs with MFA disabled on tim1 — see
    // `e2e/scripts/disable-mfa-for-tim1.sh` and the E2E README.
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
