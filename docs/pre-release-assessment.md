# 发布前能力评估 / Pre-release Capability Assessment

- 评估日期：2026-10-05（UTC+8）
- 评估对象：`freshsdok/subjex`，远端 `main` = `275fbfd`（GitHub 公共 API 核对，提交时间 2026-10-05 15:08 UTC+8，父提交 `9e62f95`）
- 本地对照：本地 `main` 在 `71f42ee`（远端是“压扁式”提交，历史不同）。逐文件比对远端树与本地 `4c08a2b`：357 个文件完全一致，只有 `docs/node4-progress.md`、`docs/node5-progress.md`（以及本地 `71f42ee` 的 `docs/node6-progress.md`）的进度备注不同。**代码与远端一致，下文的测试结论适用于远端 main。**
- 方法：阅读 `ARCHITECTURE.md`、`README.md`、`docs/*`、`web/README.md`、`web/PROGRESS.md`、`web/docs/design-notes.md`、各模块 `pom.xml` 与关键包；在本机运行 `mvn -o -B -fae test`（Docker 可用，Testcontainers 实际启动了 MySQL 8.4 / PostgreSQL 16）、`web/` 下 `vitest run` 与 `tsc --noEmit`；用 GitHub API 查看 tags / releases / `.github/workflows`。
- 原则：只写在仓库或运行结果里看得到的事实；没有亲自验证的写明“未验证”。

---

## 0. 一句话结论 / TL;DR

subjex 是一个**契约优先、刻意做薄**的 Java 21 / Spring Boot 3.5 模块化单体平台骨架，外加一个 Next.js 操作员控制台。契约面（身份三分、租户 fail-closed、审计、任务/出箱、幂等、锁、限流、存储 SPI）完整且有测试；运行面多数是“薄切片”。

**现在的 main 是红的**：完整 `mvn test` 共 164 个测试，有 4 个 ArchUnit 失败（`sample-consumer` 1 个、`entry-gateway` 3 个），且 CI 工作流还放在 `docs/ci/`，从未在 GitHub 上运行。**这两项修好之前不建议打第一个公开 tag**；修好后适合打 `v0.1.0-alpha.1`（预览版），不适合标成稳定版 `0.1.0`。

> **进度（2026-10-05 16:41 UTC+8，本机未 push）**：B1 已绿；B3 文档已落地；低代码阶段 0–6 已在远端 `77ce565`。**B2 本机已做**：`.github/workflows/build.yml`（Maven + web typecheck/test/build），`docs/ci/` 仅指引；远端写入仍需 `workflow` scope。详见 [`release-prep-progress.md`](release-prep-progress.md)。

> **低代码后续（2026-10-08 UTC+8，本机未 push）**：产品决策已锁定——`entity-declare` **不是**永久只出草稿；将按 [`lowcode-roadmap.md`](lowcode-roadmap.md)「阶段后下一步」接入 `platform-app`（顺序 1 锁定 → 2 domainAction → 4 贯通样例 → 3 实体迁移）。评估表「低代码表单」缺口里「新表单需改代码」将由 domainAction 声明化收窄。

---

## 1. 产品定位 / Product identity & positioning

### 它是什么
- 一个**开源平台框架骨架**（Apache-2.0），把“模块边界、身份、租户、审计、任务、存储、扩展”收成可单独依赖的契约（`platform-contract`），并用两个空进程证明跨进程事件、锁、熔断与追踪（`ARCHITECTURE.md` §1–§3）。
- 目标用户：以后要在它上面开发业务平台的团队（文档写明竞赛平台会是第一个依赖方，但仓库里**没有任何竞赛领域代码**）。
- 技术栈：Java 21、Spring Boot 3.5.16、JDBC + Flyway（**不用 ORM**，ArchUnit 规则 `reads_and_writes_do_not_use_a_model_layer` 守住）、MySQL / PostgreSQL 双库同一套迁移、OpenTelemetry、Micrometer/Prometheus；控制台为 Next.js 16 + React 19。
- 模块（根 `pom.xml`）：`platform-contract`、`platform-app`（宿主进程）、`sample-consumer`（示例消费进程）、`entry-gateway`（入口网关进程）、`model-gateway`（可选、不进启动路径）、`form-render`、`page-language`、`page-skin`；另有独立的 `web/`。
- 规模：Java 主代码 178 个文件、测试代码 67 个文件；Maven 测试 164 个；Web 单元测试 8 个。

