# 可插拔真实渠道进度 / Pluggable channel adapters

- 日期：2026-11-22（Asia/Shanghai）
- 基线：本地 tip 开工于 `5d16e35c0c5ec18facef26a2abf1d2a17da9d030`（终端壳 tabBar）；GitHub `main` 仍为 `8c19a7c`
- 依据：约定「把模拟实现换成可插拔的真实渠道—短信验证码供应商（现在只有开发用和默认拒绝两种发送器）；通知渠道（短信、微信或邮件，走现有出箱投递和重试）；支付与退款渠道（可配置、默认为失败闭环，等商户号到位再打开）。这三项都可以先把配置、密钥、审计和失败行为做完整，账号后补。」
- 范围：平台内核渠道适配器（SPI/端口、失败关闭默认、配置与密钥占位、发送/扣费审计）。不接入真实厂商账号或商户号，测试不呼叫真实厂商网络。不改医院/预约/医疗/签证/保险，不做完整结账页。`NamingGuardArchTest` 保持不动。

本笔记先盘点验证码、通知、支付三类适配器的接线，再记下本刀已落地的短信验证码切片。通知与支付适配器不在本刀提交中开工。

## 盘点 / Inventory（2026-11-22）

硬规则：默认失败关闭；缺配或不完整的厂商配置不得在生产静默回退到「看起来已经发出或已扣费」的模拟实现；是否启用真实外呼必须显式配置；只保留一种厂商形模板，不当成厂商市场。

### 短信验证码 / SMS OTP（本刀）

| 项 | 开工时现状 |
| --- | --- |
| SPI | 已有干净的 `VerificationCodeSender`（`configured()` + `send`），无内置商业厂商。`VerificationCodePort` / `MemoryVerificationCodePort` 在未配置时抛 `VerificationCodeSenderUnconfiguredException`，验证码只存 SHA-256。 |
| 实现 | `FailClosedVerificationCodeSender`（`configured()=false`，发送即抛错）；`DevVerificationCodeSender`（`configured()=true`，空投递，经 peek/reveal 取码，不打日志）。 |
| 接线 | `PlatformWiring.endUserVerificationCodeSender` 按 profile **写死**：生产一律失败关闭，非生产一律开发发送器。没有发送器选择键，后续即使加了厂商类也无法靠配置启用。 |
| 配置 | `platform.end-user.auth.verification-code.ttl`（默认 PT5M）、`reveal-in-response`（基础/生产 `false`；仅 `application-local.yml` 为 `true`）。生产若打开出示会启动失败。 |
| 密钥 | 无厂商端点或 API 密钥占位。 |
| 审计 | `EndUserPhoneAuthService.requestCode` 不写审计；验证码不得出现在日志中（开发发送器也遵守）。 |
| 测试 | `EndUserPhoneAuthTest` 已覆盖开发发码/校验/过期，以及失败关闭不留码。 |

用途：`phone-login` / `phone-bind` / `phone-unbind`。入口：`EndUserPhoneAuthEndpoint` `POST .../request-code`（未配置 → HTTP 503，`reason=verification-code-sender-unconfigured`）。与 NotificationPort **不合并**（ADR 0017：验证码路径不得被削弱）。

### 通知渠道 / Notifications（后续切片，本刀不动）

| 项 | 现状 |
| --- | --- |
| SPI | `NotificationChannelAdapter`（渠道 + `configured()` + `send`）。渠道：`SMS`、`EMAIL`、`WECHAT_TEMPLATE`。 |
| 实现 | 每渠道 `MockNotificationAdapter`（写入 `RecordingNotificationSink`，不外呼）或 `FailClosedNotificationAdapter`（抛 `NotificationAdapterUnconfiguredException`）。 |
| 接线 | `PlatformWiring.notificationDispatcher`：先收 `ObjectProvider<NotificationChannelAdapter>`（SPI 可插入），缺渠道时生产失败关闭、非生产用模拟。 |
| 出箱 | WP5 已有 `NotificationRequested` 出箱、后台调度、重试与死信。调度配置：`platform.notification.dispatch-interval-ms` / `dispatch-batch-size`。投递走进程内适配器，不经 `DeliveryPort`。 |
| 密钥 | 无短信/微信/邮件厂商端点或密钥。`platform.delivery.hmac-secret` 是出箱 HMAC，不是通知厂商密钥。 |
| 审计 | 调度失败记在出箱行（可供操作员发送记录查询/死信页），没有把「一次渠道投递尝试」写成 `AuditPort` 条目。 |
| 控制台 | 只读发送记录 API 与页面已有（`bf054c99`、`800d23bb`）。本项不新做发送/重试按钮。 |

