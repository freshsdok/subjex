# ADR 0018 — Unified PaymentPort / 统一支付端口

- Status: **Accepted** — direction only (WP6-0). Implementation starts at WP6-1.
- Date: 2026-11-17 (Asia/Shanghai)
- Scope: Platform-side payment order, idempotent result callback, refund (including partial), query, and a voucher ledger; not a finance mid-office, not revenue share / split-accounting / tax, not a hospital or other domain checkout module
- Relates: ADR [0017](0017-notification-port.md) (NotificationPort; reuse one outbox bus, mock-first SPI), ADR [0014](0014-end-user-identity.md) (tenant fail-closed; reserved `platform` is never a default business tenant), ADR [0004](0004-observability-audit-trace.md), ADR [0007](0007-client-triad.md), [`docs/delivery/outbox-event-contract.md`](../delivery/outbox-event-contract.md), inventory in [`docs/payment/wp6-payment-port-progress.md`](../payment/wp6-payment-port-progress.md)

## Context / 背景

WP5 is closed (`0cc9582`, acceptance `1714208`). The base work doc’s next package is a **payment port**: provide payment orders, result callbacks, refunds, and a voucher ledger. Do **not** build revenue share or a finance mid-office. `PaymentPort` must support create, payment-result callback, refund, and query; **callbacks must be idempotent**. Vendor adapters are pluggable (phase 1 may be a mock; later WeChat Pay or Alipay). The kernel records only **platform-side payment vouchers** — not hospital commissions, split-accounting, or tax rules. Acceptance later: under a mock adapter, create, payment success, partial refund, and a duplicate callback all run; a domain module can continue its own flow from the document status. The kernel must stay generic: no hospital catalogs, appointments, visa, insurance, or other domain checkout rules.

Factual inventory (paths, what can be reused, what is missing) lives in [`docs/payment/wp6-payment-port-progress.md`](../payment/wp6-payment-port-progress.md). In short:

- There is **no** `PaymentPort`, **no** payment / refund / voucher / ledger table, **no** money or currency type in `platform-contract` / `platform-app`, and **no** WeChat Pay or Alipay client. Kernel WeChat code remains **login** (`code2Session`). HTTP “callbacks” that exist today are **IdP / OIDC / WeChat login**, not payment notify.
- Tenant quota (TN-1 / V20 / V36) is a **count** cap (daily submits, storage rows), not a money ledger.
- Reusable patterns: tenant fail-closed (never default to reserved `platform`); `AuditPort` (one audit channel); `outbox_event` as the **single** cross-process bus (`TaskRecorded` via `DeliveryPort`; `NotificationRequested` dispatched in-process — ADR 0017); task `IdempotencyPort` / `idempotency_claim` (tenant + token + fingerprint, but the row is bound to `task_id` and must not be overloaded as if a payment were a `platform_task`).

WP5 已关闭。基线工作包的下一包是支付端口：支付单、结果回调、退款与凭证台账；不做分润和财务中台。回调必须幂等。供应商适配器可插拔，首期可用模拟支付。内核只记平台侧支付凭证。盘点见进度笔记：目前没有支付端口、金额类型或支付表；可复用租户失败关闭、审计端口与已有出箱总线。任务幂等表绑定 `task_id`，不能把支付当成任务来占用。

## Decision / 决策

### D1 — PaymentPort is create, callback, refund, and query; not a finance mid-office

Add a domain-facing `PaymentPort` in `platform-contract` (name illustrative). The port supports:

- **Create order** — explicit business `tenantId`, amount + currency, optional opaque domain `reference` (the kernel does not interpret it), optional payer subject / realm, optional caller idempotency token, and a **provider identifier** (phase 1: mock). Returns a stable payment id and the current document.
- **Payment-result callback** — apply a provider (or mock) result to an existing payment. **Must be idempotent** (D2). A successful pay moves a payable document to `SUCCEEDED`.
- **Refund**, including **partial** — a positive refund amount in the **same currency**, not exceeding the remaining refundable amount. Only a `SUCCEEDED` or already `PARTIALLY_REFUNDED` payment may be refunded. Full refund reaches `REFUNDED`; otherwise `PARTIALLY_REFUNDED`.
- **Query order** — return the payment document (status, amounts, provider references, refund lines) by tenant + payment id. This is the **source of truth** for “what state is this document in?”

The port does **not** implement commissions, split-accounting, tax, hospital or other domain payout, settlement cycles, invoices / fapiao, foreign-exchange conversion, or authorize-and-capture (this package is a single success/fail capture, not a pre-authorization product). It does not start WP7 (end-user client shell deepen). `web/` stays the operator console; end-user clients stay `clients/uni-app/`.

增加面向域模块的 `PaymentPort`：创单、支付结果回调、退款（含部分退款）、查单。查单是单据状态的真相源。不做分润、分账、税务、院端打款、结算周期、发票、汇率兑换或预授权/请款拆分。不启动 WP7。

