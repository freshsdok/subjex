# Outbox event contract / 出箱事件契约

Status: living doc (2026-10-16). Relates: `DeliveryPort`, `OutboxRelay`, `JdbcTaskMessagePort`, socket frame `SUBJEX-OUTBOX 2`, optional Kafka adapter. External template notifications (WP5) are directed by ADR [`0017`](../adr/0017-notification-port.md) to **reuse this bus**, not to add a second outbox product. Payment status notices (WP6) are directed by ADR [`0018`](../adr/0018-payment-port.md) to reuse the same bus (`PaymentStatusChanged`); implemented in WP6-2 (`978bce2`) and relayed via `DeliveryPort` like `TaskRecorded` (the body is not parsed as `TaskRecordedNotice`).

## Lifecycle / 生命周期

```text
submit(task) ──► INSERT outbox_event (PENDING, attempt_count=0)
              └─ sync DeliveryPort.deliver once after commit
                    ├─ success → PUBLISHED (+ published_at)
                    └─ failure → PENDING (attempt++) … relay
OutboxRelay @Scheduled ──► relayPending(batch)
                    └─ each PENDING → deliver
                         ├─ PUBLISHED
                         ├─ PENDING (attempt < max=3)
                         └─ DEAD + INSERT dead_letter (origin OUTBOX_EVENT)
Circuit breaker open → skip deliver, do not burn attempt_count
```

Max attempts: `TaskLifecycle.DEFAULT_MAX_ATTEMPT` (= **3**), shared with task step retries.

Dead-letter admin (JSON, `admin.read`):

| Method | Path | Effect |
| --- | --- | --- |
| GET | `/api/v1/dead-letters` | Active letters (`discarded_at IS NULL`) |
| POST | `/api/v1/dead-letters/{id}/replay` | OUTBOX_EVENT only: reset to PENDING, delete letter, deliver once |
| POST | `/api/v1/dead-letters/{id}/discard` | Set `discarded_at`; outbox row stays DEAD |

Legacy read: `GET /admin/dead-letters` (same list).

## Payload / 载荷

### Stored row (`outbox_event`)

| Column | Meaning |
| --- | --- |
| `event_id` | Stable id (= notice.eventId) |
| `tenant_id` | Tenant |
| `event_name` | Wire name; `TaskRecorded` or `PaymentStatusChanged` (relayed via `DeliveryPort`) or `NotificationRequested` (dispatched in-process by `NotificationDispatcher`, not via `OutboxRelay`) |
| `event_body` | JSON string ≤ 4000 chars |
| `event_state` | `PENDING` \| `PUBLISHED` \| `DEAD` |
| `trace_id` | Trace id used to build W3C `traceparent` on the wire |
| `attempt_count` | Delivery attempts recorded |
| `failure_reason` | Last failure clip |

### Body JSON

`TaskRecordedNotice` / name `TaskRecorded` (existing relay / socket / Kafka path):

```json
{
  "eventId": "<uuid>",
  "tenantId": "<tenant>",
  "taskId": "<uuid>",
  "stepName": "<step>"
}
```

`NotificationRequested` (written by `NotificationPort`; not produced by `TaskMessagePort.submit`). The body carries a template id and a parameter map, not hard-coded domain prose. `OutboxRelay` does **not** select this name. `NotificationDispatcher` (WP5-2) runs SMS/email/WeChat-template adapters in-process, records PUBLISHED / PENDING / DEAD with the same max-attempt rule, and writes `dead_letter` (`OUTBOX_EVENT`). Existing `POST /api/v1/dead-letters/{id}/replay` restores the row and dispatches once via the adapter (not `DeliveryPort`). Parameter maps that would exceed `event_body` VARCHAR(4000) fail closed; the column was not widened.

```json
{
  "eventId": "<uuid>",
  "tenantId": "<tenant>",
  "templateId": "<template-id>",
  "channel": "SMS | EMAIL | WECHAT_TEMPLATE",
  "recipient": {
    "realm": "OPERATOR | END_USER",
    "subjectId": "<subject>",
    "destination": "<optional phone/email/address>",
    "recipientTenantId": "<must match tenantId when set>"
  },
  "parameters": { "orderId": "ORD-41" }
}
```

CloudEvents-ish mapping (not on the wire yet; for integrators):

| CloudEvents | Subjex |
| --- | --- |
| `id` | `event_id` |
| `source` | `urn:subjex:tenant:{tenantId}` (convention) |
| `type` | `subjex.task.recorded` ↔ `event_name=TaskRecorded` |
| `time` | `occurred_at` |
| `data` | parsed `event_body` |
| `traceparent` | socket/Kafka header (not inside body) |

### Socket frame (`SUBJEX-OUTBOX 2`)

Headers then raw body bytes:

- `event-name`, `traceparent`, `auth-scheme`/`auth-timestamp`/`auth-signature` (HMAC-SHA256), `failure-requested`, `body-bytes`
- Body = `event_body` UTF-8 (≤ 4000)
- Reply: `accepted` \| `rejected`

### Kafka adapter (optional module)

- Key: `tenantId:eventId`
- Value: `event_body` bytes
- Headers: `traceparent`, `event-name`, `declaration-version`

## Non-goals / 非目标

- Multi-event catalog / schema registry
- Full CloudEvents JSON envelope on the wire
- Replay of `OriginKind.TASK` dead letters (use task retry)
