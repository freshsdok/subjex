# WP4 thin reusable workflow progress / 可声明通用薄工作流进度

- Date: 2026-11-17 (Asia/Shanghai)
- ADR: [`docs/adr/0016-thin-reusable-workflow.md`](../adr/0016-thin-reusable-workflow.md)
- Baseline tip when this note was opened: `f7d3a65` (WP3 closed; feature/test `b296420`)
- Predecessor: ADR [`0005`](../adr/0005-thin-workflow-repair-ticket.md) and [`docs/workflow/wf-progress.md`](wf-progress.md) (WF-0…WF-4 and WF-RT-1…5 are done)
- Status: **WP4 closed** — thin reusable workflow package accepted (2026-11-17 Asia/Shanghai)
- 已完成：`4cee0f1` / `24311f1`（WP4-0）；`5f5e778` / `52bb659`（WP4-1）；`53c657f` / `be30eee`（WP4-2）；`7743f70` / `a721d20`（WP4-3）
- 本切片提交：`e87ed36`（`e87ed3692038521ebb273cef8542c70b4cde6abb`；WP4-4 正式验收并关闭本包）。不启动 WP5（通知端口）。

This track is a **reusable declarable thin workflow** on top of the already-landed repair-ticket FSM. It is not a Camunda/BPM product, not a rewrite of the platform, and not a medical or hospital domain. `web/` stays the operator console.

本轨是把已落地的报修工单状态机收成可声明的通用薄工作流。不是 Camunda / 完整 BPM，不重写平台，内核与示例都不放医疗业务。`web/` 仍是操作员控制台。

## WP4-0 [done] — inventory + ADR

Docs-only. No schema, no second machine, no extraction of a new engine.

本切片只写文档，不改表、不实现第二台机器、不抽新引擎。

### Inventory / 盘点（事实与路径）

What already exists is a **working thin FSM for one machine** (`repair-ticket`), plus hybrid entity/form/flow declarations for the ticket record. The engine and HTTP are already keyed by `definition_key`, but the only registered machine, the upsert auto-start, the org-scope gate, and the console panel still hardcode that one key. Page `*.flow.yaml` is a **UI flow**, not an FSM declaration.

已有的是一台可用的薄状态机（报修工单），外加工单记录的 hybrid 实体/表单/页面声明。引擎与 HTTP 已按 `definition_key` 索引，但唯一注册的机器、自动启动、组织范围门闩和控制台面板仍写死该键。页面 `*.flow.yaml` 是界面流程，不是状态机声明。

