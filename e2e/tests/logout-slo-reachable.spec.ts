import { test, expect } from '@playwright/test';
import { URLS } from '../fixtures/config.js';

/**
 * Regression for the logout SLO "ERR_CONNECTION_REFUSED" bug.
 *
 * Signing out of a domain (e.g. billing) ends with a 303 from the Token
 * Handler's `/auth/logout` redirecting the BROWSER to P1's IdP-initiated SLO
 * (`/saml/idp/initiate-slo.do`). The target host comes from
 * `app.p1.initiate-slo-url`, whose default is the compose value
 * `http://localhost:8888/...`. In the in-cluster K8s deployment the browser
 * reaches P1 on a different host/port (urls.dev.env `P1_BASE_URL`), so the
 * unoverridden default sent the user to `localhost:8888`, where nothing
 * listens → "This site can't be reached / ERR_CONNECTION_REFUSED".
 *
 * The fix makes the URL deploy-overridable (`APP_P1_INITIATE_SLO_URL`, set in
 * the K8s full-stack overlay to the browser-facing P1 base). This spec pins the
 * real invariant the user cares about: whatever `/auth/logout` redirects to, it
 * MUST be a P1 SLO endpoint the browser can actually open — a plain connection
 * to it must not be refused. It is environment-agnostic: it does not assume a
 * port, only that the configured target answers.
 *
 * No login is needed. `/auth/logout` is idempotent (see AuthController javadoc):
 * it returns the SAME 303 to the SLO redirect whether or not a session exists,
 * and the redirect host — the thing this test pins — is computed identically in
 * both cases. Driving it anonymously keeps the test fast and free of the
 * authenticated-logout fan-out timing (which the SPA tolerates via best-effort).
 */
test.describe('logout SLO redirect targets a browser-reachable P1', () => {
  test('signing out of billing redirects to a reachable oidc/logout.do', async ({ request }) => {
    // POST the Token Handler's POST-only /auth/logout WITHOUT following the
    // redirect, so we can inspect the Location the browser would navigate to.
    const resp = await request.post(`${URLS.domains.billing}/auth/logout`, {
      maxRedirects: 0,
    });
    expect(resp.status(), 'logout must 303 to the P1 SLO redirect').toBe(303);

    const location = resp.headers()['location'];
    expect(location, 'logout response carries a Location header').toBeTruthy();
    expect(location, 'Location is P1 RP-initiated logout').toContain('/oidc/logout.do');

    // An absolute, browser-style URL (scheme + host) — not a bare path that a
    // prior bug let Netty re-emit relative to the billing origin, and not an
    // in-cluster-only Service host the browser can't resolve.
    const target = new URL(location);
    expect(['http:', 'https:'], 'Location is an absolute http(s) URL').toContain(target.protocol);
    expect(target.hostname, 'P1 SLO host is browser-resolvable (not an in-cluster Service DNS name)')
      .not.toBe('p1-tomcat');

    // The crux: the target must actually answer. In the bug state the redirect
    // pointed at localhost:8888, where nothing listened, so a connection was
    // refused — exactly the user's "This site can't be reached". Open it the way
    // the browser would; a connection error fails the test with a clear message.
    let reachableStatus: number | null = null;
    try {
      const slo = await request.get(location, { maxRedirects: 0, timeout: 15_000 });
      reachableStatus = slo.status();
    } catch {
      // Connection refused / DNS failure / TLS handshake refusal land here.
      reachableStatus = null;
    }
    expect(
      reachableStatus,
      `P1 SLO target ${location} must be browser-reachable — a refused connection here is the ` +
        `ERR_CONNECTION_REFUSED bug (redirect points at an unreachable host)`,
    ).not.toBeNull();
    // P1 answers the SLO with a redirect (to its login form) or a 2xx; any real
    // HTTP status proves reachability. Reject only the "no server" case above.
    expect(reachableStatus, 'P1 returned a real HTTP status').toBeGreaterThan(0);
  });
});
