# In-app messaging progress / 站内信进度

- ADR: [`docs/adr/0008-in-app-messaging.md`](../adr/0008-in-app-messaging.md) (**Accepted**, 2026-10-17 Asia/Shanghai)
- Extension: [`docs/adr/0009-inbox-attachments-and-group-broadcast.md`](../adr/0009-inbox-attachments-and-group-broadcast.md) (**Accepted**, 2026-10-17 Asia/Shanghai — attachments + groups + mass send)
- Stance: thin mailbox (SYSTEM + WORKFLOW + upcoming group/org broadcast); **not** chat; not Outbox-as-inbox; console operator first; uni-app end-user later (ADR 0007)
- Decisions (0008): **all 7 MVP defaults locked** — see ADR 0008 § Locked decisions
- Decisions (0009): **all 5 recommended defaults locked** (user accept-recommended-defaults) — see ADR 0009 § Locked decisions

## Slice board (0008 MVP)

| Slice | Status | Note |
| --- | --- | --- |
| IM-0 Architecture ADR | **done** | ADR 0008 Accepted + locked decisions |
| IM-1 Schema + InboxPort | **done** | Flyway `V43__inbox_message.sql`; contract `InboxPort`; `JdbcInboxPort`; isolation + port tests; permissions `inbox.read` / `inbox.send.system` |
| IM-2 REST + permissions | **done** | `/api/v1/inbox/**` list/unread/mark-read/detail/system; AuthZ + isolation tests |
| IM-3 Workflow hooks | **done** | repair-ticket `request_approval`→ACTION candidates; `approve`/`reject`/`close`→INFO reporter |
| IM-4 Console `/inbox` | **done** | `web/` `/inbox` list + mark-read + deep link + nav (`inbox.read`) |
| IM-5 Unread badge poll | **done** | header badge ~60s poll + visibility pause; click → `/inbox` |
| IM-6 uni-app END_USER | not started | After client auth (locked) |
| IM-7 ORG_BROADCAST | **superseded by IM-A5** | Schema enum existed; API now planned under ADR 0009 |
| IM-8 External push via outbox | deferred | WeChat/email after MVP (locked) |

## Extension board (0009 — Accepted)

| Slice | Status | Note |
| --- | --- | --- |
| IM-A0 ADR 0009 | **done** | Accepted; 5 decisions locked (defaults) |
| IM-A1 Schema + contract/port | **done** | Flyway V44 attachment+group+perms; `InboxAttachment*` / `InboxGroupPort`; JDBC bind on send; own-entry list/find attachment; sync≤200 reject; tests |
| IM-A2 Staging/chunk upload + system send w/ files | **done** | Direct+chunked staging → ObjectStorage; `POST /system` `attachmentStagingIds`; own-entry list/download; MIME allowlist |
| IM-A3 inbox_group CRUD REST | **done** | `/api/v1/inbox/groups` CRUD + members; `inbox.group.admin`; tenant fail-closed |
| IM-A4 GROUP_SEND API | **done** | `POST /inbox/group-send`; expandMembers; sync≤200 reject; optional staging; `inbox.broadcast` |
| IM-A5 ORG_BROADCAST API | **done** | `POST /inbox/broadcast`; OrgScope∩membership; sync≤200; `inbox.broadcast`; optional staging |
| IM-A6 Async fan-out job | **done** | Flyway V45 `inbox_fanout_job`+recipient; `sendDeferred`+`processFanoutBatch`; after-commit `InboxFanoutWorker`; group-send/broadcast mid-range PENDING; reject N>2000 |
| IM-A7 Console compose + groups UI | **done** | `/inbox/compose` (system/group/org + attach); `/inbox/groups` CRUD+members; BFF catch-all; nav + inbox links; perms gated |
| IM-A8 Sender outbox / sent view | optional | product ask |

## IM-A7 notes