| Area | What exists | Paths |
| --- | --- | --- |
| ADR / prior wave | ADR 0005 accepted: repair-ticket FSM + dual/role approval; not `platform_task`; not a BPM engine. Explicit negative: new ticket types need their own FSM *or a later genericizer*. WF-0…WF-RT-5 are marked done in the earlier progress note. | `docs/adr/0005-thin-workflow-repair-ticket.md`; `docs/workflow/wf-progress.md` |
| Tables | Flyway **V38** `workflow_instance` (`instance_id`, `tenant_id`, `definition_key`, `entity_key`, `record_id`, `current_state` VARCHAR, unique `(tenant_id, entity_key, record_id)`) and `workflow_transition_history`. **V39** `workflow_approval` (PENDING → CONSUMED / CANCELLED). Permissions `workflow.read` / `workflow.write` / `workflow.approve` seeded for `platform-operator` (reader gets read only). | `platform-app/src/main/resources/db/migration/V38__workflow_instance.sql`, `V39__workflow_approval.sql` |
| In-memory definition | `WorkflowDefinition` is already an immutable key + initial state + transition list (from/action/to, required permission, optional Cap id). **Registry is a static map of one entry** in `RepairTicketWorkflowDefinitions`. `WorkflowService.start` / `transition` call `RepairTicketWorkflowDefinitions.require(definitionKey)`. | `platform-app/src/main/java/com/subjex/platform/app/workflow/WorkflowDefinition.java`, `WorkflowTransition.java`, `RepairTicketWorkflowDefinitions.java`, `WorkflowService.java` |
| States / actions | `WorkflowState` and `WorkflowAction` are **Java enums** whose vocabulary is the repair-ticket graph (`OPEN` / `IN_PROGRESS` / `AWAITING_APPROVAL` / `APPROVED` / `CLOSED`; `start_work`, `request_approval`, `approve`, `reject`, `close`). Reject is not a parked `REJECTED` row: the edge returns to `OPEN`. The tables already store state and action as VARCHAR, so the enum is a kernel constraint, not a storage one. | `WorkflowState.java`, `WorkflowAction.java` |
| Repair-ticket graph | `OPEN --start_work--> IN_PROGRESS --request_approval--> AWAITING_APPROVAL --approve--> APPROVED --close--> CLOSED`; `AWAITING_APPROVAL --reject--> OPEN`. `request_approval` optionally attaches catalog id `ai.summarizePreview` (catalog check only). | `RepairTicketWorkflowDefinitions.java` |
| Service / store | `WorkflowService` + `JdbcWorkflowStore`: start, start-if-absent, transition, status, history. Tenant grant via `OperatorTenantAccess.requireGranted`. Illegal transition / already-started / self-approve → domain exceptions (HTTP 409). Missing org scope → `WorkflowApproverOutOfScope` (HTTP 403). | `WorkflowService.java`, `JdbcWorkflowStore.java`, `PlatformExceptionAdvice.java` |
| HTTP | Operator API: `POST /api/v1/workflows/{definitionKey}/instances`, `POST /api/v1/workflows/instances/{id}/transitions`, `GET .../instances/{id}`, `GET .../by-record`. Console proxies `/api/platform/workflows/**` to `/api/v1`. Body is `{action, note?}` — not a free-form status upsert. | `WorkflowEndpoint.java` (`BASE = JsonApi.BASE + "/workflows"`); `PlatformSecurityConfiguration` matchers |
| Hybrid ticket record | Classpath entity / form / UI flow `repair-ticket`: `storageMode: hybrid`, `tenantScoped: true`. Fields `ticketId`, `title`, `location`, `urgency`, `assignee` (subjectRef), `organization` (organizationRef). **Status is not an entity field**; it lives in `workflow_instance`. `demo-ticket` stays a free-text-status sample, not this FSM. | `entity-declare/src/main/resources/entities/repair-ticket.entity.yaml`; `form-render/src/main/resources/forms/repair-ticket.form.yaml`; `page-declare/src/main/resources/flows/repair-ticket.flow.yaml` |
| Auto-start (WF-RT-2) | After a successful `entity.record.upsert` for entityKey `repair-ticket`, `EntityRecordUpsertDomainActionHandler` calls `WorkflowService.startIfAbsentAfterUpsert`, which **hardcodes** `RepairTicketWorkflowDefinitions.REPAIR_TICKET`. Idempotent via by-record + unique index; same `TransactionTemplate` as the upsert (start failure rolls back). Explicit Start API still requires `workflow.write`. | `EntityRecordUpsertDomainActionHandler.java`; `WorkflowService.startIfAbsentAfterUpsert` |
| Dual approve (WF-2) | `request_approval` opens a PENDING `workflow_approval` row; `approve` / `reject` consume it and require **requester ≠ consumer** (`WorkflowNeedsSecondApprover`). Audits `workflow.approval.request` / `.consume`. This logic is in `WorkflowService` keyed by the repair-ticket **action enums**, not by a declaration flag. | `WorkflowService.java`; `JdbcWorkflowStore` open/consume approval; `WorkflowApproval.java` |
| Org-scoped approval (WF-RT-3) | `RepairTicketOrgApproverGate` is documented as **repair-ticket only, not a DSL**. On approve/reject: read hybrid field `organization`; actor's OrgScope `SELF_AND_DESCENDANTS` (membership roots + ACTIVE CONTAINS) must contain that org; **missing org fails closed** (403). Advisory candidates: ACTIVE `organization_manager` of the ticket org and ACTIVE CONTAINS ancestors, plus ACTIVE memberships on the ticket org. Gate returns early unless `definitionKey` or `entityKey` is `repair-ticket`. Relationship is not authorization. | `RepairTicketOrgApproverGate.java`; `OrganizationScopeResolver` |
| Audit (WF-RT-4) | `OperatorActionAudit.recordWorkflow` writes the existing single `audit_entry` / `AuditPort`. Request **tenant is required** (not hard-coded `platform`). `action_target` = workflow instance id; also `entity_key`, `declaration_version`, `resolution_source`. Actions: `workflow.start`, `workflow.transition`, `workflow.approval.request`, `workflow.approval.consume`. Auto-start passes the runtime entity `version()` and `EffectiveDeclarationService.resolutionSource`. | `OperatorActionAudit.java`; `RepairTicketWorkflowAuditTest.java` |
| Inbox hook (IM-3) | `RepairTicketWorkflowInboxHook` fans out WORKFLOW inbox on selected repair-ticket edges (`request_approval`, `approve`, …). This is a messaging hook, not a second FSM. | `RepairTicketWorkflowInboxHook.java` |
| Console (WF-3) | `WorkflowStatusPanel` on declared entity detail (`pages/[flowKey]/[id]`). The detail page mounts the panel whenever the flow has an `entityKey`, but the panel **hardcodes** `DEFINITION_KEY = "repair-ticket"`. Start is hidden when an instance already exists. i18n phrases exist (zh/en). | `web/src/components/page-blocks/workflow-status-panel.tsx`; `web/src/app/(console)/pages/[flowKey]/[id]/page.tsx` |
| Declaration loading (reuse pattern) | Entity / form / UI-flow catalogs already scan the **class loader / `classpath*:`** for YAML (`entities/*.entity.yaml`, `forms/*.form.yaml`, `flows/*.flow.yaml`). A jar can add a sample without editing `platform-app` Java. Effective overlay is DRAFT → PROMOTED → classpath (`EffectiveDeclarationService`). `DeclarationKind` today is `entity` / `form` / `flow` / `ui-authz` — **there is no workflow kind**, and tenant promote of an FSM document is not implemented. | `entity-declare/.../EntityCatalog.java`; `platform-app/.../form/FormCatalog.java`; `platform-app/.../page/PageCatalog.java`; `EffectiveDeclarationService.java`; `DeclarationKind.java` |
| Wiring / CI / tests | Beans in `PlatformWiring` (store, org gate, inbox hook, `WorkflowService`). Tests under `platform-app/src/test/java/com/subjex/platform/app/workflow/` (`@Tag("workflow")`; isolation also `@Tag("tenant-isolation")`). CI job step **workflow gates**: `mvn -pl platform-app -am test -Dgroups=workflow`. | `PlatformWiring.java`; `WorkflowTags.java`; `.github/workflows/build.yml` |

