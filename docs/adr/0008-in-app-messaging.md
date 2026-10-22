# ADR 0008 — Platform in-app messaging (站内信)

- Status: **Accepted**（2026-10-17 Asia/Shanghai；开放决策已按 MVP 建议默认锁死；IM-1 可开工）
- Date: 2026-10-17 (Asia/Shanghai)
- Relates: ADR 0003（OIDC / realms）、ADR 0004（audit）、ADR 0005（thin workflow）、ADR 0007（client triad）、ADR 0009（attachments / groups / mass send · Proposed）、[`docs/delivery/outbox-event-contract.md`](../delivery/outbox-event-contract.md)、[`docs/messaging/im-progress.md`](../messaging/im-progress.md)

## Context / 背景

Inventory (2026-10-17 tip ~`83de739`):

| Surface | Exists? | Role vs inbox |
| --- | --- | --- |
| `OperatorActionAudit` / `audit_entry` | Yes | Immutable **ops trail** — not an inbox, no unread/deep-link UX |
| Outbox / `DeliveryPort` | Yes | **Cross-process** task delivery (socket demo / optional Kafka) — not a mailbox |
| Email / Slack / push stubs | **No** product inbox | Do not fake inbox with email |
| OIDC consent UX | Yes | One-shot authorize page — not messaging |
| Console `web/` | Yes | Operator home for first inbox UI |
| `clients/uni-app/` | Shell only | End-user inbox **later** (ADR 0007) |

盘点：审计与出箱已有，但**不是**站内信；无邮件/Slack 产品收件箱；控制台是操作员面，uni-app 是终端面。

Product need: a **platform-level in-app message system** so operators (and later end users) see **system notices** and **workflow/approval alerts** inside the product — with unread state and deep links — without building a chat product.

产品需要：平台级站内信，优先系统通知与工作流/审批提醒；带未读与深链；**不是**聊天/IM。

### Pain points to avoid / 常见坑（刻意避开）

| Pitfall | Stance |
| --- | --- |
| Inbox overload (every audit row → notice) | Only **curated producers** (workflow transitions, explicit system send) |
| No unread / badge | First-class `read_at` + unread count API |
| No deep link | Optional `ref_kind` / `ref_id` / `deep_link` (e.g. workflow instance) |
| Mixing system + chat threads | **No Thread / chat** in MVP; channel enum only |
| Email-as-inbox | External channels are **optional outbox fan-out later**, never the store of truth |

## Decision / 决策（MVP 架构）

### D1 — Thin mailbox, not IM

Build an **in-app mailbox** for:

1. **SYSTEM** — platform / ops notices (quota, erase challenge, maintenance)
2. **WORKFLOW** — repair-ticket (and later ticket-like) approval / state alerts

Do **not** build WeChat-like chat, group rooms, typing indicators, or a generic BPM notification DSL.

站内信 = 系统通知 + 工作流提醒；不做聊天室 / 通用通知 DSL。

### D2 — Two recipient realms (same store, fail-closed)

| Realm | Audience | UI home | Auth |
| --- | --- | --- | --- |
| `OPERATOR` | Operators / authors | Console `web/` `/inbox` | Existing console BFF → Bearer / operator permissions |
| `END_USER` | End subjects | `clients/uni-app/` (later) | Bearer / OIDC OP (ADR 0007); **not** operator Redis session |

One physical schema; every row carries `recipient_realm` + `recipient_subject_id`. APIs never list across realms. Console MVP only implements **OPERATOR**. End-user slice waits until client auth is real.

同一套表；用 `recipient_realm` 区分；控制台首波只做操作员；终端后置。

### D3 — Domain model (MVP)

```text
inbox_message          (canonical body, once per send)
        │ 1
        │
        ▼ N
inbox_entry            (per-recipient mailbox row + read state)
```

