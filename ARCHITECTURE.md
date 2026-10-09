# 开源平台框架架构规范（第一版）

新仓是独立框架，不包含竞赛业务，也不依赖竞赛仓。现有产品只提供已经跑通的契约当起点。竞赛平台以后依赖本框架，用来提高开发效率。

## 1. 目标

把模块边界、身份、租户、审计、任务、存储、扩展收成可单独使用的平台契约。用两个空进程证明跨进程事件、锁、熔断和追踪。薄管理台只展示运行状态。语义驱动体现在命名和表设计上，不单做引擎。

## 2. 仓库内容

- 契约：身份（Account / Subject / Identity）、租户 fail-closed、审计、事件与任务、对象存储、编译期扩展、单一幂等端口、单一限流端口。
- 数据库端口：连接与迁移可替换。第一版跑通 MySQL 和 PostgreSQL。业务读写不经过模型层。
- 任务端口：确定性任务是可重试、可核对的数据步骤。非确定性任务只多三个字段：模型标识、输入摘要、人工确认点。未确认不算完成。同一张任务表，不另建表。
- `platform-app`：空宿主进程。`sample-consumer`：只订阅一条跨进程事件。节点四起另有 `entry-gateway`（入口转发 + 粗限流，第 13 节）；`web/` 是独立的 Next.js 操作员控制台（第 12、14 节）。
- 薄管理台：第一版只读租户、任务、死信、健康状态。之后的 HTML 页面仍只读；`/api/v1` 与 `web/` 控制台按具名权限可写配置覆盖、登记服务、提交表单（第 9、12、15 节），每次写入都留审计。
- 架构测试只守本规范已写明的边界，开发过程中不临时加门禁。

## 3. 第一版实现

保留并收口：边界测试、身份三分、租户默认拦截、outbox、存储 SPI、迁移门禁、安全默认开启。幂等、限流、消息各只留一个端口。

新写：分布式锁、熔断、OpenTelemetry、正式的就绪/存活探针、MySQL 与 PostgreSQL 双库端口、两类任务字段、薄管理台。

书写：中英文双语注释，注释说明名字在业务里指什么。模块、类、表、字段用业务词，不用 `data`、`info`、`tmp`。表名是对象，字段是事实，状态是枚举。

## 4. 不做

动态路由、自动扩容、数据库或 Redis 运维台、AI 数据库中间层、模型/智能体/工作流引擎。翻译平台和主题商店不做。AI Gateway 仍是可选模块，不进平台启动路径。第二版只登记模型提供者并记录一次调用，见第 6 节。

服务发现、配置中心、Kubernetes、低代码、代码生成不再整项拒绝。目标形状和这一版落地的薄切片见第 7 节。真实注册中心、真实配置服务器、把清单应用到集群、表单设计器、在线表单库、全量 CRUD 生成仍然不做。

## 5. 验收

空应用能在 MySQL 和 PostgreSQL 上启动。架构测试通过。`sample-consumer` 能收到一条事件，失败时熔断，追踪能串起两个进程。管理台能看见死信和健康状态。仓库中无竞赛领域包，无临时增加的门禁。

## 6. 第二版

落地：

- 出箱投递经 `DeliveryPort`：JDBC 出箱表、重投与失败语义保留。默认传输 `platform.delivery.transport=socket` 是本机/快速开始**演示**（`SUBJEX-OUTBOX 2`：单地址、正文 ≤4000 字节、共享 HMAC；非 local 须 TLS；帧上不带操作员口令）——**不是**多消费方总线。提交后同步试一次；`OutboxRelay` 在锁 `outbox-relay` 下重投 `PENDING`，经所选端口投递直到 `PUBLISHED` 或死信。熔断只包所选传输。可选 `kafka` 适配器在独立模块 `subjex-outbox-kafka`，默认不进 platform-app classpath。套接字与 TLS 单测同 JVM，不依赖 Docker。
- 可选模块 `model-gateway`：登记一个模型提供者，并记录一次调用（提供者标识、模型标识、输入摘要）。无厂商 SDK，无 AI 数据库层，无智能体或工作流引擎。platform-app 不依赖该模块，模块不在 classpath 上时平台仍可启动。

仍推迟：

- 对着真实 MySQL / PostgreSQL 的启动证明。没有 Docker 时 `VendorStartupTest` 继续跳过，不用别的库冒充。
- 第 7 节列出的仍推迟项。第 4 节里其余明确不做的能力仍然不做。

## 7. 发现、配置、清单、表单与生成

目标形状：这五项留在模块化单体里，各自是一个端口，或一个不依赖宿主进程的模块。以后可以拆成独立进程，调用方仍然依赖原来的契约。它们互相不依赖。本节落地时 `platform-app` 与 `sample-consumer` 是仅有的两个进程（节点四加了 `entry-gateway`，见第 13 节）。不新增架构门禁：模块之间的禁止依赖靠依赖声明本身守住，`form-render` 不依赖 `platform-app` 或 `sample-consumer`，两个进程也不互相依赖。

