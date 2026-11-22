# WP5 notification port progress / 统一外部通知端口进度

- Date: 2026-11-17 (Asia/Shanghai)
- ADR: [`docs/adr/0017-notification-port.md`](../adr/0017-notification-port.md)
- Baseline tip when this note was opened: `d57e923` (WP4 closed; formal acceptance `e87ed36`)
- Predecessor: ADR [`0008`](../adr/0008-in-app-messaging.md) / [`0009`](../adr/0009-inbox-attachments-and-group-broadcast.md) and [`docs/messaging/im-progress.md`](im-progress.md) (IM-0…IM-A7 done; **IM-8** external WeChat/email push was deferred). Outbox contract: [`docs/delivery/outbox-event-contract.md`](../delivery/outbox-event-contract.md)
- WP5-0 提交：`afcaf10`（`afcaf1012b7c5cb39717b979fd45d26653cfc59a`；盘点 + ADR 0017）。
- WP5-1 提交：`efd68c3`（`efd68c32ec91fd40adb98b52e82cded48ed53471`；`NotificationPort` 入队到已有 `outbox_event`）。
- WP5-2 提交：`701efad`（`701efad7be0238fbc49066a4d0a819188d2aff5d`；模拟适配器 + 调度 / 重试 / 死信）。
- 本切片提交：`1714208`（`1714208fb3bd8e5d0d3cf250ea18c470584b602d`；WP5-3 正式验收并**关闭 WP5 包**）。不启动 WP6（支付端口），不接入商业短信/邮件/微信厂商 SDK。

This track adds a **unified external NotificationPort** (SMS, email, WeChat template) beside the already-landed thin inbox. It is not a rewrite of `/api/v1/inbox`, not a chat product, and not a second competing outbox product. No medical or other domain copy belongs in the kernel.

本轨是在已落地薄站内信之外，增加统一的外部投递端口（短信、邮件、微信模板）。不改写站内信，不是即时聊天，也不另起一套出箱产品。内核不放医疗或其他域文案。

## WP5-0 [done] — inventory + ADR

Docs-only. No schema, no port, no adapters.

本切片只写文档，不改表、不实现端口与适配器。

### Inventory / 盘点（事实与路径）

What already exists is a **working thin mailbox** and a **working cross-process outbox**, plus a narrow end-user **verification-code sender** and WeChat **login** clients. There is **no** `NotificationPort`, **no** SMS/email/WeChat-template product send path, and **no** notification template catalog.

已有的是可用的薄站内信、可用的跨进程出箱、终端用户验证码发送器，以及微信登录客户端。没有 NotificationPort，没有短信/邮件/微信模板的产品投递路径，也没有通知模板目录。

