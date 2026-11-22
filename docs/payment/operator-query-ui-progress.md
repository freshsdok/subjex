# 操作员通知投递与支付状态查询页进度 / Operator notification and payment query UI

- 日期：2026-11-22（Asia/Shanghai）
- 基线：GitHub `main` `8c19a7c`；本地 tip 开工于 `0ef8276`
- 依据：ADR [`0017`](../adr/0017-notification-port.md)、[`0018`](../adr/0018-payment-port.md)、[`wp5-notification-port-progress.md`](../messaging/wp5-notification-port-progress.md)、[`wp6-payment-port-progress.md`](wp6-payment-port-progress.md)
- 范围：只补操作员只读可见性，接到已有 NotificationPort / PaymentPort 与台账。不改医院/预约/医疗/签证/保险，不做完整结账页，不接入真实短信/微信/支付厂商（继续使用模拟与失败关闭适配器）。

本笔记盘点已有端口、表与权限，并记下控制台查询页计划。WP5 / WP6 关包时明确未做操作员页面，也几乎没有查询用 HTTP。

## 盘点 / Inventory（2026-11-22）

### WP5 通知端口（复用，不重设计）

| 能力 | 路径与实现 | 权限 / 门禁 | 界面与查询现状 |
| --- | --- | --- | --- |
| 入队 | `NotificationPort.enqueue` → 在已有 `outbox_event` 写入 `NotificationRequested`（模板标识 + 参数 + 渠道 + 收件人） | 端口内租户失败关闭；保留租户 `platform` 拒绝 | 无 HTTP 入队接口（有意为之：由域模块调用） |
| 调度 / 重试 / 死信 | `NotificationDispatcher.dispatchPending` / `dispatchOne` / `replayDeadLetter`；`NotificationDispatchWorker` | 模拟适配器记录到内存 `NotificationDispatchRecord`；生产未配置则失败关闭并进死信 | 内存记录**不是**持久台账，不能当查询源 |
| 持久投递轨迹 | `outbox_event`（`event_name = NotificationRequested`，含 `event_state`、`attempt_count`、`failure_reason`、`published_at`）；失败后 `dead_letter` | `notification.read`（第 3–4 刀） | 操作员只读 HTTP 与控制台页已落地：`GET /api/v1/notification-sends`、`GET /api/v1/notification-sends/{eventId}`（投影出箱行，可关联未丢弃死信）；控制台为「通知发送记录」列表与详情（第 4 刀） |
| 死信列表与重放 | `GET /api/v1/dead-letters`，`POST .../{id}/replay`（`DeadLetterApiEndpoint`） | `admin.read`；`NotificationRequested` 经调度器重放，不走 `DeliveryPort` | 控制台已有死信页；只覆盖失败后的信，不是完整发送记录 |
| 渠道适配器 | `MockNotificationAdapter` / `FailClosedNotificationAdapter`；标识含短信、邮件、微信模板 | 无商业 SDK | 本项不接入真实厂商 |

结论（开工时）：当时没有操作员「发送记录」列表/详情 HTTP，也没有 `notification.read`。第 3–4 刀已补只读 API 与控制台「通知发送记录」页：投影已有 `outbox_event`（及未丢弃死信），不另起投递表，也不把内存模拟记录当成真相源。

### WP6 支付端口（复用，不重设计）

| 能力 | 路径与实现 | 权限 / 门禁 | 界面与查询现状 |
| --- | --- | --- | --- |
| 创单 / 查单 / 回调 / 退款 | `PaymentPort.createOrder`、`queryOrder`、`applyPaymentResult`、`refund`（`JdbcPaymentPort`） | 租户失败关闭；跨租户拒绝；回调与退款幂等 | 操作员只读 HTTP 已落地：`GET /api/v1/payments`、`GET /api/v1/payments/{id}`（第 1 刀）。`queryOrder` 仍是按单号查一张单据的端口方法 |
| 台账表 | Flyway **V51/V52**：`payment`、`payment_refund`、`payment_voucher` | 金额为非负最小货币单位 + 显式 ISO 4217 币种 | 状态、已退金额、退款行已可查询；操作员列表 API 与控制台页已落地（第 1–2 刀） |
| 单据状态 | `PENDING` / `SUCCEEDED` / `FAILED` / `PARTIALLY_REFUNDED` / `REFUNDED` | — | 适合只读状态页，不做结账或操作员代退款 |
| 适配器 | `MockPaymentAdapter` / `FailClosedPaymentAdapter`；`WECHAT_PAY` / `ALIPAY` 仅为预留名 | 生产未注册厂商 Bean 则失败关闭 | 本项不接入真实厂商，不验签 webhook |
| 出箱 | 状态变更写已有 `outbox_event` 的 `PaymentStatusChanged` | — | 不是操作员查单入口 |