- 服务发现：契约 `ServiceRegistry`，操作仍是 `register` 与 `resolve`。`platform-app` 把端点留在共享库的 `service_endpoint` 表里，并用 `POST /registry/services` 与 `GET /registry/services` 给另一个进程。`sample-consumer` 经这个 HTTP 登记自己、解析 `platform-app`。HTTP 连不上时，`StaticServiceFallback` 仍用 `PLATFORM_APP_HOST` 与 `PLATFORM_APP_PORT`。人看的页面是 `GET /services`：一句话、服务名、地址、状态词 `up` 或 `unknown`。没有 Nacos，也没有单独的注册中心进程。 `platform-app` 在 Web 服务器启动后（`WebServerInitializedEvent`）用真正绑定的端口登记自己，不读 `server.port`，所以 `server.port=0` 登记的是随机端口；单独的管理端口不登记。
- 配置中心：契约 `ConfigSource` + 生命周期端口 `ConfigCenterPort`（`ConfigCatalog` 实现）。默认底层 `LocalApplicationConfig`；可写覆盖层 `ConfigOverrideStore`（表 `config_override`，含 **namespace + revision**，Flyway V28；测试用 `MemoryConfigOverride`）。`OverridingConfigSource` 先覆盖后本地。跨进程经 `GET/POST /config/entries` 与 JSON `GET/PUT /api/v1/config`（可选 `namespace`，默认 `default`；`ETag` / `If-Match` / `If-None-Match`）。`HttpConfigSource` 可读条件 GET、写时带已知修订。控制台 `/config` 按命名空间列出键、生效值、来源、修订号。**这是库表 + HTTP 上的版本化命名空间覆盖层，不是 Nacos/Apollo，也不另起配置微服务。** 计划见 [`docs/config/independent-config-center-plan.md`](docs/config/independent-config-center-plan.md)。详见第 10 节。
- Kubernetes：`deploy/k8s/` 里是 `platform-app`、`sample-consumer`（节点四起还有 `entry-gateway`）的 Deployment 和 Service，包含存活探针、就绪探针、资源请求和限制。没有 HPA。构建和测试不把这些文件应用到集群，也不构建镜像。没有 Docker 也能测试。人看的页面是 `GET /deploy`：一句话说明这些清单没有应用到任何集群，然后每个工作负载一行（名字、占位镜像、存活路径、就绪路径、用兆比字节写明的内存上限）。页面读构建时复制到 classpath 的同一份 YAML。没有集群状态，也没有应用按钮。
- 低代码：本节落地时是一份声明式表单 `form-render/src/main/resources/forms/endpoint-publication.form.yaml`（节点六起是多表单目录，另有 `config-override.form.yaml`，并可提交落库，见第 15 节）。`FormRenderer` 把它变成校验过的字段列表。人看的页面是 `GET /forms`：一句话说明这是字段列表，不是设计器，然后每个字段一行（名字、类型、是否必填，中文在前、英文在后）。页面读的是同一份 YAML。没有界面设计器，也没有在线表单库。
- 代码生成：`FormRecordGenerator` 读同一份表单，写出一个 Java 记录。生成结果检入 `EndpointPublication`。测试核对记录组件与表单字段一致。构建不运行注解处理器。人看的页面是 `GET /codegen`：一句话说明这一页展示从表单生成的类型，不是在浏览器里运行的生成器，然后是记录名和每个组件的类型（中文在前、英文在后）。名字和类型来自已检入的记录，并与同一份 YAML 经现有生成器核对。没有运行时写文件的按钮。

这一版落地的就是上面这一薄层。

仍推迟：

- 独立的注册中心服务器（不引入 Nacos、Eureka、Consul）。跨进程登记只走 platform-app 的 HTTP，不另起进程。
- 独立配置微服务 / Nacos·Apollo 客户端（MVP 已用库表+HTTP 命名空间+修订；不另起配置进程）。
- 把 Kubernetes 清单应用到集群，以及自动扩容。镜像名只是占位符。
- 表单设计器，和在线表单运行时数据库。
- 全量 CRUD 生成（控制器、表、迁移）。现在只生成与字段对应的记录，不生成校验注解。

发现这一刀：两个进程共用 platform-app 上的 HTTP 登记簿。人打开 `/services` 看名单，不看运维控制台。状态词只有连得上才是 `up`，否则是 `unknown`。

配置这一刀：两个进程共用 platform-app 上按命名空间版本化的覆盖层与 HTTP 条目（表 `config_override`：namespace + revision + ETag）。人打开控制台 `/config`（或 HTML `GET /config`）看键、生效值、来源与修订，可切换命名空间（至少 `default`），不看巨型属性堆，也不假装是 Nacos。

清单这一刀：人打开 `/deploy` 看探针路径和内存上限，不看集群。存活路径与就绪路径分开写，避免进程还在跑却被当成已就绪。内存上限写成兆比字节，避免把 `m` 读成兆。

表单这一刀：人打开 `/forms` 看字段名、类型和是否必填，不看设计器。字段来自同一份表单 YAML，避免设计器把字段列表藏起来之后页面和定义对不上。

