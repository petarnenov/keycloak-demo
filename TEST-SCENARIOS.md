# Whitelabel test scenarios — production resolution matrix

Captures the data seeded into the GeoWealth dev Oracle (`geo-oracle` container, schema `gp`) on 2026-05-22 to exercise every production whitelabel resolution path. Each row of the matrix names a host you can drive through Keycloak's `/realms/geowealth-realm/protocol/openid-connect/auth?...` flow and the expected resolved code, brand source, and visual outcome.

The data lives in `WHITELABEL_TBL`. The FIRM-URL associations already lived in `FIRM_TBL` (admin-set in dev) — we only added the matching WL rows so each firm has a brand to render. **No production-system schemas or rules were changed.**

The resolution chain in `AuthorizationManagerTrait.IdentifyFirmByUrlMsg` runs 5 passes plus a GeoWealth default; the table below covers each.

---

## Seeded data summary

```
WHITELABEL_TBL rows (CODE, FIRM_CD, NAME, color theme):
  cca                  1   GeoWealth                    orange/teal       (preseeded)
  changepath           4   ChangePath                   blue/green
  c1wealth             5   CreativeOne Wealth           navy/gold
  c1securities         6   CreativeOne Securities       dark navy/amber
  bcj                  9   BCJ Capital Management       forest/cream
  riverwaterpartners  10   Riverwater Partners          river blue/sand
  smithandcox          8   Smith & Cox, LLC             plum/blush
  mca                  1   MCCA Test Firm               red                (FE-created, no logo BLOBs)
  code                 1   wl_name                      (no colors)        (pre-existing test placeholder)
```