### 支付与退款 / Payments（后续切片，本刀不动）

| 项 | 现状 |
| --- | --- |
| SPI | `PaymentProviderAdapter`。`PaymentProviderIds.MOCK` 是现用标识；`WECHAT_PAY` / `ALIPAY` **仅预留名称**，无 SDK、无产品形态。 |
| 实现 | `MockPaymentAdapter`（`RecordingPaymentSink`）与 `FailClosedPaymentAdapter`（抛 `PaymentAdapterUnconfiguredException`，绝不把支付写成成功）。 |
| 接线 | `PlatformWiring.paymentPort`：先收 `ObjectProvider<PaymentProviderAdapter>`；`MOCK` 在生产失败关闭，非生产用模拟。 |
| 配置 / 密钥 | 无 `platform.payment` 厂商选择键，无商户号或密钥占位。 |
| 审计 | 状态在支付单据与操作员只读查询中；没有把「一次扣费/退款尝试」写成 `AuditPort` 条目。 |
| 控制台 | 只读支付与退款状态 API/页已有。不做完整结账 UI，不等待商户号到位也不打开真实扣费。 |

## 本刀已落地 / This slice（短信验证码）

1. 进度笔记（本文件）：如实盘点三类渠道。
2. 显式发送器选择：`platform.end-user.auth.verification-code.sender`（`fail-closed` / `dev` / `http`）。基础配置默认为空：生产失败关闭，非生产仍用开发发送器（保持现有本机/测试路径）。`application-local.yml` 显式 `sender: dev` 且继续 `reveal-in-response: true`。生产禁止 `sender=dev` 或出示验证码（启动失败）。
3. 厂商形模板：`HttpVerificationCodeSender` + `VerificationCodeHttpClient`（通用 HTTP POST JSON，不绑定某一商业厂商）。密钥占位：`http.base-url` / `http.api-key` / `http.from`。仅当 `sender=http` 且端点与密钥配齐（非空、非 `change-me`、合法 http(s) URL）时才选中模板。缺配、占位密钥或没有传输实现 → 失败关闭，**绝不**回退到开发发送器。仅存在 JDK 传输类不会启用外呼。
4. 发送尝试审计：`end-user.verification-code.send`。未认证请求没有业务租户，审计记在平台级归属 `platform`（与操作员登录审计相同，不是业务写入默认落到保留租户）。`action_target` 为 `purpose:` + 遮蔽后的目的地（只留末 4 位），**不含验证码、不含完整手机号**。结果：`ALLOWED` / 未配置 `REFUSED` / 投递失败 `FAILED`。投递失败映射 HTTP 503，`reason=verification-code-send-failed`；异常消息不得带出验证码或密钥。
5. 测试：工厂选择（生产默认失败关闭、禁止 dev、http 缺配不回退、配齐才选模板）；HTTP 模板经测试替身投递（不联网），失败不泄漏验证码/密钥；`EndUserPhoneAuthTest` 覆盖未配置 503 + 拒绝审计、替身成功/失败与审计不含验证码。`ProductionConfigChecklistTest` 锁定生产保持空 sender / 不出示，local 为 `dev`，默认 k8s 清单不启用开发发送器、不写入 API 密钥。

## 下一刀 / Next slice

通知渠道适配器（短信、微信或邮件）：在现有出箱、重试与死信之上，补齐显式渠道选择、密钥占位、失败关闭默认与投递尝试审计（仍不接入真实厂商账号；测试不联网）。不要在本刀提交里开始这项。

支付与退款渠道再往后一刀：可配置、默认失败关闭，等商户号到位再打开；同样先做配置、密钥占位、审计与失败行为。

## SHA

本刀提交后填写。