生成这一刀：人打开 `/codegen` 看已生成的记录名和每个组件的类型，不在浏览器里跑生成器。组件来自已检入的 `EndpointPublication`，并与表单 YAML 核对，避免生成物和来源定义对不上。

## 8. 语言与外观

目标形状：语言和外观仍留在模块化单体里，各自一个不依赖宿主进程的模块。`page-language` 与 `page-skin` 互不依赖，也不依赖 `platform-app` 或 `sample-consumer`。不新增架构门禁。

- 语言：目录只有操作页标题，中文和英文各一份。`platform-app` 用名为 `messageSource` 的 Spring `MessageSource` 读取。一次请求先看 `?lang=zh` 或 `?lang=en`，再看 `Accept-Language`，都不是时用中文。选择不写入会话。人看的页面是 `GET /language`：一句话、当前语言的名字、目录里的话。代号不出现在页面上。没有翻译编辑台。说明见 `docs/i18n-ui.md`。
- 外观：三个名字，朴素、高对比、沉静。每个名字设置同样的四个 CSS 变量。人看的页面是 `GET /skin`：一句话、当前外观的名字、三个链到自己的选择。认得出的名字写入 `skin` cookie；查询参数可以为这一次请求选中它。`/services`、`/config`、`/deploy`、`/forms`、`/codegen`、`/language` 使用选中的外观。没有取色器，页面也不把变量列出来。说明见 `docs/skin-ui.md`。

这一版落地的就是上面这一薄层。发现、配置、清单、表单和生成的行为不变，只是标题随语言变，颜色随外观变。

## 9. 节点一：权限与可读审计

- 单个 Basic 操作员换成表：`platform_permission`、`platform_role`、`role_permission`、`subject_role`、`operator_credential`（Flyway `V2__operator_permission.sql`）。脚本只放目录，不写入任何人或口令。
- 操作员是 `account` → 保留租户 `platform` 里的 `subject_identity` → `subject`。`JdbcOperatorDirectory` 按登录名查出口令摘要、身份和权限名；账号和身份都是 `ACTIVE` 才可用。
- 每条路径要一项具名权限：`admin.read`、`page.read`、`config.read`、`config.write`、`registry.read`、`registry.write`、`task.write`。缺权限 403，未登录 401。租户仍然 fail-closed：`/tasks` 缺 `X-Tenant-Id` 仍是 403。
- 本地操作员只在 profile `local` 下由 `LocalOperatorSeeder` 写入，并打印 `LOCAL ONLY` 警告。别处以表行开通。
- 配置覆盖（`config.override`）和服务登记（`registry.register`）各写一条审计，租户是 `platform`，操作者是操作员身份，对象是键或服务名。`audit_entry` 多一列 `action_target`。`GET /admin/audit` 给 JSON，`GET /audit` 给人看的页面。仍只有一个 `AuditPort`。
- 测试用 H2 的 PostgreSQL / MySQL 兼容模式执行真实迁移，只在测试范围。这不是真实厂商的证明。

## 10. 节点二：发现、配置覆盖与锁的共享存放

- 服务端点、配置覆盖和具名锁都进共享库（Flyway `V3__shared_registry_config_lock.sql`）：`service_endpoint`、`config_override`、`platform_lock`。指向同一个库的进程看到同样的行，重启之后也一样。没有 Nacos；Java 进程不用 Redis（节点五起只有 `web/` 控制台用 Redis 存操作员会话，见第 14 节）。
- `JdbcServiceRegistry` 实现 `ServiceRoster`（在 `ServiceRegistry` 上多一个全表读取）。`FallbackServiceRegistry` 仍包一层：主名册没有名字时用静态 host/port。`InProcessServiceRegistry` 只留给测试。
- `JdbcConfigOverride` 实现 `ConfigOverrideStore`（V28 起主键 `(namespace, config_key)`，列 `revision`）。本地应用配置仍是底层；覆盖层是表行。`MemoryConfigOverride` 只留给测试。人看的来源词是“本地文件”或“已存覆盖”；控制台另显示命名空间与修订号。配置中心 MVP（Item 5）在此表层加深，见 `docs/config/independent-config-center-plan.md`。
- `JdbcRowLock` 实现 `DistributedLockPort`：一行写持有者和到期时间；释放只删自己的行，过期可被接手。`SingleProcessLock` 移到测试源码。
- 证明：同一份 H2 库上两个仓库实例（或先后新建）能读到彼此写下的登记和覆盖；锁在两个实例之间互斥，过期后可接手。

## 11. 节点三：镜像与持续集成

（本节写于节点三，当时两份 Dockerfile；节点四加了 `entry-gateway/Dockerfile`，现在是三份、三个镜像，规则相同。`web/` 还没有 Dockerfile。）