### 它不是什么（`ARCHITECTURE.md` §4、§7 明确不做）
- 不是 Nacos / Apollo / Consul 的替代品：服务发现与配置中心都只是 `platform-app` 上共享库表 + HTTP 的薄切片，没有独立注册中心或配置服务器、没有推送/监听。
- 不是低代码平台：没有表单设计器、没有在线表单库、没有全量 CRUD 生成。
- 不是 API 网关产品：没有动态路由、TLS 终结、多上游负载均衡策略。
- 不是 AI 平台：`model-gateway` 只登记提供者、记录一次调用，没有厂商 SDK、智能体或工作流引擎。
- 不是运维平台：K8s 清单只是文件，不会被应用；没有 HPA、数据库 / Redis 运维台。

> 评价：定位边界写得非常清楚，这是本项目最大的优点之一；对外发布时应把“它不是什么”放在 README 首屏，避免用户按“微服务全家桶”的期望去评测。

---

## 2. 能力矩阵 / Capability matrix

图例：**就绪** = 按自身文档承诺的范围完整、有测试；**部分** = 能用但有明显缺口或只在单进程成立；**推迟** = 文档明确不做或尚未开始。

| 领域 | 状态 | 证据（路径 / 接口） | 主要缺口 |
|---|---|---|---|
| 身份三分（Account / Subject / Identity） | 就绪 | `platform-contract/.../identity/*`；`V1__platform_schema.sql`（`account`、`subject`、`subject_identity`） | — |
| 操作员认证与具名权限 | 就绪（薄） | `V2__operator_permission.sql`；`JdbcOperatorDirectory`；`PlatformSecurityConfiguration`；7 项权限 `admin.read/page.read/config.read/config.write/registry.read/registry.write/task.write`；401/403 有测试（`OperatorPermissionSecurityTest`、`JsonApiSecurityTest` 等） | 只有 HTTP Basic；无 OIDC/SSO；**无开通/改密/禁用的 API 或界面**，非 local 环境要手写 SQL（`docs/operator-permissions.md`）；无登录失败锁定 |
| 租户隔离 | 部分 | `TenantGuard` / `DenyWhenTenantMissing`；`TenantEnforcementFilter` 对 `/tasks` 缺 `X-Tenant-Id` 回 403 | 只校验“有没有带头”，不校验“是否有权访问该租户”；`JdbcTaskMessagePort.ensureTenant` 会**在首次提交时自动建租户**；大部分操作面（`/api/v1`、`/admin`、`/config`、`/registry`）不属租户 |
| 审计 | 就绪（薄） | 单一 `AuditPort` / `JdbcAuditPort`；`config.override`、`registry.register`、`submit-task` 写审计；`GET /admin/audit`、`GET /api/v1/audit`、`/audit` 页面 | 无检索/过滤/导出；无防篡改；读取无分页参数 |
| 服务发现 | 就绪（薄切片） | `ServiceRegistry`/`ServiceRoster`；`JdbcServiceRegistry`（表 `service_endpoint`，`V3`）；`POST/GET /registry/services`；`FallbackServiceRegistry` + 静态兜底；`PlatformSelfRegistrar` 用真实绑定端口 | 状态只有 `up/unknown`（读时 TCP 探测）；无心跳/过期剔除；无独立注册中心（刻意推迟） |
| 配置中心 | 就绪（薄切片） | `ConfigSource`、`OverridingConfigSource`、`JdbcConfigOverride`（表 `config_override`）；`GET/POST /config/entries`、`PUT /api/v1/config/{key}`；`HttpConfigSource` 回退本地 | 无版本/历史/回滚、无命名空间、无变更推送；`ConfigCatalog` 只关注少量键 |
| 分布式锁 | 就绪 | `DistributedLockPort`；`JdbcRowLock`（表 `platform_lock`，持有者 + 到期时间，过期可接手）；任务提交按 `tenant/idempotencyToken` 加锁 | 基于数据库行，无续租/看门狗 |
| 幂等 | 就绪 | `IdempotencyPort` / `JdbcIdempotencyPort`；`POST /tasks` 强制 `X-Idempotency-Token` | — |
| 任务（确定性 / 非确定性） | 就绪（契约） | `platform-contract/.../task/*`（15 个类）；同一张 `platform_task` 表；非确定性多 `model_id/input_digest/human_confirmation`；`POST /tasks`、`/tasks/{id}/attempts`、`/tasks/{id}/confirmation` | 重试靠调用方发 attempts，无调度 |
| 出箱事件投递 | 部分 | `JdbcTaskMessagePort.submit`：事务提交后读出箱行，经 `OutboxSocketPublisher` 用 TCP 推给 `sample-consumer`；`SamplePathCircuitBreaker`；`traceparent` 在帧上；`CrossApplicationDeliveryTest` | **只在提交时同步尝试一次**；仓库中没有后台重投（未见 `@Scheduled` 或 relay），失败的出箱行停在 `PENDING`；消费者固定为一个 host/port；帧里带操作员明文口令，套接字无 TLS |
| 死信 | 部分 | 表 `dead_letter`；`GET /admin/dead-letters` | 只读，无重放/清理接口 |
| 对象存储 | 部分 | `ObjectStorage` SPI + `LocalDirectoryObjectStorage`（本地目录，按租户分目录）；`META-INF/services` 描述文件 | 只有本地目录实现；无 S3/MinIO；**没有任何 HTTP 接口使用它**；多副本下各副本目录不共享（compose 用容器内 `/tmp/objects`） |
| 数据库端口 / 迁移门禁 | 就绪 | `ConfiguredConnection`、`MigrationGuard`（产品名与 `platform.connection.vendor` 不一致拒绝服务）；Flyway `V1–V4` | — |
| 真库证明 | 就绪 | `VendorStartupTest`：本次运行 **2/2 通过**（Testcontainers `mysql:8.4`、`postgres:16-alpine`） | 无 Docker 时跳过（文档已说明） |
| 入口网关 / 限流 | 部分 | `entry-gateway`（`UpstreamProxyFilter`、`GatewayRateLimitFilter`、`GatewayRateLimit` 实现单一 `RateLimitPort`），超限 429；`platform-app` 内另有 `SingleProcessRateLimit`（每分钟 60） | 进程内固定窗口，多副本各算各的；`ClientIdentity` **无条件信任 `X-Forwarded-For` 首段**，可伪造绕过；请求/响应体整体缓冲（`HttpResponse<byte[]>`）；无 TLS、无路由 |
| 可观测 | 部分（基础就绪） | 三个进程 `/actuator/prometheus`（匿名）；`TracingConfiguration` 配了 `PLATFORM_OTLP_ENDPOINT` 时 OTLP/HTTP，否则 `DiscardingSpanExporter`；存活/就绪探针分开 | 无看板/告警规则；未见结构化日志或日志-trace 关联；指标端点匿名，靠网络隔离 |
| 多副本 | 部分 | 登记/配置/锁在共享库；`deploy/k8s/platform-app.yaml` `replicas: 2`；compose `--scale platform-app=2` + 网关 DNS 轮询；`deploy/load/smoke-load.sh` | 限流、熔断器、对象存储仍是单进程状态；compose 中两个副本都启用 `local` profile 各自写种子操作员（并发行为未验证）；**本机没有 `subjex/*` 镜像，compose 栈在本次评估中未验证** |
| 部署产物 | 部分 | 三个多阶段 Dockerfile（非 root uid 10001）；`ContainerImageTest` 只做文本核对；`deploy/k8s/*.yaml` 三个工作负载；`deploy/compose/docker-compose.yml` | 镜像未推送任何仓库；compose **不含 `sample-consumer` 和 `web`**（任务提交在 compose 里必然投递失败并触发熔断）；`web/` 无 Dockerfile、无 K8s 清单；compose 健康检查依赖运行镜像里有 `curl`（未验证） |
| 操作员控制台（web） | 部分（可用于演示/内网） | `web/`：登录、概览、services/config/audit/deploy/forms/codegen 页；服务端代理 `app/api/platform/[...segments]`；httpOnly + `sameSite=strict` 会话；Redis 会话（`SESSION_REDIS_URL`）；OpenAPI 生成类型；本次 `vitest` **8/8 通过**、`tsc` 无错误 | 不在 CI；无 e2e 测试入库（截图用的 Playwright 在仓库外）；**Redis 中 Basic 头已 AES-GCM 加密（需 `OPERATOR_SESSION_SECRET`；进程内存仍明文）**；无部署产物 |
| JSON 接口 / OpenAPI | 就绪 | `/api/v1/*`；`/api/v1/openapi.json`（需登录）；`web/openapi.json` | 无版本兼容策略说明 |
| 低代码表单 | 部分（按设计） | `form-render`：`FormCatalog` 加载 `classpath*:forms/*.form.yaml`（现有 `endpoint-publication`、`config-override` 两份）；`V4__form_submission.sql` + `JdbcFormSubmissionStore`；`POST/GET /api/v1/forms/{formKey}/submissions`；按表单核对写权限 | 无设计器、无在线表单库（刻意推迟）；新表单需改代码并在端点里登记写权限 |
| 代码生成 | 部分（按设计） | `FormRecordGenerator` + CLI `FormRecordWriteMain`；`GeneratedRecordMatchesFormTest` 核对已检入 record 与 YAML 一致 | 只生成 record，无校验注解、控制器、表、迁移（刻意推迟）；CLI 需手工拼 classpath |
| 语言 / 外观 | 就绪（薄） | `page-language`（中/英）、`page-skin`（三套外观）；`/language`、`/skin`；控制台语言/外观切换 | — |
| 可选模型网关 | 就绪（最小） | `model-gateway`：`RegisteredModelGateway`（内存 Map），`platform-app` 不依赖 | 无持久化、无真实调用 |
| 架构守护 | **就绪（本地；远端待合入）** | `ModuleBoundaryArchTest`、`NamingGuardArchTest` 等；B1 已在 `eccd458` 修好 | 远端 main 合入修复前仍红；见第 3 节 B1 |