| Area | What exists | Paths |
| --- | --- | --- |
| Inbox ADR / progress | ADR 0008 Accepted: thin mailbox, not chat; audit is an ops trail; outbox is external/cross-process, not a mailbox. Locked #4: WeChat/email external fan-out **deferred to IM-8**. ADR 0009 Accepted: attachments, groups, org broadcast, async fan-out caps. IM-1…IM-A7 marked done in the earlier progress note. IM-8 remains deferred there. | `docs/adr/0008-in-app-messaging.md`; `docs/adr/0009-inbox-attachments-and-group-broadcast.md`; `docs/messaging/im-progress.md` |
| Inbox tables | Flyway **V43** `inbox_message` / `inbox_entry` (one body, one row per recipient, `read_at`). **V44** `inbox_attachment`, `inbox_group`, `inbox_group_member`. **V45** `inbox_fanout_job` + staged recipients. Channels `SYSTEM` / `WORKFLOW` / `ORG_BROADCAST` / `GROUP_SEND`. Realms `OPERATOR` / `END_USER`. | `platform-app/src/main/resources/db/migration/V43__inbox_message.sql`, `V44__inbox_attachment_and_group.sql`, `V45__inbox_fanout_job.sql`; `platform-contract/.../inbox/InboxChannel.java`, `InboxRecipientRealm.java` |
| Inbox port / HTTP / UI | Contract `InboxPort` (sync send, deferred fan-out, list, unread, mark-read, attachments). JDBC `JdbcInboxPort`. Operator API `/api/v1/inbox/**`; end-user `/api/v1/me/inbox`. Console `/inbox` (+ compose/groups). Uni-app inbox page. Fan-out worker writes `inbox_entry` after commit and **does not use Delivery/Outbox as inbox**. | `platform-contract/.../inbox/InboxPort.java`; `platform-app/.../inbox/JdbcInboxPort.java`, `InboxService.java`, `InboxFanoutWorker.java`, `EndUserInboxEndpoint.java`; `web/src/app/(console)/inbox`; `clients/uni-app/src/pages/inbox` |
| IM-3 workflow hook | `RepairTicketWorkflowInboxHook` writes WORKFLOW inbox rows for repair-ticket `request_approval` / `approve` / `reject` / `close`. **Still repair-ticket-only** (explicit early return; WP4-2/4 documented: do not silently notify another machine). | `platform-app/.../workflow/RepairTicketWorkflowInboxHook.java` |
| Outbox tables / contract | Flyway **V1** `outbox_event` (`event_id`, `tenant_id`, `event_name`, `event_body` VARCHAR **4000**, `event_state` PENDING\|PUBLISHED\|DEAD, `trace_id`, `attempt_count`, `failure_reason`, `occurred_at`, `published_at`) and `dead_letter` (`origin_kind` TASK \| OUTBOX_EVENT). V42 adds `discarded_at`. Living contract: only event today is `TaskRecorded`. Max attempts shared with tasks (`TaskLifecycle.DEFAULT_MAX_ATTEMPT` = 3). Circuit breaker skips deliver without burning attempts. Admin: `GET /api/v1/dead-letters`, replay/discard (OUTBOX_EVENT replay only). | `V1__platform_schema.sql`; `V42__dead_letter_discarded_at.sql`; `docs/delivery/outbox-event-contract.md`; `platform-contract/.../task/OutboxEvent.java`, `OutboxState.java`, `OriginKind.java`, `TaskLifecycle.java`; `DeadLetterApiEndpoint.java` |
| Outbox writers / relay / transport | `TaskMessagePort.submit` persists a task **and** an outbox row, then `DeliveryPort.deliver` once after commit. `OutboxRelay` `@Scheduled` calls `relayPending`. **One process selects one transport:** `socket` (`SUBJEX-OUTBOX 2`, HMAC + TLS policy) or optional `kafka` (`subjex-outbox-kafka`, not on the default classpath). `DeliveryPort` implementations must not mark the row; the store records PUBLISHED / PENDING / DEAD. The port javadoc says there is not a second *task* messaging port beside `TaskMessagePort`. Inbox already coexists as a separate product port. | `platform-contract/.../task/TaskMessagePort.java`; `.../delivery/DeliveryPort.java`, `DeliveryTransport.java`; `platform-app/.../jdbc/JdbcTaskMessagePort.java`; `.../delivery/OutboxRelay.java`, `OutboxSocketPublisher.java`; `subjex-outbox-kafka/.../KafkaDeliveryPublisher.java`; `sample-consumer` |
| SMS / email / WeChat template send | **No** product send path. No JavaMail/SMTP client, no vendor SMS SDK, no WeChat template/subscribe-message API. `VerificationCodeSender` is a **narrow auth-code** hook (WP2-3): `DevVerificationCodeSender` is a no-op (codes via peek/reveal); `FailClosedVerificationCodeSender` is the production stand-in when no real sender is configured. It is not a template notification port. | `platform-app/.../identity/enduser/VerificationCodeSender.java`, `DevVerificationCodeSender.java`, `FailClosedVerificationCodeSender.java`, `EndUserPhoneAuthService.java` |
| WeChat in the kernel | Login / IdP only: operator and end-user adapters call `code2Session` / `redeemOpenCode`. Not a template-message client. | `platform-app/.../idp/WeChatApiClient.java`, `HttpWeChatApiClient.java`; `.../identity/enduser/EndUserWeChatAuthService.java` |
| Templates | No notification template catalog. Entity / form / page / workflow YAML catalogs are unrelated. Inbox messages carry literal `title` / `body` written by the producer (workflow hook has repair-ticket Chinese copy in that hook, not a template id). | — |
| Recipient contact (identity) | End-user **phone** is first-class: `end_user_phone_binding` (global uniqueness on the account). End-user WeChat login is `end_user_idp_link` (`provider_id`, `issuer`, `idp_subject`). ADR 0014 first authenticators are phone + WeChat mini-program; **email is not** a first-class end-user authenticator. Operator identity lives in reserved tenant `platform`; `operator_credential` is password hash only; `account.login_name` has **no** phone/email column. `subject` has no contact fields. | `V49__end_user_identity.sql`; `V2__operator_permission.sql` (`operator_credential`); `V1__platform_schema.sql` (`account`, `subject`); ADR 0014 |
| Tenant fail-closed | Business writes require an explicit tenant; reserved `platform` is the operator home, never a default business tenant. Missing / disabled tenants fail closed (WP1 / ADR 0014). | ADR 0014; `EndUserPhoneAuthService` / tenant guards |

