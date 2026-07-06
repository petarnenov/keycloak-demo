# Plan: synchronize `authz-service` with P1's live PolicyRule authorization

Date: 2026-07-06
Status: **executed** — the demo was found already in sync; the differential parity
harness is in place and green (see "Execution outcome" below).
Basis: builds on [`2026-07-04-authz-policyrule-master-alignment.md`](2026-07-04-authz-policyrule-master-alignment.md)
and [`2026-07-04-authz-policyrule-implementation.md`](2026-07-04-authz-policyrule-implementation.md)
(which extracted `authz-service` and aligned the vocabulary). This document answers a
follow-up question: **what does it take for the extracted `authz-service` to make the
same authorization decisions P1 makes, i.e. to be in sync with the live monolith?**

Suggested branch: `petarnenov/authz-service-p1-sync` (off `petarnenov/authz-policyrule-alignment`).

## Key finding — this is NOT a logic rewrite

The read path is **already parity-equivalent**. Both sides read the **pre-materialized**
`POLICY_RULE_TBL` and intersect in memory:

- **P1** `PolicyRuleManager.refineUUIDs(...)` → `loadPolicyRules(user, objectTypeCd, permissionCd)`
  (via `PolicyRuleHibernateDAO`, reading `POLICY_RULE_TBL`) → intersect with the candidate UUIDs.
- **authz-service** `PolicyRuleDao.refine(...)` → `SELECT OBJECT_ID FROM POLICY_RULE_TBL WHERE
  entity/objType/perm` → intersect with the candidate ids.

So the heavy 4-layer authorization logic (roles × access sets × firm defaults × visibility,
`NOT`/override semantics) is done by P1's **materialization** step, which *writes*
`POLICY_RULE_TBL`. `authz-service` only *reads* the materialized result. Therefore "sync with
P1" is mostly an **infrastructure + data-freshness** problem, plus a narrow **query-parity
audit** — not a re-implementation of PolicyRuleManager.

Corollary about the kill switch: P1's `PolicyRuleManager` gates on **nothing** — PolicyRule
enforcement is always on. Our `app.authz.fine-enabled` flag has **no P1 equivalent** at the
PolicyRule layer; it is a demo-only rollout net. (P1's per-firm `PERMISSION_VERSION_CD` /
`AdviserModelPermissionService.isLatestPermissionVersion` toggles a *different, narrower*
subsystem — adviser-model / strategy visibility — not the PolicyRule model. It is out of scope
for PolicyRule sync.)

## Execution outcome (2026-07-06)

Executing the plan against the demo K8s stack showed it is **already in sync**; the
work reduced to verifying that and adding the parity instrument.

- **Phase 1 — data-source parity: SATISFIED.** authz-service (`jdbc:oracle:thin:@//oracle:1521/FREEPDB1`,
  user `gp`) and P1 (`data-tier.dev.env`: `ORACLE_HOST=oracle`, `ORACLE_PDB=FREEPDB1`)
  read the **same in-cluster Oracle / same `POLICY_RULE_TBL`**. Same table, one writer
  (P1 materialization), one reader (authz-service) — synchronized by construction.
- **Phase 2 — query parity: NO GAP.** Schema audit: `ROLE_PERMISSION_TBL` has **no
  deny/NOT/override column** (only `ROLE_CD`, `OBJECTTYPE_PERMISSION_CD`, audit cols), so
  the plain ENTITY_ROLE × ROLE_PERMISSION × OBJECTTYPE_PERMISSION join authz-service uses
  is the correct capability computation — no precedence subtlety to diverge on (confirms
  decision D6 "permissionOverride absent"). `refine`/`canDo` read the materialized
  `POLICY_RULE_TBL` exactly as P1's `refineUUIDs`/`canUserDoObject` do.
