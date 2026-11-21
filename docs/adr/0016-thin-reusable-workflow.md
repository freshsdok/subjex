# ADR 0016 — Thin reusable workflow / 可声明的通用薄工作流

- Status: **Accepted** — direction only (WP4-0). Implementation starts at WP4-1.
- Date: 2026-11-17 (Asia/Shanghai)
- Scope: Reusable declarable thin FSM on top of the landed repair-ticket engine; not a BPM product
- Supersedes / extends: ADR [0005](0005-thin-workflow-repair-ticket.md) remains accepted for the repair-ticket machine. This ADR is the “later genericizer” 0005 already named. It does not rewrite 0005 or existing instance history.

## Context / 背景

WP3 is closed (`f7d3a65`). The base work doc’s next package is a **thin workflow**: fold the existing repair-ticket state machine into a **reusable, declarable** engine so a domain module can declare a new machine without changing platform kernel source, and a second example (order approval) can run submit / approve / reject / complete without copying the repair-ticket FSM code.

ADR 0005 and [`docs/workflow/wf-progress.md`](../workflow/wf-progress.md) already delivered WF-0…WF-4 and WF-RT-1…5: tables, `WorkflowService`, dual-approve, org-scoped approval, audit fields, auto-start, and a console panel. Factual inventory (paths, hardcoding, what is already generic) lives in [`docs/workflow/wp4-thin-workflow-progress.md`](../workflow/wp4-thin-workflow-progress.md).

The gap is not “there is no workflow.” The gap is that **only one machine is registered, and several behaviors still name `repair-ticket` in Java and in the console**:

- `RepairTicketWorkflowDefinitions` is a static one-entry map; `WorkflowService` always `require`s that registry.
- `WorkflowState` / `WorkflowAction` are repair-ticket **enums**, while V38 already stores state and action as VARCHAR.
- Auto-start, org-scope gate, and `WorkflowStatusPanel` hardcode the repair-ticket key (the detail page even mounts the panel for any `entityKey`).
- Dual-approve is keyed off the `request_approval` / `approve` / `reject` enums, not off a declaration flag.
- Entity / form / UI-flow catalogs already load YAML from the class loader (`classpath*:` / every classpath root). Page `*.flow.yaml` is a **UI flow**, not an FSM. `DeclarationKind` has no workflow value.

A full BPM engine (Camunda-class, parallel gateways, timers, a general DSL) stays out of scope, as ADR 0005 already decided. This ADR only generalizes what is already thin.

WP3 已关闭。基线工作包的下一包是薄工作流：把已有报修工单状态机收成可声明、可复用的引擎，让域模块不改平台内核源码即可声明新机器，并用第二个示例（订单审批）跑通提交、审批、驳回与结束，且不复制报修工单状态机代码。缺口不是“没有工作流”，而是只注册了一台机器，且若干行为仍在 Java 与控制台里写死 `repair-ticket`。完整 BPM 仍非目标。盘点见进度笔记。

## Decision / 决策

### D1 — “Declarable” in this stage means a thin FSM document, not a BPM product

A workflow declaration in WP4 may name:

- states and an initial state;
- transitions (from + action → to, required permission, optional capability id);
- whether an edge opens or consumes a **dual-approval** gate (requester ≠ consumer, as landed in WF-2);
- whether **approve/reject** (or the declared consume actions) are **org-scoped**, including the hybrid organization field and fail-closed behavior when the field is missing (as landed in WF-RT-3);
- that transition **history** is written (already the default of `workflow_transition_history`).

It may **not**, in this stage, declare parallel gateways, timers, BPMN, a general expression / scripting DSL, compensation, or any Camunda-class construct. We are not building a second process engine beside `WorkflowService`. `platform_task` / `TaskLifecycle` remains the delivery/outbox machine and is not overloaded.

本阶段“可声明”仅指状态、迁移、双人审批、组织范围审批与历史留痕。不做并行网关、定时器、通用 BPM DSL 或 Camunda 类产品。不把业务 FSM 塞进 `platform_task`。

### D2 — A domain module declares a machine as a classpath resource, without editing kernel Java

Reuse the existing declaration-catalog pattern (not a new BPM compiler):

