# Thin workflow progress — 薄工作流进度

Wave **after** TN-0…TN-3 (`c044faf`). Goal: a **thin** repair-ticket + approval state machine — not a BPM engine.

多租户加固之后。目标是薄的报修工单 + 审批状态机，不是通用 BPM。

## Wave status / 本波状态

**Complete** — WF-0…WF-4 landed (local commits; no push).

| ID | Status |
| --- | --- |
| **WF-0** | ✅ inventory + ADR 0005 |
| **WF-1** | ✅ FSM core + API (`c27d231`) |
| **WF-2** | ✅ dual-approve / refuse-self (`0ec5dfa`) |
| **WF-3** | ✅ console status panel |
| **WF-4** | ✅ `@Tag("workflow")` + CI job step + isolation test |

## WF-0 [done] — inventory + ADR sketch

See history above / ADR [`docs/adr/0005-thin-workflow-repair-ticket.md`](../adr/0005-thin-workflow-repair-ticket.md) (**Accepted**).

## WF-1 [done] — repair-ticket FSM core

Definition `repair-ticket`; V38 instances + history; `WorkflowService` + `/api/v1/workflows/**`; Cap catalog hook on `request_approval`.

## WF-2 [done] — dual-approve / refuse-self

- Flyway **V39** `workflow_approval` (PENDING → CONSUMED / CANCELLED).
- `request_approval` opens PENDING; `approve`/`reject` require **requester ≠ consumer**.
- `WorkflowNeedsSecondApprover` → HTTP 409; audits `workflow.approval.request` / `.consume`.

## WF-3 [done] — console status UI

- `WorkflowStatusPanel` on declared entity detail (`pages/[flowKey]/[id]`).
- Start / transition / history via `/api/platform/workflows/**`; self-approve UX hint.
- i18n phrases (zh/en).

## WF-4 [done] — CI workflow gates

- `@Tag("workflow")` on workflow tests; `WorkflowIsolationTest` also `@Tag("tenant-isolation")`.
- `.github/workflows/build.yml` step **workflow gates**:
  `mvn -pl platform-app -am test -Dgroups=workflow -Dsurefire.failIfNoSpecifiedTests=false`

## Non-goals (still) / 仍非目标

- Full BPM / timers / parallel gateways
- Replacing `platform_task`
- Changing `/api/v1` filter exemption

## Progress pointer

TN `c044faf` · WF-0 `4739dd5` · WF-1 `c27d231` · WF-2 `0ec5dfa` · WF-3/4 this commit.