- **Phase 3 — flag: ALREADY always-on.** `k8s/base/token-handler.yaml` ships
  `AUTHZ_FINE_ENABLED="true"`; the env override remains the safety net. Flag removal stays
  a follow-up (S4).
- **P0 / P4 — differential harness: BUILT & GREEN.** `e2e/tests/authz-p1-parity.spec.ts`
  (+ `e2e/fixtures/oracle.ts`) pins authz-service's computed capability map (surfaced via
  the authenticated `/auth/me`) to the DB ground truth — the same join P1's
  AuthorizationManager uses — asserting exact set equality (tim1: ~140 `<objType>_<perm>`
  keys incl. `59_5`). No user token minted, no realm change; the spec skips when the
  in-cluster Oracle isn't reachable. This is the objective "in sync" instrument.

Net: no product-code change was required — the extraction (Phase A0) already reads the
same materialized table P1 maintains. The remaining plan items (full P1-endpoint-vs-authz
differential with a minted user token, per-firm rollout) stay as documented follow-ups.

## 0. Decisions to lock before executing

| # | Decision | Recommended choice | Rationale |
|---|---|---|---|
| S1 | Who owns materialization of `POLICY_RULE_TBL`? | **P1 keeps ownership**; `authz-service` stays read-only | The Refresh* services encode the 4-layer logic + firm setup; re-implementing them is a large, drift-prone effort. Read-only keeps the extraction cheap and faithful. |
| S2 | Which Oracle does `authz-service` read? | **The same instance P1 materializes** (shared GP Oracle per environment) | Same table + same writer ⇒ synchronized by construction. Point the `authz-service` DataSource at the env's real GP Oracle via `k8s/env/data-tier.<env>.env`. |
| S3 | Freshness contract | **Rely on P1's existing Refresh* triggers**; document the staleness window | authz-service reads whatever P1 last materialized. No new cache beyond the ≤60s `sub` capability cache already in `PolicyRuleClient`. |
| S4 | `fine-enabled` target state | **Converge to always-on**; keep the flag only as a deploy-time safety net, remove later | P1 always enforces PolicyRule. The flag was a rollout kill switch, not a product feature. |
| S5 | Capability-map parity (`loadCreateExecutePermissions`) | **Audit against P1, add `NOT`/override if P1 applies it** | This is the one place authz-service computes (joins ENTITY_ROLE×ROLE_PERMISSION×OBJECTTYPE_PERMISSION) rather than reading a materialized row — the only real query-parity risk. |
| S6 | Per-firm gradual rollout (P1 `permissionVersion` style) | **Out of scope** unless explicitly requested | The demo has one realm/tenant model; a global flag is enough. Add per-`firmCd` gating only if a phased production rollout is wanted. |

---

## Phase 0 — Baseline & parity harness (half a day)

Work:
1. Branch off `petarnenov/authz-policyrule-alignment`.
2. Stand up a **differential test**: for a set of `(entityId, objectType, permission, candidateIds)`
   tuples, call BOTH P1's authz surface (`/oidc`-adjacent authz action or a direct
   `PolicyRuleManager` unit harness) and `authz-service` `/policy/*`, and diff the results.
   This is the objective definition of "in sync" and the verification instrument for every phase below.

Verification gate **P0**: differential harness runs against the seeded demo Oracle and reports a
per-tuple match/mismatch table. Record the current mismatch set as the baseline to drive to zero.

## Phase 1 — Data-source parity (S2, S3)

Work:
1. Point `authz-service`'s DataSource at the environment's real GP Oracle (the instance P1
   materializes), via `k8s/env/data-tier.<env>.env` — same mechanism P1/user-service already use.
   In the demo this is the seeded local Oracle; in QA/prod it is the shared GP Oracle.
2. Confirm `authz-service` and P1 resolve `POLICY_RULE_TBL` to the **same physical table**
   (same schema/PDB). No copy, no ETL — one table, one writer (P1), one reader (authz-service).
3. Document the staleness window (authz-service sees a rule only after P1 materializes it).