## WP5-1 [done] — NotificationPort enqueue onto existing outbox

Contract + durable enqueue only. No channel send, no vendor SDK, no WP5-3 acceptance suite.

本切片只落地端口与入队。不发送、不接厂商 SDK、不写 WP5-3 正式验收。

- Port: `NotificationPort.enqueue(NotificationCommand)` in `platform-contract/.../notification/`. Command fields: explicit `tenantId`, `templateId`, `NotificationChannel` (`SMS` / `EMAIL` / `WECHAT_TEMPLATE` as identifiers only), `NotificationRecipient` (inbox realm `OPERATOR`/`END_USER` + `subjectId`, and/or an explicit `destination`, optional `recipientTenantId`), and a parameter map. Returns `NotificationReceipt` (`notificationId` = `outbox_event.event_id`, `eventName`, `eventState`).
- Writer: `JdbcNotificationPort` inserts `event_name=NotificationRequested` into the existing `outbox_event` table (`PENDING`, `attempt_count=0`). It does **not** call `TaskMessagePort.submit` and writes no `platform_task` row. Inbox tables are untouched. `VerificationCodeSender` is unchanged.
- Tenant fail-closed: blank → `TenantMissingException`; unknown → `TenantNotFoundException`; `SUSPENDED` → `TenantDisabledException`; reserved `platform` → `IllegalArgumentException` (`is not a notification home`). No row is written. The port never defaults to `platform`.
- Cross-tenant: a recipient stamped with another `recipientTenantId`, or an `END_USER` subject whose `subject_identity` exists only in another business tenant, is denied (`NotificationCrossTenantDeniedException`) with no row.
- Payload: `event_body` must fit VARCHAR(4000); larger parameter maps fail closed with a clear error. The column was not widened and no payload-store product was added.
- WP5-2 hook: `JdbcTaskMessagePort.relayPending` selects only `TaskRecorded`. `NotificationRequested` stays `PENDING` so the existing socket/Kafka `DeliveryPort` does not burn attempts or treat the intent as a task frame. A later channel dispatcher will pick these rows up.
- Tests: `NotificationPortEnqueueTest` 12/12 (H2 PostgreSQL + MySQL). Focused regression: `OutboxRelayTest` 4/4, `DeadLetterReplayTest` 6/6. Did not touch `OperatorBootstrapTest.readerRoleGetsOnlyReadPermissions`.

Feature SHA: `efd68c3` (`efd68c32ec91fd40adb98b52e82cded48ed53471`).

## WP5-2 [done] — mock adapters, dispatch, retry, and DLQ

In-process dispatcher + mock/dev adapters. No formal WP5-3 acceptance suite, no commercial vendor SDK as the only production path, no WP6.

