# 发布准备进度 / Release-prep progress

- 日期：2026-10-05（UTC+8）
- 对照评估：[`pre-release-assessment.md`](pre-release-assessment.md)
- 远程 `github/main` tip：`6bc9fdc`（CI 修后与本机 d70e23b 同树）；其后本机出箱切片未 push。
- 本机提交（未 push）：见下方「本机提交」与各切片 SHA

## 已完成 / Done

### B1 — `mvn test` 全绿
- 做法：从 `sample-consumer` / `entry-gateway` 的 `ModuleBoundaryArchTest` 副本中删掉本模块测试 classpath 匹配不到类的规则（`failOnEmptyShould` 保持开启）。
  - `sample-consumer`：去掉 `gateway_does_not_depend_on_the_host`（无 gateway 类）。
  - `entry-gateway`：去掉 `host_does_not_depend_on_the_sample`、`sample_does_not_depend_on_the_host`、`host_does_not_depend_on_the_gateway`（无 host/sample 类）。
  - “契约不依赖进程”与“不用模型层”两边都保留；宿主/示例规则在 `sample-consumer`，“网关不依赖宿主”在 `entry-gateway`。
- 验证：
  - `mvn -o -B -pl sample-consumer,entry-gateway -am test` → **BUILD SUCCESS**（sample-consumer 13、entry-gateway 11）。
  - `mvn -o -B -fae test`（全 reactor）→ **BUILD SUCCESS**，9 个模块 SUCCESS，**160 测试 / 0 失败 / 0 错误 / 0 跳过**（有 Docker，`VendorStartupTest` 真跑了 MySQL 8.4 与 PostgreSQL 16）。
- 提交：`eccd458` — `test: keep only module-applicable ArchUnit boundary rules (B1)`

### B3（文档部分）— README / ARCHITECTURE / CHANGELOG / SECURITY
- README：状态与“它不是什么”、Redis 仅用于控制台会话、完整测试清单（含网关/OTLP/多表单）、compose 内容、可观测、三份 Dockerfile、控制台会话说明。
- ARCHITECTURE：§13–15 中英同号改为中文主节 + English summary 子节；标注 §2/§7/§10/§11/§12 过时表述；新增 §16 发布前状态。
- 新增 `CHANGELOG.md`（Unreleased / 拟 `v0.1.0-alpha.1`）与 `SECURITY.md`（报告渠道 + 已知限制：Redis Basic 头、出箱口令无 TLS、XFF 伪造、自动建租户、匿名指标、local 种子操作员）。
- 提交：
  - `7cff7ed` — `docs: align README and ARCHITECTURE with current tree (B3)`
  - `233d800` — `docs: add CHANGELOG and SECURITY with pre-release known limits (B3)`

### 安全加固（小切片）— 网关 XFF 信任条件化
- `entry-gateway` 默认只用远端地址做限流客户端标识；新配置 `gateway.trusted-proxies`（`GATEWAY_TRUSTED_PROXIES`，IP/CIDR，默认空）命中直连方时才从右往左读 `X-Forwarded-For`。`server.forward-headers-strategy: none` 防止 K8s 下 Tomcat 自动采信。
- 新增 `TrustedProxies`、`ForwardedHeaderSettings`；`ClientIdentity` 改为 bean。entry-gateway 测试 11 → 25。
- 提交：`fix: only trust X-Forwarded-For when gateway allows it`

### 部署切片 — compose 加入 sample-consumer
- `deploy/compose/docker-compose.yml`：新增 `sample-consumer` 服务；`platform-app` 设置 `SAMPLE_CONSUMER_HOST=sample-consumer` / `SAMPLE_CONSUMER_PORT=19081`（与 k8s 一致）；保留 Redis、`--scale platform-app=2`、gateway；不含 `web/`。
- 同步 `deploy/compose/README.md`、根 README 与 CHANGELOG 已知限制表述。
- 本切片未强制全栈镜像构建/冒烟（可选且须很快）。
- 提交：`deploy: add sample-consumer to local compose stack`

### 安全加固（小切片）— Redis 会话凭据静态加密
- `web/src/server/operator-session.ts`：启用 `SESSION_REDIS_URL` / `REDIS_URL` 时，Redis 存 `{ loginName, credentialHeaderEnc, expiresAtMillis }`；`credentialHeaderEnc` 为 AES-256-GCM（密钥由 `OPERATOR_SESSION_SECRET` SHA-256 派生，密钥至少 32 字符，缺失则明确报错）。进程内存路径仍存明文（本地/单测）。
- 单测：加解密往返、密钥过短/缺失、Redis 已配无密钥时 open/find 失败；原有内存会话用例保留。
- 文档：`SECURITY.md` #2、根 README、`web/README.md`、`CHANGELOG` Fixed。
- 远程 `main` tip：`2476ed4`（本切片之前已 push 的 tip；本提交仅本地）。
- 提交：`0e40aba` — `fix(web): encrypt operator credentials at rest in Redis sessions`

