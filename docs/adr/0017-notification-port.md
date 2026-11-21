# ADR 0017 — Unified external NotificationPort / 统一外部通知端口

- Status: **Accepted** — implemented (WP5-1…WP5-3). **WP5 package closed** at `1714208`. Production vendor adapters may follow via SPI; WP6 is not started.
- Date: 2026-11-17 (Asia/Shanghai)
- Scope: External template delivery (SMS, email, WeChat template) on top of the landed outbox bus and beside the thin inbox; not a chat product, not a second outbox product, not a medical notification module
- Relates: ADR [0008](0008-in-app-messaging.md) (thin inbox; IM-8 external push was deferred), ADR [0009](0009-inbox-attachments-and-group-broadcast.md), ADR [0004](0004-observability-audit-trace.md), ADR [0007](0007-client-triad.md), ADR [0014](0014-end-user-identity.md), [`docs/delivery/outbox-event-contract.md`](../delivery/outbox-event-contract.md), inventory in [`docs/messaging/wp5-notification-port-progress.md`](../messaging/wp5-notification-port-progress.md)

## Context / 背景

WP4 is closed (`d57e923`). The base work doc’s next package is a **notification port**: besides the in-app inbox, provide a unified delivery port for SMS, email, and WeChat template messages, with template id, tenant isolation, outbox delivery, and failure retry. Phase 1 may ship mock adapters and swap in real vendors later. Inbox must not become chat. The kernel must not hardcode medical (or other domain) copy.

Factual inventory (paths, what is already generic, what is missing) lives in [`docs/messaging/wp5-notification-port-progress.md`](../messaging/wp5-notification-port-progress.md). In short:

- The **thin inbox** (ADR 0008/0009) is a per-user mailbox (`InboxPort`, `/api/v1/inbox/**`, `/api/v1/me/inbox`). Its async fan-out uses `inbox_fanout_job`, not the outbox. IM-3 workflow hooks remain **repair-ticket-only**.
- The **outbox** (`outbox_event` + `OutboxRelay` + `DeliveryPort`) is a cross-process reliability bus. Today the only written event is `TaskRecorded`, produced by `TaskMessagePort.submit`. Transports are `socket` or optional `kafka` — **one transport per process**. This is not SMS/email/WeChat delivery.
- There is **no** NotificationPort, **no** vendor SMS/email/WeChat-template send client, and **no** notification template catalog. `VerificationCodeSender` is a narrow auth-code hook (dev no-op / prod fail-closed). WeChat clients in the kernel are **login** (`code2Session`), not template send.
- ADR 0008 already locked WeChat/email external fan-out as **IM-8, deferred**, and forbade using email as the inbox store of truth. This ADR is that later external-delivery track, named as a first-class port.

WP4 已关闭。基线工作包的下一包是通知端口：在站内信之外，用统一端口投递短信、邮件与微信模板；带模板标识、租户隔离、出箱投递与失败重试。首期可用模拟适配器。站内信不变成聊天。内核不写死医疗文案。盘点见进度笔记。出箱是跨进程可靠性总线，不是渠道适配器。IM-8 正是这条后做的外部投递轨。

## Decision / 决策

### D1 — NotificationPort is external template delivery, not an inbox rewrite

Add a domain-facing `NotificationPort` in `platform-contract` (name illustrative). A caller enqueues one send intent: **template id**, **channel**, **tenant**, **recipient**, and **parameter map**. The port returns a stable id so the caller (or an operator) can later see state and last failure reason.

`NotificationPort` does **not** replace `InboxPort` or `/api/v1/inbox`. Inbox stays a thin mailbox (read/unread, deep link, attachments, groups) as ADR 0008/0009 already accepted. Optional “also notify externally after an inbox send” is **not** automatic in this package (see Open points).

增加面向域模块的 `NotificationPort`：一次入队一个投递意图（模板标识、渠道、租户、收件人、参数）。它不改写站内信。本包不自动把站内信再向外扇出。

### D2 — Reuse the existing outbox as the reliability bus; do not invent a second outbox product