All but `mca` and `code` carry LOGIN_LOGO_BIG + FAVICON BLOBs (SVGs with the firm's primary color); `mca` has no BLOBs because it was created via the FE without logo upload.

## Test matrix

| Pass | Resolution rule | Host header | Expected `/lookup` code | Expected brand source | Notes |
|---|---|---|---|---|---|
| 1 | `FIRM.SYSTEM_BASE_URL` substring | `c1wealth.geowealth.com` | `c1wealth` | navy/gold | Advisor portal subdomain |
| 1 | same | `c1securities.geowealth.com` | `c1securities` | dark navy/amber | |
| 1 | same | `bcj.geowealth.com` | `bcj` | forest/cream | |
| 1 | same | `riverwaterpartners.geowealth.com` | `riverwaterpartners` | river blue/sand | |
| 1 | same | `smithandcox.geowealth.com` | `smithandcox` | plum/blush | |
| 1 | same | `wisewealthkc.geowealth.com` | `wisewealthkc` | (registry fallback) | No WL row seeded — exercises SPI fallback chain |
| 2 | `FIRM.CLIENT_PORTAL_BASE_URL` substring | `c1wealthclient.geowealth.int` | `c1wealth` | navy/gold | Client portal subdomain |
| 2 | same | `bcjclient.geowealth.int` | `bcj` | forest/cream | |
| 2 | same | `smithandcoxclient.geowealth.int` | `smithandcox` | plum/blush | |
| 3 | `EMPLOYEE.SYSTEM_BASE_URL` + cpWhitelabelKeyword override | `wisewealthkcadv.geowealth.com` | (would be) `c1wealth` | (would be) navy/gold | **Seed infrastructure ready, runtime blocked** — see "Pass 3/4 dev blocker" below |
| 4 | `EMPLOYEE.CLIENT_PORTAL_BASE_URL` | `wisewealthkcadvclient.geowealth.int` | (would be) `c1wealth` | (would be) navy/gold | Same blocker |
| 5 | `topSubDomain` equals `FIRM.CODE` | `bcj.localhost` | `bcj` | forest/cream | Fallback for any subdomain matching an active firm's code |
| 5 | same | `c1wealth.localhost` | `c1wealth` | navy/gold | |
| default | nothing matched | `unknown.foo.com` | `cca` | orange/teal | GeoWealth default firm |
| default | same | `localhost` | `cca` | orange/teal | No subdomain at all |

`mca.localhost` and `changepath.localhost` resolve to `cca` under the 1:1 production rules because no FIRM row carries those codes and no advisor binds them to a URL. To exercise the `mca` and `changepath` whitelabels via URL routing, provision the corresponding FIRM_TBL row or advisor mapping — see "Adding mca / changepath to URL routing" below.

## How to drive each scenario through Keycloak

For `host=<firm>.geowealth.com`:

```bash
source .envrc

curl -s -L -H "Host: c1wealth.geowealth.com:8898" \
  "http://localhost:8898/realms/geowealth-realm/protocol/openid-connect/auth?client_id=geowealth-poc-client&response_type=code&redirect_uri=http%3A%2F%2Fdefault.localhost%3A5174%2F&scope=openid&state=test" \
  | grep -oE '(GeoWealth|ChangePath|CreativeOne|BCJ|Riverwater|Smith)' | sort -u

# Then check Keycloak log:
docker logs --since 10s keycloak-demo-keycloak-1 | grep "Branding:"
# Expected: code=c1wealth code_src=api_hit brand_src=api_hit
```

For client-portal lookups, swap the Host header to e.g. `c1wealthclient.geowealth.int:8898` (the redirect_uri can stay the same — it just has to match the client whitelist).

To see the brand visually:
1. Add `<firm>.localhost` or whichever Host header you're testing to the geowealth-realm client's redirect URI whitelist (or open via curl with `Host:` header overriding the default).
2. Drive a browser flow with the matching Host header.

## Reading the seeded WHITELABEL_TBL state

```sql
SELECT CODE, FIRM_CD, NAME,
       LENGTH(LOGIN_LOGO_BIG) AS LOGO_BYTES,
       LENGTH(FAVICON) AS FAV_BYTES,
       LINKS_COLOR
  FROM gp.WHITELABEL_TBL
 ORDER BY CODE;
```

WHITELABEL_ID column must be 32 hex characters. The Hibernate Whitelabel entity parses it as `com.netfolio.util.UUID`; non-hex characters cause the row to be silently skipped during fetch (404 from `/whitelabel/{code}` despite the row existing). All seeded rows use valid hex UUIDs.

## Adding `mca` / `changepath` to URL routing (out of POC scope)

Three production-aligned paths to bind a whitelabel to a URL:

1. **Sub-firm:** insert a FIRM_TBL row with CODE matching the whitelabel and a SYSTEM_BASE_URL pointing at the desired subdomain. Pass-1 then matches the URL → returns the firm code → which matches the WL row.
2. **Per-advisor binding:** insert an EMPLOYEE_TBL row with SYSTEM_BASE_URL set to the subdomain and CP_WHITELABEL_KEYWORD set to the whitelabel code. Pass-3 then resolves via advisor.
3. **Existing firm's URL alignment:** rename the WL row's CODE to match an existing FIRM.CODE (e.g., rename `changepath` to one of the active firms' codes). Pass-1/5 matches by firm. Not recommended — clobbers data semantics.

None of these are seeded today. Test rounds with `mca.localhost` / `changepath.localhost` will continue to fall through to the GeoWealth default until one of the above is added.

## Pass 3 / 4 dev blocker — advisor seeding

The seed for advisor-level URL overrides:

```sql
INSERT INTO gp.ENTITY_TBL (
    ENTITY_ID, ENTITY_TYPE_CD, ENTITY_ACTIVE_FLAG, FIRM_CD,
    SYSTEM_BASE_URL, CLIENT_PORTAL_BASE_URL, CP_WHITELABEL_KEYWORD, NICKNAME
) VALUES (
    'ADV0001000000000000000000ADV0001', 4, 1, 7,
    'wisewealthkcadv.geowealth.com', 'wisewealthkcadvclient.geowealth.int',
    'c1wealth', 'Test Override Advisor'
);
INSERT INTO gp.USER_DETAIL_TBL (ENTITY_ID, HIDE_DISABLED_WORKFLOWS)
VALUES ('ADV0001000000000000000000ADV0001', 0);
COMMIT;
```

After this seed, `/lookup?host=wisewealthkcadv.geowealth.com` *should* return `c1wealth` (the firm's keyword override). Instead it returns `cca` plus an `identifyFirmByUrl failed` WARN — and worse, **every host that doesn't match passes 1 or 2 fails the same way** (so `bcj.localhost`, `c1wealth.localhost`, etc. start returning `cca` even though pass-5 would otherwise resolve them correctly). The Akka actor for `IdentifyFirmByUrlMsg` raises `ServiceException: null` whose inner cause never surfaces in `catalina.out` — suspected NPE inside `Firm.toDTO` (similar shape to the pre-restart `FirmSSO.getEnforcementType().isOff()` NPE we saw on a different code path), but the actor swallows it before `logAndThrow`.

Until that's debugged on the GeoWealth side, the advisor row stays **deleted** so it doesn't break the rest of the matrix. The two affected tests in `WhitelabelScenariosIT.Pass3And4Lookup` are `@Disabled` with the seed and blocker documented; drop the annotation once the actor surfaces the underlying cause and the NPE is fixed.

## Caveat: Tomcat class reload

After SPI source edits in `~/geowealth/`, the new `.class` file needs to be deployed to `~/tools/tomcat9/webapps/ROOT/WEB-INF/classes/` AND Tomcat must be restarted to pick it up. Tomcat does not auto-reload classes. Each session's `dev-bff.sh`-style reload is for the BFF, not for this Tomcat — restart manually (`shutdown.sh && startup.sh` or `nfstart_dev`).