| Concept | MVP? | Notes |
| --- | --- | --- |
| **Message** | Yes | `message_id`, `tenant_id`, `channel`, `title`, `body`, `severity`, producer metadata, optional refs |
| **InboxEntry** | Yes | `entry_id`, `message_id`, `tenant_id`, `recipient_realm`, `recipient_subject_id`, `read_at`, `created_at` |
| **Thread** | **No** | Chat / reply chains are non-goals; revisit only with a new ADR |
| **Channel** | Yes enum | `SYSTEM` \| `WORKFLOW` \| `ORG_BROADCAST` (`ORG_BROADCAST` **schema-ready, API deferred**) |

Copy-on-write fan-out: one Message, N InboxEntries (N small for workflow approvers; `ORG_BROADCAST` deferred — bound N when opened).

写扩散：一条 Message + N 条 InboxEntry；广播上限待 IM-7。

### D4 — Storage / 存储

- **PostgreSQL / MySQL** via existing Flyway dual-vendor path; **JDBC only**, no ORM.
- Tables sketch (names illustrative; next free Flyway after V42):

```text
inbox_message (
  message_id            PK,
  tenant_id             NOT NULL,   -- fail-closed; cross-tenant SYSTEM = one message row per target tenant (locked)
  channel               NOT NULL,   -- SYSTEM | WORKFLOW | ORG_BROADCAST
  title                 NOT NULL,
  body                  NOT NULL,   -- plain text / short markdown; no HTML mail templates in MVP
  severity              NOT NULL,   -- INFO | WARN | ACTION  (ACTION = needs click-through)
  producer_kind         NOT NULL,   -- e.g. workflow | system | admin
  producer_id           NULL,       -- definitionKey / actor subject
  ref_kind              NULL,       -- workflow_instance | entity_record | …
  ref_id                NULL,       -- instance_id / record id
  entity_key            NULL,
  declaration_version   NULL,       -- align WF-RT-4 audit linkage
  deep_link             NULL,       -- console-relative path, e.g. /pages/repair-ticket?…
  created_at            NOT NULL
)

inbox_entry (
  entry_id              PK,
  message_id            FK,
  tenant_id             NOT NULL,   -- denormalized for isolation queries
  recipient_realm       NOT NULL,   -- OPERATOR | END_USER
  recipient_subject_id  NOT NULL,
  read_at               NULL,       -- null = unread
  created_at            NOT NULL,
  UNIQUE (message_id, recipient_realm, recipient_subject_id)
)

INDEX (tenant_id, recipient_realm, recipient_subject_id, read_at, created_at DESC)
```

- **Tenant isolation:** every list/get/mark-read filters `tenant_id` + grant (`X-Tenant-Id` + operator grant / end-user tenant membership). Cross-tenant → **403**. ArchUnit / isolation tests mirror workflow (TN-3 posture).
- **Retention (locked):** MVP keep rows; **no auto-purge**. Soft-delete optional. Configurable purge job (e.g. read + older than N days) is **IM-later**. Unread never auto-purged without policy.

租户 fail-closed；MVP 不强制清理；已读过期清理后置。

### D5 — API sketch / API 草图

Base: `/api/v1/inbox/...` (tenant-scoped like workflow). Permissions below.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/inbox/entries` | List own entries (`unreadOnly?`, `channel?`, `limit`, cursor/offset) |
| `GET` | `/api/v1/inbox/unread-count` | Badge `{ count }` |
| `POST` | `/api/v1/inbox/entries/{entryId}/read` | Mark one read (idempotent) |
| `POST` | `/api/v1/inbox/entries/read` | Batch mark `{ entryIds: [...] }` |
| `GET` | `/api/v1/inbox/entries/{entryId}` | Detail = entry + message (own only) |
| `POST` | `/api/v1/inbox/system` | **Send system** notice `{ title, body, severity?, recipientSubjectIds[], deepLink? }` |

**Not in MVP:** public “send to anyone” chat API, SSE/WebSocket push, email webhook.

Responses include `messageId`, `entryId`, `channel`, `title`, `severity`, `readAt`, `refKind`/`refId`, `deepLink`, `createdAt`.

### D6 — Delivery: sync write first; Outbox is not the inbox

```text
Workflow transition / system send
        │
        ├─ same TX: insert inbox_message + inbox_entry(s)   ← source of truth
        ├─ same TX: OperatorActionAudit (optional send audit)
        └─ AFTER commit (optional later): OutboxEvent for WeChat/email push