Durable enqueue, attempt counting, scheduled retry, circuit-breaker skip, and dead-letter replay already exist on `outbox_event` / `dead_letter` / `OutboxRelay`. WP5 **extends that bus** with a new `event_name` (illustrative: `NotificationRequested`) whose `event_body` carries the send intent.

`NotificationPort` must **not** be implemented as `TaskMessagePort.submit` (that API creates a `platform_task` and a `TaskRecorded` notice). Implementation should add a narrow enqueue path onto the same table (a small outbox store or an additional method), then let the existing relay/DLQ mechanics apply. That is an extension of one bus, not a second product.

`DeliveryPort` stays the **cross-process transport** selector (`socket` | `kafka`). SMS, email, and WeChat template are **channel adapters**, not new `DeliveryTransport` values competing with the bus. Phase 1 may dispatch adapters **in-process after the outbox row is durable**, using the same PENDING → PUBLISHED / DEAD lifecycle so failure is visible and retryable (including existing `POST /api/v1/dead-letters/{id}/replay` for `OUTBOX_EVENT`). Whether a later slice must publish to Kafka first and send from a worker is not locked here (see Open points).

`event_body` is currently VARCHAR(4000). Phase 1 parameter maps must fit; widening the column is an implementation follow-up if needed, not a reason to create a parallel table in this ADR.

复用现有出箱作为可靠性总线（新事件名写入同一张表），不要另起一套出箱产品。不要把通知做成 `TaskMessagePort.submit`。短信/邮件/微信是渠道适配器，不是与 socket/kafka 并列的传输枚举。首期可在出箱行落库后进程内调度适配器，失败可查、可重试。

### D3 — Phase 1 channels: SMS, email, WeChat template; mock/dev first

Phase 1 channels are **SMS**, **email**, and **WeChat template**. Each channel has an adapter interface. **Mock/dev adapters** ship first (record the dispatch, do not call a vendor, do not log secrets). Production adapters are later and must be **SPI-style**: no commercial vendor is hardcoded as the only implementation.

Existing `VerificationCodeSender` stays the **auth-code** path (no plaintext codes in logs; production fail-closed until a real sender is configured). This ADR does not merge it into `NotificationPort`. A later slice may share a low-level SMS client; it must not weaken auth-code rules.

Kernel WeChat code remains login-only until a production WeChat **send** adapter is added. This ADR does not pick official-account template vs mini-program subscribe message (see Open points).

首期渠道为短信、邮件、微信模板；先模拟/开发适配器，再生产 SPI，不把某一厂商写成唯一实现。验证码发送器仍走独立鉴权路径，本 ADR 不合并。

### D4 — Templates are ids + parameters; domain modules own the wording

The kernel stores and transports a **template id** and a **parameter map**. Wording lives in the declaring module (classpath catalog in a later slice, same idea as `workflows/*.workflow.yaml`), not as hard-coded medical, hospital, visa, insurance, or other domain sentences in platform-app.

Inbox hook copy that already exists for repair-ticket stays in that hook; WP5 must not move domain sentences into the kernel “to make notifications work.” Test/demo templates, if any, stay generic (no clinical content).

内核只认模板标识与参数；文案由域模块拥有。不把医疗或其他业务句子写进平台内核。

### D5 — Tenant fail-closed; cross-tenant send denied; never default to `platform`

Every enqueue requires an explicit business `tenantId`. The port never defaults the reserved tenant `platform`. Missing tenant, unknown tenant, or a non-ACTIVE tenant **fail-closes**. A send that names tenant A must not deliver under tenant B (recipient, template resolution, and outbox row all stay in A). This matches WP1 / ADR 0014 posture.

入队必须带明确业务租户；绝不默认保留租户 `platform`；缺失/停用失败关闭；禁止跨租户发信。

### D6 — Recipients follow existing identity; this is not a patient-notification product

Callers address a notification in a **tenant context**, with a recipient that is either:

- a **subject** plus realm (`OPERATOR` / `END_USER`, already used by the inbox), or
- an **explicit destination** (phone / email / channel-specific address) still attributed to that tenant.

What existing identity actually provides today:

- End-user **phone** can be resolved later from `end_user_phone_binding` (global on the account, identity in a business tenant).
- End-user WeChat **login** subject lives on `end_user_idp_link`; that is not yet a confirmed template-message destination.
- **Email is not** a first-class end-user authenticator (ADR 0014).
- Operators have **no** phone/email directory in current tables (`operator_credential` is a password hash; `account` has `login_name` only).

Phase 1 may therefore accept an explicit destination under a tenant, and/or resolve end-user phone when that path is implemented. This ADR does **not** invent an operator address book or a patient-notification product. `web/` stays the operator console; end-user clients stay `clients/uni-app/`.

收件人沿用已有身份三分与领域，不发明患者通知产品。操作员目前没有电话/邮箱目录；终端用户有手机绑定与微信登录主体。首期可在租户上下文中接受显式目的地。

### D7 — Small slices; no WP6 in this package

Implementation is sliced (below). This ADR does not start WP6 (payment port), does not rewrite the inbox or the outbox transport, and does not rewrite the platform.

## Proposed implementation slices / 拟定实现切片

| Slice | Intent | Not in that slice |
| --- | --- | --- |
| **WP5-0** | This ADR + inventory progress note | Code / schema |
| **WP5-1** | `NotificationPort` contract; enqueue a `NotificationRequested` (name illustrative) row on existing `outbox_event`; tenant fail-closed and cross-tenant denial tests | Channel send; vendor SDKs; inbox auto-fan-out |
| **WP5-2** | In-process (or equivalent) dispatcher + mock/dev adapters for SMS, email, WeChat template; retry / last failure / DLQ replay on the existing bus | Production vendors; WeChat product choice beyond a mock |
| **WP5-3** | Formal acceptance: a domain module sends one template message via the port; failure is visible and retryable; cross-tenant send is denied. Close the WP5 package | WP6 payment port; automatic inbox→external fan-out |

Production vendor adapters (SPI, no single hardcoded vendor) may land after WP5-3 if a later brief says so.

## Consequences / 后果

- **Positive**: Domain modules get one port for external template send; durability and retry reuse a bus the platform already operates; inbox remains a mailbox; vendors stay replaceable.
- **Positive**: Tenant fail-closed and “no domain copy in the kernel” stay consistent with earlier ADRs.
- **Negative**: Phase 1 will not automatically push every inbox or workflow event outside the app. IM-3 remains repair-ticket-only until a later messaging brief opts in.
- **Negative**: Operator SMS/email cannot be resolved from today’s schema; WeChat send is not the same as WeChat login.

## Out of scope / 非目标（本 ADR 不发明的产品）

- Instant chat, group rooms, typing indicators, a rewrite of `/api/v1/inbox`.
- A second outbox table/product that races `outbox_event`.
- Medical / hospital / visa / insurance / other domain wording in the kernel.
- Hardcoding a commercial SMS, email, or WeChat vendor as the only adapter.
- Merging authentication verification codes into this port in phase 1.
- A patient-notification product, or an operator contact directory that does not exist yet.
- WP6 payment port.
- Changing the `/api/v1` tenant-filter exemption, or moving `web/` off the operator console.

## Open points not settled from existing docs / 现有文档无法锁死、故不臆造

- Automatic external fan-out from inbox send or from non-repair-ticket workflow machines.
- Operator destination resolution (no contact columns today).
- Which WeChat send product to use in a production adapter.
- Sharing a future SMS client with `VerificationCodeSender` without weakening auth-code rules.
- Tenant draft / P7 promote of notification templates (no `DeclarationKind` value today).
- Whether dispatch must travel on socket/Kafka `DeliveryPort` before adapters run.
- Widening `event_body` if 4000 characters is not enough.

## References / 引用

- [`docs/messaging/wp5-notification-port-progress.md`](../messaging/wp5-notification-port-progress.md) — WP5-0 inventory
- [`docs/messaging/im-progress.md`](../messaging/im-progress.md) — IM-0…IM-A7; IM-8 deferred
- [`docs/delivery/outbox-event-contract.md`](../delivery/outbox-event-contract.md)
- ADR 0008 / 0009 / 0004 / 0007 / 0014