本切片落地进程内调度器与模拟/开发适配器。不写 WP5-3 正式验收套件，不把商业厂商 SDK 写成唯一生产路径，不启动 WP6。

- SPI: `NotificationChannelAdapter` (`channel()`, `configured()`, `send(NotificationRequested)`). Default wiring: mock adapters (`MockNotificationAdapter` + in-memory `RecordingNotificationSink`) outside prod; `FailClosedNotificationAdapter` under `prod`/`production` when no vendor bean is registered. A later `NotificationChannelAdapter` bean for a channel replaces the default (`putIfAbsent`). Adapters send template id + params only; they do not log secrets.
- Dispatcher: `NotificationDispatcher` / `JdbcNotificationDispatcher` selects `PENDING` + `event_name=NotificationRequested`, calls the adapter, then records success or a retry/dead-letter outcome with `DeliveryProgress` and `TaskLifecycle.DEFAULT_MAX_ATTEMPT` (3) on the **same** `outbox_event` / `dead_letter` (`OriginKind.OUTBOX_EVENT`). Lock name `notification-dispatch` is distinct from `outbox-relay`. Scheduled by `NotificationDispatchWorker` (`platform.notification.dispatch-interval-ms` / `dispatch-batch-size`). Dispatch is in-process after the row is durable; socket/Kafka `DeliveryPort` is not a mandatory hop.
- Replay: `NotificationDispatcher.replayDeadLetter` restores the row to `PENDING`, deletes the letter, and dispatches once via the adapter. Existing `POST /api/v1/dead-letters/{id}/replay` reuses that path for `NotificationRequested` (`TaskMessagePort.replayDeadLetter` only restores those rows and does not call `DeliveryPort`). `GET /api/v1/dead-letters` already lists them.
- `OutboxRelay` / `relayPending` still select only `TaskRecorded`.
- Tests: `NotificationDispatchTest` 12/12 (H2 PostgreSQL + MySQL): three-channel mock sinks, forced failure retries then dead-letters, replay succeeds, relay does not steal rows, cross-tenant enqueue still denied, unconfigured adapter fail-closes into DLQ. Regression: `NotificationPortEnqueueTest` 12/12, `OutboxRelayTest` 4/4, `DeadLetterReplayTest` 6/6. Did not touch `OperatorBootstrapTest.readerRoleGetsOnlyReadPermissions`.

Feature SHA: `701efad` (`701efad7be0238fbc49066a4d0a819188d2aff5d`).

## WP5-3 [done] — formal acceptance; WP5 package closed

This slice locks ADR 0017 acceptance and **closes the WP5 package**. No production hole was found; no production code was added. WP6 (payment port) is not started. Real vendor adapters may follow later via the existing SPI; they are not required to close the package.

本切片锁定 ADR 0017 验收并**关闭 WP5 包**。未发现需改生产代码的缺口。未启动 WP6（支付端口）。生产厂商适配器可稍后按已有 SPI 接入，不是关包前提。

Acceptance met / 验收已满足：

- A domain module can send one template message through `NotificationPort` (`NotificationRequested` on the existing `outbox_event` bus; mock adapter records template id + params, not kernel prose).
- Failures are queryable and retryable (forced adapter failure becomes `DEAD` + `dead_letter`; `NotificationDispatcher.replayDeadLetter` can succeed).
- Cross-tenant send is denied (no outbox row, no dispatch).
- Inbox was not turned into chat (enqueue/dispatch write no inbox tables). No medical or other domain copy is hardcoded in the notification kernel sources. `VerificationCodeSender` is not imported there. No second outbox migration/product was added.
- Mock/dev adapters are enough for this package. Production stays fail-closed until a vendor `NotificationChannelAdapter` bean is registered.

Formal lock: `NotificationPortAcceptanceTest` 4/4 (domain-style enqueue → mock sink; forced failure → dead letter → replay; cross-tenant deny; notification kernel source scan). It does not repeat the WP5-1/WP5-2 focused suites.

