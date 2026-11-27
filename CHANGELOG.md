# Changelog / 变更记录

Format loosely follows [Keep a Changelog](https://keepachangelog.com/). Maven coordinates remain `0.1.0-SNAPSHOT` until a stable cut; first public tag is **`v0.1.0-alpha.1`**.
格式大致参照 Keep a Changelog。Maven 坐标仍为 `0.1.0-SNAPSHOT`；首个公开 tag 为 **`v0.1.0-alpha.1`**。

## [Unreleased]

Post-alpha.1 work. Tree may move past the tagged tip (`e7dc268`); recent tip lineage includes PC-deepen-2/3 (local) on `49172c3`+.

### Added / 新增
- **Retire leftover `/api/v1/me/inbox`:** The working end-user mailbox remains `/api/v1/end-user/inbox/**`. The old path now returns HTTP 410 (`reason=inbox-path-retired`, `use=/api/v1/end-user/inbox`) for any authenticated caller (operator or end-user). Anonymous callers still get 401. This is not a second working inbox and not a silent redirect. Uni-app already called the new path. Operator `/api/v1/inbox/**` is unchanged.
  **退役残留的 `/api/v1/me/inbox`：** 终端收件箱仍走 `/api/v1/end-user/inbox/**`。旧路径对已登录调用方一律返回 410，不再提供第二套可用收件接口。未登录仍为 401。操作员 `/api/v1/inbox/**` 不变。

- **Async personal-data export and keyset volume increments:** After approval, generation is enqueued automatically (`APPROVED` → `GENERATING`). A thin `@Scheduled` `ExportGenerationWorker` (same style as `PaymentExpiryWorker`) streams the artifact to object storage and completes to `READY` or `FAILED`. `POST .../generate` only enqueues or retries a failure; it does not write the artifact on the request thread. Operator `/exports` and uni-app 「我的导出」 show generating/failed honestly; retry is optional for `FAILED`. Lists use tenant-scoped keyset pages (`{exports, nextCursor}`). Flyway V66 adds composite indexes. Artifact TTL remains 7 days on `READY`. Self-approval remains forbidden. Questionnaire CSV and tenant ZIP are unchanged. No Elasticsearch and no new job platform.
  **个人资料导出改为后台生成，并补上键集分页：** 批准后自动入队；薄定时工作线程流式写入对象存储；失败可重试。列表改为租户内键集分页。产物仍 7 天后过期。禁止自己批准自己。未改问卷 CSV 与租户 ZIP，不上 Elasticsearch，不另做任务平台。

- **Rename exporter SPI for the naming guard:** `PersonalDataExporter` / `GenericPlatformPersonalDataExporter` are now `SubjectRecordExporter` / `GenericSubjectRecordExporter` so the class-name token `data` is not a standalone fragment (NamingGuardArchTest). No behavior change, no allowlist, no guard weakening.
  **导出 SPI 更名以通过命名护栏：** 独立词段 `data` 不得出现在类名中。行为不变，不放宽护栏。

- **WP9-1 audit export and NDJSON SIEM sink:** `POST /api/v1/audit/export` streams tenant-scoped audit rows as NDJSON (`audit.export`, seeded to `platform-operator` only). Window cap P31D, row cap default 1000 / max 5000; reserved tenant `platform` is denied unless granted. The export is itself audited as `audit.export`. Optional `AuditSink` file/stdout fan-out is off by default and wraps the existing write path (`SinkingAuditPort`) — not a second outbox and no vendor SDK. Schema: [`docs/observability/audit-ndjson-schema.md`](docs/observability/audit-ndjson-schema.md). `AuditExportTest` 3/3 plus named regressions **50/50**. No `entry-gateway` change, no WP9-2/3. SHA `4a9797d`. [`docs/observability/wp9-observability-edge-progress.md`](docs/observability/wp9-observability-edge-progress.md).
  **WP9-1 审计导出与 NDJSON SIEM 投递：** 操作员按租户导出审计 NDJSON；可选写路径文件/标准输出 sink 默认关闭。无第二张出箱、无厂商 SDK。未改入口转发器，不启动 WP9-2/3。

- **WP8-4 formal acceptance; WP8 package closed:** Thin umbrella `ConsentExportAcceptanceTest` 3/3 locks ADR 0020 / canvas WP8: `ConsentPort.require` blocks without consent, passes after grant, and blocks after withdraw (grant/withdraw audited); end-user request → other operator approves → generate → signed link → redeem; two subjects' questionnaire responses and inbox entries are seeded and the bundle contains only the requester's; unapproved generate is denied and audited; self-approval is forbidden; expired requests yield no link; cross-tenant is denied; kernel sources have no medical/hospital, visa, insurance or legal-text wording, no second outbox table, and `startFromHook` is not public. Named regressions **53/53**. No production change, no uni-app / `web/` pages, no WP9-1. Lands on current tip after WP9-0. SHA `483bf10`. [`docs/compliance/wp8-consent-export-progress.md`](docs/compliance/wp8-consent-export-progress.md).
  **WP8-4 正式验收并关闭 WP8 包：** 伞测试锁定同意门禁、导出全流程（含两人问卷与站内信隔离）、拒绝路径与内核护栏。未改生产代码，不启动 WP9-1。本提交叠在已落地的 WP9-0 之后。

- **WP9-0 ADR 0021 observability and edge recipe:** Inventory of today's actuator/health/readiness (management port), Micrometer/Prometheus, opt-in OTel, log correlation, audit query, outbox/DLQ, `deploy/k8s`, and `entry-gateway`. ADR 0021 keeps that baseline, locks prod actuator to `health` + `prometheus` only (never public `env`/`heapdump`), and ships follow-along Nginx (default walkthrough) and APISIX recipes under `deploy/edge/` (TLS, HSTS, route-class rate limits, 52MB upload, real-IP, actuator blocked). `entry-gateway` is not turned into a gateway product. Nginx 1.26.3 `nginx -t` passed on this box with dummy certs. No Java. Audit export / SIEM is WP9-1. SHA `eb60b4e`. [`docs/adr/0021-observability-and-edge-recipe.md`](docs/adr/0021-observability-and-edge-recipe.md), [`docs/observability/wp9-observability-edge-progress.md`](docs/observability/wp9-observability-edge-progress.md).
  **WP9-0 ADR 0021 可观测性与边缘配方：** 盘点现有探针/指标/跟踪/日志关联/审计查询；对公边缘用 Nginx 或 APISIX 终结 TLS 并限流，不把入口转发器改成网关产品。审计导出留到 WP9-1。不改 Java。

- **WP8-3 exporter SPI and signed export download:** Pluggable `PersonalDataExporter` plus a generic JSON platform bundle (profile, consent current+history, inbox metadata, own questionnaire responses, owned object descriptors only). After `ExportApprovalGate.requireApproved`, `POST /api/v1/exports/{id}/generate` (reuses `export.approve`; synchronous) stores the artifact via WP3 (`ObjectStorage` + `object_descriptor`, owner = subject, SUBJECT grant, `SENSITIVE`), moves the request/workflow to `READY`, and sets request-level `expiresAt` (default P7D). End-user `POST /api/v1/end-user/exports/{id}/download-link` delegates to `ObjectDownloadLinkService` (PT15M / cap PT1H); operators need `export.approve` and an `export.download-link` audit. Unapproved, rejected, or expired requests never yield a link. `startFromHook` is package-private behind a `data-export` allowlist. Flyway V55. Focused `ExportGenerationTest` 5/5 plus named regressions **68/68**. No domain exporter, no uni-app / `web/` pages, no WP8-4. SHA `85cc1d2`. [`docs/compliance/wp8-consent-export-progress.md`](docs/compliance/wp8-consent-export-progress.md).
  **WP8-3 导出 SPI 与短时签名下载：** 通用 JSON 包经 WP3 存储与签名下载；生成与操作员下载复用 `export.approve`；过期后拒绝链接。不启动 WP8-4。

- **WP8-2 sensitivity markers and export approval hook:** Entity fields accept optional `sensitivity: NORMAL|SENSITIVE` (default `NORMAL`); `object_descriptor.sensitivity` defaults to `NORMAL` (no silent reclassify to SENSITIVE). Flyway V54 `data_export_request` plus permissions `export.request` / `export.approve` (seeded on `platform-operator` only). End-user routes `/api/v1/end-user/exports` (own data only); operator routes `/api/v1/exports` (create on behalf, list, approve, reject). Approval uses declarable thin workflow `data-export` (`REQUESTED` → `APPROVED`/`REJECTED`; `READY`/`EXPIRED` reserved for WP8-3). Self-approval is forbidden. `ExportApprovalGate.requireApproved` fails closed and audits denials. No artifacts, no outbox, no uni-app / `web/` pages. Focused `ExportApprovalTest` 3/3 plus named regressions **42/42**. SHA `a44aad2`. [`docs/compliance/wp8-consent-export-progress.md`](docs/compliance/wp8-consent-export-progress.md).
  **WP8-2 敏感标记与导出审批钩子：** 字段/对象可标 NORMAL 或 SENSITIVE；导出申请须经薄工作流审批；未批准则门禁失败关闭并留痕。不生成产物。不启动 WP8-3。

- **WP8-1 ConsentPort with append-only consent ledger:** New `ConsentPort` (`grant` / `withdraw` / `current` / `listCurrent` / `history` / fail-closed `require`) and `JdbcConsentPort`. Flyway V53 `consent_event` (append-only) plus transactional `consent_current` projection. Tenant fail-closed; reserved `platform` denied; no cross-tenant access. Grant/withdraw audit via `AuditPort` (`consent.grant` / `consent.withdraw`); no outbox events. End-user routes under `/api/v1/end-user/consents` (own subject only); operator routes skipped. Kernel stores no legal text and does not use `oauth_consent`. Focused `ConsentPortTest` 3/3 plus named shell regressions, **40/40**. No uni-app / `web/` pages, no WP8-2. SHA `6d321db`. [`docs/compliance/wp8-consent-export-progress.md`](docs/compliance/wp8-consent-export-progress.md).
  **WP8-1 追加式同意台账与 ConsentPort：** 授予/撤销/当前/历史与失败关闭的 require；V53 事件表与当前投影；终端路由 `/api/v1/end-user/consents`。无出箱、无法律正文、不启动 WP8-2。

- **WP8-0 ADR 0020 consent and export hooks:** Docs-only inventory and direction ADR for generic purpose-consent records and export-approval hooks (reuse `AuditPort`, thin workflow, WP3 signed download, existing outbox; do not overload OAuth consent or TN-2 tenant ZIP). No schema or code. WP8-1 not started. SHA `a59da3f`. [`docs/adr/0020-consent-and-export-hooks.md`](docs/adr/0020-consent-and-export-hooks.md), [`docs/compliance/wp8-consent-export-progress.md`](docs/compliance/wp8-consent-export-progress.md).
  **WP8-0 ADR 0020 同意与导出审批钩子：** 仅文档盘点与方向 ADR；不改表、不改代码，不启动 WP8-1。

- **WP7-4 end-user shell package closed:** Restored uni-app primary button styling by excluding `button` from `vueCompilerOptions.nativeTags` and declaring `GlobalComponents.button` so `type="primary"` type-checks again; `vue-tsc --noEmit`, `uni build` (H5) and `uni build -p mp-weixin` all passed (SHA `d52f80f`). Formal umbrella `EndUserShellAcceptanceTest` 3/3: an end user in a business tenant can update profile, read own inbox, submit an `AUTH_END_USER` questionnaire once (second submit 409), and upload → list → download an owned file; operator tokens, another end user, reserved tenant `platform`, and a missing tenant are rejected. A static client guard asserts `clients/uni-app/src` no longer calls `/api/v1/me/inbox`, operator questionnaire routes, or `/api/v1/auth/login`, and has no domain pages. Named regressions **66/66**. Residual gaps remain (no chunked end-user upload, inbox attachments closed, `/api/v1/me/inbox` not retired, no questionnaire assignment, no avatar/email profile fields, mini-program appid provisioning). No `web/` changes. WP8 (consent and export approval hooks) not started. SHA `bf30bdf`. [`docs/clients/wp7-end-user-shell-progress.md`](docs/clients/wp7-end-user-shell-progress.md).
  **WP7-4 终端壳包关闭：** 恢复主按钮样式并通过 vue-tsc 与 H5/微信小程序构建；正式验收锁定资料、本人站内信、认证问卷一次提交（第二次 409）与文件上传/列表/下载，以及操作员/他人/保留租户/缺租户拒绝。WP7 关闭。未启动 WP8（同意与导出审批钩子）。

- **WP7-3 uni-app profile, files, inbox and questionnaire on end-user routes:** `clients/uni-app` now calls `/api/v1/end-user/profile`, `/end-user/inbox/**`, `/end-user/questionnaires` (list/get/submit) and `/end-user/objects` (list, `uni.uploadFile` field `file`, download-links). New pages: profile (display name + logout) and files (list/upload/open). Existing inbox and questionnaire pages were rerouted off `/api/v1/me/inbox` and operator questionnaire list; public ANON collect-token fill remains. 401 retries refresh then goes to login; 403/409/413/415 show Chinese toasts. Home nav links added (no tabBar). `vue-tsc --noEmit`, `uni build` (H5) and `uni build -p mp-weixin` all passed. No backend or `web/` changes, no domain pages, no WP7-4. SHA `a1c7014`. [`docs/clients/wp7-end-user-shell-progress.md`](docs/clients/wp7-end-user-shell-progress.md).
  **WP7-3 uni-app 资料、文件、站内信与问卷改走终端路由：** 新增资料与文件页，站内信/问卷改打 `/api/v1/end-user/**`。H5 与微信小程序构建通过。下一步为 WP7-4（正式验收并关闭 WP7）。

- **WP7-2 end-user object upload and list:** `POST /api/v1/end-user/objects` (multipart) stores bytes via existing `ObjectStorage` and registers `object_descriptor` with the end-user subject as owner; `GET /api/v1/end-user/objects` lists owned or unexpired SUBJECT-granted objects in the current business tenant (`limit`/`offset`). Reuses `platform.storage.upload.max-bytes` (default 50MB) and `ObjectContentTypes`; oversize 413, disallowed MIME 415; audit via `AuditPort`. Download remains `POST /api/v1/end-user/objects/download-links` + local HMAC redeem. No chunked end-user upload; inbox attachments and unregistered `entity_blob` are not listed. No uni-app / WP7-3. Focused `EndUserObjectUploadTest` 4/4 plus named shell/WP3 regressions, 99/99. SHA `44c04c6`. [`docs/clients/wp7-end-user-shell-progress.md`](docs/clients/wp7-end-user-shell-progress.md).
  **WP7-2 终端对象上传与列表：** 在 `/api/v1/end-user/objects` 上提供 multipart 上传与本人/SUBJECT 授权列表；下载仍走既有签名链接。不做分块上传，不改 uni-app。下一步为 WP7-3（uni-app 页面）。

- **WP7-1 end-user profile, inbox and questionnaire routes:** Backend `EndUserPrincipal` APIs under `/api/v1/end-user/**`: `GET`/`PATCH /profile` (display name / `subjectName` only, length-validated, `AuditPort`); inbox list/unread/get/mark-read scoped to own subject + current tenant (reuse `InboxPort`; attachments not exposed; `/api/v1/me/inbox` left unchanged); questionnaire list/fetch/submit for published `AUTH_END_USER` questionnaires in the current business tenant only (reuse Q0–Q7 validation; respondent is the end-user subject; existing one-submission rule → 409). Operator principals are rejected on these routes; end-user tokens stay rejected on operator routes; tenant fail-closed; reserved `platform` denied. No end-user `ui-permissions` endpoint, no uni-app or domain pages, no WP7-2 upload/list. Focused `EndUserShellBackendTest` 6/6 plus named auth/questionnaire regressions, 75/75. SHA `b7131e5`. [`docs/clients/wp7-end-user-shell-progress.md`](docs/clients/wp7-end-user-shell-progress.md).
  **WP7-1 终端资料、收件箱与认证问卷路由：** 在 `/api/v1/end-user/**` 下落地资料、本人收件箱与已发布认证问卷的列表/读取/提交。不拓宽操作员路由，不改 uni-app。下一步为 WP7-2（终端对象列表与上传）。

- **ADR 0019 — end-user client shell (WP7-0):** Docs only. Inventory of `clients/uni-app` pages, token handling, and realm gates: `/api/v1/me/inbox` and AUTH_END_USER questionnaire routes still require `OperatorPrincipal` (end-user tokens get 403); no profile API; signed download exists at `POST /api/v1/end-user/objects/download-links`; no end-user upload/list. Accepted direction: end-user variants under `/api/v1/end-user/**`; minimal profile read/update; own subject + tenant inbox/questionnaire scope; upload via `ObjectGrantStore` with the existing 50 MiB cap and MIME allowlist; generic H5 + mini-program pages only. Implementation starts at WP7-1. SHA `2b43c74`. [`docs/adr/0019-end-user-client-shell.md`](docs/adr/0019-end-user-client-shell.md), [`docs/clients/wp7-end-user-shell-progress.md`](docs/clients/wp7-end-user-shell-progress.md).
  **ADR 0019 — 终端用户客户端壳（WP7-0）：** 仅文档。盘点 uni-app 与门禁缺口。接受方向：在 `/api/v1/end-user/**` 下落地终端变体。实现从 WP7-1 开始。

- **WP6-3 payment port package closed:** Formal umbrella `PaymentPortAcceptanceTest` locks ADR 0018 acceptance: under the mock adapter, a domain-style caller creates an order, receives a successful payment, makes a partial refund, and a duplicate callback does not double-credit; a domain module continues from `queryOrder` status; `PaymentStatusChanged` appears on the existing `outbox_event` exactly once per state change; cross-tenant access is denied; payment kernel sources have no commission/split/tax or hospital/medical wording, no second outbox table/migration, and no vendor SDK import. Focused payment + notification + outbox regression 31/31. No production code in this slice. Residual gaps: no real vendor adapters or webhook signature verification; no expiry/cancel or refund window; no pay pages in `web/` or uni-app; settlement questions and the specific WeChat Pay / Alipay product remain open; no automatic workflow or notification fan-out on success. Next package would be WP7 (end-user client shell deepen), not started here. SHA `0ab8160`. [`docs/payment/wp6-payment-port-progress.md`](docs/payment/wp6-payment-port-progress.md).
  **WP6-3 支付端口包关闭：** 正式验收锁定模拟适配器下创单、支付成功、部分退款与重复回调不二次入账；域模块经查单继续；已有出箱每次状态变化一行 `PaymentStatusChanged`；禁止跨租户访问。本切片无生产代码。下一包将是 WP7（终端用户客户端壳加深），此处未启动。

- **WP6-2 mock adapter, idempotent callback and partial refund:** Pluggable `PaymentProviderAdapter` SPI with a mock/dev `MOCK` adapter; production without a vendor bean fails closed (`PaymentAdapterUnconfiguredException`). `WECHAT_PAY` / `ALIPAY` remain reserved identifiers (no SDK). `applyPaymentResult` is idempotent on `callback_notification_id` + fingerprint (Flyway V52): same payload is a no-op, a conflicting duplicate is rejected, cross-tenant callback is denied. `refund` supports partial refunds (same currency, amount > 0, not over remaining), is idempotent by refund token, and moves status to `PARTIALLY_REFUNDED` / `REFUNDED`. Success and refund writes a `PaymentStatusChanged` row on the existing `outbox_event` bus in the same transaction; `OutboxRelay` delivers it via `DeliveryPort` (still skips `NotificationRequested`). No second outbox, no `TaskMessagePort.submit`, no workflow/notification fan-out, no pay UI, no formal WP6-3 acceptance. Tests: `PaymentCallbackRefundTest` 10/10; regression `PaymentPortCreateQueryTest` 10/10, `NotificationPortAcceptanceTest` 4/4, `OutboxRelayTest` 4/4. SHA `978bce2`. [`docs/adr/0018-payment-port.md`](docs/adr/0018-payment-port.md), [`docs/payment/wp6-payment-port-progress.md`](docs/payment/wp6-payment-port-progress.md).
  **WP6-2 模拟适配器、幂等回调与部分退款：** 可插拔支付 SPI，开发用模拟适配器，生产未配置则失败关闭。回调按通知标识幂等；退款含部分退款且按令牌幂等。状态变化在已有出箱上写 `PaymentStatusChanged`，经现有 `DeliveryPort` 中继。无正式验收（WP6-3）。

- **WP6-1 PaymentPort create and query with voucher ledger:** Domain-facing `PaymentPort` (`createOrder` / `queryOrder`) plus Flyway V51 `payment`, `payment_refund`, and `payment_voucher` tables. Amounts are non-negative BIGINT minor units plus an explicit ISO 4217 CHAR(3) currency (no default). Tenant fail-closed reuses the notification-port checks (missing / disabled / reserved `platform` denied; no cross-tenant query). Callback and refund are declared but throw `PaymentOperationUnsupportedException` until WP6-2. Idempotency-key columns sit on the payment aggregate; `idempotency_claim.task_id` and `TaskMessagePort.submit` are not reused; no second outbox table, no mock adapter, no `PaymentStatusChanged`, no pay UI. Focused tests: `PaymentPortCreateQueryTest` 12/12; `NotificationPortAcceptanceTest` 4/4. SHA `9e47729`. [`docs/adr/0018-payment-port.md`](docs/adr/0018-payment-port.md), [`docs/payment/wp6-payment-port-progress.md`](docs/payment/wp6-payment-port-progress.md).
  **WP6-1 支付端口创单与查单及凭证台账：** 落地 `PaymentPort` 创单/查单与 V51 支付/退款/凭证表。金额为非负最小货币单位 + 显式 ISO 4217 币种。租户失败关闭；禁止跨租户查单。回调与退款在接口上保留但失败关闭，留给 WP6-2。

- **ADR 0018 — unified PaymentPort (WP6-0):** Docs only. Inventory finds no payment port, money type, or voucher table; reusable pieces are tenant fail-closed, `AuditPort`, the existing `outbox_event` bus, and the *pattern* of task idempotency (the `idempotency_claim` row is bound to `task_id` and must not be overloaded). Accepted direction: a domain-facing `PaymentPort` (create, idempotent payment-result callback, refund including partial, query) plus a platform-side voucher ledger; phase 1 is a mock adapter (WeChat Pay / Alipay are identifiers only); amounts are integer minor units + explicit ISO 4217 currency; domain modules observe status by query and a `PaymentStatusChanged` event on the existing outbox (not a second bus, not `TaskMessagePort.submit`). Out of scope: commissions, split-accounting, tax, hospital payout, settlement cycles, WP7. Implementation starts at WP6-1. SHA `fd18d81`. [`docs/adr/0018-payment-port.md`](docs/adr/0018-payment-port.md), [`docs/payment/wp6-payment-port-progress.md`](docs/payment/wp6-payment-port-progress.md).
  **ADR 0018 — 统一支付端口（WP6-0）：** 仅文档。目前没有支付端口或金额类型。接受方向：创单、幂等回调、退款（含部分退款）、查单与平台侧凭证台账；首期模拟适配器。不做分润/分账/税务/结算周期。实现从 WP6-1 开始。

- **WP5-3 notification port package closed:** Formal umbrella `NotificationPortAcceptanceTest` locks ADR 0017 acceptance: a domain-style caller sends one template via `NotificationPort` to a mock sink; a forced failure is visible as a dead letter and can be replayed; cross-tenant send is denied; notification kernel sources have no medical/domain copy, no second outbox product, and do not import `VerificationCodeSender`. Focused notification + outbox/DLQ regression 38/38. No production code in this slice. Residual gaps: no operator contact directory / destination resolution; WeChat official vs subscribe-message product still open; `event_body` 4000-char cap; notification dispatch has no DeliveryPort circuit breaker; prod unconfigured adapters fail into DLQ after 3 attempts; no automatic inbox or non-repair-workflow fan-out; `VerificationCodeSender` stays separate. Next package would be WP6 (payment port), not started here. SHA `1714208`. [`docs/messaging/wp5-notification-port-progress.md`](docs/messaging/wp5-notification-port-progress.md).
  **WP5-3 通知端口包关闭：** 正式验收锁定域模块经 `NotificationPort` 发出一条模板消息、失败可查可重放、禁止跨租户发信。本切片无生产代码。下一包将是 WP6（支付端口），此处未启动。

- **WP5-2 mock notification adapters and dispatch:** In-process `NotificationDispatcher` reads `PENDING` `NotificationRequested` rows and calls SPI channel adapters (`SMS` / `EMAIL` / `WECHAT_TEMPLATE`). Mock/dev adapters record to an in-memory sink; production is fail-closed until a vendor `NotificationChannelAdapter` bean is registered. Retry and dead-letter reuse `outbox_event` / `dead_letter` (max 3 attempts); existing dead-letter list/replay works for this event name without sending TaskRecorded frames. `OutboxRelay` still only handles `TaskRecorded`. No WP5-3 formal acceptance, no WP6, no hardcoded commercial vendor. SHA `701efad`. [`docs/messaging/wp5-notification-port-progress.md`](docs/messaging/wp5-notification-port-progress.md).
  **WP5-2 模拟通知适配器与调度：** 进程内调度器读取 PENDING 的 `NotificationRequested` 并调用渠道 SPI。开发用模拟适配器写入内存接收槽；生产未注册厂商 Bean 则失败关闭。重试与死信复用已有出箱表（最多 3 次）；现有死信列表/重放可用于该事件且不走 TaskRecorded 帧。`OutboxRelay` 仍只处理 `TaskRecorded`。本切片无正式验收、不启动 WP6、不写死商业厂商。

- **WP5-1 NotificationPort enqueue to existing outbox:** Domain-facing `NotificationPort.enqueue` writes `NotificationRequested` into the existing `outbox_event` table (not via `TaskMessagePort.submit`). Body carries template id + parameter map, not kernel prose. Missing / disabled / reserved `platform` tenants fail closed with no row; a recipient belonging to tenant B cannot be used under tenant A; inbox and `platform_task` are not written. `event_body` over 4000 characters fails closed (column not widened). Existing `OutboxRelay` skips this event name (WP5-2 hook). No channel adapters, no WP5-3 acceptance, no WP6. SHA `efd68c3`. [`docs/messaging/wp5-notification-port-progress.md`](docs/messaging/wp5-notification-port-progress.md).
  **WP5-1 通知端口入队到已有出箱：** `NotificationPort` 把 `NotificationRequested` 写入现有 `outbox_event`（不走 `TaskMessagePort.submit`）。事件体只带模板标识与参数。缺失/停用/保留租户 `platform` 失败关闭且不写行；禁止跨租户发信；不写站内信与 `platform_task`。超 4000 字符失败关闭。现有中继跳过该事件名，留给 WP5-2。本切片无适配器、无正式验收、不启动 WP6。

- **ADR 0017 — unified external NotificationPort (WP5-0):** Docs only. Inventory of the landed thin inbox (ADR 0008/0009), JDBC outbox / relay / DLQ, and the absence of SMS/email/WeChat-template product send. Accepted direction: a domain-facing `NotificationPort` (template id, tenant isolation, enqueue, failure retry) reuses the existing `outbox_event` bus rather than a second outbox product; phase 1 channels are SMS, email, and WeChat template with mock/dev adapters first; inbox stays a mailbox, not chat; no domain copy in the kernel; cross-tenant send denied. Implementation starts at WP5-1. SHA `afcaf10`. [`docs/adr/0017-notification-port.md`](docs/adr/0017-notification-port.md), [`docs/messaging/wp5-notification-port-progress.md`](docs/messaging/wp5-notification-port-progress.md).
  **ADR 0017 — 统一外部通知端口（WP5-0）：** 仅文档。复用现有出箱总线，新增面向域模块的通知端口；首期短信/邮件/微信模板先模拟适配器。不改写站内信，内核不放域文案。实现从 WP5-1 开始。

- **WP4-4 thin workflow package closed:** Formal umbrella `ThinWorkflowAcceptanceTest` locks ADR 0016 acceptance: `repair-ticket` and `order-approval` both run on the existing catalog / engine / HTTP surface with distinct action tokens, and production Java does not special-case `order-approval`. Focused workflow + catalog regression 96/96. No production code in this slice. Residual gaps: IM-3 inbox still repair-ticket-only; console may show raw tokens for non-repair actions; no tenant-draft `DeclarationKind` for workflow; no end-user workflow API. Next package would be WP5 (notification port), not started here. SHA `e87ed36`.
  **WP4-4 薄工作流包关闭：** 正式验收锁定两台 classpath 机器走现有引擎且线名不同；内核不为 `order-approval` 写特例。本切片无生产代码。下一包将是 WP5（通知端口），此处未启动。

- **WP4-3 official `order-approval` example:** Classpath declaration `workflows/order-approval.workflow.yaml` (`definitionKey` / `entityKey`: `order-approval`) runs submit, approve, reject, and complete on the existing engine and HTTP API, with auto-start, dual approval, and fail-closed org scope. Minimal generic entity/form/UI-flow (order id, title, optional organization) so the console can resolve the panel by `entityKey`. No copied repair-ticket FSM, no kernel special-case, no inbox hook. SHA `7743f70`.
  **WP4-3 官方 `order-approval` 示例：** classpath 声明跑通提交、审批、驳回与结束；带自动启动、双人审批与失败关闭的组织范围。附最小通用实体/表单/页面以便控制台按 `entityKey` 显示面板。不复制报修状态机，内核无该键特例。

- **WP4-2 declaration-driven auto-start, approval, and org scope:** The engine applies YAML hooks `autoStartOnUpsert`, `dualApproval`, and `orgScope` instead of Java branches that name only `repair-ticket`. Org-scope remains fail-closed when the declaration requires it (`WorkflowOrgApproverGate`). The operator console resolves the machine by `entityKey` (`GET /api/v1/workflows/definitions/by-entity`) and no longer hardcodes `repair-ticket`. IM-3 inbox fan-out stays repair-ticket-only. No official `order-approval` example (WP4-3). SHA `53c657f`.
  **WP4-2 声明驱动的自动启动、审批与组织范围：** 引擎按声明钩子应用自动启动、双人审批与失败关闭的组织范围；控制台按 `entityKey` 解析机器，不再写死 `repair-ticket`。站内信钩子仍仅报修工单。正式 `order-approval` 示例留待 WP4-3。

- **WP4-1 workflow catalog and repair-ticket declaration:** Thin FSM graphs load from classpath `workflows/*.workflow.yaml` (`WorkflowCatalog`, same scan style as entity/form/flow). State and action are declaration tokens, not kernel enums. The repair-ticket machine moves to `workflows/repair-ticket.workflow.yaml` with unchanged `definitionKey` and wire names (`OPEN`, `start_work`, …). Existing instance/history/approval rows are not rewritten. Auto-start, dual-approve, org-scope, and the console panel stay hardcoded until WP4-2 (YAML already records those hooks). SHA `5f5e778`.
  **WP4-1 工作流目录与报修工单声明：** 从 classpath YAML 加载薄状态机；报修工单成为声明之一且线名不变。自动启动、双人审批、组织范围与控制台仍待 WP4-2 声明化。

- **ADR 0016 — thin reusable workflow (WP4-0):** Docs only. Inventory of the landed repair-ticket FSM (WF-0…WF-RT-5) plus accepted direction: a classpath `workflows/*.workflow.yaml` catalog (same loading pattern as entity/form/flow); states, transitions, dual approval, org-scoped approval, and history — not a BPM/Camunda product or general DSL. Repair-ticket becomes one declaration with unchanged wire names (no history rewrite). A later `order-approval` example must run submit/approve/reject/complete without copying the repair-ticket FSM. Keep audit fields and fail-closed org scope. Implementation starts at WP4-1. [`docs/adr/0016-thin-reusable-workflow.md`](docs/adr/0016-thin-reusable-workflow.md), [`docs/workflow/wp4-thin-workflow-progress.md`](docs/workflow/wp4-thin-workflow-progress.md).
  **ADR 0016 — 可声明的通用薄工作流（WP4-0）：** 仅文档。把已落地报修工单状态机收成 classpath 声明目录；不做完整 BPM。报修工单成为声明之一且不改写历史。实现从 WP4-1 开始。

- **WP3-5 object access isolation and audit-trace suite:** Formal MockMvc suite `ObjectAccessIsolationTest` locks WP3 acceptance: owner/grant/revoke and organization membership issue/deny, cross-tenant issue plus tenant-scoped `GET /api/v1/audit`, operator vs end-user realm split (audit stays operator-only; local redeem still requires a valid HMAC), deny-by-default unregistered `entity_blob` keys, and prod still defaulting to `platform.storage.type=s3` with no local-disk fallback. No production code in this slice; grants are still seeded via the store. This **closes the WP3 package**. Residual gaps: inbox self-recipient not migrated, unscoped `_platform_` uploads, no end-user upload route, process-local chunk sessions. Next package would be WP4 (thin workflow), not started here. SHA `b296420`.
  **WP3-5 对象访问隔离与审计追溯套件：** 正式 HTTP 套件锁定 WP3 验收；未授权拿不到可用签名 URL，已授权签发可按租户追溯。生产仍默认共享 S3。本切片无生产代码。WP3 包关闭。

- **WP3-4 audit object upload, download issue, and grants:** Authorized-track upload, signed-link issue, and grant create/revoke are recorded on the existing `AuditPort` (`object.upload`, `object.download.issue`, `object.grant.create`, `object.grant.revoke`) and are queryable via `GET /api/v1/audit` scoped by tenant. The WP3-2 issue hook now writes the audit event at issue time. Shared MIME allowlist `ObjectContentTypes` (ADR 0009 inbox set; inbox types delegate to it); non-whitelisted types are rejected and not stored. SHA `3ca4514`.
  **WP3-4 对象上传/签发/授权审计：** 写入既有单一 AuditPort；签发在授权并出链时记审计。MIME 与站内信共用 ADR 0009 名单；非白名单类型失败关闭且不落盘。不向终端开放操作员审计接口。

- **WP3-3 raise single-file upload cap to 50 MiB:** Generic cap is `platform.storage.upload.max-bytes` / `PLATFORM_STORAGE_UPLOAD_MAX_BYTES` (default `50MB`, was 5 MiB). CHUNK-1 still assembles in a local temp dir then `ObjectStorage.put` (no S3 multipart, no CDN). Spring multipart, entity-blob and inbox server checks, and the operator console (`BLOB_MAX_BYTES` / `INBOX_ATTACH_MAX_BYTES`, i18n) are aligned. New tenant-scoped entity-blob uploads are registered with `ObjectGrantStore` (no legacy backfill; unscoped `_platform_` objects stay unregistered). Chunk sessions remain process-local. No new end-user upload route. SHA `b2dc1cc`.
  **WP3-3 将通用单文件上限提高到 50 MiB：** 由同一配置键驱动；仍走 CHUNK-1 组装后 put。对齐 Spring multipart、实体附件、站内信与控制台文案。显式业务租户下的新实体附件会登记所有者。不回填历史行；不新增终端上传路由；分片会话仍为进程内。

- **WP3-2 short-lived signed download URLs:** After `ObjectAccessAuthorizer` allows the actor, the platform issues a short-lived signed GET; otherwise it fails closed with HTTP 403 `reason=object-access-denied` and no URL. Default TTL `PT15M`, hard cap `PT1H` (`platform.storage.download.ttl` / `PLATFORM_STORAGE_DOWNLOAD_TTL`, `ttl-max` / `PLATFORM_STORAGE_DOWNLOAD_TTL_MAX`; the cap cannot be raised by config). Production/S3 uses AWS SDK v2 presigned GET (`S3ObjectStorage.presignGet`); local-disk uses HMAC-SHA256 URLs redeemed at `GET /api/v1/end-user/objects/content`. Issue paths: `POST /api/v1/objects/download-links` (operator) and `POST /api/v1/end-user/objects/download-links` (end user). Realm-gate allow-list is unchanged; only the redeem GET is `permitAll` (signature is the credential). Audit hook only (WP3-4); 50 MiB cap unchanged (WP3-3). SHA `646e8ab`.
  **WP3-2 短时签名下载：** 仅在访问判定允许后签发；否则失败关闭且无可用 URL。默认 PT15M，硬上限 PT1H。生产走 S3 预签名 GET；本地盘走 HMAC，由平台兑换端点回流字节。操作员与终端分路径签发。未扩展终端领域门允许列表。审计仅留钩子；不抬 50 MiB 上限。

- **WP3-1 object grant schema and deny-by-default:** Flyway `V50__object_grant.sql` adds `object_descriptor` (required owner subject) and `object_grant` (in-tenant subject or organization grant). `ObjectAccessAuthorizer` sits above `ObjectStorage` and allows only the owner or an explicit unexpired grant; org grants require current ACTIVE Membership and an ACTIVE tenant-organization link. Membership alone is denied. Reserved tenant `platform` is never a default; missing/disabled tenants and unregistered (including legacy unowned `entity_blob`) objects fail closed. No signed download, 50 MiB cap, or audit wiring in this slice.
  **WP3-1 对象授权表与默认拒绝：** V50 登记表（必须有所有者）与授权表；判定在对象存储端口之上；组织授权须当前 ACTIVE 成员且租户已绑定该组织；不回填既有 entity_blob。本切片不含签名下载、50 MiB 或审计挂钩。

- **ADR 0015 — authorized object access (WP3-0):** Docs only. Inventory + accepted direction: keep local-disk (dev) and S3-compatible (prod) ports; authorization sits above the blob port (owner subject or explicit grant to a subject or organization; membership is not authorization). Short-lived signed download (S3 presign in prod, app-issued signed URL on local disk; default TTL PT15M). Raise the generic per-file cap to at least 50 MiB via existing chunked assemble-then-put (no CDN; no S3 multipart protocol required in this track). Audit upload, signed-link issue, and grant changes on the existing `AuditPort`; keep the MIME whitelist. Implementation starts at WP3-1. [`docs/adr/0015-object-storage-authorization.md`](docs/adr/0015-object-storage-authorization.md), [`docs/storage/wp3-object-auth-progress.md`](docs/storage/wp3-object-auth-progress.md).
  **ADR 0015 — 对象存储授权访问（WP3-0）：** 仅文档。保留本地盘与 S3 端口；授权在端口之上（所有者或对主体/组织的显式授权）；短时签名下载；通用上限至少 50 MiB；审计走现有端口；保留 MIME 白名单。实现从 WP3-1 开始。

- **WP2-6 uni-app end-user phone and WeChat auth:** `clients/uni-app` stops calling operator `/api/v1/auth/login` and `/api/v1/auth/idp/wechat/callback`. It now uses `/api/v1/end-user/auth/**` for phone request-code / register-or-login / bind / unbind, WeChat `jsCode` login / bind / unbind, refresh, and logout. Only `eu.at.` / `eu.rt.` are stored; launch refreshes silently; logout revokes then clears storage. Tenant is required (login field, `?tenantId=`, or `VITE_SUBJEX_TENANT_ID`) and never defaults to `platform`. WeChat mini login is hidden on H5. Public ANON_LINK questionnaire fill still works without login. This closes the WP2 package.
  **WP2-6 uni-app 终端手机号与微信登录：** 停止调用操作员认证接口；H5 走手机号，小程序走 `jsCode`；只存终端令牌；租户必填且失败关闭；匿名问卷填写不强制登录。WP2 包至此闭合。

- **WP2-5 end-user cross-realm isolation:** Formal suite `EndUserCrossRealmIsolationTest` (34 tests) locks that `eu.at.` / `eu.rt.` (`EndUserPrincipal`) cannot call operator console / management APIs — directory, `/me`, MFA, tenant.manage, UI-permission/manifest, OAuth clients, inbox compose/broadcast, questionnaire admin, operator login/refresh/logout, and operator WeChat/OIDC bind routes all return **403** `reason=operator-authentication-required`. End-user refresh/logout on `/api/v1/end-user/auth/**` stay allowed. Operator tokens and HTTP Basic on end-user phone/WeChat bind/unbind stay **403** `end-user-authentication-required`; an operator refresh token is not accepted as `eu.rt.`. Tenant fail-closed is unchanged. Minimal production fix: `EndUserRealmGateFilter` after the Bearer filter (allowlist `/api/v1/end-user/**`, `/actuator/health`, `/api/v1/questionnaires/public/**`) plus the same reason on the access-denied handler, and operator refresh/logout reject `eu.` body tokens.
  **WP2-5 终端跨领域隔离：** 正式套件锁定终端令牌不能调用操作员管理接口（统一 403 `operator-authentication-required`）；反向绑定/解绑仍为 403 `end-user-authentication-required`。最小生产修复：Bearer 之后的领域门 + 拒绝处理器同一 reason；操作员刷新/退出拒绝 `eu.` 请求体令牌。租户失败关闭未削弱。不包含 uni-app（WP2-6）。

- **WP2-4 WeChat mini-program auto-provision login:** First WeChat login may auto-provision an END_USER (account + subject + identity + `end_user_idp_link`) in an explicit existing ACTIVE business tenant; never creates a tenant; never defaults to `platform`. Identity key is the existing adapter model: unique `(issuer, idp_subject)` with `wechat:mini` and subject = unionId if present else openId. Reuses `WeChatIdpAdapter` / `WeChatApiClient.code2Session` (`AdapterEndUserWeChatAssertion`); tests/`local` use `DevEndUserWeChatAssertion` (`dev:{openId}` / `dev:{openId}:{unionId}`); `prod`/`production` without mini-program config uses `FailClosedEndUserWeChatAssertion` (HTTP **503** `reason=wechat-unconfigured`, no silent provision). HTTP: `POST /api/v1/end-user/auth/wechat/login` (permitAll), `/bind`, `/unbind`. Issues WP2-2 `eu.at.` / `eu.rt.` pairs. Lockout key `wechat:{issuer}:{idp_subject}` on `end_user_login_lockout` (same 5 / PT15M grade). Unbind of the last authenticator (phone or WeChat) returns **409** `reason=last-authenticator`. Operator `IdpLoginOrchestrator` / `operator_idp_link` / unlinked-deny are not used. Bind/unbind require `EndUserPrincipal`.
  **WP2-4 微信小程序自动开户登录：** 首次登录可自动开户，须显式 ACTIVE 业务租户；复用现有微信适配器模型；生产未配置失败关闭；解绑最后认证器返回 409；不套用操作员「未绑定即拒绝」。

- **WP2-3 end-user phone login and lockout:** Generic `VerificationCodePort` (in-memory store, SHA-256 at rest) with a no-op `DevVerificationCodeSender` for tests/`local` and a fail-closed sender under `prod`/`production` (no vendor in the kernel; codes are never logged). HTTP: `POST /api/v1/end-user/auth/phone/request-code`, `/login` (register-or-login), `/bind`, `/unbind`. Register/login require an explicit existing ACTIVE business `tenantId` (`X-Tenant-Id` or JSON); never create a tenant; never default to `platform`. Issues WP2-2 `eu.at.` / `eu.rt.` pairs. Phone is globally unique; unbind releases the number. Lockout uses `end_user_login_lockout` only, default **5 / PT15M** (`platform.end-user.auth.lockout-*`), HTTP **429** `reason=login-lockout`. Bind/unbind require `EndUserPrincipal` (operator tokens and HTTP Basic are rejected).
  **WP2-3 终端手机号登录与锁定：** 通用验证码端口（无厂商）；生产未配置发送器失败关闭且不打印验证码；注册/登录须显式 ACTIVE 业务租户；锁定与操作员同级（5 次 / 15 分钟，429）；绑定/解绑只接受终端主体。

- **WP2-2 end-user token issue / refresh / logout:** Opaque prefixed tokens (`eu.at.` / `eu.rt.`), SHA-256 at rest, rotating refresh with family reuse detection, in `end_user_*` tables only (operator token tables untouched). `JdbcEndUserTokenStore.issue` is an internal helper (no phone/WeChat login HTTP). HTTP: `POST /api/v1/end-user/auth/refresh` and `/logout`. `EndUserPrincipal` is distinct from `OperatorPrincipal`. Bearer filter routes `eu.` tokens only to the end-user store. Defaults: access `PT30M`, refresh `PT8H` (`platform.end-user.auth.*`, env `PLATFORM_END_USER_ACCESS_TOKEN_TTL` / `PLATFORM_END_USER_REFRESH_TOKEN_TTL`). Tenant fail-closed: no issue for reserved `platform`, missing, or disabled tenants.
  **WP2-2 终端令牌签发/刷新/退出：** 带前缀不透明令牌，只存哈希，轮换刷新并检测重用；独立仓库与主体；HTTP 仅刷新与退出；默认 TTL 与操作员同量级（30 分钟 / 8 小时）；拒绝 `platform` 与缺失/停用租户。

- **WP2-1 end-user account schema:** Flyway `V49__end_user_identity.sql` adds `end_user_phone_binding` (global unique phone), `end_user_idp_link` (unique issuer+idp_subject), plus unused-yet `end_user_access_token` / `end_user_refresh_token` / `end_user_login_lockout`. Reuses `account` / `subject` / `subject_identity`; reserved tenant `platform` is refused as a home. `EndUserAccountStore` (JDBC + memory) proves insert/get and uniqueness. No login/token issue in this slice.
  **WP2-1 终端用户账号表：** V49 新增绑定/令牌/锁定表；复用身份三分；拒绝保留租户 `platform` 作为归属。本切片不签发令牌、不实现登录。

- **ADR 0014 — first-class end-user identity (WP2-0):** inventory + accepted direction. End-user accounts/sessions stay isolated from operators; phone + WeChat mini-program; WeChat first login auto-provisions an END_USER but never creates a tenant; prefixed opaque access/refresh tokens (`eu.at.` / `eu.rt.`). Implementation starts at WP2-1. [`docs/adr/0014-end-user-identity.md`](docs/adr/0014-end-user-identity.md), [`docs/identity/wp2-end-user-progress.md`](docs/identity/wp2-end-user-progress.md).
  **ADR 0014 — 一等终端用户身份（WP2-0）：** 盘点与方向。账号/会话与操作员隔离；手机号+微信小程序；微信首次登录可自动开户但不建租户；令牌带可区分前缀。实现从 WP2-1 开始。

- **WP1-5 production deployment boundary and release checklist:** `SECURITY.md` Deployment boundary is now **edge TLS + reliable public-facing evaluation capability under production config** (not “keep off the public internet”). New checklist [`docs/release/production-config-checklist.md`](docs/release/production-config-checklist.md) makes prod profile, MFA, no local seed, no auto-create tenant, S3 storage, edge TLS, management-port isolation, JDBC shared backends before `replicas > 1`, and a recorded backup/restore drill explicit release gates. Runtime rate-limit/circuit-breaker defaults stay `process`; default manifests stay `replicas: 1`. Nginx/APISIX recipe remains WP9; `entry-gateway` is not a gateway product.
  **WP1-5 生产部署边界与发布检查表：** `SECURITY.md` 部署边界改为「边缘 TLS + 生产配置下可靠公网面向评估能力」。新检查表把生产 profile、MFA、禁止本地种子、关闭自动建户、S3 存储、边缘 TLS、管理口隔离、多副本前 JDBC 共享后端、以及已记录的备份恢复演练列为发布门禁。运行时限流/熔断默认仍为 `process`；默认清单仍单副本。Nginx/APISIX 配方留待 WP9。

- **WP1-4 production default S3-compatible object storage:** `application-prod.yml` / `application-production.yml` set `platform.storage.type=s3` (existing `S3ObjectStorage`, AWS SDK v2, MinIO-compatible). Missing endpoint/bucket/credentials fail fast; `type=local` is refused under prod. Base and `local` stay directory storage. k8s exposes S3 settings via `platform-object-storage` Secret placeholders; compose stays on local disk.
  **WP1-4 生产默认 S3 兼容对象存储：** 生产配置默认 `s3`（复用已有适配器）；配置缺失则启动失败，生产拒绝本地盘。基础与 `local` 仍用目录。k8s 用 Secret 占位；compose 仍用本地盘。

- **WP1-3 disable auto-create tenant on submit:** `platform.tenant.auto-create-on-submit` defaults to `false` in `application.yml` and `prod`/`production`; `true` only in `application-local.yml`. Missing tenant on submit is fail-closed (403 `reason=tenant-not-found`); explicit `/api/v1/tenants` create and SUSPENDED refusal are unchanged.
  **WP1-3 关闭提交时自动建租户：** 基础与生产配置默认关闭；仅 `local` 打开逃生。提交时租户不存在则失败关闭（403）；显式创建与停用拒绝不变。

- **WP1-2 require operator MFA under prod:** `platform.mfa.required` defaults to `true` in `application-prod.yml` / `application-production.yml`; `local` stays optional. Unenrolled password login fail-closes with 403 `reason=mfa-enrollment-required`; enrolled TOTP verify unchanged. HTTP Basic stays off in production.
  **WP1-2 生产强制操作员 MFA：** `prod`/`production` 默认 `platform.mfa.required=true`；`local` 仍可选。未登记口令登录失败关闭（403）；已登记 TOTP 校验不变。生产不打开 HTTP Basic。

- **WP1-0/1 production profile guard:** dedicated `prod`/`production` profiles; fail-fast if combined with `local`; `LocalOperatorSeeder` stays `@Profile("local")` and does not run under production; default `platform-operator` / `change-me` moved to `application-local.yml` only; k8s `platform-app` sets `SPRING_PROFILES_ACTIVE=prod`. Progress: [`docs/release/wp1-prod-hardening-progress.md`](docs/release/wp1-prod-hardening-progress.md).
  **WP1-0/1 生产配置门禁：** 专用 `prod`/`production` profile；与 `local` 同时启用则启动失败；本地种子不在生产配置下运行；默认口令仅留在 `application-local.yml`；k8s 清单启用 `prod`。进度见加固进度文档。

- **Questionnaire Q7 uni-app fill:** `pages/questionnaire/list` + `fill?token=`; public ANON collect client; uni-app Manifest nav/page/button (8 entries). AUTH_END_USER submit HTTP still deepen.
  **问卷 Q7 uni-app 填写：** 入口页 + 令牌填写；公开匿名采集客户端；终端 Manifest；认证终端提交 HTTP 仍属加深。

- **Questionnaire Q6 HTTP + thin console:** REST `/api/v1/questionnaires/**` with `questionnaire.read|write`; public ANON collect; console `/questionnaires` + Manifest nav/page/button (52 entries). Uni-app fill remains Q7.
  **问卷 Q6 HTTP + 薄控制台：** REST 读写分权；公开匿名采集；控制台 `/questionnaires` + Manifest；答卷端仍属 Q7。

- **Questionnaire Q5 thin stats + CSV:** `stats` / `exportCsv`; production responses only (exclude OPERATOR preview); choice/rating bucket counts; CSV metadata + per-item columns.
  **问卷 Q5 薄汇总 + CSV：** 仅生产答卷；选择/评分桶计数；CSV 元数据+按题列。

- **Questionnaire Q4 ANON_LINK collect token:** Flyway `V48__questionnaire_collect_token`; auto-issue on publish; `submitAnon` + `RateLimitPort` bucket per questionnaire/clientKey; revoke; `QuestionnaireRateLimited`.
  **问卷 Q4 匿名采集令牌：** V48 令牌表；发布自动签发；submitAnon + 按问卷/客户端限流；可撤销。

- **Questionnaire Q3 auth submit + list:** `submit` / `listResponses`; END_USER one-shot; OPERATOR preview excluded from production list (`countsForStats=false`); ANON deferred to Q4.
  **问卷 Q3 认证答卷：** 提交/列表；终端用户每卷一份；操作员预览不进生产列表；匿名待 Q4。

- **Questionnaire Q2 items + lifecycle:** `replaceItems` / `publish` / `close`; DRAFT in-place edit; PUBLISHED revision bump (prior item rows kept); CLOSED read-only; `QuestionnaireItemSupport`.
  **问卷 Q2 题目与生命周期：** 替换题目/发布/关闭；草稿原地改；已发布升 revision（旧题保留）；已关闭只读。

- **Questionnaire Q1 definition store:** Flyway `V47__questionnaire` (definition/item/response/answer + perms); `MemoryQuestionnairePort` + `JdbcQuestionnairePort` create/get/list; `PlatformWiring` bean. No HTTP yet.
  **问卷 Q1 定义存储：** V47 四表+权限；Memory/JDBC 端口 create/get/list；已接线。尚无 HTTP。

- **ADR 0013 Accepted — questionnaire platform capability:** survey as architecture foundation (not Form rename); MVP types/collect/stats/clients locked; amends ADR 0011 §D1; Q0 contract stub `QuestionnairePort`. [`docs/adr/0013-questionnaire-platform-capability.md`](docs/adr/0013-questionnaire-platform-capability.md).
  **ADR 0013 Accepted — 问卷平台能力：** 调研采集作架构基础能力（不改名 Form）；锁定 MVP 题型/采集/统计/客户端；修订 0011 §D1；Q0 契约桩。

- **Config-7a restore from history:** `POST /api/v1/config/{key}/history/{revision}/restore`; console confirm restore; audit `config.restore`.
  **Config-7a 从历史恢复：** POST restore；控制台确认；审计 config.restore。

- **Config-6b history API + console:** `GET /api/v1/config/{key}/history`; web `/config?historyKey=` thin list (uni-app out of scope).
  **Config-6b 历史 API + 控制台：** GET 覆盖历史；web 薄列表（uni-app 本片不做）。

- **Config-6a override history (ADR 0012):** post–Item 5 deepen track; Flyway `V46__config_override_history`; JDBC/Memory put appends history; `ConfigOverrideStore.history`.
  **Config-6a 覆盖历史（ADR 0012）：** Item 5 后加深轨；V46 历史表；put 追加；store.history。

- **AuthZ-1g upsert via PolicyEngine:** `EntityRecordUpsertDomainActionHandler` uses `DeclarationAccess.requireVia`; declaration AuthZ widen inventory clear.
  **AuthZ-1g upsert 走 PolicyEngine：** 实体 upsert 领域动作改 requireVia；声明鉴权拓宽盘点清空。

- **AuthZ-1f PolicyEngine widen:** Forms / FormSubmission / GenericEntity / EntityBlob use `DeclarationAccess.requireVia` so default Cedar covers declaration CRUD paths. Inventory updated.
  **AuthZ-1f PolicyEngine 拓宽：** 表单/提交/实体/附件改走 requireVia；默认 Cedar 覆盖声明 CRUD。

- **AuthZ-1e PolicyEngine widen start:** inventory of AccessChecker/DeclarationAccess vs PolicyEngine callers; `DeclarationAccess.requireVia`; `PagesApiEndpoint` detail uses PolicyEngine (Cedar default applies). [`docs/authz/policy-engine-caller-inventory.md`](docs/authz/policy-engine-caller-inventory.md).
  **AuthZ-1e PolicyEngine 拓宽起步：** 调用盘点；`requireVia`；页面详情走 PolicyEngine（默认 Cedar 生效）。

- **PC-deepen-3 UI Manifest coverage:** +8 remaining console `page.*` (services / deploy / forms / pages / search / codegen / capabilities / operators) + `btn.operators.manage`; nested `/pages/*` reuse `page.pages`. Classpath **49**.
  **PC-deepen-3 UI Manifest 加深：** 余下八个控制台页 + 操作员管理按钮；嵌套 pages 共用 page.pages；条目 49。

- **PC-deepen-2 UI Manifest coverage:** +7 `page.*` Forbidden gates (org / inbox / compose / groups / config / audit / dead-letters) + `btn.config.write`; console pages wired to `pageAllowed` / `buttonAllowed`.
  **PC-deepen-2 UI Manifest 加深：** 七个页面 Forbidden + 配置写按钮；控制台页改读 Manifest。

- **ADR 0011 Accepted — no standalone questionnaire (near-term):** Form stays operator business entry (not renamed 问卷); future survey/quiz needs a new ADR (types, public/anon, stats, export, subject). [`docs/adr/0011-no-standalone-questionnaire.md`](docs/adr/0011-no-standalone-questionnaire.md).
  **ADR 0011 Accepted — 近端不做独立问卷：** Form 仍作操作员业务录入（不改名问卷）；未来问卷须新 ADR。

- **UI Manifest deepen (#5):** console classpath +6 write buttons + 4 `page.*` Forbidden gates; flags / ui-authz / declarations / tenants consume Manifest `buttonAllowed` / `pageAllowed`.
  **UI Manifest 加深（#5）：** 高价值写按钮与页面 Forbidden 绑定；四页改读 Manifest。

- **PC-5 + #3 terminal auth / IM-6:** uni-app UI permission shell; Bearer password + wechat:mini IdP; `/api/v1/me/inbox/**` END_USER read+mark-read + uni-app inbox page (`6d52bc5` lineage).
  **PC-5 + #3 终端认证 / IM-6：** uni-app Manifest 壳；薄 Bearer；终端收件只读。

- **ADR 0010 / PC-0…PC-6b UI permission Manifest:** classpath YAML + GET + console grey menus + sample buttons + visual editor draft→promote; uni-app client (PC-5).
  **ADR 0010 / PC-0…PC-6b UI 权限 Manifest：** classpath+GET+置灰菜单+样例按钮+可视化编辑晋升；uni-app 壳。

- **CI-1 green:** Pages/DeadLetter test flakes fixed; runtime image `eclipse-temurin:21-…-noble` (Trivy). Tip `cb3ecf1` / Actions `39702424977`.
  **CI-1 全绿：** Pages/DeadLetter 测试与 noble 运行镜像。

- **IM-A7 inbox compose + groups console:** `/inbox/compose` (system / group-send / org broadcast + file attach); `/inbox/groups` CRUD + members; permission-gated nav and inbox links; BFF reuse.
  **IM-A7 站内信写信与组控制台：** 写信三模式+附件；组与成员管理；权限导航；复用平台 BFF。

- **IM-A6 async inbox fan-out:** Flyway `V45` job+recipient tables; `InboxPort.sendDeferred` / `processFanoutBatch`; `InboxFanoutWorker` after-commit drain into `inbox_entry`; group-send/broadcast accept 200<N≤2000 (`fanoutStatus=PENDING`); reject N>2000. Sync ≤200 unchanged.
  **IM-A6 站内信异步写扩散：** V45 任务表；延迟发送+批次写 `inbox_entry`；提交后 worker 排空；组发/广播支持中等 N；超 2000 拒绝。

- **IM-A5 ORG_BROADCAST sync fan-out:** `POST /api/v1/inbox/broadcast` expands ACTIVE org memberships ∩ sender OrgScope (≤200); `inbox.broadcast`; optional staging attachments; out-of-scope 403.
  **IM-A5 组织广播（同步）：** 按组织成员∩发送者 OrgScope 写扩散（≤200）；需 broadcast；可带附件；越权 403。

- **IM-A4 GROUP_SEND sync fan-out:** `POST /api/v1/inbox/group-send` expands `inbox_group` members (≤200); `inbox.broadcast`; optional staging attachments; empty/over-cap → 400.
  **IM-A4 站内信组群发（同步）：** 展开组成员写扩散（≤200）；需 broadcast；可带暂存附件；空组/超限 400。

- **IM-A3 inbox group CRUD REST:** `/api/v1/inbox/groups` list/create/update/delete + members add/remove; `inbox.group.admin`; tenant fail-closed. No GROUP_SEND yet (IM-A4).
  **IM-A3 站内信组 CRUD REST：** 组与成员管理 API；需 `inbox.group.admin`；租户失败关闭。群发仍后置。

- **IM-A2 inbox attachment upload + system send:** staging (direct + CHUNK-1 reuse) → ObjectStorage; `POST /inbox/system` accepts `attachmentStagingIds`; own-entry list/download; MIME allowlist; fail-closed AuthZ.
  **IM-A2 站内信附件上传与系统发送：** 直传/分片暂存→对象存储；系统发送绑定暂存 id；本人列表/下载；MIME 白名单。

- **IM-A1 inbox attachments + groups schema:** Flyway `V44` `inbox_attachment` / `inbox_group` / `inbox_group_member`; permissions `inbox.broadcast` / `inbox.group.admin`; `InboxPort` bind+own-entry download; `InboxGroupPort` JDBC; sync fan-out ≤200 reject. No REST/UI yet (IM-A2+).
  **IM-A1 站内信附件与分组表：** V44 三表+新权限；发送绑定附件；本人收件可列附件；组端口 JDBC；同步写扩散上限 200。尚无 REST/UI。

- **ADR 0009 Accepted — inbox attachments + groups + mass send:** ObjectStorage + `inbox_attachment`; dedicated `inbox_group` and org `ORG_BROADCAST`; sync fan-out ≤200 / async ≤2000; perms `inbox.broadcast` + `inbox.group.admin`; own-entry download only. [`docs/adr/0009-inbox-attachments-and-group-broadcast.md`](docs/adr/0009-inbox-attachments-and-group-broadcast.md).
  **ADR 0009 Accepted — 站内信附件·分组·群发：** 对象存储附件表；专用组 + 组织广播；同步/异步限额；新权限；仅本人收件可下附件。

- **IM-5 inbox unread badge:** console header polls unread-count ~60s (pause when tab hidden); tenant from cookie; click opens `/inbox`.
  **IM-5 站内信未读角标：** 顶栏约 60s 轮询未读（页签隐藏暂停）；租户来自 cookie；点击进 `/inbox`。

- **IM-4 console inbox:** `web/` `/inbox` — list/filter unread, mark-read, deep link; nav 站内信 (`inbox.read`).
  **IM-4 控制台站内信：** `/inbox` 列表/未读筛选/已读/深链；导航「站内信」。

- **IM-3 workflow inbox hooks:** repair-ticket `request_approval` → ACTION to org candidates; `approve`/`reject`/`close` → INFO to reporter; same-TX `InboxPort` fan-out.
  **IM-3 工作流站内信钩子：** 待审 ACTION 给组织候选人；批准/驳回/关闭 INFO 给报告人；同事务写扩散。

- **IM-2 inbox REST:** `/api/v1/inbox` list / unread-count / entry detail / mark-read (one+batch) / system send; `inbox.read` + `inbox.send.system`; tenant grant fail-closed; own-entry isolation.
  **IM-2 站内信 REST：** 列表/未读/详情/已读/系统发送；权限与租户失败关闭；本人隔离。

- **IM-1 inbox schema + InboxPort:** Flyway `V43` `inbox_message` / `inbox_entry`; JDBC `InboxPort` (send fan-out, list, unread, mark-read, get); permissions `inbox.read` / `inbox.send.system`; TN-3-style isolation tests. No REST/UI yet (IM-2+).
  **IM-1 站内信表与 InboxPort：** V43 双表；JDBC 端口写扩散/列表/未读/已读；权限入库；隔离测试。尚无 REST/UI。

- **ADR 0008 Accepted — in-app messaging MVP decisions locked:** seven recommended defaults (per-tenant SYSTEM fan-out; no auto-purge; no ORG_BROADCAST API; external push deferred; ~60s unread poll; END_USER after uni-app auth; Chinese copy first). [`docs/adr/0008-in-app-messaging.md`](docs/adr/0008-in-app-messaging.md); progress [`docs/messaging/im-progress.md`](docs/messaging/im-progress.md).
  **ADR 0008 Accepted — 站内信 MVP 决策锁死：** 七条建议默认（按租户写 SYSTEM；不自动清理；广播 API 后置；外推后置；约 60s 轮询；终端后置；首波中文）。

- **FE-1 client triad:** ADR [`0007-client-triad.md`](docs/adr/0007-client-triad.md) — `web/` = operator console; `clients/uni-app/` = end-user H5 + WeChat mini-program (uni-app Vue3+Vite, HBuilderX). Root `AGENTS.md`; ARCHITECTURE §2.1; scaffold shell (index + login placeholder). No C-end inside console; no Taro/RN without new ADR.
  **FE-1 客户端三端原则：** ADR 0007；控制台 vs uni-app H5/小程序；根 `AGENTS.md`；脚手架壳页。
- **CI clients H5:** GitHub Actions job `clients-uni-app` runs `npm ci` + `npm run build:h5` (mp-weixin stays local/HBuilderX).
  CI 增加 `clients-uni-app` 仅构建 H5；小程序仍本机/HBuilderX。

### Changed / 变更
- **DX-Onboard-1:** quickstart golden path = `platform-app` + Next.js console (`web/`); HTML routes demoted to read-only demos; optional `web/Dockerfile` + Compose profile `console` (`3b51840`).
  开发者黄金路径改为 platform-app + 控制台；HTML 降为演示；可选 compose console。
- **DX-Docs-1:** mark [`docs/pre-release-assessment.md`](docs/pre-release-assessment.md) superseded; config center plan inventory marked historical (Item 5 DONE); README tip/assessment pointers aligned.
  评估文标过时；配置中心盘点标史料；README 与 tip 对齐。
- **DX-Extend-1:** `DomainActionHandler` SPI (Spring `List` dispatch); migrate all domainAction keys off the runner switch; docs `contributing-domain-actions.md` + `auth-default-matrix.md`.
  领域动作处理器 SPI；迁出 Runner switch；贡献文档与默认认证矩阵。
- **CI-green wave (local):** align operator permission expectations with `workflow.*`; NamingGuard CamelCase token match + `TenantLifecycle` rename; web typecheck fixes (`storageMode`, org-csv).
  本地 CI 等价全绿：权限期望、命名护栏、web 类型检查修复。

## [0.1.0-alpha.1] — 2026-10-09

 — GitHub **pre-release** on tip [`e7dc268`](https://github.com/freshsdok/subjex/commit/e7dc268569159a31c7d6d6ee883d16c79e955af9) (Boot **4.0.8**). **Evaluation build — not production-ready.** CI run [37899546752](https://github.com/freshsdok/subjex/actions/runs/37899546752): test / web / image-scan green. Checklist: `docs/release/v0.1.0-alpha.1-checklist.md`.

Contract preview with thin runtime slices, for evaluation on a trusted network. See `SECURITY.md` before exposing anything.
契约预览 + 运行面薄切片，仅供受信网络内评估。对外暴露前请先读 `SECURITY.md`。

### Added / 新增
- **Pre-alpha capacity track (in `v0.1.0-alpha.1`):** **1** AuthZ Cedar default (`platform.authz.engine=cedar`); **2** controlled migration UX (MigUX); **3** real AI preview + mandatory confirm write-back; **4** horizontal scale MVP (JDBC shared rate-limit + delivery breaker; advertise replicas>1 only when both `jdbc`); **5** independent config center MVP (namespaces + revision/ETag; DB+HTTP, not Nacos). Closing tip before docs收口: capacity+CI at `78520cc` lineage; see `web/PROGRESS.md`.
  **预 alpha 能力轨（已入 `v0.1.0-alpha.1`）：** 1 Cedar AuthZ；2 受控迁移 UX；3 真 AI+强制确认写；4 水平扩展 JDBC 共享；5 配置中心命名空间/ETag。详见 `web/PROGRESS.md`。

- **O1 Model Freeze:** six-concept ontology — ADR + `docs/ontology/*`; review PASS (`github/main` `de1846ae`). Superseded for runtime by **O8 FULL PASS** (legacy thin tables dropped).
  **O1 模型冻结：** 六概念本体文档冻结；运行时以 **O8 FULL PASS** 为准（旧薄表已删）。

- **O2–O8 Organization:** V22 ontology tables → O3 dual-read → O7 DROP `org_unit`/`org_membership` → **O8 FULL PASS** (ontology-only; SubjectPicker/OrganizationPicker; squash on `github/main` `1e431b2`).
  **O2–O8 组织：** 本体持久化直至 O8 全量通过；旧薄表已 DROP。

- **Production gaps P1–P7 (local code+docs):** image-scan workflow + non-root images; management-port probes + NetworkPolicy; secrets rotation / reject `change-me` outside local; single-replica gate (Scale-4d: replicas>1 only with both JDBC shared backends); tenant quotas; **backup/restore drill PASS** (`docs/release/drills/2026-10-09-backup-restore.md`); dual-operator promote + rollback. Checklist: `docs/release/v0.1.0-alpha.1-checklist.md`. **`v0.1.0-alpha.1` tagged** on `e7dc268` (pre-release); still **not** production-ready.
  生产缺口 P1–P7（代码/文档齐；P6 演练 PASS）。**已打 alpha pre-release**；仍非生产就绪。

- **Outbox `DeliveryPort` (OB-1):** transport as a port; default `platform.delivery.transport=socket` keeps `OutboxSocketPublisher` → sample-consumer. Socket demoted to local/quickstart demo (single address, ≤4000 body, shared HMAC — not multi-consumer). Circuit breaker wraps only the selected transport.
  出箱 `DeliveryPort`（OB-1）：传输端口化；默认 socket 演示路径保留；熔断只包所选传输。

- **Outbox Kafka adapter (OB-2):** optional module `subjex-outbox-kafka` (not on default platform-app classpath). `transport=kafka` requires bootstrap servers + topic (fail-fast); non-`local` forbids plaintext (TLS or SASL). Key `tenantId:eventId`; headers `traceparent` / `event-name` / `declaration-version`; body = existing event_body. No ack frame — broker offsets. Own circuit breaker instance.
  出箱 Kafka 适配器（OB-2）：可选模块，默认不进 classpath；缺主机/主题拒启；非 local 禁明文；键与头约定如上；独立熔断实例。

- **Sample flow entity→page→form (post-stage #4)**: `service-note` flow + form + optional flow `entityKey`; in-memory `ServiceNoteStore` + `GET /api/v1/entities/service-note/notes`; domain action `entity.serviceNote.save`. Flyway host wiring remains step 3. See `docs/lowcode-roadmap.md`.
  贯通样例（阶段后第 4 项）：服务备注流程/表单/实体键；内存实体列表 API 与领域动作；Flyway 接入仍属步骤 3。

- **OIDC SSO (Slice E)**: platform as OIDC RP (Authorization Code + PKCE); `operator_idp_link` + pending PKCE state (Flyway `V10`); `GET/POST /api/v1/auth/oidc/{status,start,callback}`; deny unlinked IdP users; admin `GET/PUT/DELETE /api/v1/operators/{login}/idp-link`; SSO issues same opaque Bearer as Slice C and skips local TOTP. Console SSO button + `/api/session/oidc/*` callback; Operators page IdP bind/unlink UI.
  OIDC SSO（切片 E）：平台作依赖方（授权码+PKCE）；绑定表与 PKCE 态（V10）；未绑定拒绝；管理员绑定/解绑；SSO 签发与 C 相同 Bearer 且跳过本地 TOTP。控制台 SSO 与回调；操作员页绑定 UI。

- **TOTP MFA (Slice D)**: `POST /api/v1/auth/mfa/totp/{start,confirm,disable}`, `GET /api/v1/auth/mfa`, login returns `mfaToken` when enrolled, `POST /api/v1/auth/mfa/verify` → Bearer pair; recovery codes (hashed, one-shot); AES-GCM TOTP secret at rest; `platform.mfa.required=false` / `required-for-platform-operator`; console login second step + Operators self enroll/disable. Flyway `V9__operator_mfa.sql`.
  TOTP 多因素（切片 D）：登记/确认/关闭与状态；已登记则登录先发 mfaToken；校验后签发令牌；恢复码摘要入库一次性；密钥 AES-GCM；默认不强制；控制台二次登录与自助登记。迁移 V9。

- **Platform-issued tokens (Slice C)**: `POST /api/v1/auth/login|refresh|logout`; opaque Bearer access (default TTL 30m) + rotating refresh (8h, SHA-256 at rest, family reuse detection); APIs accept Bearer or Basic; password change / disable revokes all families. Console stores only encrypted access/refresh (no Basic-at-rest); proxy silent-refreshes on 401. Flyway `V8__operator_tokens.sql`.
  平台签发令牌（切片 C）：登录/刷新/退出；不透明 Bearer + 轮换刷新（摘要入库、重放吊销整族）；接口接受 Bearer 或 Basic；改密/禁用吊销令牌。控制台只存加密令牌；代理 401 静默刷新。迁移 V8。

- **Tenant management (Slice B)**: permission `tenant.manage`; `/api/v1/tenants` list/get/create, `PATCH` rename, disable/enable (soft `SUSPENDED`); reserved `platform` immutable; task submit to suspended tenant → 403; console `/tenants` (review→confirm). Flyway `V7__tenant_manage.sql`.
  租户管理（切片 B）：`tenant.manage`；列表/创建/改名/软禁用；保留租户不可改；禁用租户拒绝任务；控制台 `/tenants`。迁移 V7。
- **Operator management (thin slice)**: permission `operator.manage`; APIs under `/api/v1/operators` for list/create, disable/enable, change other password, replace tenant grants; `POST /api/v1/operators/me/password` (current password required) for any signed-in operator. One-shot bootstrap remains optional. Console Operators page for self password change + admin list/disable/enable. Flyway `V6__operator_management.sql`.
  操作员管理薄切片：`operator.manage`；列表/创建/禁用/启用/改他人口令/租户授权；自己改密需当前口令。一次性开通仍可选。控制台操作员页。迁移 V6。
- **Operator–tenant authorization**: table `operator_tenant_grant` (subject + tenant id, `*` = all tenants). Tenant-scoped paths (`/tasks/**`, declaration `tenantScoped`) reject missing grant as well as missing `X-Tenant-Id` (fail-closed). Local seed and bootstrap grant `*`.
  操作员—租户授权：`operator_tenant_grant`（`*` 表示全部）。租户作用域路径缺授权与缺头均 403。本地种子与开通写 `*`。
- **Console session store never keeps Basic or password material (Slice C)**: process-memory and Redis hold AES-256-GCM ciphertext of access/refresh tokens only.
  控制台会话从不保留 Basic/口令（切片 C）：内存与 Redis 只存 access/refresh 密文。

- **Console debug UX (stage 6)**: form submit API returns structured success (`submissionId`, `declarationVersion`, `submittedAt`, `effects[]`) and problems (`validation` + fieldErrors, or `permission_denied` + permission name); console `FormDebugPanel` on `/forms` and `/pages/.../new` shows them in one place (zh/en). No designer / online schema edit. See `docs/lowcode-roadmap.md`.
  控制台调试 UX（阶段 6）：提交成功/失败结构化；控制台同处展示校验、权限、落库与副作用摘要；无设计器。

- **Declaration versioning (stage 5)**: required integer `version` (≥ 1) on form / entity / flow YAML (fail-closed); catalog JSON exposes `version`; form submissions persist `declaration_version` (Flyway `V5`); process notes in `docs/declaration-migration.md` (bump version on field change; entity → new migration draft; read old submissions against old versions; rollback = redeploy prior declaration, no auto-drop). No online schema edit. See `docs/lowcode-roadmap.md`.
  声明版本化（阶段 5）：YAML 必填整数 `version`；目录暴露版本；提交落库记下声明版本；迁移策略文档；无在线改 schema。

- **Side-effect catalog (stage 4)**: checked-in `effects/side-effect-catalog.yaml` + `SideEffectKey` (`audit.write`, `task.enqueue`, `extension.invoke`); forms may declare `effects` (samples updated); `FormSideEffectRunner` runs them after validate/domain action via existing Audit / Task / extension ports under the same subject/tenant; unknown effect keys rejected at render (fail-closed). No online designer. See `docs/lowcode-roadmap.md`.
  副作用目录（阶段 4）：检入目录与枚举；表单可声明 `effects`；提交后经已有端口执行；未知键渲染即拒；无设计器。

- **Permission / tenant on declarations (stage 3)**: form / entity / flow YAML require `permission` (fail-closed if missing) and optional `tenantScoped`; samples updated; catalog JSON exposes flags; `DeclarationAccess` enforces declared permission (+ tenant header when scoped) on form/page detail and submissions without new security-config matchers per form. Console greys out entries the operator lacks. See `docs/lowcode-roadmap.md`.
  声明上的权限/租户（阶段 3）：YAML 必填 `permission`、可选 `tenantScoped`；目录暴露标志；API 按声明强制；控制台无权限置灰。

- **Page / flow declare (stage 2)** (`page-declare`): checked-in `flows/*.flow.yaml` (sample `endpoint-publication`) → validated list / detail / submit specs (console paths, `/api/v1/...` apiPaths, post-submit `redirectTo`). `PageCatalog` + `GET /api/v1/pages` / `{flowKey}`; console `/pages` renders from the declaration (list, detail, submit-then-redirect). No visual designer. See `docs/lowcode-roadmap.md`.
  页面/流程声明（阶段 2）：检入 YAML → 校验列表/详情/提交描述；目录与 JSON API；控制台按声明渲染；无设计器。

- **Entity declare (stage 1)** (`entity-declare`): checked-in `entities/*.entity.yaml` (sample `service-note`) → validated render, Flyway-style `CREATE TABLE` draft under `db/migration-draft/`, Java record + CRUD port stub + JDBC sketch (`com.subjex.entity.generated`), and `EntityDraftWriteMain` CLI. Drafts are human-editable and not applied by `platform-app` Flyway; no online schema edit; not wired into `platform-app` yet. See `docs/lowcode-roadmap.md`.
  实体声明（阶段 1）：检入 YAML → 校验渲染、Flyway 风格建表草稿、记录/CRUD 端口/JDBC 草图与 CLI；草稿可人工编辑，未接入 `platform-app` Flyway，无在线改 schema。
- **Contracts** (`platform-contract`): Account / Subject / Identity split, fail-closed tenant guard, single `AuditPort`, deterministic and non-deterministic tasks on one table, outbox, single idempotency / rate-limit / message ports, `DistributedLockPort`, object-storage SPI, compile-time extensions.
- **Database**: JDBC + Flyway `V1`–`V4` shared by MySQL 8.4 and PostgreSQL 16; migration guard refuses a vendor mismatch; no ORM (enforced by ArchUnit). Live startup proof via Testcontainers when Docker is present.
- **Operators**: table-backed HTTP Basic, seven named permissions, read-only admin pages, audit for config overrides, registrations and task submission.
- **Operator bootstrap (one-shot)**: `platform-app` can create/update one operator without hand-written SQL when `--platform.operator.bootstrap=true` (off by default); env `PLATFORM_OPERATOR_LOGIN` / `PLATFORM_OPERATOR_PASSWORD` / optional `PLATFORM_OPERATOR_ROLE`; idempotent by login; password min length 8; process exits after upsert. See `docs/operator-permissions.md`.
- **Shared registry / config / lock** in the platform database (`service_endpoint`, `config_override`, `platform_lock`), with static fallback.
- **Outbox delivery** from `platform-app` to `sample-consumer` over a socket (`SUBJEX-OUTBOX 2`, HMAC, optional TLS), with circuit breaker, background relay, and `traceparent` propagation.
- **Entry gateway** (`entry-gateway`): HTTP forwarder with coarse in-process rate limiting (429).
- **Observability**: `/actuator/prometheus` on all three processes; optional OTLP/HTTP trace export (`PLATFORM_OTLP_ENDPOINT`).
- **Multi-replica slice**: two `platform-app` replicas in `deploy/k8s` and `deploy/compose`; `deploy/load/smoke-load.sh`.
- **JSON API** under `/api/v1` with OpenAPI at `/api/v1/openapi.json`.
- **Forms**: `FormCatalog` over `forms/*.form.yaml` (`endpoint-publication`, `config-override`), `form_submission` store, `POST/GET /api/v1/forms/{formKey}/submissions`, record generator and `FormRecordWriteMain` CLI.
- **Language / skin** modules (zh/en titles, three skins).
- **Optional `model-gateway`** (registers a provider, records one invocation; not on the startup path).
- **Operator console** (`web/`, Next.js): server-side proxy, httpOnly session cookie, optional Redis session store (`SESSION_REDIS_URL`).
- **Images**: multi-stage, non-root Dockerfiles for `platform-app`, `sample-consumer`, `entry-gateway` (not published).
- `SECURITY.md`, this changelog.
- **CI active at `.github/workflows/build.yml`**: on every push and pull request runs `mvn -B test` (Java 21) and, for `web/`, `npm ci` + typecheck + vitest + `next build` (Node 22). Docs pointer only at `docs/ci/README.md`.
  CI 已落到 `.github/workflows/build.yml`：每次 push / PR 跑后端 `mvn -B test` 与控制台 typecheck/test/build；`docs/ci/` 仅作指引。

### Fixed / 修复
- **WP7-4 restore uni-app primary button style:** `vueCompilerOptions.nativeTags` excludes `button`; `GlobalComponents.button` accepts `type="primary" | "default" | "warn"`. Main-action buttons on login, home, profile, files, inbox, and questionnaire pages again use `type="primary"`. `vue-tsc`, H5 and mp-weixin builds passed. SHA `d52f80f`.
  **WP7-4 恢复 uni-app 主按钮样式：** 从 nativeTags 中排除 `button` 并声明组件类型，主操作按钮重新使用 `type="primary"`。vue-tsc 与 H5/小程序构建通过。

- **CI — VendorStartupTest MySQL row size + ready probe (CI-fix-1):** V16 `declaration_migration.sql_text` → **TEXT** (MySQL utf8mb4 InnoDB); V29 widen-after-repair; probes assert **management port** (P2). Local H2 V29 green; live VendorStartupTest needs Docker/CI.
  CI：MySQL 行大小与管理口就绪探针修复；真库启动待 CI Docker 再验。
- **Security / reliability — outbox background relay.** `TaskMessagePort.relayPending` + scheduled `OutboxRelay` retry `PENDING` rows under distributed lock `outbox-relay` until `PUBLISHED` or dead-letter; circuit breaker gains half-open cooldown so a transient consumer outage does not leave rows stuck. Attempt count is not burned while the breaker refuses calls.
  出箱后台重投：调度工人在分布式锁下重试 PENDING，直到送出或死信；熔断半开冷却；拒呼时不消耗尝试次数。
- **Security — outbox frame no longer carries the operator password; TLS fail-closed outside local.** Protocol `SUBJEX-OUTBOX 2` uses HMAC-SHA256 (`OUTBOX_HMAC_SECRET` ≥ 32). TLS (PKCS12) required unless `local` / `platform.delivery.allow-insecure=true`. Local default secret only in `application-local.yml`.
  出箱帧不再带操作员口令；非 local 未配 TLS 则启动失败关闭。协议 2 + HMAC；本机默认密钥仅在 `application-local.yml`。
- **Security — console sessions no longer retain Basic; they encrypt access/refresh tokens at rest (Slice C).** Memory and Redis store `accessTokenEnc` / `refreshTokenEnc` (AES-256-GCM) under `OPERATOR_SESSION_SECRET` when Redis is used.
  控制台会话不再保留 Basic；静态加密的是 access/refresh（切片 C）。
- **Security — `entry-gateway` no longer trusts `X-Forwarded-For` unconditionally.** The rate-limit client id is now the remote address unless the direct peer matches the new `gateway.trusted-proxies` setting (env `GATEWAY_TRUSTED_PROXIES`, IPs/CIDRs, default empty); then the header is read right to left, skipping trusted hops. `server.forward-headers-strategy` is pinned to `none` so Tomcat does not rewrite the remote address under Kubernetes. **Behaviour change:** deployments behind a load balancer must list it in `GATEWAY_TRUSTED_PROXIES`, or all clients share the proxy's bucket.
  入口网关不再无条件信任 `X-Forwarded-For`：默认按远端地址限流；仅当直连方命中新配置 `gateway.trusted-proxies`（默认空）时才从右往左读该头。在负载均衡后面部署时需配置 `GATEWAY_TRUSTED_PROXIES`。
- Full `mvn test` reactor was red: the `ModuleBoundaryArchTest` copies in `sample-consumer` and `entry-gateway` carried rules with no matching classes on that module's classpath (ArchUnit `failOnEmptyShould`). Each copy now keeps only the rules its module can check; all 160 tests pass.
- README / ARCHITECTURE brought in line with the tree (Redis use, multiple forms, three images, duplicated §13–15 headings).

### Known limitations / 已知限制
See `SECURITY.md` for the security-relevant ones. Also:
- Outbox mutual TLS (client certificate) and short-lived delivery tokens are not implemented yet (HMAC + server TLS is the current bar).
- Circuit breaker open-cooldown is per process (not shared across `platform-app` replicas).
- Dead letters are read-only (no replay).
- Object storage has only a local-directory implementation and no HTTP endpoint uses it.
- Rate limit and delivery circuit breaker default to **per-process** (`process`); optional shared **`jdbc`** backends (Scale-4b/4c) — advertise `replicas>1` only when **both** are `jdbc` (Scale-4d). Object storage remains per-pod local directory unless a shared volume/SPI is used.
- `deploy/compose` includes `sample-consumer` (wired like k8s) but not `web/`; full compose image build / end-to-end smoke still optional and not always run in this release cycle.
- `web/` has no Dockerfile or manifests (CI now covers typecheck/test/build; no image yet).
- Activating `.github/workflows/*` on the remote requires a token with the `workflow` scope; until that push lands, GitHub Actions still will not run.
- Console operator create / password reset / grant editor landed (Slice A); role catalog UI still deferred.
- Auto-creating tenants on first granted task submission remains for missing ACTIVE tenants; explicit admin is `/api/v1/tenants` + `/tenants` (Slice B). Hard delete / quotas deferred.
- Opaque Bearer, TOTP MFA, OIDC RP, and **login lockout** landed (Slices C–E + lockout). HTTP Basic remains local/script opt-in; console uses Bearer after login.
- **AuthZ default Cedar**; set `platform.authz.engine=sql` only when Cedar natives are unavailable.
- Alpha **not tagged**; local tip **not squash-pushed**. Remote Actions must re-verify `VendorStartupTest` + `image-scan` before cutting `v0.1.0-alpha.1`.