---

## 3. 阻塞项与非阻塞项 / Release blockers vs non-blockers

### 阻塞项（打第一个公开 tag 之前必须处理）

**B1. 完整构建是红的（评估时已复现；本地已修）**
- 评估时：`mvn -o -B -fae test`：164 个测试，**4 个失败**，`sample-consumer` 与 `entry-gateway` 两个模块 FAILURE。
- 失败全部是 ArchUnit 的 “failed to check any classes”（`failOnEmptyShould`）：
  - `sample-consumer/.../ModuleBoundaryArchTest.java`：`gateway_does_not_depend_on_the_host`（无 gateway 类）。
  - `entry-gateway/.../ModuleBoundaryArchTest.java`：`host_does_not_depend_on_the_sample`、`sample_does_not_depend_on_the_host`、`host_does_not_depend_on_the_gateway`（无 host/sample 类）。
- 来源：节点四把同一份规则复制进两个模块，未跑完整 reactor。
- **本地修复（2026-10-05，提交 `eccd458`，未 push）**：各模块副本只保留本模块测试 classpath 能匹配到类的规则。复验 `mvn -o -B -fae test` → **BUILD SUCCESS，160 测试 / 0 失败 / 0 错误 / 0 跳过**。远端 `275fbfd` 在合入该修复前仍是红的。

