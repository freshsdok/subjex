# 开源平台框架架构规范（第一版）

新仓是独立框架，不包含竞赛业务，也不依赖竞赛仓。现有产品只提供已经跑通的契约当起点。竞赛平台以后依赖本框架，用来提高开发效率。

## 1. 目标

把模块边界、身份、租户、审计、任务、存储、扩展收成可单独使用的平台契约。用两个空进程证明跨进程事件、锁、熔断和追踪。薄管理台只展示运行状态。语义驱动体现在命名和表设计上，不单做引擎。

## 2. 仓库内容

- 契约：身份（Account / Subject / Identity）、租户 fail-closed、审计、事件与任务、对象存储、编译期扩展、单一幂等端口、单一限流端口。
- 数据库端口：连接与迁移可替换。第一版跑通 MySQL 和 PostgreSQL。业务读写不经过模型层。
- 任务端口：确定性任务是可重试、可核对的数据步骤。非确定性任务只多三个字段：模型标识、输入摘要、人工确认点。未确认不算完成。同一张任务表，不另建表。
- `platform-app`：空宿主进程。`sample-consumer`：只订阅一条跨进程事件。
- 薄管理台：只读租户、任务、死信、健康状态。
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

- 出箱投递是产品路径。platform-app 在事务提交后读出 JDBC 出箱行，把 `event_body` 经本地 TCP 套接字推给 sample-consumer。两个应用各自打开套接字，不是进程内方法调用，也不再使用第一版的 HTTP 替身。失败仍打开示例路径熔断器。traceparent 仍穿过两个应用。这项测试在同一个 JVM 里完成，不依赖 Docker。
- 可选模块 `model-gateway`：登记一个模型提供者，并记录一次调用（提供者标识、模型标识、输入摘要）。无厂商 SDK，无 AI 数据库层，无智能体或工作流引擎。platform-app 不依赖该模块，模块不在 classpath 上时平台仍可启动。

仍推迟：

- 对着真实 MySQL / PostgreSQL 的启动证明。没有 Docker 时 `VendorStartupTest` 继续跳过，不用别的库冒充。
- 第 7 节列出的仍推迟项。第 4 节里其余明确不做的能力仍然不做。

## 7. 发现、配置、清单、表单与生成

目标形状：这五项留在模块化单体里，各自是一个端口，或一个不依赖宿主进程的模块。以后可以拆成独立进程，调用方仍然依赖原来的契约。它们互相不依赖。`platform-app` 与 `sample-consumer` 仍然是仅有的两个进程。不新增架构门禁：模块之间的禁止依赖靠依赖声明本身守住，`form-render` 不依赖 `platform-app` 或 `sample-consumer`，两个进程也不互相依赖。

- 服务发现：契约 `ServiceRegistry`，操作仍是 `register` 与 `resolve`。`platform-app` 把端点留在共享库的 `service_endpoint` 表里，并用 `POST /registry/services` 与 `GET /registry/services` 给另一个进程。`sample-consumer` 经这个 HTTP 登记自己、解析 `platform-app`。HTTP 连不上时，`StaticServiceFallback` 仍用 `PLATFORM_APP_HOST` 与 `PLATFORM_APP_PORT`。人看的页面是 `GET /services`：一句话、服务名、地址、状态词 `up` 或 `unknown`。没有 Nacos，也没有单独的注册中心进程。 `platform-app` 在 Web 服务器启动后（`WebServerInitializedEvent`）用真正绑定的端口登记自己，不读 `server.port`，所以 `server.port=0` 登记的是随机端口；单独的管理端口不登记。
- 配置中心：契约 `ConfigSource`。默认实现 `LocalApplicationConfig` 读本进程的应用配置。`ConfigOverrideStore` 是可写的覆盖层（platform-app 用表 `config_override`，测试仍可用 `MemoryConfigOverride`）。`OverridingConfigSource` 先查覆盖层，再查本地配置。`platform-app` 用 `GET/POST /config/entries` 给另一个进程读生效值或压过一个键。`sample-consumer` 经 `HttpConfigSource` 读生效值，连不上时仍用本地应用配置。人看的页面是 `GET /config`：一句话、键、生效值、来源词“本地文件”或“已存覆盖”。没有单独的配置服务器，也没有 Nacos/Apollo 客户端。详见第 10 节。
- Kubernetes：`deploy/k8s/` 里是 `platform-app` 与 `sample-consumer` 的 Deployment 和 Service，包含存活探针、就绪探针、资源请求和限制。没有 HPA。构建和测试不把这些文件应用到集群，也不构建镜像。没有 Docker 也能测试。人看的页面是 `GET /deploy`：一句话说明这些清单没有应用到任何集群，然后每个工作负载一行（名字、占位镜像、存活路径、就绪路径、用兆比字节写明的内存上限）。页面读构建时复制到 classpath 的同一份 YAML。没有集群状态，也没有应用按钮。
- 低代码：一份声明式表单 `form-render/src/main/resources/forms/endpoint-publication.form.yaml`。`FormRenderer` 把它变成校验过的字段列表。人看的页面是 `GET /forms`：一句话说明这是字段列表，不是设计器，然后每个字段一行（名字、类型、是否必填，中文在前、英文在后）。页面读的是同一份 YAML。没有界面设计器，也没有在线表单库。
- 代码生成：`FormRecordGenerator` 读同一份表单，写出一个 Java 记录。生成结果检入 `EndpointPublication`。测试核对记录组件与表单字段一致。构建不运行注解处理器。人看的页面是 `GET /codegen`：一句话说明这一页展示从表单生成的类型，不是在浏览器里运行的生成器，然后是记录名和每个组件的类型（中文在前、英文在后）。名字和类型来自已检入的记录，并与同一份 YAML 经现有生成器核对。没有运行时写文件的按钮。

