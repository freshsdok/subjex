# 可插拔真实渠道进度 / Pluggable channel adapters

- 日期：2026-11-22（Asia/Shanghai）
- 基线：本地 tip 开工于 `5d16e35c`（终端壳 tabBar）；GitHub `main` 仍为 `8c19a7c`
- 依据：约定「把模拟实现换成可插拔的真实渠道—短信验证码供应商（现在只有开发用和默认拒绝两种发送器）；通知渠道（短信、微信或邮件，走现有出箱投递和重试）；支付与退款渠道（可配置、默认为失败闭环，等商户号到位再打开）。这三项都可以先把配置、密钥、审计和失败行为做完整，账号后补。」
- 范围：平台内核渠道适配器（SPI/端口、失败关闭默认、配置与密钥占位、发送/扣费审计）。不接入真实厂商账号或商户号，测试不呼叫真实厂商网络。不改医院/预约/医疗/签证/保险，不做完整结账页。`NamingGuardArchTest` 保持不动。

本笔记盘点验证码、通知、支付三类适配器的接线，并记下已落地的三刀。约定清单第 4 项在本地闭合后，不要在本提交里开始 WP9-2/WP9-3 或边缘落地。

硬规则：默认失败关闭；缺配或不完整的厂商配置不得在生产静默回退到「看起来已经发出或已扣费」的模拟实现；是否启用真实外呼必须显式配置；只保留一种厂商形模板，不当成厂商市场。仅存在客户端类不会启用真实厂商。

## 盘点 / Inventory

### 短信验证码 / SMS OTP（第 1 刀已落地）

见提交 `5453143ad35baee081ebb7c7dadc1091e176b722`。

- SPI：`VerificationCodeSender`。选择键 `platform.end-user.auth.verification-code.sender`（`fail-closed` / `dev` / `http`）。基础配置默认为空：生产失败关闭，非生产用开发发送器。`application-local.yml` 显式 `sender: dev`。生产禁止 `sender=dev` 或出示验证码。
- 厂商形模板：`HttpVerificationCodeSender` + `VerificationCodeHttpClient`。密钥占位 `http.base-url` / `http.api-key` / `http.from`。仅当 `sender=http` 且配齐时启用；缺配失败关闭，不回退到开发发送器。
- 审计：`end-user.verification-code.send`；`action_target` 为用途 + 遮蔽目的地，不含验证码或完整手机号。

### 通知渠道 / Notifications（本刀）

| 项 | 本刀开工时现状 |
| --- | --- |
| SPI | `NotificationChannelAdapter`（渠道 + `configured()` + `send`）。渠道：`SMS`、`EMAIL`、`WECHAT_TEMPLATE`。 |
| 实现 | 每渠道 `MockNotificationAdapter`（写入 `RecordingNotificationSink`，不外呼）或 `FailClosedNotificationAdapter`（抛 `NotificationAdapterUnconfiguredException`）。 |
| 接线 | `PlatformWiring.notificationDispatcher` 按 profile 补齐：生产失败关闭，非生产模拟；并可用 `ObjectProvider` 自动换成已注册的 Bean（仅存在类就可能启用）。 |
| 出箱 | WP5 已有 `NotificationRequested` 出箱、后台调度、重试与死信。调度配置：`platform.notification.dispatch-interval-ms` / `dispatch-batch-size`。投递走进程内适配器，不经 `DeliveryPort`。 |
| 密钥 | 无短信/微信/邮件厂商端点或密钥。`platform.delivery.hmac-secret` 是出箱 HMAC，不是通知厂商密钥。 |
| 审计 | 调度失败记在出箱行（可供操作员发送记录查询/死信页），没有把「一次渠道投递尝试」写成 `AuditPort` 条目。 |
| 控制台 | 只读发送记录 API 与页面已有（`bf054c99`、`800d23bb`）。本项不新做发送/重试按钮。 |

本刀不另建投递管道，继续走现有出箱、重试与死信。

### 支付与退款 / Payments（本刀开工时现状）

| 项 | 本刀开工时现状 |
| --- | --- |
| SPI | `PaymentProviderAdapter`。`PaymentProviderIds.MOCK` 是现用标识；`WECHAT_PAY` / `ALIPAY` **仅预留名称**，无 SDK、无产品形态。 |
| 实现 | `MockPaymentAdapter`（`RecordingPaymentSink`）与 `FailClosedPaymentAdapter`（抛 `PaymentAdapterUnconfiguredException`，绝不把支付写成成功）。 |
| 接线 | `PlatformWiring.paymentPort`：先收 `ObjectProvider<PaymentProviderAdapter>`；`MOCK` 在生产失败关闭，非生产用模拟。 |
| 配置 / 密钥 | 无 `platform.payment` 厂商选择键，无商户号或密钥占位。 |
| 审计 | 状态在支付单据与操作员只读查询中；没有把「一次扣费/退款尝试」写成 `AuditPort` 条目。 |
| 控制台 | 只读支付与退款状态 API/页已有。不做完整结账 UI，不等待商户号到位也不打开真实扣费。 |

## 已落地 / Landed slices

### 第 1 刀：短信验证码 / SMS OTP — `5453143ad35baee081ebb7c7dadc1091e176b722`

显式发送器选择、失败关闭默认、一种厂商形 HTTP 模板、密钥占位、发送尝试审计（不含验证码）。详见上一节。

### 第 2 刀：通知渠道 / Notification channels（本刀）

