# WP6 payment port progress / 统一支付端口进度

- Date: 2026-11-17 (Asia/Shanghai)
- ADR: [`docs/adr/0018-payment-port.md`](../adr/0018-payment-port.md)
- Baseline tip when this note was opened: `0cc9582` (WP5 closed; formal acceptance `1714208`)
- Predecessor: ADR [`0017`](../adr/0017-notification-port.md) (NotificationPort, mock-first SPI, one outbox bus). Tenant posture: ADR [`0014`](../adr/0014-end-user-identity.md). Outbox contract: [`docs/delivery/outbox-event-contract.md`](../delivery/outbox-event-contract.md)
- 本切片提交：`0ab8160`（`0ab8160ea496426746cd00f57865e79988f1a903`；WP6-3 正式验收并**关闭 WP6 包**）。不启动 WP7（终端用户客户端壳加深）。生产厂商适配器可稍后按已有 SPI 接入，不是关包前提。

This track adds a **unified PaymentPort** (create order, idempotent payment-result callback, refund including partial, query, and a platform-side voucher ledger). It is not a finance mid-office, not revenue share or split-accounting, and not a hospital or other domain checkout module. No medical or other domain finance rules belong in the kernel.

本轨增加统一的支付端口：创单、幂等的支付结果回调、退款（含部分退款）、查单，以及平台侧凭证台账。不是财务中台，不做分润或分账，也不是医院或其他域的结账模块。内核不放医疗或其他域财务规则。

## WP6-3 [done] — formal acceptance; WP6 package closed

This slice locks ADR 0018 acceptance and **closes the WP6 package**. No production hole was found; no production code was added. WP7 (end-user client shell deepen) is not started. Real vendor adapters may follow later via the existing SPI; they are not required to close the package.

本切片锁定 ADR 0018 验收并**关闭 WP6 包**。未发现需改生产代码的缺口，未改生产代码。未启动 WP7（终端用户客户端壳加深）。生产厂商适配器可稍后按已有 SPI 接入，不是关包前提。

Acceptance met / 验收已满足：