**B2. CI 从未运行**
- 工作流仍在 `docs/ci/build.yml`，`docs/ci/README.md` 写明“移到位之前 CI 不运行”；GitHub API 确认远端没有 `.github/workflows/`。
- 公开发布时没有绿色 CI 徽章，B1 这类回归就是这样漏进 main 的。需要有 `workflow` 权限的人执行 `git mv docs/ci/build.yml .github/workflows/build.yml`。建议同时加上 `web/` 的 `npm ci && npm run typecheck && npm test && npm run build`。

**B3. 文档与现状不一致（会直接误导首批用户）**
- `README.md`：“There is no Nacos and no Redis” 已过时（节点五起控制台会话用 Redis，compose 起了 Redis）；测试清单不含网关、Prometheus、OTLP、表单提交；“form-render 把 `endpoint-publication.form.yaml`……”仍按单表单描述（节点六已有多表单目录）。
- `ARCHITECTURE.md`：§13、§14、§15 各出现两次（中英各一个同号标题）；§7 仍写“一份声明式表单”；§11 仍写“两份 Dockerfile / 两个镜像”（现在是三个）；§2 写“薄管理台：只读”，但控制台已能提交表单写配置与登记。
- 缺 `CHANGELOG` / release notes、`SECURITY.md`（漏洞报告渠道）、`CONTRIBUTING.md`。对公开开源发布来说，至少要有 release notes 和安全报告渠道。

### 非阻塞项（可以带着发预览版，但要写进 release notes 的“已知限制”）