Non-goals already written in ADR 0005 / `wf-progress.md` and still in force: full BPM, timers, parallel gateways, replacing `platform_task`, a generic BPM DSL, changing the `/api/v1` tenant-filter exemption.

ADR 0005 / `wf-progress.md` 已写明且仍然有效的非目标：完整 BPM、定时器、并行网关、取代 `platform_task`、通用 BPM DSL、改掉 `/api/v1` 的租户拦截豁免。

## WP4-1 [done] — classpath catalog + repair-ticket declaration

Repair-ticket is now one classpath declaration. `WorkflowService` looks up `WorkflowCatalog`, not a one-entry static graph. State and action are declared tokens; historic wire names are unchanged. No table rewrite.

报修工单已成为 classpath 声明之一。服务从目录取图，不再使用静态硬编码边表。状态与动作为声明令牌；历史线名不变。未改写表。

- **YAML:** `platform-app/src/main/resources/workflows/repair-ticket.workflow.yaml`. `definitionKey: repair-ticket`. States `OPEN` / `IN_PROGRESS` / `AWAITING_APPROVAL` / `APPROVED` / `CLOSED`. Actions `start_work` / `request_approval` / `approve` / `reject` / `close`. `request_approval` still attaches `ai.summarizePreview`.
- **Catalog:** `WorkflowCatalog` scans `classpath*:workflows/*.workflow.yaml` (same style as entity/form/flow). `WorkflowDeclarationReader` accepts only thin FSM fields plus marked WP4-2 hooks; unknown keys (e.g. `timers`) fail closed. This is not a BPM DSL.
- **Tokens:** `WorkflowState` / `WorkflowAction` are no longer kernel enums. Well-known repair-ticket constants remain so existing API clients and tests keep working (`OPEN`, `start_work`, and the historic enum spelling `START_WORK` on the HTTP action). New machines may declare other tokens without changing these classes.
- **Still hardcoded (WP4-2):** auto-start on `entity.record.upsert` for entityKey `repair-ticket`; dual-approve keyed off `request_approval` / `approve` / `reject` in `WorkflowService`; `RepairTicketOrgApproverGate` (fail-closed org scope unchanged); console `DEFINITION_KEY = "repair-ticket"`; IM-3 inbox hook. The YAML already records `autoStartOnUpsert`, `orgScope`, and `dualApproval` as hooks so the catalog is honest; the engine does not apply them yet.
- **Unchanged:** tables and `/api/v1/workflows/**`; `platform_task` is not used; no `order-approval` example.
- Tests: `WorkflowCatalogTest` 4/4 (YAML graph matches the former machine, including hooks; start/transition still use historic wire names; unknown definition and illegal transition fail closed; reader accepts other declared tokens and rejects blank / unknown keys). `RepairTicketWorkflowDefinitionsTest` 1/1. Focused regression 28/28 including `WorkflowServiceTest`, `RepairTicketAutoStartTest`, `RepairTicketOrgApproverTest`, `RepairTicketWorkflowAuditTest`, `RepairTicketWorkflowInboxHookTest`, `WorkflowIsolationTest`. Pre-existing `OperatorBootstrapTest.readerRoleGetsOnlyReadPermissions` was not run and was not fixed.