### D2 — Callbacks (and refunds) are idempotent; do not double-credit or double-refund

A duplicate payment-result callback for the same payment and the same provider notification (same logical event) must return the **already-applied** document and must **not** credit the amount again, change a terminal status twice, or write a second success voucher. A duplicate refund with the same caller (or provider) idempotency key must return the original refund line and must not increase refunded totals.

How to store the key: persist a processed-callback / refund key **on the payment aggregate** (tenant + payment id + provider notification id or caller refund token). Do **not** implement this as `TaskMessagePort.submit`, and do **not** overload `idempotency_claim.task_id` as if the payment were a task. The existing `IdempotencyPort` remains the task-submission port; payment idempotency is a property of the payment/voucher store (same pattern — tenant + token, same-fingerprint replay, conflict on mismatch — not a second platform-wide idempotency product).

Create-order may also accept a caller idempotency token and return the original payment when the token is replayed with the same fingerprint.

回调（以及带幂等键的退款）必须幂等：重复投递不得二次入账或二次退款，应返回已应用的单据。幂等键落在支付/凭证聚合上；不要占用任务幂等表的 `task_id`，也不要把支付写成 `TaskMessagePort.submit`。

### D3 — Pluggable vendor adapters; phase 1 is a mock

Phase 1 ships a **mock/dev adapter** (records the intent, can simulate success / failure / refund, does not call a commercial API, does not log secrets). Production stays **fail-closed** when no real adapter is configured (same posture as notification adapters and `VerificationCodeSender`: do not mark a payment succeeded as a silent no-op).

Adapters are **SPI-style**. Identifiers such as `MOCK`, and later `WECHAT_PAY` / `ALIPAY`, are **names only**. This ADR does **not** hardcode WeChat Pay or Alipay as the only implementation, does not pick a WeChat Pay or Alipay product (JSAPI, Native, mini-program, in-app, …), and does not add a vendor SDK in WP6-0…WP6-3. A later vendor adapter may drop in the same way a notification vendor bean replaces the default.

微信支付与支付宝在本包只是预留标识；首期只有模拟适配器。生产未配置真实适配器则失败关闭，不得把未支付写成成功。

### D4 — Platform-side voucher ledger only

The kernel records a **tenant-scoped voucher ledger** of payment and refund facts. At minimum each fact carries: payment id, tenant, amount, currency, resulting status, provider identifier and provider reference (when known), and refund ids / amounts when the fact is a refund. Implementation may store this as a payment document plus refund lines, with or without a separate append-only entry table; that is still one ledger product, not a general ledger.

Explicitly **out of scope**: commissions, revenue share, split-accounting, tax rules, hospital or other domain payout, settlement / reconciliation cycles, a chart of accounts, or an invoice product. Domain modules own their own business documents (they may store the payment id on their side via the opaque `reference` or by querying the port).

内核只记平台侧、按租户隔离的支付与退款凭证台账。不实现分润、分账、税务、院端打款、结算周期、会计科目或发票产品。

### D5 — Tenant fail-closed; cross-tenant access denied; never default to `platform`

Every create, callback, refund, and query requires an explicit business `tenantId`. The port never defaults the reserved tenant `platform`. Missing, unknown, or non-ACTIVE tenants **fail-close**. A payment, refund, callback, voucher line, and any outbox row that names tenant A must stay in A; a tenant-B id must not be used under tenant A. This matches WP1 / ADR 0014 / ADR 0017 posture.

入队与查单必须带明确业务租户；绝不默认保留租户 `platform`；缺失/停用失败关闭；禁止跨租户访问或入账。

### D6 — Amounts are minor units plus an explicit ISO 4217 currency

The kernel has no money type today (inventory). This package introduces a simple value: a **non-negative integer in minor units** (smallest currency unit, e.g. fen for `CNY`) plus an **explicit ISO 4217 currency code**. The caller must supply the currency; the port does not silently default one. Unknown or blank currency, non-integer / negative create amounts, and refund amounts that are not strictly positive or that exceed the remaining refundable amount **fail closed**. A refund uses the **same currency** as the original payment. This package does **not** implement FX, currency conversion, or rounding policies beyond “integer minor units in one currency”.

金额用最小货币单位的非负整数 + 显式 ISO 4217 币种；不默认币种，不做汇率兑换。退款必须与原单同币种，且不得超过剩余可退金额。

### D7 — Domain modules observe status by query, and by an event on the existing outbox bus

`PaymentPort.query` is the source of truth. In addition, status changes that matter to other processes (at least: payment succeeded, refund applied) write a new `event_name` (illustrative: `PaymentStatusChanged`) onto the **existing** `outbox_event` table. Body carries payment id, tenant, status, amounts, and provider reference — not domain prose.

This reuses one bus (ADR 0017 already added `NotificationRequested` to the same table). It must **not** invent a second outbox product, and must **not** go through `TaskMessagePort.submit` (that creates `platform_task` + `TaskRecorded`).