### 操作员开通（小切片）— bootstrap 无需手写 SQL
- `OperatorBootstrap`：按 `account.login_name` 幂等 upsert（account / subject / platform identity / credential / 单一 role）；口令最短 8；未知角色拒绝。
- `OperatorBootstrapConfiguration`：仅当 `--platform.operator.bootstrap=true`（默认 `false`）时装配；读 `PLATFORM_OPERATOR_LOGIN` / `PLATFORM_OPERATOR_PASSWORD` / 可选 `PLATFORM_OPERATOR_ROLE`；打印 `BOOTSTRAP` 警告；写入后 `SpringApplication.exit` 退出进程。
- 测试：`OperatorBootstrapTest`（H2 PostgreSQL 模式）覆盖创建、reader 角色、幂等改密改角色、短口令/空登录/未知角色拒绝。
- 文档：`docs/operator-permissions.md`、`SECURITY.md` #7、`CHANGELOG`、根 README。
- 范围：无用户管理 UI / 改密 / 禁用 API。
- 提交：见下方「本机提交」。

### B2 — CI 落到 `.github/workflows/`（本机完成，远端待 workflow 权限 push）
- 新增 `.github/workflows/build.yml`：`test` job（`mvn -B test`，Java 21）+ `web` job（`web/` 下 `npm ci` / typecheck / test / build，Node 22）。
- `docs/ci/build.yml` 已删；`docs/ci/README.md` 改为指向正式路径，并注明写入工作流需要令牌 **`workflow`** scope。
- 同步 CHANGELOG Unreleased、ARCHITECTURE §11 / §16。
- **远端**：经 Git Data API / push 创建 `.github/workflows/*` 通常需要 `workflow` 权限；本机 pack 已备（见 `/workspace/subjex-ci-push/`），未从 box push。落地前 GitHub Actions 仍不跑。


### 安全加固（小切片）— 出箱后台重投 + HMAC/TLS
- **A. Relay**：`TaskMessagePort.relayPending` + `@Scheduled OutboxRelay`；锁名 `outbox-relay`；熔断半开冷却；拒呼不烧 attempt。
- **B. Transport**：协议 `SUBJEX-OUTBOX 2`，帧内 HMAC-SHA256（`OUTBOX_HMAC_SECRET` ≥32），去掉操作员口令；TLS（PKCS12）在非 `local` 失败关闭；`application-local.yml` / compose 本机逃生舱；k8s 清单要求 Secret + `outbox-delivery-tls` 卷。
- **C. Docs**：`SECURITY.md` #3、`CHANGELOG`、`ARCHITECTURE` §6。
- 仍余：mTLS 客户端证书、短时投递令牌、熔断状态跨副本共享。
- 提交：见下方「本机提交」。

## 刻意跳过 / Skipped this slice

- **完整 compose 镜像构建 / 全栈实测**：可选；compose 已含 `sample-consumer`，仍不含 `web/`。本切片未强制 `--build` 冒烟。

## 仍阻塞正式 tag / Still blocking a public tag（2026-10-09 docs收口后）

1. **远端 CI 未验绿**：本机 tip 含 O8 + items 1–5 + CI-fix-1（MySQL TEXT / 管理口探针）+ docs收口，**尚未 squash-push**。push 后须确认 `mvn test`（含 Docker `VendorStartupTest`）与 `image-scan` 绿。
2. **image-scan**：历史上有 “Set up job” 基建失败；代码侧工作流仍在；远端再确认。
3. **可选 compose 全栈冒烟**（非硬门禁）：`--scale platform-app=2` 时须按 Scale-4d 打开双 JDBC 共享后端。
4. **非阻塞已知限制**（可进 alpha release notes）：出箱 mTLS/短时令牌；对象存储仍按副本本地盘；网关限流仍按进程。

P6 备份演练：**PASS**（`docs/release/drills/2026-10-09-backup-restore.md`）。**无 `DRILL-PENDING.md`。**

## 建议的下一个标签

远端 CI 绿且文档对齐后打 **`v0.1.0-alpha.1`**（pre-release），不要标稳定 `0.1.0`。当前仍是 `0.1.0-SNAPSHOT`，**不要打 tag**（本片只做 docs收口）。