- **许可证：基本合规。** `LICENSE` 为 Apache-2.0 全文，`NOTICE` 写明 “Copyright 2026 The subjex authors”，GitHub 识别为 `apache-2.0`。附录里的 `Copyright [yyyy] [name of copyright owner]` 是标准模板，不是问题。可选：源文件加 SPDX 头。
- **出箱没有后台重投**：首次投递失败的事件停在 `PENDING`，需要在已知限制里写明，或补一个基于 `JdbcRowLock` 选主的 relay。
- **compose 不完整**：不含 `sample-consumer`、`web`；本次评估没有构建出 `subjex/*` 镜像，compose 全栈与 `smoke-load.sh` 在本机未验证（节点进度里写了 gateway jar 冒烟和 compose 方案，但未见 compose 全栈实测记录）。
- **K8s 清单只是样例**：占位镜像、不应用、无 Ingress/Secret/NetworkPolicy；`docs` 已如实说明。
- **安全默认值适合内网演示，不适合公网**：见第 5 节。
- **控制台无部署产物、不在 CI**。
- **技术债登记**：`web/docs/design-notes.md` 只登记了 TD-1（已完成），“待讨论”为空；但上面列出的单进程限流、出箱重投、Redis 中存认证头等都没有进入技术债表。

---

## 4. 发布标签建议与前 5 项动作 / Tag readiness & top-5 actions

### 标签建议
- **现在：不打 tag**，main 保持 `0.1.0-SNAPSHOT`。理由：构建是红的（B1），CI 不存在（B2）。
- **B1 + B2 + B3 处理完、CI 绿之后**：可以打 **`v0.1.0-alpha.1`**（或 `0.1.0-M1`），GitHub Release 标为 pre-release，正文写清“契约预览、运行面薄切片、仅限内网/评估”。
- **稳定版 `v0.1.0`**：建议至少等到出箱 relay、compose 全栈实测（含 sample-consumer）和操作员开通方式（CLI 或 API）落地之后。
- 说明：目前远端没有任何 tag 或 release（GitHub API 返回空），Maven 版本是 `0.1.0-SNAPSHOT`，镜像标签也都是 `0.1.0-SNAPSHOT`；打 tag 时要同步把 pom、Dockerfile 注释、`deploy/k8s` 镜像名、`ContainerImageTest` 期望值一起改掉，或者约定 tag 不改 pom（二选一，并写进发布说明）。

### 前 5 项发布前动作（按优先级）
1. **修 4 个 ArchUnit 失败，让 `mvn test` 在全部 9 个模块上通过**（B1）— **本地已完成**（`eccd458`，160/0/0/0）。
2. **把 CI 移到 `.github/workflows/`**，补上 `web/` 的 typecheck / test / build；考虑在 CI 里开 Docker 让 `VendorStartupTest` 真跑（本次在本机 2/2 通过）。
3. **同步文档**：修 README 的 Redis / 测试清单 / 多表单描述，修 ARCHITECTURE 重复节号和过时段落；新增 `CHANGELOG.md`（或首个 release notes）和 `SECURITY.md`。— **本地已完成**（`7cff7ed`、`233d800`）。
4. **跑一次真实 compose 全栈**：`docker compose -f deploy/compose/docker-compose.yml up --build --scale platform-app=2` + `deploy/load/smoke-load.sh`，确认 JRE 镜像里的 `curl` 健康检查可用；把 `sample-consumer` 加进 compose，或者在 compose README 里明说任务投递在该栈里会失败。
5. **写一份“安全与部署边界”说明**（放进 README 或 `SECURITY.md`）：Basic 认证、Redis 中的会话内容、出箱帧中的口令、匿名指标端点、`X-Forwarded-For` 信任，明确“仅限受信网络”；如果有余力，优先修 XFF 信任（只在配置了可信代理时才取 XFF）和 Redis 会话内容（改存会话号到服务端短期令牌，或至少加密）。

---

## 5. 风险与“差评式”缺口 / Honest risks & negative-review gaps

下面按成熟产品常见差评的口吻，列出一个真实评测者最可能挑出来的问题。

1. **“clone 下来 `mvn test` 就红。”** 对开源项目第一印象最伤（B1）。
2. **“没有 CI 徽章，不知道 main 能不能用。”**（B2）
3. **“说是 outbox，但失败不重投。”** 出箱只在提交时同步试一次，没有后台 relay；熟悉 outbox 模式的人会第一时间指出这点。
4. **“说是服务发现/配置中心，其实就是一张表。”** 没有推送、监听、版本、回滚、命名空间、心跳剔除。文档已经很诚实，但标题里用了“发现”“配置中心”，容易被拿去和 Nacos/Apollo 比较。建议对外统一叫“共享登记表 / 配置覆盖层”。
5. **“说是低代码，其实只有两份 YAML。”** 无设计器、新表单需改代码、生成器只出 record。同样是定位措辞的风险。
6. **“多副本只是‘能起两个’。”** 限流、熔断、对象存储都是进程内状态；网关限流可被伪造 `X-Forwarded-For` 绕过。
7. **“安全只适合内网演示。”**
   - HTTP Basic 每请求带口令；无锁定、无 MFA、无 SSO。
   - 控制台 Redis 会话里 Basic 头已 AES-256-GCM 加密（`OPERATOR_SESSION_SECRET`）；无密钥的 Redis 泄露不再直接等于口令泄露。进程内存会话仍持明文 Basic；后续可改为平台签发操作员 API 令牌。
   - `platform-app → sample-consumer` 的出箱帧里带操作员明文口令，套接字无 TLS。
   - `/actuator/prometheus` 匿名。
   - compose 默认 `local` profile + `platform-operator/change-me`；K8s 清单不启用 `local`，但非 local 环境开通操作员只能手写 SQL。