Regression (this turn, all passed): `NotificationPortAcceptanceTest` 4/4, `NotificationPortEnqueueTest` 12/12, `NotificationDispatchTest` 12/12, `OutboxRelayTest` 4/4, `DeadLetterReplayTest` 6/6. Total **38/38**. Pre-existing `OperatorBootstrapTest.readerRoleGetsOnlyReadPermissions` was not run and was not fixed.

Residual gaps (honest, not in this package) / 仍存缺口（本包不解决）：

- Operator destination resolution (no operator phone/email directory in current schema). End-user phone/WeChat destinations are not auto-resolved from identity tables in this package (explicit destination or subject is accepted at enqueue).
- Which WeChat send product to use in a production adapter (official-account template vs mini-program subscribe message) is still open; the kernel still only has login (`code2Session`).
- `outbox_event.event_body` remains VARCHAR(4000); oversized parameter maps fail closed (column not widened; no payload-store product).
- Notification dispatch does not use the `DeliveryPort` circuit breaker (it is a separate in-process adapter path).
- Production unconfigured adapters fail into DLQ after 3 attempts (visible, but can be noisy until a vendor bean is registered).
- No automatic external fan-out from inbox send or from non-repair-ticket workflow machines (IM-8 auto-fan-out remains out of this package).
- `VerificationCodeSender` stays a separate auth-code path (not merged; a later slice may share a low-level SMS client without weakening auth-code rules).
- Tenant draft / P7 promote of notification templates (`DeclarationKind` has no notification value).
- Whether a later slice must publish to socket/Kafka `DeliveryPort` before adapters run remains an open point (this package dispatches in-process after the row is durable).

Next package would be **WP6 (payment port)**, not started here.

## Operator send-log query (after WP5 close) / 操作员发送记录查询（关包后）

Operator read-only HTTP and the console 「通知发送记录」 page for `NotificationRequested` outbox rows landed later as a console-query slice; see [`operator-query-ui-progress.md`](../payment/operator-query-ui-progress.md) (第 3–4 刀). Permission `notification.read`. No send, retry, or replay on that API or page.
操作员只读查询 `NotificationRequested` 出箱行的 HTTP 接口与控制台「通知发送记录」页在关包后作为控制台查询切片落地；见上述进度笔记第 3–4 刀。权限为 `notification.read`。该接口与页面不提供发送、重试或重放。

## Slice status / 切片状态

- **WP5-0** — ✅ done.
- **WP5-1** — ✅ done.
- **WP5-2** — ✅ done.
- **WP5-3** — ✅ done (this slice). **WP5 package closed.**


## Open points not settled from existing docs / 现有文档无法锁死、故不臆造

- Whether a successful inbox send should automatically also enqueue an external notification (ADR 0008 left IM-8 optional; this package does not auto-fan-out).
- How to resolve operator SMS/email/WeChat destinations (no operator contact directory exists today).
- Which WeChat delivery product (official-account template vs mini-program subscribe message); the kernel today only has login (`code2Session`).
- Whether `VerificationCodeSender` should share a future SMS adapter with NotificationPort (auth-code security must not be weakened; not merged in this ADR).
- Tenant draft / promote of notification templates (`DeclarationKind` has no notification value).
- Whether dispatch must go through the socket/Kafka `DeliveryPort` first, or may call channel adapters in-process after the outbox row is durable.
- `outbox_event.event_body` VARCHAR(4000) may be tight for large parameter maps (implementation may later widen or split; not decided here).

## SHA

- WP5-0：`afcaf10` (`afcaf1012b7c5cb39717b979fd45d26653cfc59a`)
- WP5-1：`efd68c3` (`efd68c32ec91fd40adb98b52e82cded48ed53471`)
- WP5-2：`701efad` (`701efad7be0238fbc49066a4d0a819188d2aff5d`)
- WP5-3：`1714208` (`1714208fb3bd8e5d0d3cf250ea18c470584b602d`)