1. 显式按渠道选择：`platform.notification.{sms,email,wechat-template}.adapter`（`fail-closed` / `mock` / `http`）。基础配置默认为空：生产失败关闭，非生产仍用模拟适配器（保持现有本机/测试路径）。`application-local.yml` 三渠道显式 `adapter: mock`。生产禁止 `adapter=mock`（或 `dev`），启动失败。不再仅因注册了适配器 Bean 就自动启用（去掉 `ObjectProvider` 静默替换）。
2. 一种厂商形模板服务全部渠道：`HttpNotificationChannelAdapter` + `NotificationHttpClient`（通用 HTTP POST JSON，不绑定某一商业厂商）。各渠道密钥占位：`http.base-url` / `http.api-key` / `http.from`。仅当该渠道 `adapter=http` 且端点与密钥配齐（非空、非 `change-me`、合法 http(s) URL）时才选中模板。缺配、占位密钥或没有传输实现 → 失败关闭，**绝不**回退到模拟适配器。仅存在 `JdkNotificationHttpClient` 不会启用外呼。尚未单独定制微信/邮件报文，后续若需要可在同一配置形状上加映射，不必再引入厂商市场。
3. 投递尝试审计：`notification.channel.send`，记在发信业务租户上，操作者为 `notification-dispatcher`。`action_target` 为 `渠道:模板:` + 遮蔽后的目的地或 `subject:` + 遮蔽主体（手机只留末 4 位，邮件只留域名），**不含请求体、模板参数、完整手机号或 API 密钥**。结果：投递成功 `ALLOWED` / 未配置或校验拒绝 `REFUSED` / 投递或解析失败 `FAILED`。HTTP 投递失败原因稳定为 `notification-channel-send-failed`；异常消息不得带出请求体或密钥。出箱重试与死信路径不变；每一次尝试都写审计。
4. 测试：工厂选择（生产默认三渠道失败关闭、禁止 mock、http 缺配不回退、配齐才选模板）；HTTP 模板经测试替身投递（不联网），失败不泄漏请求体/密钥；目的地遮蔽；`NotificationDispatchTest` 覆盖未配置进入死信 + 拒绝审计、模拟成功审计不含完整目的地或参数、HTTP 替身成功投递与审计、local 下不完整 http 失败关闭且不走模拟槽。`ProductionConfigChecklistTest` 锁定生产保持空 adapter / 不写密钥，local 为 `mock`，默认 k8s 清单不启用模拟适配器、不写入 API 密钥。

### 第 3 刀：支付与退款 / Payment and refund adapters（本刀）

1. 显式按供应商标识选择：`platform.payment.{mock,wechat-pay,alipay}.adapter`（`fail-closed` / `mock` / `http`）。基础配置默认为空：生产三标识均失败关闭。非生产仅对 `MOCK` 使用模拟适配器；预留的 `WECHAT_PAY` / `ALIPAY` 即使在本机也不会挂模拟适配器（避免「看起来已经按微信支付/支付宝扣费」）。`application-local.yml` 仅显式 `payment.mock.adapter: mock`。生产禁止 `adapter=mock`（或 `dev`），启动失败。去掉 `ObjectProvider` 静默替换，仅存在客户端类不会启用真实厂商。
2. 一种厂商形模板服务全部已知标识：`HttpPaymentAdapter` + `PaymentHttpClient`（通用 HTTP POST JSON）。这不是微信支付或支付宝 SDK，只是把预留名称接到同一模板上。密钥/商户占位：`http.base-url` / `http.api-key` / `http.merchant-id`。仅当该标识 `adapter=http` 且端点、密钥与商户号配齐（非空、非 `change-me`、合法 http(s) URL）时才选中模板。缺配、占位或没有传输实现 → 失败关闭，**绝不**回退到模拟适配器。仅存在 `JdkPaymentHttpClient` 不会启用外呼。
3. 扣费/退款尝试审计：`payment.charge` 与 `payment.refund`，记在发单业务租户上，操作者为 `payment-port`。`action_target` 为 `供应商:charge|refund:` + 遮蔽后的支付单标识（只留末 4 位），**不含请求体、完整支付单号、通知标识、商户号或 API 密钥**。成功 `ALLOWED` 与入账同一事务；未配置或跨租户 `REFUSED`、HTTP 投递失败 `FAILED` 在支付事务回滚之后另写，避免审计一起被回滚。HTTP 失败原因稳定为 `payment-adapter-send-failed`。创单仍允许在适配器未配置时落下 PENDING 单据（现有行为）；真正入账/退款必须已配置。支付单据、退款台账、出箱 `PaymentStatusChanged`、操作员只读查询与不得自行批准的既有规则保持不变。不做结账 UI 或终端支付页。
4. 测试：工厂选择（生产默认三标识失败关闭、禁止 mock、http 缺配不回退、配齐才选模板；本机空配置不会把预留标识挂成模拟）；HTTP 模板经测试替身扣费/退款（不联网），失败不泄漏请求体/密钥/商户号；目的地遮蔽；`PaymentCallbackRefundTest` 覆盖未配置回调失败关闭 + 拒绝审计、模拟成功扣费/退款审计不含完整标识、HTTP 替身成功扣费与退款、local 下不完整 http 失败关闭且单据保持 PENDING。`ProductionConfigChecklistTest` 锁定生产保持空 adapter / 不写密钥与商户号，local 仅 `mock.adapter=mock`，默认 k8s 清单不启用模拟适配器、不写入 API 密钥或商户号。

## 关包 / Close-out

约定清单第 4 项（把模拟实现换成可插拔的真实渠道：短信验证码、通知渠道、支付与退款）在本地已闭合。默认失败关闭，账号/商户号后补；测试不呼叫真实厂商或支付网络。未推送。不要在本提交里开始 WP9-2/WP9-3 或边缘落地。

## SHA

- 第 1 刀（短信验证码）：`5453143ad35baee081ebb7c7dadc1091e176b722`
- 第 2 刀（通知渠道）：`42872c24276e20a42997ec90d8c0ec9e22db3fb9`
- 第 3 刀（支付与退款）：见本提交