- `platform-app/Dockerfile` 与 `sample-consumer/Dockerfile`：多阶段构建。第一阶段用 Maven 在仓库根目录执行 `-pl <进程> -am package`；第二阶段只有 JRE 和可运行 jar，以 uid 10001 的 `subjex` 用户运行。构建上下文是仓库根目录，`.dockerignore` 排除 `target/` 和 `.git/`。
- `deploy/k8s/` 的镜像名就是这两份 Dockerfile 打的标签（`subjex/platform-app:0.1.0-SNAPSHOT`、`subjex/sample-consumer:0.1.0-SNAPSHOT`），并设置 `runAsNonRoot`、`runAsUser: 10001`。镜像不推送到任何仓库，清单仍不被应用。清单里不启用 `local`，操作员要以表行开通。
- `mvn test` 不构建镜像，也不需要 Docker。`ContainerImageTest` 只读文字：两阶段、非 root、清单镜像名与 Dockerfile 标签一致。
- CI：工作流在 `.github/workflows/build.yml`。每次 push 和 pull request 跑 `mvn -B test`（Java 21），并在 `web/` 跑 `npm ci` / typecheck / test / build（Node 22）。`docs/ci/README.md` 仅作指引。经 Git Data API 或 push 写入工作流文件需要令牌带 `workflow` 权限；远端尚未写入前 Actions 仍不跑。


## 12. JSON 接口与 OpenAPI

每个操作页在 `/api/v1` 下都有一个 JSON 接口，给 `web/` 前端（Next.js）使用。节点六另加 `GET /api/v1/forms/{formKey}/submissions` 与 `POST` 同路径（写权限随表单而定，见第 15 节）。HTML 页面保留不变。权限沿用节点一的具名权限：

| 接口 | 权限 |
|---|---|
| `GET /api/v1/me` | 已登录即可，返回登录名和权限列表 |
| `POST /api/v1/operators/me/password` | 已登录；核对当前口令后改自己的口令 |
| `/api/v1/operators/**`（其余） | `operator.manage`；列表/创建/禁用/启用/改他人口令/租户授权 |
| `GET /api/v1/services` | `registry.read` |
| `GET /api/v1/config` | `config.read` |
| `PUT /api/v1/config/{key}` | `config.write`，写一条 `config.override` 审计 |
| `GET /api/v1/audit` | `admin.read` |
| `GET /api/v1/deploy`、`/forms`、`/codegen`、`/language`、`/skins` | `page.read` |

- 说明文档由 springdoc 生成，地址 `/api/v1/openapi.json`，取它也要登录；不带 Swagger UI。
- 认证是每次请求带 HTTP Basic，没有会话。`web/` 通过自己的服务端代理调用：浏览器只持有 httpOnly 会话 cookie（随机会话号），代理在服务端到服务端的调用上加 Basic，口令不进浏览器脚本。服务端会话存的是 AES-256-GCM 加密后的 Basic 头（内存与 Redis 同形状；Redis 必填 `OPERATOR_SESSION_SECRET`），见 `SECURITY.md`。
- 这些是操作员接口，不属某个租户，所以租户拦截放行 `/api/v1`。

Each operator page has a JSON twin under `/api/v1` for the `web/` Next.js app; the HTML pages stay. OpenAPI is served at `/api/v1/openapi.json` (signed-in only). Auth prefers opaque Bearer access tokens from `/api/v1/auth/login` (HTTP Basic remains for scripts); the Next.js server-side proxy keeps a random session id in an httpOnly cookie. The server-side session (memory or Redis) stores encrypted access/refresh tokens only — never Basic or password material. `/api/v1` routes are operator-scoped; tenant-scoped declaration APIs still require `X-Tenant-Id` + operator–tenant grant when `tenantScoped`.

## 13. 节点四：真库与入口网关 / Node 4

- 真库证明：`VendorStartupTest` 在 Docker 可用时对着 MySQL 8.4 与 PostgreSQL 16 各启动一次 `platform-app`（命令行参数压过 `application.yml`）。没有 Docker 时跳过，不用别的库冒充。
- 入口网关：独立进程 `entry-gateway`（包名 `com.subjex.gateway`）。它把 HTTP 原样转发到 `platform-app`，不替代认证与权限；操作员凭据仍由上游校验。
- 粗粒度限流：网关按客户端标识（默认远端地址；直连方属于 `gateway.trusted-proxies` 时从右往左取 `X-Forwarded-For` 中第一个不可信跳）在本进程时间窗内计数，超限回 429。使用契约里的单一 `RateLimitPort`；实现只活在网关进程内，不是第二个限流端口，也不是 Redis。
- 网关自己的存活/就绪探针不转发；其余路径转发并带回上游状态码与正文。
- 本地编排：`deploy/compose/docker-compose.yml` 起 PostgreSQL、`platform-app`、`entry-gateway`（节点五加了 Redis 与第二个 `platform-app` 副本；不含 `sample-consumer` 与 `web/`）。清单与镜像同节点三风格：多阶段、非 root、`mvn test` 不构建镜像。
- 不做：动态路由、按路径改写、TLS 终结、多上游负载均衡（留给节点五多副本之后）。
- 已知限制：计数只在本进程内；`gateway.trusted-proxies` 配得过宽会重新允许伪造 `X-Forwarded-For`。见 `SECURITY.md`。
- 架构测试：`sample-consumer` 与 `entry-gateway` 各自带一份 `ModuleBoundaryArchTest`，只保留本模块测试 classpath 能匹配到类的规则（ArchUnit 默认 `failOnEmptyShould`）。宿主/示例规则在 `sample-consumer`，“网关不依赖宿主”在 `entry-gateway`；“契约不依赖进程”和“不用模型层”两边都有。