## WP4-2 [done] — declaration-driven auto-start, dual-approve, org scope, console by entityKey

The engine now applies the hooks already present on the repair-ticket YAML (`autoStartOnUpsert`, `orgScope`, `dualApproval`). Repair-ticket behavior is unchanged, including fail-closed org scope. A test-only fixture (`review-case`, not the official `order-approval` example) proves the hooks follow the declaration rather than the `repair-ticket` key. The catalog can already load a second YAML so WP4-3 can be declaration-plus-tests.

引擎现已应用报修工单 YAML 上的钩子。报修行为不变（含失败关闭的组织范围）。测试夹具 `review-case`（不是正式 `order-approval` 示例）证明钩子跟声明走。目录已能加载第二份 YAML，便于 WP4-3 只补声明和测试。

- **Auto-start:** `EntityRecordUpsertDomainActionHandler` and `WorkflowService.startIfAbsentAfterUpsert` use `WorkflowCatalog.findAutoStart(entityKey)`. A machine with `autoStartOnUpsert: false` is not started on upsert.
- **Dual approval / org scope:** `WorkflowService.transition` uses the declaration's `requestAction` / `consumeActions` / `onActions`. `RepairTicketOrgApproverGate` is generalized to `WorkflowOrgApproverGate`: applied only when the machine declares `orgScope`; field and entity come from the declaration + instance; missing org still fails closed; relationship is not authorization; candidate list semantics are unchanged.
- **Console:** `workflow-status-panel.tsx` drops `DEFINITION_KEY = "repair-ticket"`. It calls `GET /api/v1/workflows/definitions/by-entity?entityKey=` (404 hides the panel so unbound entities do not show a fake Start). Start uses the looked-up `definitionKey`. Action buttons come from status `legalActions` (known repair-ticket wires keep existing phrases). The panel hint is generic.
- **Still special-cased:** IM-3 `RepairTicketWorkflowInboxHook` remains repair-ticket-only, with an explicit comment — do not silently notify the wrong machine. Left for a later messaging slice.
- **Unchanged:** repair-ticket HTTP wire names; tables and existing instance/history/approval rows; no parallel gateways, timers, or BPM DSL; no official `order-approval` example; no medical domain.
- Tests: `WorkflowDeclarationHooksTest` 7/7 (review-case auto-start at `SUBMITTED`; idle-case with `autoStartOnUpsert: false` does not auto-start; dual-approve uses `submit` not `request_approval`; org-scope fail-closed / out-of-scope / in-scope; lookup by `entityKey` does not force `repair-ticket`; repair-ticket historic wires still work on the shared catalog; duplicate `entityKey` fails closed). Focused regression 35/35 including `RepairTicketAutoStartTest`, `RepairTicketOrgApproverTest`, `RepairTicketWorkflowAuditTest`, `RepairTicketWorkflowInboxHookTest`, `WorkflowCatalogTest`, `WorkflowServiceTest`, `WorkflowIsolationTest`, `RepairTicketWorkflowDefinitionsTest`. Pre-existing `OperatorBootstrapTest.readerRoleGetsOnlyReadPermissions` was not run and was not fixed.