- Under the mock adapter, a domain-style caller creates an order, receives a successful payment, makes a partial refund, and replays a duplicate callback without double credit (status and refunded totals stay put; no second success voucher or outbox row).
- A domain module can continue its own flow from document status via `PaymentPort.queryOrder`. `PaymentStatusChanged` is written to the existing `outbox_event` table exactly once per state change (create writes no notice; success and partial refund each write one row).
- Cross-tenant query, callback, and refund are denied (the home tenant's document is unchanged).
- Payment kernel sources (`platform-contract/.../payment`, `platform-app/.../payment`) have no commission, split-accounting, tax, or hospital/medical wording; no second outbox table or migration; no WeChat Pay / Alipay SDK import. Reserved identifiers `WECHAT_PAY` / `ALIPAY` remain names only.

Formal lock: `PaymentPortAcceptanceTest` 3/3. It does not repeat the WP6-1/WP6-2 focused suites.

Regression (this turn, all passed): `PaymentPortAcceptanceTest` 3/3, `PaymentPortCreateQueryTest` 10/10, `PaymentCallbackRefundTest` 10/10, `NotificationPortAcceptanceTest` 4/4, `OutboxRelayTest` 4/4. Total **31/31**. Pre-existing `OperatorBootstrapTest.readerRoleGetsOnlyReadPermissions` was not run and was not fixed.

Residual gaps (honest, not in this package) / 仍存缺口（本包不解决）：

- No production vendor adapter is registered. `WECHAT_PAY` / `ALIPAY` are reserved identifiers only; there is no commercial SDK and no webhook signature verification. Production stays fail-closed until a vendor `PaymentProviderAdapter` bean is registered.
- Payment expiration, caller-initiated cancel, and a refund time window are not implemented.
- No operator console payment pages (`web/`) and no end-user pay sheet in `clients/uni-app/`.
- Settlement / reconciliation cycles or windows remain open, as does which WeChat Pay or Alipay product a later adapter should use.
- This package does not automatically transition a thin workflow or enqueue `NotificationPort` when a payment succeeds.
- Whether create-order must always take a caller idempotency token is still optional (callbacks and token-bearing refunds are mandatory-idempotent).
- `outbox_event.event_body` remains VARCHAR(4000); oversized bodies fail closed (column not widened; no payload-store product).

Next package would be **WP7 (end-user client shell deepen)**, not started here.

## Slice status / 切片状态

- **WP6-0** — ✅ done.
- **WP6-1** — ✅ done.
- **WP6-2** — ✅ done.
- **WP6-3** — ✅ done (this slice). **WP6 package closed.**

## WP6-2 [done] — mock adapter, idempotent callback, partial refund, outbox notice

Mock/dev adapter plus fail-closed production placeholder. Callback and refund apply, including partial refund and idempotent replay. `PaymentStatusChanged` is written on the existing `outbox_event` bus and relayed via `DeliveryPort`. No formal package acceptance (WP6-3). No vendor SDK, no second outbox, no workflow/notification fan-out, no pay UI.

本切片落地模拟/开发适配器，以及生产未配置时的失败关闭占位。回调与退款（含部分退款、幂等回放）已入账。`PaymentStatusChanged` 写在已有 `outbox_event` 上，经 `DeliveryPort` 中继。无正式包验收（WP6-3）。无厂商 SDK、无第二出箱、不自动推进工作流或发送通知、无支付页面。

- SPI: `PaymentProviderAdapter` + identifiers `MOCK` (implemented), `WECHAT_PAY` / `ALIPAY` (names only). Dev/test uses `MockPaymentAdapter`; production without a vendor bean uses `FailClosedPaymentAdapter`.
- `applyPaymentResult` is idempotent on `callback_notification_id` + fingerprint (V52 column). Same payload replays; a conflicting duplicate is rejected; cross-tenant callback is denied. Only a `PENDING` document can be newly credited.
- `refund` (including partial): same currency, amount `> 0`, not exceeding remaining refundable amount; only `SUCCEEDED` or `PARTIALLY_REFUNDED`. Idempotent by refund token. Status becomes `PARTIALLY_REFUNDED` or `REFUNDED`. Voucher facts: `PAYMENT_SUCCEEDED` / `PAYMENT_FAILED` / `REFUND_APPLIED`.
- Status changes write `PaymentStatusChanged` to existing `outbox_event` in the same transaction. `OutboxRelay` now selects `TaskRecorded` and `PaymentStatusChanged` (still skips `NotificationRequested`) and delivers via `DeliveryPort` without parsing the body as `TaskRecordedNotice`.
- Focused tests: `PaymentCallbackRefundTest` 10/10. Regression: `PaymentPortCreateQueryTest` 10/10, `NotificationPortAcceptanceTest` 4/4, `OutboxRelayTest` 4/4.
- SHA：`978bce2`（`978bce288a93c227c4e09d0bae2e6f2fb98d8182`）

## WP6-1 [done] — PaymentPort create + query + voucher tables

Contract, schema, and create/query only. No callback apply, no refund apply, no mock adapter, no `PaymentStatusChanged`.

本切片落地端口契约、表结构与创单/查单。不办理回调、不办理退款、无模拟适配器、不写出箱事件。

- `PaymentPort` in `platform-contract` (`createOrder`, `queryOrder`). `applyPaymentResult` / `refund` are declared and throw `PaymentOperationUnsupportedException` (fail closed, not a no-op).
- Flyway **V51** (`V51__payment_voucher_ledger.sql`): tenant-scoped `payment`, `payment_refund`, and `payment_voucher`. Amounts are non-negative `BIGINT` minor units plus explicit ISO 4217 `CHAR(3)` currency (no default). Refund rows are schema-ready (`amount_minor > 0`); same-currency and “must not exceed remaining refundable amount” are documented service rules for WP6-2, with `payment.refunded_amount_minor <= amount_minor` already checked. Idempotency-key columns live on this aggregate (`create_idempotency_token` / fingerprint, `callback_notification_id`, refund token columns). Does not reuse `idempotency_claim.task_id` or `TaskMessagePort.submit`. No second outbox table.
- Tenant fail-closed reuses the same checks as `JdbcNotificationPort.requireActiveBusinessTenant`: explicit business tenant, never reserved `platform`, missing / unknown / non-ACTIVE denied, cross-tenant query denied.
- Focused tests: `PaymentPortCreateQueryTest` 12/12 (both H2 modes). Notification regression: `NotificationPortAcceptanceTest` 4/4.
- SHA：`9e47729`（`9e477292985c64f1ceb03e0c2505ffca211425ec`）

## WP6-0 [done] — inventory + ADR

## WP6-0 [done] — inventory + ADR

Docs-only. No schema, no port, no adapters.

本切片只写文档，不改表、不实现端口与适配器。

### Inventory / 盘点（事实与路径）

What already exists is a **working tenant model**, a **working audit port**, a **working outbox bus**, and a **task-scoped idempotency table**. There is **no** `PaymentPort`, **no** payment / refund / voucher / ledger table or money type, and **no** WeChat Pay or Alipay product client.

已有的是可用的租户模型、审计端口、出箱总线，以及绑定任务的幂等表。没有 PaymentPort，没有支付/退款/凭证表或金额类型，也没有微信支付/支付宝的产品客户端。

| Area | What exists | Paths |
| --- | --- | --- |
| Payment / billing product | **Nothing.** No `PaymentPort`, no Java type named Payment / Money / Billing / Refund / Voucher / Ledger / Wallet, no Flyway table whose name or columns describe payment, refund, voucher, ledger, wallet, invoice, payout, commission, Alipay, or WeChat Pay. `rg` over `platform-contract` and `platform-app` main sources finds **no** `BigDecimal` or `java.util.Currency` usage. | — |
| Tenant quota (not money) | Daily submit count and storage row count (hard caps, HTTP 429). Not a currency ledger. | `docs/release/tenant-quota.md`; `V20__form_submission_tenant_quota.sql`; `V36__tenant_quota_override.sql`; `TenantQuotaService` |
| WeChat / Alipay in the kernel | WeChat is **login / IdP only** (`code2Session` / `redeemOpenCode`). No pay/unified-order API. No Alipay SDK or client. | `platform-app/.../idp/WeChatApiClient.java`, `HttpWeChatApiClient.java`; `.../identity/enduser/EndUserWeChatAuthService.java`; ADR 0014 / 0017 |
| HTTP “callback” today | Operator OIDC and end-user / IdP WeChat **login** callbacks. Not payment notify webhooks. | `OperatorOidcEndpoint`; `IdpProvidersEndpoint`; `EndUserWeChatAuthEndpoint`; `IdpCallbackRequest` |
| Outbox bus | Flyway **V1** `outbox_event` (`event_body` VARCHAR **4000**) + `dead_letter`. Living events: `TaskRecorded` (relayed via `DeliveryPort` / `OutboxRelay`) and `NotificationRequested` (in-process `NotificationDispatcher`; relay skips this name). Must not invent a second outbox product. Must not use `TaskMessagePort.submit` for payments (that writes `platform_task` + `TaskRecorded`). | `V1__platform_schema.sql`; `V42__dead_letter_discarded_at.sql`; `docs/delivery/outbox-event-contract.md`; `platform-contract/.../task/OutboxEvent.java`; `NotificationRequested.java`; `JdbcTaskMessagePort.java`; `OutboxRelay.java`; ADR 0017 |
| Idempotency | `IdempotencyPort` / `idempotency_claim` (PK `tenant_id` + `idempotency_token`, fingerprint, **`task_id`**, `claimed_at`). Used by task submit. Javadoc says there is one port; the **schema is task-bound**. Payment callback idempotency should reuse the *pattern*, not overload `task_id`. | `V1__platform_schema.sql`; `platform-contract/.../idempotency/IdempotencyPort.java`, `IdempotencyClaim.java`; `JdbcIdempotencyPort.java` |
| Audit | Single `AuditPort` / `audit_entry`. Do not add a second audit channel. Future payment actions can record here (implementation). | `platform-contract/.../audit/AuditPort.java`; ADR 0004 |
| Tenant fail-closed | Explicit business `tenantId`; reserved `platform` is the operator home, never a default business tenant. Missing / unknown / non-ACTIVE (`SUSPENDED`) fail closed. Cross-tenant writes denied (WP1 / ADR 0014; same posture on `NotificationPort`). | ADR 0014; `TenantGuard`, `TenantMissingException`, `TenantState`; `JdbcNotificationPort.requireActiveBusinessTenant`; `JdbcTenantAdmin` |
| Notification SPI (pattern to copy, not to merge) | `NotificationPort` + `NotificationChannelAdapter` (mock/dev first; prod fail-closed until a vendor bean is registered). Payments are a different port. | ADR 0017; `platform-contract/.../notification/` |
| Client triad | `web/` is the operator console; end-user clients are `clients/uni-app/`. No payment UI exists. This package does not add one. | ADR 0007 |
| Current Flyway tip | Last script **V50** (`V50__object_grant.sql`). No payment migration yet. | `platform-app/src/main/resources/db/migration/` |

## Proposed later slices / 后续切片（本切片不实现）

See ADR 0018. Sketch:

- **WP6-1** — [done, `9e47729`] `PaymentPort` contract + persist payment / refund / voucher facts; create order + query; tenant fail-closed; amount/currency rules. No callback or refund apply yet.
- **WP6-2** — [done, `978bce2`] mock adapter; idempotent payment-result callback; refund including partial; `PaymentStatusChanged` on the existing `outbox_event` bus.
- **WP6-3** — [done, `0ab8160`] formal acceptance: create, payment success, partial refund, and a duplicate callback all run under the mock adapter; a domain module can continue from document status. **WP6 package closed.**
- Production vendor adapters (SPI, no single hardcoded WeChat Pay or Alipay) may follow after acceptance; they are not required to close the package.

Do **not** start WP7 (end-user client shell deepen) in those slices unless a later brief says so.

## Open points not settled from existing docs / 现有文档无法锁死、故不臆造

- Settlement / reconciliation cycles or windows.
- Which WeChat Pay or Alipay product a production adapter should use.
- Payment expiration, caller-initiated cancel, or a refund time window.
- Automatic thin-workflow transition or `NotificationPort` enqueue when a payment succeeds.
- Operator console payment pages, or an end-user pay sheet in uni-app.
- Whether create-order must always take a caller idempotency token (callbacks are mandatory-idempotent).
- Vendor webhook signature details (a production adapter must verify; not locked to one vendor scheme).
- Widening `event_body` if 4000 characters is not enough.

## SHA

- WP6-3：`0ab8160` (`0ab8160ea496426746cd00f57865e79988f1a903`)
- WP6-2：`978bce2` (`978bce288a93c227c4e09d0bae2e6f2fb98d8182`)
- WP6-1：`9e47729` (`9e477292985c64f1ceb03e0c2505ffca211425ec`)
- WP6-0：`fd18d81` (`fd18d81204e29693ef69fce42687d3212ffe59f6`)