### English summary — Node 4: live databases and entry gateway

- Live proof: `VendorStartupTest` starts `platform-app` once on MySQL 8.4 and once on PostgreSQL 16 when Docker is present.
- Entry gateway: separate process `entry-gateway` (`com.subjex.gateway`) that forwards HTTP to `platform-app` without replacing auth.
- Coarse rate limit: per client id in-process via the single `RateLimitPort`; 429 when exceeded.
- Local compose under `deploy/compose/` (no `sample-consumer`, no `web/`). No dynamic routing or TLS termination in this node.
- Client id: remote address by default; `X-Forwarded-For` is read (right to left, skipping trusted hops) only when the peer is in `gateway.trusted-proxies`. Known limit: counters are per gateway process.
- ArchUnit: each process module keeps only the boundary rules its test classpath can match (`failOnEmptyShould` stays on).

## 14. 节点五：可观测与单副本门禁 / Node 5

- Prometheus：各进程在**管理端口**暴露 `/actuator/prometheus`（见 P2）；存活/就绪匿名且应限制在集群网段。
- 追踪：默认丢弃导出器；配置 `PLATFORM_OTLP_ENDPOINT` 时改为 OTLP/HTTP。
- **单副本门禁（P4 → Scale-4d）：** `deploy/k8s/platform-app.yaml` 默认 `replicas: 1`；compose 默认一个 `platform-app`。对外宣称 `replicas > 1` **仅当**同时 `platform.rate-limit.backend=jdbc` 与 `platform.delivery.circuit-breaker.backend=jdbc`（默认仍为 `process`）。本地对象存储（`platform.storage.directory`）多副本不共享，除非挂共享卷。网关限流仍按进程。详见 `docs/release/single-replica-gate.md`。
- 压测：`deploy/load/smoke-load.sh` 经网关打只读请求；不是基准测试。
- 控制台会话（TD-1）：Redis 共享登录态（多控制台副本时）。网关限流仍进程内。会话只存加密 access/refresh（`SECURITY.md`）。
- 不做：HPA、跨区域、无共享后端时的多副本对外宣称、无 PVC 时的共享对象存储。

### English summary — Node 5: observability and single-replica gate

- Prometheus on the management port (P2); probes anonymous but cluster-CIDR only.
- Optional OTLP/HTTP when configured.
- **P4 / Scale-4d:** default one `platform-app` replica. Advertise `replicas > 1` only when both rate-limit and delivery circuit-breaker backends are `jdbc`. Object store remains local disk without a shared volume. Gateway rate limits stay per process. See `docs/release/single-replica-gate.md`.
- Smoke load via gateway; Redis console sessions; gateway rate limits stay in-process.

## 15. 节点六：低代码加深 / Node 6

- 多表单目录：`form-render` 下可有多份 `*.form.yaml`；`FormCatalog` 按 `formKey` 列出并加载，不再写死仅 `endpoint-publication`。
- 提交落库：合法提交写入共享表 `form_submission`（Flyway），含 formKey、操作员、取值 JSON、结果摘要；`GET /api/v1/forms/{formKey}/submissions` 列历史。仍不做在线表单库或设计器。
- 第二张业务表单：`config-override.form.yaml`（键 + 值）；提交需 `config.write`，写入配置覆盖并记 `config.override` 审计；控制台表单页可选表单。
- 生成器 CLI：`form-render` 提供可执行入口 `FormRecordWriteMain`，从 YAML 重写已检入的 Java record（调用方检入；构建仍不跑注解处理器）。
- 不做：拖拽设计器、全量 CRUD 生成、按租户的表单市场、浏览器内写文件。

### English summary — Node 6: deeper low-code

- Multi-form catalog from classpath `*.form.yaml`.
- Persist accepted submissions in shared `form_submission`; list via API.
- Second form `config-override` → config override + audit.
- CLI (`FormRecordWriteMain`) regenerates checked-in records from YAML.
- No designer, no online form library, no browser file write.

## 16. 发布前状态 / Release status

- 仍是 `0.1.0-SNAPSHOT`，**尚未打 tag，本机 tip 尚未 squash-push**。全部模块 `mvn test` 应当通过（见 `docs/release-prep-progress.md`）。
- CI 工作流在 `.github/workflows/build.yml`（含 `web/` + `image-scan`）。**厂商启动 / image-scan 需远端 Actions 再验**（本机曾无 Docker 跳过 `VendorStartupTest`；CI-fix-1 已修 MySQL V16 行大小与管理口探针）。
- **O8 FULL PASS**（旧 `org_unit` 已 DROP）；预 alpha **items 1–5 + CI-fix-1 已在本地完成**；**docs收口**对齐本文件与检查表。下一动作：远端 CI 绿 → 再考虑 `v0.1.0-alpha.1`（仍非生产就绪）。
- 已知限制：`SECURITY.md`；变更：`CHANGELOG.md`；检查表：`docs/release/v0.1.0-alpha.1-checklist.md`。