- Add a workflow catalog that loads every `workflows/*.workflow.yaml` on the class loader, the same way `EntityCatalog` loads `entities/*.entity.yaml`, `FormCatalog` loads `forms/*.form.yaml`, and `PageCatalog` loads `flows/*.flow.yaml`.
- `platform-app` keeps the **engine** (`WorkflowService`, `JdbcWorkflowStore`, `WorkflowEndpoint`, audit, org-scope resolver). Machine graphs live in declaration resources. A domain module (a jar on the classpath, or a first-party sample module already used for entity/form/flow YAML) can add a new `*.workflow.yaml` **without changing platform kernel source**.
- Bind a definition to an `entityKey` in that document (one instance per `(tenant_id, entity_key, record_id)` stays, as V38 already enforces).
- **State and action names become declared tokens** (strings), not kernel enums. Wire names already travel on the HTTP API and in history VARCHAR columns. The repair-ticket tokens stay exactly as they are today so existing rows keep matching.
- Do not confuse this document with page `*.flow.yaml`. The UI flow stays the list/detail/submit declaration; the workflow document is the FSM.

A minimal shape (illustrative, not a DSL standard; implementation may tighten names) maps 1:1 onto the fields `WorkflowDefinition` / `WorkflowTransition` already have, plus the flags that today are hardcoded:

```yaml
# illustrative only — 仅示意，实施时再锁字段名
definitionKey: order-approval
entityKey: order-approval
version: 1
initialState: SUBMITTED
autoStartOnUpsert: true
orgScope:
  field: organization          # missing value fails closed on scoped actions
  onActions: [approve, reject]
dualApproval:
  requestAction: submit
  consumeActions: [approve, reject]
transitions:
  - { from: SUBMITTED, action: submit, to: AWAITING_APPROVAL, permission: workflow.write }
  - { from: AWAITING_APPROVAL, action: approve, to: APPROVED, permission: workflow.approve }
  - { from: AWAITING_APPROVAL, action: reject, to: SUBMITTED, permission: workflow.approve }
  - { from: APPROVED, action: complete, to: COMPLETED, permission: workflow.write }
```

Existing permissions `workflow.read` / `workflow.write` / `workflow.approve` are reused. Existing HTTP `/api/v1/workflows/**` is reused (the path already has `{definitionKey}`). Tenant draft/promote of this document is **not** decided here (`DeclarationKind` has no workflow value; see Open points).

域模块以 classpath 上的 `workflows/*.workflow.yaml` 声明机器，加载方式对齐已有实体/表单/页面目录，无需改内核 Java。状态与动作改为声明令牌；报修工单的既有线名保持不变。不要与页面 flow YAML 混为一谈。租户草稿晋升本文件是否开放，现有文档锁不死，不在此臆造。

### D3 — Repair-ticket becomes one declaration among others; do not rewrite history

- Port the current `RepairTicketWorkflowDefinitions` graph into a first-party classpath declaration with `definitionKey: repair-ticket` and the **same** state and action wire names (`OPEN`, `start_work`, `request_approval`, `approve`, `reject`, `close`, …).
- Existing `workflow_instance`, `workflow_transition_history`, and `workflow_approval` rows stay. No backfill, no id rewrite, no state rename.
- Kernel special cases that name `repair-ticket` (upsert auto-start, `RepairTicketOrgApproverGate` early-return, console `DEFINITION_KEY`, `startIfAbsentAfterUpsert` hardcoding the key) move onto declaration flags in later slices, with **behavior preserved** for that machine: auto-start on upsert, dual-approve, fail-closed org scope on approve/reject, advisory candidates unchanged.
- The Java class may shrink to a loader or compatibility facade; it must not remain the only place a graph can be registered.
- The IM-3 inbox hook stays a repair-ticket messaging integration unless a later messaging brief says otherwise (not invented here).

报修工单变成声明之一，线名与历史行不改写。写死该键的自动启动、组织门闩、控制台定义键在后续切片改为声明驱动，但该机器的既有行为保持。

### D4 — Second example: domain-agnostic order approval, no copied FSM

Acceptance (implement in WP4-3, not in this slice): a second classpath machine, **`order-approval`** (name may be the definition and entity key), can run **submit, approve, reject, and complete** through the existing engine and HTTP, without copying `RepairTicketWorkflowDefinitions` or the repair-ticket transition list.

- The example is generic (an order id, title, optional organization for the same fail-closed scope). It must not put hospital catalogs, appointments, visa escort, insurance, or other medical business rules into the kernel or into the example.
- Its graph is allowed to differ from repair-ticket (no requirement to have `start_work` / `request_approval` / `close`). The sketch in D2 is the intended default; reject may return to `SUBMITTED` (same idea as repair-ticket reject → `OPEN`) unless implementation chooses a declared terminal, without inventing extra business rules.
- It reuses `WorkflowService`, tables, API, audit fields, dual-approve, and org-scope when those flags are set on its declaration — not a forked service.
- `web/` stays the operator console. The status panel must look up the definition **by the page’s `entityKey`**, not hardcode `repair-ticket`, and must hide when no workflow is bound.
- Relationship ≠ authorization (same posture as ADR 0001 / 0006 / 0015 and WF-RT-3).

