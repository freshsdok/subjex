# ADR 0005 — Thin workflow: repair-ticket + approval state machine

- Status: **Accepted** — WF-1 FSM core landed (instances + history + API)
- Date: 2026-10-16 (Asia/Shanghai)
- Scope: Business repair-ticket FSM + dual/role approval; not platform delivery tasks

## Context / 背景

After Identity, Observability, and Tenant hardening (TN-0…TN-3), the next product slice is a **thin workflow**: operators file and progress **repair tickets** with an explicit approval step.

We already have three related-but-different mechanisms:

1. **`platform_task` / `TaskLifecycle`** — delivery/outbox step machine (`PENDING` / `AWAITING_CONFIRMATION` / `COMPLETED` / `DEAD`). Wrong layer for business tickets.
2. **Declaration promote P7** — dual-operator PENDING→CONSUMED approval before git promote. Good **approval pattern**.
3. **`demo-ticket`** — hybrid entity with free-text `status`, `tenantScoped: false`, upsert-only. Good **UI/entity sample**, not an FSM.

A full BPM engine is out of scope. We need a **finite state machine** with fail-closed transitions, tenant isolation, and an approval gate reusable later for other ticket-like entities.

身份 / 可观测 / 多租户之后，下一产品片是薄工作流：报修工单 + 显式审批。已有 `platform_task`、晋升双人确认、`demo-ticket` 样例，均不可直接当业务 FSM。不要上完整 BPM。

## Decision / 决策

### 1. Separate business ticket FSM from platform tasks

- New (or evolved) **repair-ticket** domain sits on **hybrid entity storage** (ADR 0002), tenant-scoped.
- Do **not** store ticket state in `platform_task`. Optional: emit outbox events *after* a transition for integrations (later).

### 2. Finite states (MVP)

```text
OPEN ──start_work──► IN_PROGRESS ──request_approval──► AWAITING_APPROVAL
  ▲                                                      │
  │                                                      ├─approve──► APPROVED ──close──► CLOSED
  │                                                      │
  └──────────── reject ◄─────────────────────────────────┘
```

| State | Who may act | Notes |
| --- | --- | --- |
| OPEN | reporter / assignee role | create lands here |
| IN_PROGRESS | assignee | work started |
| AWAITING_APPROVAL | approver role (not self) | approval row required |
| APPROVED | closer | terminal-ish; only `close` |
| REJECTED | (transient) | immediately or via transition back to OPEN |
| CLOSED | — | terminal |

Illegal transitions → **409** (or domain exception mapped to 409). Missing tenant grant → **403** (TN-3 posture).

### 3. Approval gate (copy P7 shape)

- Table (sketch): `ticket_approval(approval_id, tenant_id, ticket_id, from_state, to_state, requested_by, approval_state PENDING|CONSUMED|CANCELLED, created_at, consumed_at, consumed_by)`.
- Request while ticket is `IN_PROGRESS` → moves to `AWAITING_APPROVAL` + PENDING approval.
- Consume by **different** subject with `ticket.approve` (or tenant-admin) → `APPROVED` or `REJECTED`→`OPEN`.
- Audit actions: `ticket.transition`, `ticket.approval.request`, `ticket.approval.consume`.

### 4. API shape (WF-3)

- Prefer explicit transitions: `POST /api/v1/tickets/{id}/transitions` body `{action, note?}` over free-form status upsert.
- Keep list/detail on generic `/api/v1/entities/repair-ticket/records` **read**; writes that change `status` must go through the transition service (ArchUnit or service-layer guard in WF-4).

### 5. Relation to `demo-ticket`

- Leave classpath `demo-ticket` as **sample/regression** (may stay free-text status).
- New `repair-ticket` declaration (tenant draft → promote) or first-party classpath entity with `tenantScoped: true` and FSM-backed status field.

## Consequences / 后果

- **Positive**: Small surface; reuses hybrid entity, grant filter posture, P7 approval lessons, TN-3 isolation tests.
- **Positive**: Clear 409/403 semantics for console and API clients.
- **Negative**: Not a general workflow product — new ticket types need their own FSM or a later genericizer.
- **Negative**: Generic `entity.record.upsert` must refuse raw `status` mutation for repair-ticket (enforce in WF-1/3).

## Alternatives considered / 备选

| Option | Why not (now) |
| --- | --- |
| Overload `platform_task` | Couples delivery retries/dead-letter to business UX |
| Embed BPMN engine | Ops + deps + UX cost; violates “thin” |
| Status enum only in YAML without server FSM | Clients can skip approval; fail-open |
| Reuse only promote approval table | Wrong domain; couples declaration lifecycle |

## Implementation map / 实现地图

See [`docs/workflow/wf-progress.md`](../workflow/wf-progress.md): WF-1 ✅ FSM+API → WF-2 dual-approve harden → WF-3 console UI → WF-4 CI.

### WF-1 landed surface / 已落地表面

- Tables: `workflow_instance`, `workflow_transition_history` (Flyway **V38**)
- Definition: `repair-ticket` (`RepairTicketWorkflowDefinitions`)
- API: `POST /api/v1/workflows/{definitionKey}/instances`, `POST .../instances/{id}/transitions`, `GET .../instances/{id}`, `GET .../by-record`
- Permissions: `workflow.read` / `workflow.write` / `workflow.approve`
- Cap light hook: `request_approval` records catalog id `ai.summarizePreview` (not executed)

