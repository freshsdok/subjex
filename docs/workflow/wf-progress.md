# Thin workflow progress — 薄工作流进度

Wave **after** TN-0…TN-3 (`c044faf`). Goal: a **thin** repair-ticket + approval state machine — not a BPM engine.

多租户加固之后。目标是薄的报修工单 + 审批状态机，不是通用 BPM。

## Wave status / 本波状态

**WF-0…WF-4 Complete.** Product follow-on **WF-RT-*** in progress (local commits; no push).

| ID | Status |
| --- | --- |
| **WF-0** | ✅ inventory + ADR 0005 |
| **WF-1** | ✅ FSM core + API (`c27d231`) |
| **WF-2** | ✅ dual-approve / refuse-self (`0ec5dfa`) |
| **WF-3** | ✅ console status panel |
| **WF-4** | ✅ `@Tag("workflow")` + CI job step + isolation test |
| **WF-RT-1** | ✅ hybrid classpath repair-ticket + wizard `organizationRef` (2026-10-17 Asia/Shanghai) |
| **WF-RT-2** | ✅ submit auto-start instance (2026-10-17 Asia/Shanghai) |
| **WF-RT-3** | ✅ OrgScope approval assignee (2026-10-17 Asia/Shanghai) |
| **WF-RT-4** | ✅ audit instance_id + declaration version (2026-10-17 Asia/Shanghai) |
| **WF-RT-5** | ✅ thin console + CI note (2026-10-17 Asia/Shanghai) |

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

## WF-RT-1 [done] — hybrid repair-ticket declaration (2026-10-17 Asia/Shanghai)

- Classpath entity / form / flow: `repair-ticket` with `storageMode: hybrid`, `tenantScoped: true`.
- Fields: `ticketId`, `title`, `location`, `urgency` (enum), `assignee` (subjectRef), `organization` (organizationRef for WF-RT-3).
- Console wizard `defaultRepairTicketWizard` aligned; `/pages/repair-ticket` via classpath (promote overlay still works).
- **Not** auto-start (WF-RT-2); **not** OrgScope approve (WF-RT-3); **not** generic DSL.


## WF-RT-2 [done] — auto-start on repair-ticket submit (2026-10-17 Asia/Shanghai)

- After successful `entity.record.upsert` for entityKey `repair-ticket`, call
  `WorkflowService.startIfAbsentAfterUpsert` (OPEN; idempotent via by-record + unique index).
- Form submit already authorized via `form.permission`; auto-start does **not** require a second
  `workflow.write` click (explicit Start API still does).
- Upsert + start share one `TransactionTemplate` (fail-closed: start failure rolls back the row).
- Console: existing panel hides Start when instance exists; no manual Start needed after submit.
- **Not** OrgScope approve (WF-RT-3); **not** generic DSL.


## WF-RT-3 [done] — org-scoped repair-ticket approvers (2026-10-17 Asia/Shanghai)

**Rule (repair-ticket only, not a DSL):**

1. Read hybrid field `organization` on the bound record.
2. **Enforce** on `approve`/`reject`: actor's OrgScope `SELF_AND_DESCENDANTS` (Membership + ACTIVE CONTAINS)
   must contain that org. Missing org → fail-closed (`WorkflowApproverOutOfScope` → HTTP 403).
3. **Candidates** (GET status advisory): ACTIVE `organization_manager` of the ticket org and each
   ACTIVE CONTAINS ancestor, plus ACTIVE memberships on the ticket org itself.
4. Dual-approve (requester ≠ consumer) and `workflow.approve` unchanged.

## Non-goals (still) / 仍非目标

- Full BPM / timers / parallel gateways
- Replacing `platform_task`
- Changing `/api/v1` filter exemption
- Generic workflow DSL

## Progress pointer

TN `c044faf` · WF-0…WF-4 · WF-RT-1…5 complete (local; no push).


## WF-RT-4 [done] — audit instance_id + declaration version (2026-10-17 Asia/Shanghai)

- `OperatorActionAudit.recordWorkflow`: same `audit_entry` as form effects; request tenant (not hard-coded `platform`); `entity_key`, `declaration_version`, `resolution_source`; `action_target` = workflow instance id.
- Auto-start (form upsert): passes runtime entity `version()` + `EffectiveDeclarationService.resolutionSource` into `startIfAbsentAfterUpsert`.
- Explicit start / transition / approval: resolve linkage via `EffectiveDeclarationService` when wired (null → leave columns null for legacy tests).
- Test: `RepairTicketWorkflowAuditTest` asserts `workflow.start` row has tenant, instance id, `repair-ticket`, version `1`, source `classpath`.
- **Not** SIEM dashboard; **not** generic DSL.

## WF-RT-5 [done] — thin console + CI note (2026-10-17 Asia/Shanghai)

- Console: existing `WorkflowStatusPanel` already hides Start when auto-start left an OPEN instance (WF-RT-2); no SIEM / audit dashboard.
- CI: `workflow gates` step comment covers WF-RT-1…4 (`@Tag("workflow")` includes `RepairTicketWorkflowAuditTest`).
- **Not** generic DSL; **not** full SIEM product.
