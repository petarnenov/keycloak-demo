import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const execFileP = promisify(execFile);

/**
 * Read-only ground-truth access to the in-cluster demo Oracle via `kubectl exec`.
 * Used by the authz-service ↔ P1 sync parity spec to compare what authz-service
 * SERVES against what the shared `POLICY_RULE_TBL` / role tables actually hold —
 * the same tables P1's PolicyRuleManager / AuthorizationManager read.
 *
 * Everything here fails soft: if kubectl or the oracle pod isn't reachable
 * (running the suite outside the cluster context), the helpers return null and
 * the caller `test.skip()`s rather than reddening the suite.
 */
const NS = process.env.K8S_NAMESPACE ?? 'geowealth-demo';
const ORACLE_POD = process.env.ORACLE_POD ?? 'oracle-0';
const ORACLE_CONN =
  process.env.ORACLE_CONN ?? 'gp/gp123@//localhost:1521/FREEPDB1';

/** Run a SQL SELECT in the in-cluster Oracle; return trimmed non-empty stdout
 *  lines, or null if the cluster DB can't be reached. */
export async function oracleQuery(sql: string): Promise<string[] | null> {
  const script = [
    `sqlplus -s ${ORACLE_CONN} <<'SQLEOF'`,
    'set pagesize 0 feedback off heading off linesize 400',
    'whenever sqlerror exit failure',
    sql,
    'SQLEOF',
  ].join('\n');
  try {
    const { stdout } = await execFileP(
      'kubectl',
      ['-n', NS, 'exec', ORACLE_POD, '--', 'bash', '-lc', script],
      { timeout: 30_000 }
    );
    return stdout
      .split('\n')
      .map((s) => s.trim())
      .filter((s) => s.length > 0);
  } catch {
    return null;
  }
}

/**
 * Ground-truth capability map keys ({@code "<objectTypeCd>_<permissionCd>"}) for
 * an entity, computed by the exact ENTITY_ROLE × ROLE_PERMISSION ×
 * OBJECTTYPE_PERMISSION join that authz-service's {@code loadCreateExecutePermissions}
 * and P1's AuthorizationManager both use (VIEW/CREATE/EXECUTE = 1/3/5). Returns
 * null if the cluster Oracle is unreachable.
 */
export async function groundTruthCapabilities(entityId: string): Promise<Set<string> | null> {
  const rows = await oracleQuery(
    `SELECT DISTINCT OTP.OBJECT_TYPE_CD || '_' || OTP.PERMISSION_CD
       FROM ENTITY_ROLE_TBL ER
       JOIN ROLE_PERMISSION_TBL RP ON RP.ROLE_CD = ER.ROLE_CD
       JOIN OBJECTTYPE_PERMISSION_TBL OTP ON OTP.OBJECTTYPE_PERMISSION_CD = RP.OBJECTTYPE_PERMISSION_CD
      WHERE ER.ENTITY_ID = '${entityId}'
        AND OTP.PERMISSION_CD IN (1, 3, 5)
      ORDER BY 1;`
  );
  if (rows === null) return null;
  return new Set(rows.filter((r) => /^\d+_\d+$/.test(r)));
}