## 本机提交 / Local commits (not pushed)

本机 `main` 与远端 `77ce565` 历史分叉（API squash），树在 B2 前相同。B2 提交见下表最后一行（本文件随提交写入）。

| SHA | 说明 |
| --- | --- |
| `0e40aba` | `fix(web): encrypt operator credentials at rest in Redis sessions` |
| `eb49df5` | `feat: bootstrap operator without hand-written SQL` |
| `266b862` | low-code stages 0–6 tip（与远端 `77ce565` 同树） |
| （本文件所在提交） | `ci: activate GitHub Actions workflow with Maven and web jobs` |
| `33f2de1` | `feat(outbox): background relay for PENDING rows` |
| `67c17bb` | `feat(outbox): HMAC auth and TLS; drop password from frame` |
| （本文件所在提交） | `docs: outbox relay and transport security` |

## O1 — Ontology model freeze（2026-10-09）

- 文档包：`docs/adr/0001-o1-organization-ontology.md` + `docs/ontology/*`
- 冻结六概念；十问已书面回答；内审 **approve**；已推 `github/main` `de1846ae`
- 交叉引用：`ARCHITECTURE.md` §18、`docs/lowcode-roadmap.md`、`SECURITY.md` #9

## O2 — Persistence（2026-10-09）

- Flyway `V22__organization_ontology.sql`：`organization` / `membership` / `organization_relation` / `tenant_organization`
- `JdbcOrganizationStore`：存在性校验、CONTAINS 禁自环/禁环；旧表其后在 O7/O8 **已 DROP**（本条保留为 O2 当时状态）
- 门禁：MODEL/MEM/ORG/TENANT 单测；AUTH/MIG/DECL `@Disabled` 桩
- Subject / Tenant 沿用 V1（不新建 subject 表）
- 提交：本地 `feat(org): O2 persist organization membership relation tenant_organization`（未 push）

## O3 — Compatibility & Backfill（2026-10-09）

- Flyway `V23__org_unit_organization_map.sql`：旁路映射表
- `OrganizationOntologyBackfill`：按租户 1:1 回填 organization / tenant_organization / CONTAINS / membership；禁跨租户同名合并
- `JdbcOrgDirectory`：完整回填后优先读新表并投影；写仍落旧表 + 写透；回滚不删旧表
- 策略文档：`docs/ontology/o3-dual-read.md`
- 门禁：MIG-01 / MIG-02 / MIG-03（`OrganizationOntologyBackfillGatesTest`）
- 提交：本地 `feat(org): O3 backfill org_unit to organization with dual-read`（未 push）

## O4 — Authorization（2026-10-09）

- OrgScope 五模式；Membership + OrganizationRelation 推导；fail-closed
- 门禁 AUTH-01..05；提交：`878ba35`

## O5 — API / Console（2026-10-09）

- 新 API：`/api/v1/organizations`（列表/详情/写入）+ `/memberships`
- 旧 `/api/v1/org/**`：`@Deprecated` + `Deprecation`/`Link`/`Warning` 响应头
- 控制台 `/org` 改走新 API；写透旧表维持 O3 双读
- 安全单测：`OrganizationApiSecurityTest`
- 文档：`docs/ontology/o5-api-console.md`
- 提交：本地 `feat(org): O5 organization API and console migration`（未 push；tip 见 git log）

## O6 — Zero-code refs（2026-10-09）

- 字段：`subjectRef` / `organizationRef`（解析仍接受 `userRef` / `orgRef`）；拒绝 organizationUnitRef/tenantOrgUnitRef
- 积木：SubjectPicker + OrganizationPicker（O8-5 起旧 UserPicker/OrgPicker **已删除**）
- 向导/样例写出规范名；报修单 assignee = subjectRef
- 门禁 DECL-01 / DECL-02：`DeclarationRefGatesTest`
- 文档：`docs/ontology/o6-zero-code.md`
- 提交：本地 `feat(lowcode): O6 organizationRef and subjectRef with pickers`（未 push）

## Docs收口 / Alpha readiness tip（2026-10-09）

- Local tip lineage includes: O8 FULL PASS, AuthZ Cedar, MigUX, AI confirm, Scale-4d, Config-5d, CI-fix-1 (`78520cc`), then this docs align commit.
- Aligned: `ARCHITECTURE.md` §16/§18, `docs/lowcode-roadmap.md` pickers/O8, `CHANGELOG` Unreleased, `SECURITY` §9/Cedar+O8, checklist, `web/PROGRESS.md`.
- **Not done:** squash-push to GitHub; cut `v0.1.0-alpha.1`; claim production-ready.