结论（开工时）：当时没有操作员支付/退款列表 HTTP，也没有 `payment.read`。第 1–2 刀已补只读列表、按单号查看与控制台页，复用 `queryOrder` 与同一张台账，不把 `createOrder` / `refund` / 回调暴露给控制台。

### 易混淆的既有能力（不要挪用）

- 站内信（ADR 0008/0009）：`/api/v1/inbox/**`，权限 `inbox.read` 等——内部信箱，不是外部短信/邮件/微信模板投递。
- 审计 NDJSON 导出、租户 ZIP 导出、问卷 CSV、个人资料导出队列——与支付/通知投递无关。
- 终端 uni-app：本项先做操作员控制台；若以后做薄的「我的支付 / 我的通知」再另开切片。

## 本切片已落地（第 1 刀）

1. 新增操作员只读查询：`GET /api/v1/payments`（当前业务租户内列表，可按状态、付款人筛选，按时间从新到旧），`GET /api/v1/payments/{paymentId}`（复用 `PaymentPort.queryOrder`，含退款行与已退金额）。无创单、回调、退款或结账路由。
2. 新增权限 `payment.read`，授予 `platform-operator` 与 `platform-reader`。须有租户授权；保留租户 `platform`、缺租户、跨租户一律拒绝。
3. 控制台支付/退款列表页、通知发送记录查询 API 与页面：当时尚未开始。

### 本切片已落地（第 2 刀）

1. 控制台新增「支付与退款状态」页（`/payments`）及详情页（`/payments/{paymentId}`）：按租户列出状态、金额、已退金额、付款人和时间，可按状态与付款人筛选；点进一条查看退款明细。金额按币种小数位展示，不用原始最小单位。
2. 页面只读，没有收款、确认到账或办理退款的按钮。进入走 Manifest `nav.payments` / `page.payments`（`payment.read`）。复用 `GET /api/v1/payments` 与 `GET /api/v1/payments/{paymentId}`。
3. 通知发送记录查询 API 与页面：当时尚未开始。

### 本切片已落地（第 3 刀）

1. 新增操作员只读查询：`GET /api/v1/notification-sends`（当前业务租户内列表，可按状态、渠道、收件人和时间窗筛选，按时间从新到旧），`GET /api/v1/notification-sends/{eventId}`（投影已有 `NotificationRequested` 出箱行；如有未丢弃死信则带上 `deadLetterId`）。无发送、重试或重放路由；重放仍走已有死信接口与控制台死信页。
2. 新增权限 `notification.read`，授予 `platform-operator` 与 `platform-reader`。须有租户授权；保留租户 `platform`、缺租户、跨租户一律拒绝。非 `NotificationRequested` 出箱行按不存在处理（404），避免泄露其它事件。
3. 控制台「通知发送记录」页：当时尚未开始。

### 本切片已落地（第 4 刀）

1. 控制台新增「通知发送记录」页（`/notification-sends`）及详情页（`/notification-sends/{eventId}`）：按租户列出模板、渠道、收件人、状态、尝试次数和时间；可按状态、渠道、收件人和时间窗筛选；点进一条查看失败说明与模板参数。页面标题不使用「出箱」等内部名。
2. 页面只读，没有发送、重试或重放按钮。若记录已关联未丢弃死信，只提供通往现有死信页的链接，重放仍在那里办理。进入走 Manifest `nav.notification-sends` / `page.notification-sends`（`notification.read`）。复用 `GET /api/v1/notification-sends` 与 `GET /api/v1/notification-sends/{eventId}`。
3. Manifest 由 59 条增至 61 条（24 个菜单增至 25 个）。至此，本项的支付/退款状态查询与通知发送记录查询（API + 控制台页）均已在本地落地。

## 界面计划 / UI plan

### 控制台（操作员，`web/`）

- **支付与退款状态（第 2 刀，已落地）**：在当前业务租户内列出单据；展示状态、金额与币种、已退金额、付款人、时间；可按状态与付款人筛选；点进一条看退款明细。只读。不能代为收款、退款或发起支付。菜单与页面门禁为 `payment.read`。
- **通知发送记录（第 4 刀，已落地）**：按租户列出发送记录（模板、渠道、收件人、状态、尝试次数、时间）；失败的可看到已有死信关联并链到现有死信页，查询页没有发送、重试或重放按钮。只读查询 API 与 `notification.read` 已在第 3 刀落地；控制台页标题为「通知发送记录」/“Notification send records”。

### uni-app（终端用户）

本项先不做。若之后证明「我的支付 / 我的通知」对终端有明显价值，再另开薄页，不另起第三套前端。

导航与构建：控制台沿用现有 `web/` 与 Manifest 门禁。不接入真实厂商，不改 `NamingGuardArchTest`。