## WP4-3 [done] — official `order-approval` example

Official domain-agnostic example on the classpath. It runs **submit, approve, reject, and complete** through the existing `WorkflowService` / `/api/v1/workflows/**` and the YAML catalog. No copied repair-ticket FSM Java, and no kernel special-case for `order-approval`. Inbox stays repair-ticket-only (not silently hooked).

正式的领域无关示例已进入 classpath。提交、审批、驳回、结束走现有引擎与 HTTP，不复制报修状态机代码，内核也不为该键写特例。站内信仍仅报修工单。

- **Workflow:** `platform-app/src/main/resources/workflows/order-approval.workflow.yaml`. `definitionKey` / `entityKey`: `order-approval`. States `SUBMITTED` / `AWAITING_APPROVAL` / `APPROVED` / `COMPLETED`. Actions `submit` / `approve` / `reject` / `complete`. Hooks: `autoStartOnUpsert: true`; `dualApproval.requestAction: submit`; org scope on `approve` / `reject` (missing org fail-closed). Reject returns to `SUBMITTED`.
- **Minimal generic record:** entity `entity-declare/src/main/resources/entities/order-approval.entity.yaml` (order id, title, optional organization); form and thin UI flow so the WP4-2 console can resolve the panel by `entityKey`. Not a commerce or medical module; no new React designer.
- **Unchanged:** repair-ticket wire names and behavior; no BPM/timers/parallel gateways; no WP4-4 CI close.
- Tests: `OrderApprovalWorkflowTest` 6/6 (declaration distinct from repair-ticket; HTTP submit/approve/complete with audit and no inbox fan-out; reject returns to `SUBMITTED`; illegal transitions including repair-ticket wires fail closed; org-scope fail-closed / out-of-scope / in-scope; repair-ticket historic wires still work). Catalog/renderer checks updated. Focused workflow regression 41/41 (previous 35 plus this suite) plus `EntityCatalogTest`, `FormCatalogTest`, `PageCatalogTest`, `FormRendererTest`, `PageRendererTest`. Pre-existing `OperatorBootstrapTest.readerRoleGetsOnlyReadPermissions` was not run and was not fixed.

## WP4-4 [done] — formal acceptance; WP4 package closed

This slice locks ADR 0016 acceptance and **closes the WP4 package**. No production hole was found; no production code was added. WP5 (notification port) is not started.

本切片锁定 ADR 0016 验收并**关闭 WP4 包**。未发现需改生产代码的缺口。未启动 WP5（通知端口）。

Acceptance met / 验收已满足：

