# Changelog / 变更记录

Format loosely follows [Keep a Changelog](https://keepachangelog.com/). Maven coordinates remain `0.1.0-SNAPSHOT` until a stable cut; first public tag is **`v0.1.0-alpha.1`**.
格式大致参照 Keep a Changelog。Maven 坐标仍为 `0.1.0-SNAPSHOT`；首个公开 tag 为 **`v0.1.0-alpha.1`**。

## [Unreleased]

Post-alpha.1 work. Tree may move past the tagged tip (`e7dc268`); recent tip lineage includes PC-deepen-2/3 (local) on `49172c3`+.

### Added / 新增
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