Still `0.1.0-SNAPSHOT`, **not tagged, not squash-pushed**. Local tip has O8 + capacity items 1–5 + CI-fix-1 + docs align. Remote Actions must re-verify VendorStartupTest / image-scan before cutting alpha. Not production-ready.

## 17. 低代码加深路线（有序） / Ordered low-code deepening

节点六已交付多表单目录、提交落库、第二张业务表单与 FormRecord CLI（见 §15）。后续加深**严格按下列顺序**推进；前一阶段未落地前不开始后一阶段。详细阶段说明、非目标与切片进度见 [`docs/lowcode-roadmap.md`](docs/lowcode-roadmap.md)。

Node 6 delivered the multi-form catalog, submission store, second business form, and FormRecord CLI (§15). Further deepening follows **this exact order**; do not start a later stage before the prior one lands. Stages, non-goals, and slice notes: [`docs/lowcode-roadmap.md`](docs/lowcode-roadmap.md).

1. **可跳过的 CLI 初始化** / Skippable CLI init — 生成本地 stub（`.env.example` / `application-local.yml`）、Redis 与 `OPERATOR_SESSION_SECRET` 提示、compose 提示、可选开通命令说明；默认值与 `--yes`；显式 skip，不强制 bootstrap。
2. **实体 YAML → 迁移 / CRUD 草稿** / Entity YAML → migration / CRUD drafts — 检入声明生成 Flyway 与薄 CRUD 草稿；构建不跑在线设计器。
3. **页面 / 流程声明** / Page / flow declarations — 声明式页面与流程，接在实体之后。
4. **声明上的权限 / 租户** / Permission / tenant on declarations — 具名权限与租户边界写进同一套声明，不另开在线权限编辑器。
5. **副作用目录** / Side-effect catalog — 可枚举的副作用登记，供流程与审计引用。
6. **版本化** / Versioning — 声明与生成物的版本约定。
7. **控制台调试 UX** / Console debug UX — 操作员控制台侧的只读/调试体验，放在声明链路之后；提交结果与校验/权限失败在同一调试面板可见（见 `docs/lowcode-roadmap.md` 第 6 阶段）。

**本路线（阶段 0–6）明确不做 / Explicit non-goals for stages 0–6：** 强制跑初始化向导（init 可完全跳过）、把 bootstrap 绑进日常启动。浏览器内自由画布设计器、随意在线 DDL 与任意脚本仍非目标；零代码目标下的**结构化页面构建器 + 声明双轨 + 严格库管迁移**见 §18。

**Stages 0–6 non-goals:** no forced init wizard; bootstrap stays one-shot. Free-canvas designer, casual online DDL, and arbitrary page scripts remain out of scope; structured page builder + dual-track declarations + strict schema migrations are the zero-code target — see §18.

## 18. 零代码模型（七维 + 人员/组织底座） / Zero-code model

产品方向：在声明为唯一真相的前提下，用控制台结构化编排搭出系统基本能力。详细七维、第 0 维人员/组织、**已拍板**双轨晋升、非目标与 Z0–Z6 切片见 [`docs/lowcode-roadmap.md`](docs/lowcode-roadmap.md) 的 **Zero-code model / 七维零代码模型**。

Product direction: with declarations as the single source of truth, structured console authoring builds baseline system capability. Seven dimensions, people/org base (dim 0), **decided** dual-track promote, non-goals, and Z0–Z6 slices: [`docs/lowcode-roadmap.md`](docs/lowcode-roadmap.md) § Zero-code model.