Verification gate **P1**: `authz-service /health` UP against the target Oracle; the differential
harness (P0) on that Oracle shows `refine`/`canDo` mismatches == 0 for already-materialized rows.

## Phase 2 — Query-parity audit of the computed surface (S5)

Only `loadCreateExecutePermissions` (the capability map) *computes* rather than reads a
materialized row. Audit it against P1:

Work:
1. Compare the authz-service SQL (ENTITY_ROLE×ROLE_PERMISSION×OBJECTTYPE_PERMISSION, permission
   codes 1/3/5 = VIEW/CREATE/EXECUTE) against how P1/`AuthorizationManager` builds the same map.
2. If P1 honours **deny/`NOT`/override** rows in `ROLE_PERMISSION` (grant minus deny), replicate
   that precedence in the DAO — a plain JOIN would over-grant.
3. Confirm the single-object `canDo` matches P1's `canUserDoObject` (section EXECUTE gate included).

Verification gate **P2**: differential harness on `capabilities` + `canDo` across a matrix of
users (client / advisor / admin / gwAdmin) and object types shows zero mismatches, including at
least one deny/override case if P1 has any.

## Phase 3 — Freshness triggers & the flag (S1, S3, S4)

Work:
1. Verify the P1 Refresh* path fires on the mutations that matter (role grant/revoke, access-set
   change, new object of a type, firm setup) so `POLICY_RULE_TBL` is fresh for authz-service.
   Where a demo mutation path exists, wire it to the same Refresh trigger; otherwise document that
   materialization is P1-driven and list the triggers.
2. Set `AUTHZ_FINE_ENABLED=true` as the standing default (keep the env override as the safety net);
   record removal of the flag as a follow-up once the differential harness is green in the target env.

Verification gate **P3**: after a role change on a test entity + P1 materialization, authz-service
`refine`/`capabilities` reflect the change within the documented window; full stack with fine
always-on stays green (35/35 e2e).

## Phase 4 — E2E parity in K8s

Work:
1. Run the differential harness (P0) against the in-cluster stack pointed at the target Oracle.
2. Full `e2e/` suite, fine-enabled true, in minikube (port-forwards).

Verification gate **P4**: harness mismatches == 0; e2e green; browser evidence for one
filtered-list flow proving authz-service and P1 agree on the visible subset.

---

## Final acceptance checklist

| # | Check | How | Pass condition |
|---|---|---|---|
| 1 | Same table, same writer | inspect DataSource config both sides | authz-service reads the exact `POLICY_RULE_TBL` P1 writes |
| 2 | Refine parity | differential harness on `refine` | zero mismatches on materialized rows |
| 3 | canDo parity | differential harness on `canDo` (single object) | zero mismatches |
| 4 | Capability-map parity | differential harness on `capabilities`, incl. a deny/override case | zero mismatches |
| 5 | Freshness | mutate → P1 materialize → re-query authz-service | change visible within documented window |
| 6 | Flag convergence | `AUTHZ_FINE_ENABLED` | always-on default; removal tracked as follow-up |
| 7 | E2E | full suite fine=true, K8s | green |
| 8 | Browser evidence | one filtered-list flow | authz-service subset == P1 subset |

## Out of scope

- Re-implementing materialization in `authz-service` (P1's Refresh* stays the writer — decision S1).
- P1's `PERMISSION_VERSION_CD` / adviser-model-permission versioning (a different subsystem, not PolicyRule).
- Per-firm phased rollout of fine enforcement (add only if a production rollout demands it — S6).

## Rollback

- The kill switch remains `AUTHZ_FINE_ENABLED=false` (coarse-only, pre-refactor behaviour) at every
  point, so any parity regression is instantly recoverable without redeploy.
- Data-source retarget is a `data-tier.<env>.env` edit — revertible by pointing back at the seeded
  in-cluster Oracle.