Unlike `NotificationRequested` (an in-process **adapter dispatch** that `OutboxRelay` deliberately skips), a payment status change is a **consumer notice**, in the same role as `TaskRecorded`. Implementation should deliver `PaymentStatusChanged` via the existing `DeliveryPort` path (socket or Kafka), without parsing the body as `TaskRecordedNotice`. Consumers subscribe by `event-name`. `event_body` VARCHAR(4000) is expected to be enough for this notice; if it is not, fail closed or make the smallest safe widen — do not invent a payload-store product.

Domain modules continue their own workflows by querying status and/or consuming the event. This package does **not** automatically transition a thin workflow or send a notification when a payment succeeds (see Open points).

查单是真相源；支付成功、退款入账等状态变化另在已有 `outbox_event` 上写 `PaymentStatusChanged`（名称示意），经现有 `DeliveryPort` 投递给消费者，不另起出箱，不当成 `TaskRecorded`，也不经 `TaskMessagePort.submit`。本包不自动推进工作流或发送通知。

### D8 — Small slices; no WP7 and no vendor lock-in in this package

Implementation is sliced (below). This ADR does not start WP7, does not rewrite the outbox transport, does not rewrite the platform, and does not put hospital, visa, insurance, commission, or tax rules in the kernel.

## Proposed implementation slices / 拟定实现切片

| Slice | Intent | Not in that slice |
| --- | --- | --- |
| **WP6-0** | This ADR + inventory progress note | Code / schema |
| **WP6-1** | `PaymentPort` contract; persist payment / refund / voucher facts; create order + query; tenant fail-closed; amount/currency rules | Vendor or mock callback; applying a refund; production SDKs |
| **WP6-2** | Mock adapter; idempotent payment-result callback; refund including partial; `PaymentStatusChanged` on the existing outbox bus | Commercial WeChat Pay / Alipay SDK as the only path; authorize/capture; settlement |
| **WP6-3** | Formal acceptance: under the mock adapter, create, payment success, partial refund, and a duplicate callback all run; a domain module can continue from document status. Close the WP6 package | WP7 end-user client shell; revenue share / tax; production vendor SDKs |

Production vendor adapters (SPI, no single hardcoded vendor) may land after WP6-3 if a later brief says so.

## Consequences / 后果

- **Positive**: Domain modules get one port for create / callback / refund / query; callbacks are idempotent; vendors stay replaceable; the voucher trail stays a platform-side ledger; observation reuses the outbox already in production.
- **Positive**: Tenant fail-closed and “no domain finance rules in the kernel” stay consistent with earlier ADRs.
- **Negative**: Phase 1 will not auto-advance workflows or send notifications on payment success. There is no operator or end-user checkout UI in this package. Production pay remains fail-closed until a vendor adapter is registered.
- **Negative**: `idempotency_claim` cannot be reused as-is (it is bound to `task_id`); payment keys live on the payment store.

## Out of scope / 非目标（本 ADR 不发明的产品）

- Revenue share, commissions, split-accounting, tax / fapiao, settlement or reconciliation cycles, a general ledger or finance mid-office.
- Hospital catalogs, appointments, visa escort, insurance, or other domain checkout rules in the kernel.
- Authorize-and-capture, FX / multi-currency conversion, payout to a hospital or seller bank account.
- Hardcoding WeChat Pay or Alipay as the only adapter, or adding their SDKs in this package.
- Implementing payment as `TaskMessagePort.submit`, or inventing a second outbox or audit bus.
- Automatic workflow transition or notification fan-out on payment success.
- WP7 end-user client shell deepen.
- Changing the `/api/v1` tenant-filter exemption, or moving `web/` off the operator console.

## Open points not settled from existing docs / 现有文档无法锁死、故不臆造

- Settlement / reconciliation cycles or windows (explicitly not invented here).
- Which WeChat Pay or Alipay product a production adapter should use.
- Payment expiration, caller-initiated cancel, or a refund time window.
- Automatic thin-workflow transition or `NotificationPort` enqueue when a payment succeeds.
- Operator console payment pages, or an end-user pay sheet in `clients/uni-app/` (client triad is already locked; the UI is not part of this ADR).
- Whether create-order must always take a caller idempotency token, or only callbacks/refunds must (callbacks are mandatory; create-order token is recommended and may be required in implementation).
- Signature / webhook authentication details for a specific vendor (a production adapter must verify; the mock may accept a controlled test payload).
- Widening `outbox_event.event_body` if 4000 characters is not enough (unlikely for this notice).

## References / 引用

- [`docs/payment/wp6-payment-port-progress.md`](../payment/wp6-payment-port-progress.md) — WP6-0 inventory
- [`docs/delivery/outbox-event-contract.md`](../delivery/outbox-event-contract.md)
- ADR 0017 / 0014 / 0004 / 0007