```

- **In-app truth** = JDBC tables above.
- **Outbox / `DeliveryPort`** remains for **cross-process / external** delivery. Do **not** store mailbox rows only in outbox; do **not** make socket transport the operator UI feed.
- MVP: **synchronous** fan-out in the producer transaction (workflow approve request → N approver entries). `ORG_BROADCAST` deferred (IM-7); do not silently use outbox as a substitute inbox.

站内信真相在库表；出箱只做事后外推（微信/邮件），MVP 同步写扩散。

### D7 — AuthZ / 授权

| Permission (proposed) | Who | Effect |
| --- | --- | --- |
| `inbox.read` | Operator (own realm) | List / unread / mark-read **own** entries only |
| `inbox.send.system` | Operator admin or service account | `POST /inbox/system` within tenant grant |
| (future) `inbox.broadcast` | Explicit grant | `ORG_BROADCAST` when opened |

- Recipients **cannot** read another subject’s entries (even same tenant) → **403**.
- Workflow producer is **trusted server code** (like auto-start / org approver gate): inserts entries for computed candidates; does not require the reporter to hold `inbox.send.system`.
- **OrgScope:** used to **compute WORKFLOW recipients** (reuse repair-ticket approver candidate logic). Listing the inbox itself is **not** OrgScope-filtered beyond “own subject” — you always see your own mail. `ORG_BROADCAST` targeting (when opened) must intersect sender OrgScope ∩ target org membership (fail-closed).

读仅本人；系统发送需权限；工作流由服务端受信任路径写入；OrgScope 用于算审批人，不用于「偷看他人信箱」。

### D8 — Audit linkage / 审计关联

Align with WF-RT-4 / form `audit.write`:

- On **send** (system or workflow hook): optional `OperatorActionAudit` action `inbox.send` with `tenant_id`, `action_target=message_id`, optional `entity_key`, `declaration_version`, and body/detail carrying `instance_id` when `ref_kind=workflow_instance`.
- Mark-read: **no** audit by default (noise). Optional debug flag later.
- Inbox message columns already hold `ref_id` / `declaration_version` for UI without joining audit.

发送可审计；已读默认不审计以免淹没。

### D9 — UI slices / 界面切片

| Surface | Slice | Scope |
| --- | --- | --- |
| Console `web/` | `/inbox` | List + unread badge in shell + deep link to repair-ticket / workflow panel |
| Console | Header badge | Poll `unread-count` (interval); no WS in MVP |
| `clients/uni-app/` | Later | Same API, `END_USER` realm; after real Bearer/OP login |

Do **not** put end-user inbox pages inside `web/` (ADR 0007).

### D10 — Producers (curated)

MVP producers only:

1. **Workflow hook** — e.g. `request_approval` → ACTION to org-scope candidates; `approve`/`reject` → INFO to reporter (exact events in IM-3).
2. **System send API** — explicit admin/ops.

Explicitly **not** producers: every `audit_entry`, every form submit, every config override (unless an admin chooses system send).

## Non-goals / 非目标（本 ADR / MVP）

- Chat, DMs, group channels, reactions, read receipts beyond `read_at`
- Generic notification preference center / digest engine
- Replacing Outbox or Audit
- Email/SMS/WeChat template product (external push = later outbox consumer)
- Real-time WebSocket/SSE hub
- Free-form “notify arbitrary role DSL”
- Second mobile stack for inbox UI

## Alternatives considered / 备选

| Option | Why not (now) |
| --- | --- |
| Reuse `audit_entry` as inbox | No per-user unread; wrong semantics; overload |
| Outbox row = inbox | Wrong layer; socket/Kafka demo; no multi-recipient mailbox UX |
| Full chat / Thread model | Product scope explosion; violates thin stance |
| Per-channel microservices | Modular monolith ports first |
| Fan-out only via async queue | Extra moving parts; MVP N is small for approvers |

## Locked decisions / 已锁定决策（原 Open decisions）

Accepted 2026-10-17 (Asia/Shanghai) after user「请继续」with no per-item overrides — treat as **accept the 7 recommended MVP defaults**.

用户「请继续」且未逐条覆盖 → 按建议默认锁死：

| # | Topic | Locked answer | Rationale (one-liner) |
| --- | --- | --- | --- |
| 1 | Cross-tenant / platform SYSTEM | Write **one `inbox_message` (+ entries) per target tenant**; no single platform-wide row multi-tenant read | Keeps fail-closed tenant filters simple; no special `tenant_id` sentinel |
| 2 | Retention | MVP **no auto-purge**; purge policy later | Avoid deleting unread/audit-linked mail without a product policy |
| 3 | `ORG_BROADCAST` | First wave **no API**; schema enum placeholder only (IM-7) | Scope control; fan-out bounds undecided |
| 4 | WeChat / email external fan-out | **Deferred** to IM-8; not MVP | In-app truth first; Outbox stays external-only |
| 5 | Console unread poll | **~60s** + manual refresh (IM-5) | Low chatter; enough for ops badge |
| 6 | `END_USER` inbox | **After** uni-app auth wiring (IM-6) | ADR 0007; operator console first |
| 7 | Copy / i18n | First wave **Chinese** title/body; structure reserves i18n key+params later | Ship readable notices; avoid key catalog work in MVP |

## Implementation map / 实现地图

See [`docs/messaging/im-progress.md`](../messaging/im-progress.md).

| Slice | Intent | Depends |
| --- | --- | --- |
| **IM-0** | This ADR + pointers | — |
| **IM-1** | Flyway tables + `InboxPort` JDBC + tenant isolation tests | Locked decisions 1–2 |
| **IM-2** | REST list / unread / mark-read / system send + permissions | IM-1 |
| **IM-3** | Workflow hooks (repair-ticket approve path) | IM-2, ADR 0005 |
| **IM-4** | Console `/inbox` + nav | IM-2 |
| **IM-5** | Console unread badge (~60s poll) | IM-4 |
| **IM-6** | **done** (thin) uni-app `/me/inbox` + client page | #3a Bearer |
| **IM-7** | `ORG_BROADCAST` API | Product bound-N when opened |
| **IM-8** | Optional outbox → external push | Product confirm WeChat/email |

**IM-1 unblocked** — schema-facing decisions are locked.

## Consequences / 后果

**Positive**

- Clear separation: Audit = trail, Outbox = external/cross-process, Inbox = per-user product mail.
- Reuses tenant grant, OrgScope candidate logic, declaration_version linkage patterns.
- Client triad respected: operator first, uni-app later.

**Negative / cost**

- Another Flyway pair + permission enum entries.
- Workflow service gains an inbox producer dependency (keep behind a narrow port).
- Broadcast-at-scale not solved in MVP.

## Links / 链接

- Progress: [`docs/messaging/im-progress.md`](../messaging/im-progress.md)
- Workflow: [`docs/adr/0005-thin-workflow-repair-ticket.md`](0005-thin-workflow-repair-ticket.md)
- Clients: [`docs/adr/0007-client-triad.md`](0007-client-triad.md)
- Extension (Proposed): [`0009-inbox-attachments-and-group-broadcast.md`](0009-inbox-attachments-and-group-broadcast.md)
- Outbox: [`docs/delivery/outbox-event-contract.md`](../delivery/outbox-event-contract.md)
