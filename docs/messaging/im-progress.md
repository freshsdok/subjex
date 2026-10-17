# In-app messaging progress / 站内信进度

- ADR: [`docs/adr/0008-in-app-messaging.md`](../adr/0008-in-app-messaging.md) (**Accepted**, 2026-10-17 Asia/Shanghai)
- Stance: thin mailbox (SYSTEM + WORKFLOW); not chat; not Outbox-as-inbox; console operator first; uni-app end-user later (ADR 0007)
- Decisions: **all 7 MVP defaults locked** (user「请继续」; no per-item overrides) — see ADR § Locked decisions

## Slice board

| Slice | Status | Note |
| --- | --- | --- |
| IM-0 Architecture ADR | **done** | ADR 0008 Accepted + locked decisions |
| IM-1 Schema + InboxPort | **done** | Flyway `V43__inbox_message.sql`; contract `InboxPort`; `JdbcInboxPort`; isolation + port tests; permissions `inbox.read` / `inbox.send.system` |
| IM-2 REST + permissions | **done** | `/api/v1/inbox/**` list/unread/mark-read/detail/system; AuthZ + isolation tests |
| IM-3 Workflow hooks | **done** | repair-ticket `request_approval`→ACTION candidates; `approve`/`reject`/`close`→INFO reporter |
| IM-4 Console `/inbox` | **done** | `web/` `/inbox` list + mark-read + deep link + nav (`inbox.read`) |
| IM-5 Unread badge poll | **done** | header badge ~60s poll + visibility pause; click → `/inbox` |
| IM-6 uni-app END_USER | not started | After client auth (locked) |
| IM-7 ORG_BROADCAST | deferred | Schema enum only; no API (locked) |
| IM-8 External push via outbox | deferred | WeChat/email after MVP (locked) |

## IM-5 notes

- Header `InboxUnreadBadge` when `inbox.read`; tenant from org/declaration cookie (same as `/inbox`)
- Poll `GET /api/platform/inbox/unread-count` every 60s; pause while tab hidden; refresh on visibility
- Click badge → `/inbox` (manual path); inbox load writes org tenant cookie for badge
- MVP operator track **IM-1…IM-5 complete** locally; IM-6/7/8 remain deferred

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

## Locked decisions (summary)

1. Cross-tenant SYSTEM → one message per target tenant
2. Retention → no auto-purge in MVP
3. ORG_BROADCAST → no API first wave
4. External push → IM-8
5. Unread poll → ~60s + manual
6. END_USER → after uni-app auth
7. Copy → Chinese first; i18n structure later