第二示例为领域无关的订单审批：提交、审批、驳回、结束；不复制报修工单状态机代码；不把医疗业务写入内核或示例。控制台按实体键解析定义，不再写死报修工单。关系不是授权。

### D5 — Keep audit fields and fail-closed org scope

- Keep `OperatorActionAudit.recordWorkflow` on the existing single `AuditPort`: tenant is required and is never defaulted to reserved `platform`; `action_target` is the workflow instance id; `entity_key`, `declaration_version`, and `resolution_source` stay. Action names `workflow.start` / `workflow.transition` / `workflow.approval.request` / `workflow.approval.consume` stay.
- Keep fail-closed org scope: when a declaration enables it, approve/reject (or the declared consume actions) read the configured organization field; a missing value is 403, not a skip; the actor’s `SELF_AND_DESCENDANTS` scope must contain that org. Membership or a CONTAINS relation alone is not authorization.

保留既有审计字段与失败关闭的组织范围，不新开审计端口，不把缺组织当成放行。

### D6 — Small slices; no WP5 in this package

Implementation is sliced (below). This ADR does not start WP5 (notification port), does not extract a second engine, and does not rewrite the platform.

## Proposed implementation slices / 拟定实现切片

| Slice | Intent | Not in that slice |
| --- | --- | --- |
| **WP4-0** | This ADR + inventory progress note | Code / schema |
| **WP4-1** | Workflow catalog (`workflows/*.workflow.yaml`); declared state/action tokens; port repair-ticket to a declaration with identical wire names; `WorkflowService` looks up the catalog, not a one-entry static map | Second example machine; console rewrite; tenant promote |
| **WP4-2** | Declaration-driven auto-start, dual-approve, and fail-closed org scope; console panel resolves definition by `entityKey` (hide if unbound) | Order-approval example; inbox generalization |
| **WP4-3** | Second example `order-approval` (entity/form/UI-flow + workflow declaration). Tests: submit, approve, reject, complete, no copied repair-ticket FSM, audit rows present, org fail-closed when configured | BPM features; medical fields; WP5 |
| **WP4-4** | Formal acceptance / CI regression of both machines; close the WP4 package | WP5 notification port |

## Consequences / 后果

- **Positive**: New machines are data/resources on the classpath; the kernel engine stays small; repair-ticket history is compatible; acceptance is a second example, not a BPM suite.
- **Positive**: Dual-approve, org fail-closed, and audit fields become reusable instead of remaining one-key special cases.
- **Negative**: Tenant-promoted workflow documents, inbox for arbitrary machines, and live-graph migration are still not specified.
- **Negative**: The console panel and upsert hook must be generalized or they will silently keep binding every entity detail to repair-ticket.

## Out of scope / 非目标（本 ADR 不发明的产品）

- Full BPM / Camunda, parallel gateways, timers, general DSL, compensation.
- Replacing `platform_task`.
- Medical / hospital / visa / insurance business rules in the kernel or in the example machine.
- Changing the `/api/v1` tenant-filter exemption.
- A public unauthenticated workflow UI, or moving `web/` off the operator console.
- WP5 notification port.

## Open points not settled from existing docs / 现有文档无法锁死、故不臆造

- Tenant draft / P7 promote of a workflow FSM document (no `DeclarationKind.WORKFLOW` today; P7 covers entity/form/flow).
- Whether IM-3 WORKFLOW inbox fan-out applies to machines other than repair-ticket.
- Per-machine permission names beyond the existing three workflow permissions.
- End-user (non-operator) actors on `/api/v1/workflows/**`.
- Policy for changing a declaration graph that already has instance or history rows (this track will not rewrite history; a migrator is not specified).

## References / 引用

- [`docs/workflow/wp4-thin-workflow-progress.md`](../workflow/wp4-thin-workflow-progress.md) — WP4-0 inventory
- [`docs/workflow/wf-progress.md`](../workflow/wf-progress.md) — WF-0…WF-RT-5
- [`docs/adr/0005-thin-workflow-repair-ticket.md`](0005-thin-workflow-repair-ticket.md)
- ADR 0001 / 0002 / 0004 / 0006 (org, hybrid entity, audit, relations)
