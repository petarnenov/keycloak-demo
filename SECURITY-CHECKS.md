# Phase 4 §9.3 security checks — results and reproduction

Records the four security probes from `geowealth-keycloak-poc-migration-plan.md` §9.3 against the running POC stack. Each section captures the result on a known-good run plus the exact command to re-run if anything looks off.

All probes were run on the `petarnenov/geowealth-whitelabel-poc` branch with the fake branding API (`dev-branding-api/server.py`) standing in for the GeoWealth Tomcat. Real-GeoWealth probes will be done on the geowealth side and recorded in that repo's findings doc.

---

## 1. Token leak audit — CLEAN

The POC bearer token (`POC_BRANDING_API_TOKEN`) lives only in `.envrc` (gitignored). Every committed reference is by name — `${POC_BRANDING_API_TOKEN}`, `os.environ.get("POC_BRANDING_API_TOKEN")`, prose mentioning the var — never the literal value.

Reproduce:

```bash
source .envrc

# (a) the literal value must not appear anywhere in the working tree.
LC_ALL=C grep -RFn \
  --exclude-dir=.git --exclude-dir=node_modules \
  --exclude-dir=build --exclude-dir=.gradle \
  --exclude-dir=dist --exclude-dir=.vite \
  "$POC_BRANDING_API_TOKEN" .
#  expected: no output

# (b) likewise no tracked file (covers cases the grep above might
#     accidentally miss — e.g. files only loaded via git history).
git ls-files | xargs -I{} sh -c \
  'grep -lF "$1" "{}" >/dev/null 2>&1 && echo "{}"' _ "$POC_BRANDING_API_TOKEN"
#  expected: no output

# (c) .envrc must be gitignored.
git check-ignore -v .envrc
#  expected: ".gitignore:NN:.envrc<tab>.envrc"
```

Token rotation has no impact on the audit — only the env var name appears in code.

---

## 2. Path traversal probe — CLOSED

The fake's routing is a simple prefix match. Every traversal payload either:
- gets URL-decoded to a "code" segment the brand map doesn't know → 404 not_found,
- or falls outside the `/branding-api/keycloak/whitelabel/*` prefix → 404 unknown_route.

No probe leaked file content. Reproduce:

```bash
source .envrc; T="$POC_BRANDING_API_TOKEN"

probe() {
  printf "%-58s " "$1"
  out=$(curl -s --path-as-is -H "Authorization: Bearer $T" \
        -w '|HTTP=%{http_code}' "http://127.0.0.1:8080$2")
  code=${out##*|HTTP=}
  body=${out%|HTTP=*}
  printf "%s  body=%.100s\n" "$code" "$body"
}

probe "/whitelabel/../../etc/passwd"             "/branding-api/keycloak/whitelabel/../../etc/passwd"
probe "/whitelabel/%2e%2e%2f…%2fpasswd"          "/branding-api/keycloak/whitelabel/%2e%2e%2f%2e%2e%2fetc%2fpasswd"
probe "/branding-api/../etc/passwd"              "/branding-api/../etc/passwd"
probe "/whitelabel/changepath/../../etc/passwd"  "/branding-api/keycloak/whitelabel/changepath/../../etc/passwd"
probe "/whitelabel/changepath/asset/../../passwd" "/branding-api/keycloak/whitelabel/changepath/asset/../../passwd"
probe "/whitelabel/changepath%00.json"           "/branding-api/keycloak/whitelabel/changepath%00.json"
probe "/../../etc/passwd"                        "/../../etc/passwd"
probe "/whitelabel/cca (control)"                "/branding-api/keycloak/whitelabel/cca"
```

Expected: every probe except the control returns `404` with a JSON error body. The control returns `200` with the cca brand.

**Note for the geowealth-side implementation:** the same probes should be re-run against the real Jakarta servlet once it's up. The fake doesn't read the filesystem at all, but the real servlet streams BLOBs from `etc/whitelabel/<code>/` and must not follow `..` segments out of that directory. The §9.3 plan item explicitly calls this out.

---

## 3. Color / value injection — BLOCKED at server, ZERO bytes in HTML

The Keycloak SPI's `Brand` constructor filters every `cssVariables` entry through `BrandCss.isSafeKey()` and `BrandCss.isSafe()`. Anything that doesn't match (`^--[a-zA-Z0-9_-]{1,64}$` for keys, `^#[0-9a-fA-F]{3,8}$` or one of `transparent|inherit|initial|unset|none|currentColor` for values) is dropped with a WARN log — and never reaches the FreeMarker template.

Demonstrated by spinning the fake up with `INJECT_POISON=1`, which adds two deliberately-malicious entries to every brand response. The fake's poison payload:

```python
POISON_ENTRIES = {
    # bad key — closes :root, opens body, embeds an evil URL
    "--theme-link-color; } body { background: url(http://evil/x); /*": "#ff0000",
    # bad value — same attack delivered through the value side
    "--theme-injected-poison": "red; } body { background: url(http://evil/y); /*",
}
```

Reproduce:

```bash
# (1) restart the fake with poison enabled
INJECT_POISON=1 ./dev-branding-api.sh   # foreground, ctrl-c when done

# (2) in another shell, drive a Keycloak render against a fresh host so
#     the SPI's brand cache misses and re-fetches.
UNIQ="poison-$RANDOM"
HTML=$(curl -s -L -H "Host: changepath.localhost:8898" \
  "http://localhost:8898/realms/geowealth-realm/protocol/openid-connect/auth?client_id=geowealth-poc-client&response_type=code&redirect_uri=http://changepath.localhost:5174/&scope=openid&state=$UNIQ")

# (3) the rendered <style id="geowealth-brand-vars"> block must contain
#     ONLY the legitimate 10 entries — no injected entries.
echo "$HTML" | awk '/<style id="geowealth-brand-vars">/,/<\/style>/' | head -25

# (4) absence assertions on the HTML.
echo "$HTML" | grep -c 'evil'                  # expect 0
echo "$HTML" | grep -cF '} body {'             # expect 0
echo "$HTML" | grep -c '\-\-theme-injected-poison' # expect 0

# (5) Keycloak log must show two Brand WARN drops per fresh render.
docker logs --since 5s keycloak-demo-keycloak-1 | grep "Brand\[changepath\]"
# expected (sample):
#   WARN  Brand[changepath]: dropping unsafe CSS variable key '--theme-link-color; } body { bac…'
#   WARN  Brand[changepath]: dropping unsafe CSS value for key '--theme-injected-poison' (value prefix: 'red; } body { background: url(ht…')
```

The logged values are truncated to 32 chars + ellipsis so a pathologically-long payload can't be used to spam the log file.

**Defense in depth.** The `BrandCss.isSafe()` validator must run server-side (`Brand` constructor), and the template additionally inherits Keycloak's HTML auto-escaping for any `${...}` interpolation. The first line of defense is the validator; the second is HTML escaping if the validator is ever weakened.

---

## 4. CSP audit — CLEAN, zero violations

Both branded login pages load with zero browser console errors and zero console warnings under Keycloak's default CSP.

Headers Keycloak emits on the login page (`start-dev` mode, no `browserSecurityHeaders` override in the realm):

```
Content-Security-Policy: frame-src 'self'; frame-ancestors 'self'; object-src 'none';
Referrer-Policy: no-referrer
Strict-Transport-Security: max-age=31536000; includeSubDomains
X-Content-Type-Options: nosniff
X-Frame-Options: SAMEORIGIN
X-XSS-Protection: 1; mode=block
```

The CSP does not restrict `style-src` or `script-src`, so the inline `<style id="geowealth-brand-vars">` block we add does not generate violations. `frame-ancestors 'self'` protects the login page from being embedded cross-origin (which would be a phishing risk); the POC app navigates top-frame, so this never trips.

Reproduce header inspection:

```bash
curl -s -o /dev/null -D - -H "Host: changepath.localhost:8898" \
  "http://localhost:8898/realms/geowealth-realm/protocol/openid-connect/auth?client_id=geowealth-poc-client&response_type=code&redirect_uri=http://changepath.localhost:5174/&scope=openid" \
  -L | grep -iE 'content-security-policy|x-frame|strict-transport|referrer-policy|x-content-type|x-xss'
```

Reproduce in-browser audit (Playwright MCP or any local Playwright install):

```js
// pseudocode — adapt to whichever runner you have
await page.goto('http://changepath.localhost:8898/realms/geowealth-realm/protocol/openid-connect/auth?client_id=geowealth-poc-client&response_type=code&redirect_uri=http://changepath.localhost:5174/&scope=openid&state=csp-audit');
const messages = page.consoleMessages();   // both errors and warnings
console.assert(messages.length === 0, 'expected zero console messages');

await page.goto('http://default.localhost:8898/.../&redirect_uri=http://default.localhost:5174/...');
const messages2 = page.consoleMessages();
console.assert(messages2.length === 0, 'expected zero console messages');
```

Last run (2026-05-22): `Total messages: 0 (Errors: 0, Warnings: 0)` on both hosts.

**Production tightening to track (out of scope for the POC):** add an explicit `style-src 'self' 'unsafe-inline'; script-src 'self'` CSP via the realm's `browserSecurityHeaders` once the prod reverse-proxy is in place. `'unsafe-inline'` is unavoidable as long as we ship the brand block inline; the alternative is a nonce-per-render or moving the CSS variables into a per-firm theme resource file, both of which are bigger changes than this POC needs.

---

## What the geowealth-side audit must add

These items aren't reproducible here — they live on the geowealth Tomcat servlet — and should be recorded in `~/geowealth/keycloak-poc-findings.md` on `team/petarnenov/keycloak-whitelabel-poc`:

| Item | What to verify |
|---|---|
| Token compare timing | `BrandingApiAuthFilter` uses a constant-time compare (`MessageDigest.isEqual` or equivalent), not `String.equals`. |
| Token never logged | Grep server logs after a failing auth — the bearer value MUST NOT appear, not even at DEBUG. |
| Path traversal under `etc/whitelabel/` | Re-run the §2 probes against the asset endpoints; the servlet should refuse `..` segments and `%2e%2e` escapes before the filesystem read. |
| Color validation at source | DB row containing an unsafe value: server should `400` (or return the row with the unsafe value stripped server-side, mirroring the Keycloak `Brand` filter). |
| Per-tenant data isolation | Asking for one firm's asset with another firm's token (if scoping is added) must `403`. POC token is global, so the test is moot at POC scope. |