- **底座 / Base：** 租户隔离；**O1 冻结**六概念（Subject / Organization / Tenant / Membership / OrganizationRelation / TenantOrganization）——见 [`docs/ontology/README.md`](docs/ontology/README.md) 与 ADR 0001。**O8 FULL PASS**：运行时只走本体表；Flyway V24 已 **DROP** 旧 `org_unit` / `org_membership`；零代码字段仅 `subjectRef` / `organizationRef`。人员按**权限分层**；平台超管独立角色名；IdP/OIDC 为主；组织只约束权限范围（关系≠授权）。
- **Base：** Tenant isolation; **O1 freeze** six concepts. **O8 FULL PASS:** runtime is ontology-only; legacy thin `org_unit` / `org_membership` **dropped** (V24); field kinds `subjectRef` / `organizationRef` only. People by **permission tiers**; super-admin own role name; IdP/OIDC primary; org = scope only.
- **上下文权限 / Context AuthZ：** **AX-1+AX-2+AX-3 + AuthZ-1d 已落地**——可解释 `AccessDecision`；组织范围「本部门及下级」；`PolicyEngine` 默认 **`platform.authz.engine=cedar`**（`CedarPolicyEngine` + baseline.cedar）；`sql` 作无原生库环境逃生。无自研 DSL、不加角色名、不上 Zanzibar。
- **Context AuthZ：** **AX-1..3 + AuthZ-1d landed** — explainable `AccessDecision`; org scope self+descendants; default **`platform.authz.engine=cedar`**; `sql` escape hatch without natives. No custom DSL / Zanzibar.
- **七维 / Seven dims：** 数据 · **页面（结构化构建器 + 常用组件积木）** · 流程 · 权限/租户 · 动作/副作用 · 算法目录 · AI 目录（经 `model-gateway`，关键写回需确认）。
- **Seven dims：** Data · **pages (structured builder + common visual component blocks)** · flow · permission/tenant · actions · algorithm catalog · AI catalog (`model-gateway`; confirm before critical writes).
- **页面 UX / Page UX：** **已拍板**——首波用结构化**页面构建器/配置器**（非自由画布）；具体首波积木见下方「首波页面积木」。
- **Page UX：** **Decided** — structured **page builder/configurator** first (not free canvas); concrete first-wave blocks under “First-wave page blocks” below.
- **算法/AI / Algo·AI：** **已拍板且首波已落地**——薄层（目录 + 桩 + 领域动作挂接）；完整引擎后置。
- **Algo/AI：** **Decided and first-wave landed** — thin layer (catalog + stubs + domain-action bind); full engines later.
- **Z6（基线已落地 / baseline landed）：** Entity kinds `boolean` / `enum` / `date` / `entityRef`; generic list filter/sort API + thin declared-list filter UI; form FieldKind boolean/date/enum; entity/form wizards; page form widgets (checkbox/date/select) + `FieldDocument.enumValues`.
- **Z6：** 实体种类 boolean/enum/date/entityRef；列表筛选/排序 API + 声明列表薄筛选 UI；表单种类与向导；页面表单控件（布尔/日期/枚举）+ API 暴露 enumValues。
- **Schema：** **已拍板**——字段/schema 变更走**严格数据库管理**（版本化受控迁移），经双轨晋升落地；**禁止**控制台随意在线 DDL。
- **Schema：** **Decided** — field/schema changes follow **strict DB management** (controlled migrations) via dual-track promote; **no** casual online DDL.
- **热加载与迁移绑定 / Hot-reload bind：** **已拍板**——已晋升**声明元数据**可热加载（实现：库内 `PROMOTED` 覆盖 classpath，非重建 jar）；**改表**走**迁移队列**，迁移完成后才切换声明，二者绑定。
- **Hot-reload bind：** **Decided** — promoted declaration **metadata** may hot-reload (impl: DB `PROMOTED` overlay over classpath, not jar rebuild); **schema/table** changes use a **migration queue** and declaration switches only after migration completes — **bound together**.
- **首波页面积木 / First-wave page blocks：** **已拍板且 O8 后现行**——通用窗体组件、ListTable、FormFields、DetailReadonly、Section/Tabs、SubmitBar、**SubjectPicker / OrganizationPicker**（旧 UserPicker/OrgPicker id **已删除**）、流程分拣器。
- **First-wave page blocks：** **Decided and current after O8** — generic form components, ListTable, FormFields, DetailReadonly, Section/Tabs, SubmitBar, **SubjectPicker / OrganizationPicker** (legacy UserPicker/OrgPicker **removed**), flow sorter/router.
- **Z1 样例 / Z1 samples：** **已拍板**——通用引擎先吃**新样例**和/或**并行只读适配 `service_note`**；旧 JDBC 可暂留再删。
- **Z1 samples：** **Decided** — generic engine first eats a **new sample** and/or **parallel read-adapts `service_note`**; old JDBC may remain then delete.
- **HTTP Basic：** **已落地（Basic-1）**——默认关（`platform.auth.http-basic-enabled=false`）；`local` 打开；脚本可设 `PLATFORM_AUTH_HTTP_BASIC_ENABLED=true`。控制台主路径 Bearer。
- **HTTP Basic：** **Landed (Basic-1)** — off by default; on under `local`; opt-in via env for scripts. Console uses Bearer.
- **真相 / Truth：** **已拍板双轨 B**（**租户隔离**控制台草稿 → 晋升进**内部 git** YAML，自建/平台内置，**不依赖 GitHub**）；不以「仅改 YAML」为产品路径。首波交付含通用运行时（Z1–Z2）与声明库 + 页面构建器（Z3–Z4，可与 Z1 重叠）。
- **Truth：** **Dual-track B decided** (**tenant-scoped** console drafts → promote into **internal git** YAML; self-hosted / in-platform; **not** GitHub-dependent); YAML-only is not the product path. First wave includes generic runtime (Z1–Z2) and declaration store + page builder (Z3–Z4; Z1↔Z3 overlap OK).
- **优先级 / Priority：** **已拍板 R1（已走过）** — Z1 → 薄人员/组织（历史，已由 O1–O8 本体替换）→ Z2 → Z3/Z4 → Z5；SCIM/复杂兼岗后置。
- **Priority：** **R1 decided (executed)** — Z1 → thin people/org (**historical**; replaced by O1–O8 ontology) → Z2 → Z3/Z4 → Z5; SCIM / dual-role deferred.
- **Thin-org / O1–O8（历史收口）：** V13 薄组织 → O2 本体表 → O3 双读 → O7 DROP 旧表 → **O8 FULL PASS**（无 map 正式消费者；Subject/Organization pickers only）。现行 API：`/api/v1/organizations`（旧 `/api/v1/org/**` 为兼容薄适配）。
- **Thin-org / O1–O8 (closed):** V13 thin tables → O2 ontology → O3 dual-read → O7 DROP → **O8 FULL PASS**. Current API: `/api/v1/organizations` (legacy `/api/v1/org/**` thin adapter only).
- **Z2-3：** 详情优先 `GET /records/{id}`；**SubjectPicker / OrganizationPicker**；表单 JSON `entityKey`。**Z2 基线齐**（随后 Z3–Z5 / MQ / RT 已落地，见路线图）。
- **Z2-3：** Detail prefers `GET /records/{id}`; **SubjectPicker / OrganizationPicker**; forms `entityKey`. **Z2 baseline landed** (Z3–Z5 / MQ / RT followed — see roadmap).
- **Z3-1：** Flyway V14 `declaration_revision`（租户草稿修订，YAML 文本）；`declaration.read`/`write`；`JdbcDeclarationStore`。尚无 HTTP / 目录覆盖 / 晋升（Z3-2 / Z5）。
- **Z3-1：** Flyway V14 `declaration_revision` (tenant draft revisions, YAML text); `declaration.read`/`write`; `JdbcDeclarationStore`. No HTTP / catalog overlay / promote yet (Z3-2 / Z5).
- **Z3-2：** 声明草稿 HTTP + `EffectiveDeclarationService`（库内 DRAFT 覆盖 classpath）+ `/effective`；运行时目录仍 classpath。
- **Z3-2：** Declaration draft HTTP + `EffectiveDeclarationService` (DB DRAFT over classpath) + `/effective`; runtime catalogs still classpath.
- **Z3-3：** 带 `X-Tenant-Id` 时实体/表单/流程运行时覆盖（实体须 classpath 且表名+主键一致，否则 409）；**Z3 基线齐** → Z4。
- **Z3-3：** Runtime overlay with `X-Tenant-Id` for entity/form/flow (entity safe rule: classpath + matching tableName/PK else 409); **Z3 baseline landed** → Z4.
- **Z4-3：** `/declarations` 实体/表单薄结构化向导（字段表 → 写回 YAML；流程积木编排已在 Z4-2）；**Z4 基线齐** → Z5 晋升。
- **Z4-3：** Thin entity/form structured wizards on `/declarations` (field tables → YAML; flow composer in Z4-2); **Z4 baseline landed** → Z5 promote.
- **Z5-1：** 平台内 git 晋升核心（非裸工作树、`declaration_promote`、`declaration.promote`、`PROMOTED`）；仅本地提交；尚无 HTTP。
- **Z5-1：** In-platform git promote core (non-bare tree, `declaration_promote`, `declaration.promote`, `PROMOTED`); local commit only; no HTTP yet.
- **Z5-2：** POST `/api/v1/declarations/{kind}/{key}/promote` + GET `…/promotes`；`declaration.promote` / 审计 / 409 已晋升；仅本地提交。
- **Z5-2：** POST promote + GET promote history; `declaration.promote` / audit / 409 already PROMOTED; local commit only.
- **Z5-3：** 控制台 `/declarations` 晋升（审阅→确认、git SHA、晋升历史）；**Z5 基线齐**，双轨晋升闭环。仅本地提交（不依赖 GitHub）。
- **Z5-3：** Console `/declarations` promote (review→confirm, git SHA, history); **Z5 baseline landed**, dual-track promote loop closed. Local commit only (not GitHub-dependent).
- **Thin algo/AI：** `capabilities/algorithm-catalog.yaml` + `ai-catalog.yaml`；`CapabilityCatalog`/`CapabilityRunner` 桩（含 `normalizeWhitespace` / `suggestTitlePreview`）；领域动作；`GET/POST /api/v1/capabilities`（列表 + 试跑，`page.read`）；流程可选 `submit.capabilityId`；控制台 `/capabilities`；AI 桩不写库、不依赖 model-gateway。仅本地提交。
- **Thin algo/AI：** Algorithm/AI YAML catalogs; stub runner + domain actions; `GET/POST /api/v1/capabilities` (list + try-run, `page.read`); flow `submit.capabilityId`; console `/capabilities`; AI stub does not write / no model-gateway hard dep. Local commit only.
- **MQ-1：** Flyway V16 `declaration_migration` 队列 + `declaration.migrate`；HTTP 入队/列表/审阅。仅本地提交。
- **MQ-1：** Flyway V16 `declaration_migration` queue + `declaration.migrate`; HTTP enqueue/list/review. Local commit only.
- **MQ-2：** 执行 REVIEWED 实体 DDL（失败关闭：单语句 `ALTER TABLE`/`CREATE TABLE`，拒 DROP/TRUNCATE）；`POST …/migrations/{id}/apply`；实体晋升与同修订迁移绑定（未 APPLIED/CANCELLED → 409；无行可晋升）。仅本地提交。
- **MQ-2：** Apply REVIEWED entity DDL (fail-closed allowlist); apply HTTP; entity promote bound to migration settlement (block unless APPLIED/CANCELLED; no rows OK). Local commit only.