8. **“租户隔离只拦‘没带头’。”** 带任意 `X-Tenant-Id` 都能提交任务，且会自动建租户；操作员与租户之间没有授权关系。
9. **“对象存储只有本地目录，也没有接口用它。”** SPI 存在，但对使用者没有可见价值。
10. **“控制台不能部署。”** 没有 Dockerfile/K8s/compose 条目，也不在 CI 里；只有 8 个单元测试，没有 e2e。
11. **“镜像拉不到。”** 镜像未发布到任何仓库，必须本地构建。
12. **“文档前后矛盾。”** README 说没有 Redis，compose 里却有 Redis；ARCHITECTURE 节号重复（B3）。
13. **“项目很年轻。”** GitHub 上 0 star、0 issue，所有提交集中在 2026-10-05 一天内，以压扁式提交推送（本地历史与远端历史不一致）；外部贡献者看不到细粒度历史，`git blame` 价值有限。

### 同样要写进报告的优点（避免只有差评）
- 边界与“不做”清单写得非常清楚，且每个 UI 决策都附了 Nacos / Consul / Keycloak 等真实 issue 作为反例（`docs/*-ui.md`、`web/docs/design-notes.md`）。
- 契约层干净：`platform-contract` 无宿主依赖，ArchUnit 守住不用 ORM、模块不互相依赖（问题只是复制规则时没处理空规则）。
- 真库证明是真的：本次在 Docker 上 `VendorStartupTest` 对 MySQL 8.4 与 PostgreSQL 16 均通过，测试没有用 H2 冒充真库。
- 安全默认开启：除探针与指标外全部要认证；每条路径一项具名权限；控制台浏览器端不持有口令。
- 中英双语注释和文档一致性高，命名遵守“表是对象、字段是事实、状态是枚举”。

---

## 附录 A：本次运行记录 / Evidence log

| 检查 | 结果 |
|---|---|
| `curl https://api.github.com/repos/freshsdok/subjex/commits/main` | `275fbfd6bdb6…`，2026-10-05 15:08 UTC+8，父 `9e62f95` |
| 远端树 vs 本地 `4c08a2b` | 357/357 文件；仅 `docs/node4-progress.md`、`docs/node5-progress.md` 不同 |
| 远端 `.github/workflows` | 404（不存在） |
| 远端 tags / releases | 均为空 |
| 远端 license | Apache-2.0（GitHub 识别） |
| `mvn -o -B -fae test`（JDK 21.0.12，Maven 3.9.9，Docker 可用） | contract 31、form-render 6、page-language 2、page-skin 2、platform-app 93（含 `VendorStartupTest` 2/2）、sample-consumer 14（**1 失败**）、model-gateway 2、entry-gateway 14（**3 失败**）；合计 164，失败 4，跳过 0；BUILD FAILURE |
| `web/`：`npx vitest run` | 3 个文件、8 个测试全部通过 |
| `web/`：`npx tsc --noEmit` | 无错误 |
| `web/`：`next build` | 本次未运行；`web/PROGRESS.md` 6a 记录曾通过 |
| 本机 `docker images` | 有 `mysql:8.4`、`postgres:16-alpine`、`redis:7-alpine`；**无 `subjex/*` 镜像**，compose 全栈未验证 |

## 附录 B：Flyway 迁移 / Migrations
- `V1__platform_schema.sql`：`tenant`、`account`、`subject`、`subject_identity`、`audit_entry`、`platform_task`、`outbox_event`、`dead_letter`、`idempotency_claim`
- `V2__operator_permission.sql`：`platform_permission`、`platform_role`、`role_permission`、`subject_role`、`operator_credential`（+ `audit_entry.action_target`）
- `V3__shared_registry_config_lock.sql`：`service_endpoint`、`config_override`、`platform_lock`
- `V4__form_submission.sql`：`form_submission`