- Routes: `/inbox/compose`, `/inbox/groups` (Chinese-first); links from `/inbox` when permitted
- Compose modes: SYSTEM (`inbox.send.system`), GROUP_SEND / ORG_BROADCAST (`inbox.broadcast`); attachments via direct octet-stream staging (needs send.system)
- Groups: `inbox.group.admin` CRUD + members; tenant cookie same as list
- BFF: existing `/api/platform/[...segments]` (octet-stream already supported) — no new proxy
- Nav: compose gated `inbox.send.system`; groups gated `inbox.group.admin`; broadcast-only operators use inbox page link
- Not in IM-A7: uni-app, WeChat push, list redesign beyond compose/groups links

## IM-A6 notes

- Caps (ADR 0009 locked #3): sync ≤200; async `200 < N ≤ 2000`; reject N>2000
- Same TX: `inbox_message` + attachments + `inbox_fanout_job` + staged recipients; **truth = `inbox_entry`** (worker batches inserts)
- Trigger: `InboxFanoutWorker` after-commit / immediate (`Runnable::run` default); Outbox not used as inbox
- Response: `fanoutStatus=PENDING` + `fanoutJobId` (entryIds empty until drain); sync path `COMPLETE`
- Endpoints: `POST /group-send`, `POST /broadcast` accept mid-range N
- Not in IM-A6: console compose/groups UI (IM-A7), uni-app, WeChat push

## IM-A5 notes

- `POST /api/v1/inbox/broadcast` `{orgIds[], includeDescendants?=true, title, body, severity?, deepLink?, attachmentStagingIds?}`
- AuthZ: `inbox.broadcast` + tenant grant; each org ACTIVE-linked to tenant; must be in sender `SELF_AND_DESCENDANTS` OrgScope (else 403); unknown → 404
- Recipients: ACTIVE memberships under target orgs (∩ scope); channel `ORG_BROADCAST`; empty → 400; mid-range async = IM-A6
- Helper `InboxOrgBroadcastExpander` reuses `JdbcOrganizationStore` + `OrganizationScopeResolver`
- Not in IM-A5: async was A6 (done), console compose UI (A7)

## IM-A4 notes

- `POST /api/v1/inbox/group-send` `{groupId,title,body,severity?,deepLink?,attachmentStagingIds?}`
- AuthZ: `inbox.broadcast` + tenant grant; group must exist in tenant
- Expand via `InboxGroupPort.expandMembers`; empty → 400; mid-range async = IM-A6
- Channel `GROUP_SEND`; `ref_kind=inbox_group`; optional staging attachments (same as system send)
- Not in IM-A4: ORG_BROADCAST (A5), async 200–2000 (A6), console compose

## IM-A3 notes

- REST `/api/v1/inbox/groups`: list/get/create/patch/delete; members list/add/remove
- AuthZ: `inbox.group.admin` + tenant grant; OPERATOR realm groups only (console MVP)
- Uses existing `InboxGroupPort` / `JdbcInboxGroupPort` (IM-A1)
- Not in IM-A3: GROUP_SEND (A4), ORG_BROADCAST (A5), console groups UI (A7)

## IM-A2 notes

- Direct: `POST /api/v1/inbox/attachments` (octet-stream + `fileName`/`contentType`) → stagingId
- Chunked: `POST …/attachments/uploads` → `PUT …/chunks/{i}` → `POST …/complete` (reuses `ChunkedBlobUploadStore`, entityKey=`inbox`)
- Staging registry in-memory TTL 1h (`InboxAttachmentStagingStore`); bind via `attachmentStagingIds` on `POST /system`
- Download: `GET …/entries/{entryId}/attachments` + `…/{attachmentId}` — own entry only (`inbox.read`)
- Upload/send: `inbox.send.system`; MIME allowlist (pdf/png/jpeg/webp/plain/office openxml)
- Not in IM-A2: group CRUD REST (A3), broadcast APIs (A4/A5), async fan-out, console compose

## IM-A1 notes

- Flyway `V44__inbox_attachment_and_group.sql`: `inbox_attachment`, `inbox_group`, `inbox_group_member`; perms `inbox.broadcast` / `inbox.group.admin` → `platform-operator`
- Contract: `InboxAttachment` / `InboxAttachmentBind` / `InboxGroup*` / `GROUP_SEND` channel; `InboxSendRequest` optional attachments + sync/hard caps
- `JdbcInboxPort.send` binds attachments in same TX; `listAttachmentsForOwnEntry` / `findAttachmentForOwnEntry` (locked #5)
- `JdbcInboxGroupPort`: CRUD + members + expandMembers (REST = IM-A3)
- Not in IM-A1: chunk upload API, REST compose/broadcast, async fan-out, console UI

## Architecture one-liner（0009）

文字+附件外置 ObjectStorage；专用 `inbox_group` + 组织 `ORG_BROADCAST` 双目标群发；同步≤200 / 异步≤2000；薄信箱不变。

## Locked decisions (0009 summary)

1. Attachments → ObjectStorage + `inbox_attachment`（非 entity_blob）
2. Groups → `inbox_group` **and** ORG_BROADCAST
3. Fan-out → sync ≤200, async ≤2000, reject above
4. Perms → `send.system`+attachments; `inbox.broadcast`; `inbox.group.admin`（GROUP_SEND 共用 broadcast）
5. Download → own entry only 

## IM-5 notes

- Header `InboxUnreadBadge` when `inbox.read`; tenant from org/declaration cookie (same as `/inbox`)
- Poll `GET /api/platform/inbox/unread-count` every 60s; pause while tab hidden; refresh on visibility
- Click badge → `/inbox` (manual path); inbox load writes org tenant cookie for badge
- MVP operator track **IM-1…IM-5 complete** on `github/main` @ `47fe668`; IM-6/8 remain deferred; IM-7 → IM-A5

## IM-4 notes

- Console route `/inbox`; nav `navInbox` gated by `inbox.read`
- Client loads via BFF `/api/platform/inbox/**` + `X-Tenant-Id`
- Mark-read on select (if unread) + explicit button; deep link only if path starts with `/`
- Badge poll: see IM-5

## IM-3 notes

- `RepairTicketWorkflowInboxHook` on curated edges only (trusted server path via `InboxPort`, not HTTP)
- Same TX as workflow transition side-effect; empty recipients = no-op
- Channel WORKFLOW; `ref_kind=workflow_instance`; deep link `/pages/repair-ticket/{recordId}`
- Chinese title/body MVP

## IM-2 notes

- REST under `/api/v1/inbox/**` (OPERATOR realm); tenant via `X-Tenant-Id` or `tenantId`
- Permissions: `inbox.read` (list/unread/mark/detail), `inbox.send.system` (POST `/system`)
- Cross-subject get/mark → 403; missing entry → 404; missing/ungranted tenant → 403
- Not in IM-2: workflow hooks (IM-3), console UI (IM-4/5), END_USER (IM-6)

## IM-1 notes

- Tables: `inbox_message` + `inbox_entry` (shared MySQL/PG/H2 SQL)
- Port: send (TX fan-out), list, unreadCount, markRead (idempotent), getEntry
- Fail-closed: every read filters `tenant_id` + realm + subject; cross-tenant / cross-subject / cross-realm return empty / false
- Not in IM-1: REST, console UI, workflow hooks, ORG_BROADCAST API, external push

## Locked decisions (0008 summary)

1. Cross-tenant SYSTEM → one message per target tenant
2. Retention → no auto-purge in MVP
3. ORG_BROADCAST → no API first wave → **now planned as IM-A5 under 0009**
4. External push → IM-8
5. Unread poll → ~60s + manual
6. END_USER → after uni-app auth
7. Copy → Chinese first; i18n structure later