- A second classpath machine (`order-approval`) runs submit / approve / reject / complete without copying repair-ticket FSM Java.
- Domain modules declare a machine via `workflows/*.workflow.yaml` without changing kernel source (`order-approval` has no production Java special-case).
- States, transitions, dual approval, org-scoped approval, and history work for more than repair-ticket (declaration hooks + focused suites).
- Audit fields (instance id, declaration version) and fail-closed org scope are preserved.
- This remains a thin FSM, not Camunda / parallel gateways / timers / a general BPM DSL.
- Repair-ticket wire names and behavior are unchanged.

Formal lock: `ThinWorkflowAcceptanceTest` 2/2 (both machines on the existing catalog / `WorkflowService` / `WorkflowEndpoint` with distinct tokens; production Java must not contain `order-approval`; historic repair wires and order `submit` both run and write history plus `workflow.start` audit). It does not repeat the WP4-3 scenario suite.

Regression (this turn, all passed): workflow-focused 43/43 (`ThinWorkflowAcceptanceTest` 2, `OrderApprovalWorkflowTest` 6, `WorkflowDeclarationHooksTest` 7, `WorkflowCatalogTest` 4, `WorkflowServiceTest` 7, `WorkflowIsolationTest` 1, `RepairTicketAutoStartTest` 4, `RepairTicketOrgApproverTest` 4, `RepairTicketWorkflowAuditTest` 2, `RepairTicketWorkflowInboxHookTest` 5, `RepairTicketWorkflowDefinitionsTest` 1) plus related catalog/renderer 53/53 (`EntityCatalogTest` 10, `FormCatalogTest` 1, `PageCatalogTest` 5, `FormRendererTest` 18, `PageRendererTest` 19). Total 96/96. CI already runs `-Dgroups=workflow` (comment updated to name both machines). Pre-existing `OperatorBootstrapTest.readerRoleGetsOnlyReadPermissions` was not run and was not fixed (outside WP4; missing `questionnaire.read`).

Residual gaps (honest, not in this package) / 仍存缺口（本包不解决）：

- IM-3 inbox fan-out is still repair-ticket-only (left special-cased on purpose; do not silently notify other machines).
- The operator console may show raw action tokens for non-repair-ticket wires (`submit`, `complete`, …).
- Workflow declarations are not a tenant-draft / P7 `DeclarationKind` (no `workflow` value today).
- No end-user (non-operator) workflow API.
- Per-machine permissions beyond `workflow.read` / `workflow.write` / `workflow.approve` are not specified.
- Live-graph migration against existing instance/history rows is not specified (this track does not rewrite history).

Next package would be **WP5 (notification port)**, not started here.

## Slice status / 切片状态

- **WP4-0** — ✅ done.
- **WP4-1** — ✅ done.
- **WP4-2** — ✅ done.
- **WP4-3** — ✅ done.
- **WP4-4** — ✅ done (this slice). **WP4 package closed.**

## Open points not settled from existing docs / 现有文档无法锁死、故不臆造

- Tenant draft / P7 promote of a workflow FSM document (`DeclarationKind` has no `workflow` value today).
- Whether the IM-3 inbox fan-out applies to any machine other than repair-ticket (WP4-2 left the hook special-cased on purpose).
- Per-machine permissions beyond the existing `workflow.read` / `workflow.write` / `workflow.approve`.
- End-user (non-operator) workflow actions.
- How to evolve a live graph against already-written `workflow_instance` / history rows (this track will not rewrite history; a migration policy is not specified).

## SHA

- WP4-0：`4cee0f1` (`4cee0f1cf9cb4065450f7d878056bad05b94a5f7`)
- WP4-1：`5f5e778` (`5f5e7784f71429cfe18669c04459c2377dac0f11`)
- WP4-2：`53c657f` (`53c657faa6f67765729ae082b9c5cd190615c159`)
- WP4-3：`7743f70` (`7743f70903874613fbfb0d6e1bc0ab4b65d5c90d`)
- WP4-4：`e87ed36` (`e87ed3692038521ebb273cef8542c70b4cde6abb`)