这一版落地的就是上面这一薄层。

仍推迟：

- 独立的注册中心服务器（不引入 Nacos、Eureka、Consul）。跨进程登记只走 platform-app 的 HTTP，不另起进程。
- 真实的配置服务器。
- 把 Kubernetes 清单应用到集群，以及自动扩容。镜像名只是占位符。
- 表单设计器，和在线表单运行时数据库。
- 全量 CRUD 生成（控制器、表、迁移）。现在只生成与字段对应的记录，不生成校验注解。

发现这一刀：两个进程共用 platform-app 上的 HTTP 登记簿。人打开 `/services` 看名单，不看运维控制台。状态词只有连得上才是 `up`，否则是 `unknown`。

配置这一刀：两个进程共用 platform-app 上的内存覆盖与 HTTP 条目。人打开 `/config` 看键、生效值和来源，不看巨型属性堆。来源词只有本地文件或内存覆盖。

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

- 服务端点、配置覆盖和具名锁都进共享库（Flyway `V3__shared_registry_config_lock.sql`）：`service_endpoint`、`config_override`、`platform_lock`。指向同一个库的进程看到同样的行，重启之后也一样。没有 Nacos，也没有 Redis。
- `JdbcServiceRegistry` 实现 `ServiceRoster`（在 `ServiceRegistry` 上多一个全表读取）。`FallbackServiceRegistry` 仍包一层：主名册没有名字时用静态 host/port。`InProcessServiceRegistry` 只留给测试。
- `JdbcConfigOverride` 实现 `ConfigOverrideStore`。本地应用配置仍是底层；覆盖层是表行。`MemoryConfigOverride` 只留给测试。人看的来源词是“本地文件”或“已存覆盖”。
- `JdbcRowLock` 实现 `DistributedLockPort`：一行写持有者和到期时间；释放只删自己的行，过期可被接手。`SingleProcessLock` 移到测试源码。
- 证明：同一份 H2 库上两个仓库实例（或先后新建）能读到彼此写下的登记和覆盖；锁在两个实例之间互斥，过期后可接手。

## 11. 节点三：镜像与持续集成

- `platform-app/Dockerfile` 与 `sample-consumer/Dockerfile`：多阶段构建。第一阶段用 Maven 在仓库根目录执行 `-pl <进程> -am package`；第二阶段只有 JRE 和可运行 jar，以 uid 10001 的 `subjex` 用户运行。构建上下文是仓库根目录，`.dockerignore` 排除 `target/` 和 `.git/`。
- `deploy/k8s/` 的镜像名就是这两份 Dockerfile 打的标签（`subjex/platform-app:0.1.0-SNAPSHOT`、`subjex/sample-consumer:0.1.0-SNAPSHOT`），并设置 `runAsNonRoot`、`runAsUser: 10001`。镜像不推送到任何仓库，清单仍不被应用。清单里不启用 `local`，操作员要以表行开通。
- `mvn test` 不构建镜像，也不需要 Docker。`ContainerImageTest` 只读文字：两阶段、非 root、清单镜像名与 Dockerfile 标签一致。
- CI：工作流在 push 和 pull request 上跑 `mvn -B test`。推送令牌缺少 `workflow` 权限，GitHub 拒绝写入 `.github/workflows/`，所以同一份文件暂放 `docs/ci/build.yml`，等有权限的人移到位。移到位之前 CI 不运行。

