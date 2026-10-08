# subjex

Semantic modular platform: subject identity, tenant isolation, and a single task contract.

第一版按 `ARCHITECTURE.md` 实现平台契约，不包含具体业务领域。业务读写走 JDBC，不经过 ORM 模型层。MySQL 与 PostgreSQL 共用同一套迁移脚本，靠配置切换连接。

**Status / 状态：** pre-release (`0.1.0-SNAPSHOT`, no tag yet). Contracts are complete and tested; most runtime pieces are deliberately thin slices. Intended for evaluation on a trusted network only — read [`SECURITY.md`](SECURITY.md) for known limits and [`CHANGELOG.md`](CHANGELOG.md) for what is in the tree.

**状态：** 发布前（`0.1.0-SNAPSHOT`，尚未打 tag）。契约完整且有测试，运行面多为刻意做薄的切片。仅适合在受信网络内评估，已知限制见 [`SECURITY.md`](SECURITY.md)，内容清单见 [`CHANGELOG.md`](CHANGELOG.md)。

**What it is not / 它不是：** not a Nacos/Apollo/Consul replacement (discovery and config are shared tables behind `platform-app` HTTP), not a low-code platform (two declarative forms, no designer), not an API gateway product (no dynamic routing, no TLS), not an AI platform (`model-gateway` only records an invocation). See `ARCHITECTURE.md` §4 and §7.

不是 Nacos/Apollo/Consul 的替代（发现与配置是 `platform-app` HTTP 后面的共享表），不是低代码平台（两份声明式表单，无设计器），不是 API 网关产品（无动态路由、无 TLS），不是 AI 平台（`model-gateway` 只记录一次调用）。见 `ARCHITECTURE.md` 第 4、7 节。

Processes / 进程：`platform-app` (host), `sample-consumer` (receives one cross-process event), `entry-gateway` (HTTP forwarder + coarse rate limit), and the separate Next.js operator console in `web/`. `model-gateway` is an optional library, not on the startup path.

Start here / 从这里开始: [`docs/quickstart.md`](docs/quickstart.md) builds the project, starts `platform-app` and `sample-consumer` locally, and lists the pages to open. The default operator login is for local use only.

[`docs/quickstart.md`](docs/quickstart.md) 讲怎么构建、在本机启动 `platform-app` 和 `sample-consumer`，以及打开哪些页面。默认操作员口令只用于本地。

Optional local stubs / 可选本地 stub: build and run `tools/subjex-init` (`java -jar tools/subjex-init/target/subjex-init-0.1.0-SNAPSHOT.jar --yes`, or `--skip` to do nothing). Details in [`docs/quickstart.md`](docs/quickstart.md) §0. Low-code deepening order: [`docs/lowcode-roadmap.md`](docs/lowcode-roadmap.md) / `ARCHITECTURE.md` §17.

可选本地 stub：构建并运行 `tools/subjex-init`（`--yes` 写默认，`--skip` 什么都不做）。见 [`docs/quickstart.md`](docs/quickstart.md) §0。低代码加深顺序：[`docs/lowcode-roadmap.md`](docs/lowcode-roadmap.md) / `ARCHITECTURE.md` §17.


## Run / 运行

Both drivers are on the classpath. Pick one vendor per process; the migration guard refuses to serve when the live product name disagrees with `platform.connection.vendor`.

两个驱动都在 classpath 上。一个进程只选一个厂商。迁移门禁发现真实库产品名与 `platform.connection.vendor` 不一致时拒绝提供服务。

PostgreSQL:

```shell
export PLATFORM_CONNECTION_VENDOR=postgresql
export PLATFORM_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/subjex
export PLATFORM_JDBC_USERNAME=subjex
export PLATFORM_JDBC_PASSWORD=subjex
export PLATFORM_JDBC_DRIVER=org.postgresql.Driver
export SAMPLE_CONSUMER_HOST=127.0.0.1
export SAMPLE_CONSUMER_PORT=19081
export SPRING_PROFILES_ACTIVE=local
mvn -pl platform-app spring-boot:run
```

MySQL:

```shell
export PLATFORM_CONNECTION_VENDOR=mysql
export PLATFORM_JDBC_URL='jdbc:mysql://127.0.0.1:3306/subjex?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8'
export PLATFORM_JDBC_USERNAME=subjex
export PLATFORM_JDBC_PASSWORD=subjex
export PLATFORM_JDBC_DRIVER=com.mysql.cj.jdbc.Driver
export SAMPLE_CONSUMER_HOST=127.0.0.1
export SAMPLE_CONSUMER_PORT=19081
export SPRING_PROFILES_ACTIVE=local
mvn -pl platform-app spring-boot:run
```

Create the empty database yourself. Flyway applies `V1__platform_schema.sql` and the later `V*` scripts on startup. There is no database console.

请自行建空库。启动时 Flyway 执行 `V1__platform_schema.sql` 和之后的 `V*` 脚本。没有数据库控制台。

Sample consumer (second process / 第二个进程):

```shell
export SAMPLE_DELIVERY_PORT=19081
export SPRING_PROFILES_ACTIVE=local
mvn -pl sample-consumer spring-boot:run
```

`platform-app` writes `TaskRecorded` into the JDBC outbox, then pushes that row to `sample-consumer` on a socket (`127.0.0.1:19081` unless the variables above are set). Frames are `SUBJEX-OUTBOX 2` with HMAC (`OUTBOX_HMAC_SECRET`); under `local` plaintext TCP is allowed, otherwise TLS is required (see `SECURITY.md`). A background relay retries `PENDING` rows. The event leaves one application and enters the other. It is not an in-process method call. `traceparent` travels on the socket frame. The consumer's HTTP port is only for probes.

`platform-app` 把 `TaskRecorded` 写入 JDBC 出箱，再经套接字推到 `sample-consumer`（默认 `127.0.0.1:19081`）。帧为 `SUBJEX-OUTBOX 2` + HMAC；`local` 下允许明文，否则须 TLS（见 `SECURITY.md`）。后台会重投 `PENDING`。事件跨进程，不是进程内调用。`traceparent` 在帧上。消费者的 HTTP 端口只用于探针。

Shared discovery, config overrides, and the lock live in the same database as the rest of the platform (`service_endpoint`, `config_override`, `platform_lock`). Two processes on that database see the same rows after a restart. Static host/port keys remain the fallback when the registry has no name. There is no Nacos. The Java processes do not use Redis; Redis is used only by the `web/` console to share operator sessions across console replicas (optional, `SESSION_REDIS_URL`).

发现、配置覆盖和锁与平台其它表同库（`service_endpoint`、`config_override`、`platform_lock`）。连同一个库的两个进程重启后仍看到同样的行。登记簿没有名字时，静态 host/port 仍是兜底。没有 Nacos。Java 进程不用 Redis；Redis 只被 `web/` 控制台用来在多个控制台副本之间共享操作员会话（可选，`SESSION_REDIS_URL`）。

Operator HTTP Basic (security is on by default / 安全默认开启): operators sign in against the tables (`account`, `operator_credential`, `subject_identity` in tenant `platform`), and each path needs one named permission held through `subject_role` → `role_permission`. With `SPRING_PROFILES_ACTIVE=local` platform-app seeds `platform-operator` / `change-me` (or `PLATFORM_OPERATOR_NAME` / `PLATFORM_OPERATOR_PASSWORD`); that seed is local only. Outside `local`, provision one operator with a one-shot run: `java -jar platform-app.jar --platform.operator.bootstrap=true --spring.main.web-application-type=none` and env `PLATFORM_OPERATOR_LOGIN` / `PLATFORM_OPERATOR_PASSWORD` (optional `PLATFORM_OPERATOR_ROLE`); the flag is off by default and the process exits after upsert. Probes `GET /actuator/health/liveness` and `GET /actuator/health/readiness` are anonymous. Read-only admin (`admin.read`): `GET /admin/tenants`, `GET /admin/tasks`, `GET /admin/dead-letters`, `GET /admin/health`, `GET /admin/audit`, and the page `GET /audit`. Config overrides and registry registrations leave audit entries. Details: `docs/operator-permissions.md`.

操作员 HTTP Basic：操作员对着表登录，每条路径需要一项经 `subject_role` → `role_permission` 持有的具名权限。`SPRING_PROFILES_ACTIVE=local` 时 platform-app 写入本地操作员，只用于本地。非 local 可用一次性开通：`--platform.operator.bootstrap=true`（默认关闭）+ `PLATFORM_OPERATOR_LOGIN` / `PLATFORM_OPERATOR_PASSWORD`，写入后退出。配置覆盖和服务登记会留下审计条目，`GET /admin/audit` 与 `GET /audit` 可读。详见 `docs/operator-permissions.md`。

## Tests / 测试

`mvn test` runs across all modules and is expected to pass. It always runs the contract, operator-permission and audit, JSON API / OpenAPI security, shared registry/config/lock (Flyway scripts on H2 in PostgreSQL and MySQL mode, test scope only), lock, rate-limit, breaker, outbox-socket, tracing-exporter choice (discarding vs OTLP), model-gateway, service-discovery, config-override, http-config, form-render (multi-form catalog, submission validation, `form_submission` store, record generation and the `FormRecordWriteMain` CLI), entry-gateway (HTTP forwarding, rate limit, client identity, Prometheus metrics wiring), container-image and Kubernetes-manifest text checks, deploy/forms/codegen/language/skin pages, and architecture tests. Each process module keeps only the ArchUnit boundary rules its own test classpath can match.

Live MySQL and PostgreSQL startup tests use Testcontainers and run only when Docker is available. Without Docker they are skipped, not faked. The outbox socket test does not start a database. See `docs/local-docker.md`.

`mvn test` 覆盖全部模块，应当全绿。总会跑契约、操作员权限与审计、JSON 接口 / OpenAPI 安全（H2 的 PostgreSQL 与 MySQL 兼容模式执行 Flyway 脚本，只在测试范围）、锁、限流、熔断、出箱套接字、追踪导出器选择（丢弃或 OTLP）、模型网关、服务发现、配置覆盖、表单（多表单目录、提交校验、`form_submission` 存储、记录生成与 `FormRecordWriteMain` 命令行）、入口网关（转发、限流、客户端标识、Prometheus 指标接线）、镜像与 K8s 清单的文本核对、部署/字段/生成类型/语言/外观页和架构测试。每个进程模块只保留本模块测试 classpath 能匹配到类的 ArchUnit 边界规则。

The console has its own checks: `cd web && npm ci && npm run typecheck && npm test` (vitest). 控制台单独检查：`cd web && npm ci && npm run typecheck && npm test`。

MySQL 与 PostgreSQL 的启动测试使用 Testcontainers，只有本机有 Docker 时才执行。没有 Docker 时跳过，不用别的库冒充。出箱套接字测试不启动数据库。见 `docs/local-docker.md`。


## Discovery, config, manifests, and forms / 发现、配置、清单与表单

`platform-app` keeps the `ServiceRegistry` and publishes it at `POST /registry/services` and `GET /registry/services`. `sample-consumer` registers itself there and resolves `platform-app` over HTTP. When that call cannot connect, static keys `platform.discovery.static.platform-app.host` and `.port` are still the fallback (`PLATFORM_APP_HOST`, `PLATFORM_APP_PORT`). `GET /services` is a read-only page for a person: one sentence, then service name, address, and the word `up` or `unknown`. The operator HTTP Basic gate applies. Notes on why the page is small are in `docs/discovery-ui.md`. Configuration is read through `ConfigSource`. `platform-app` stores overrides in the shared database and serves `GET/POST /config/entries` for one named key; `sample-consumer` reads the effective value over HTTP and falls back to local application config when unreachable. `GET /config` is a read-only page: key, effective value, and plain source (local file or stored override). Notes are in `docs/config-ui.md`. Kubernetes manifests under `deploy/k8s/` are files only; nothing applies them. `GET /deploy` is a read-only page: one sentence that these manifests are not applied to a cluster, then workload name, placeholder image, probe paths, and memory limit. Notes are in `docs/k8s-ui.md`. `form-render` loads every `classpath*:forms/*.form.yaml` into a `FormCatalog` (today `endpoint-publication` and `config-override`), turns each into a validated field list, and keeps a checked-in record per form (e.g. `EndpointPublication`). Accepted submissions are stored in the shared `form_submission` table (`V4`) through `POST/GET /api/v1/forms/{formKey}/submissions`; each form has its own write permission: `endpoint-publication` registers a service (`registry.write`, audit `registry.register`), `config-override` writes a config override (`config.write`, audit `config.override`). `FormRecordWriteMain` is a CLI that regenerates the checked-in record from YAML; the build does not run it. `GET /forms` is a read-only page: one sentence that this is the field list, not a designer, then each field name, type, and whether it is required. Notes are in `docs/form-ui.md`. `GET /codegen` is a read-only page: one sentence that this page shows the type generated from the form, not a generator you run from the browser, then the record name and each component with its type. The values come from the checked-in record and are checked against the same YAML. Notes are in `docs/codegen-ui.md`.

`platform-app` 保存 `ServiceRegistry`，并在 `POST /registry/services` 与 `GET /registry/services` 公布。`sample-consumer` 在那里登记自己，并用 HTTP 解析 `platform-app`。调用连不上时，静态键 `platform.discovery.static.platform-app.host` 和 `.port` 仍是兜底（`PLATFORM_APP_HOST`、`PLATFORM_APP_PORT`）。`GET /services` 是给人看的只读页：一句话，然后是服务名、地址，以及 `up` 或 `unknown`。操作员 HTTP Basic 门禁同样适用。页面为什么做小，写在 `docs/discovery-ui.md`。配置经 `ConfigSource` 读取。`platform-app` 把覆盖存进共享库，并用 `GET/POST /config/entries` 提供一个具名键；`sample-consumer` 经 HTTP 读生效值，连不上时回退本地应用配置。`GET /config` 是只读页：键、生效值，以及直白来源（本地文件或已存覆盖）。说明见 `docs/config-ui.md`。`deploy/k8s/` 下的 Kubernetes 清单只是文件，不会被应用。`GET /deploy` 是只读页：一句话说明这些清单没有应用到任何集群，然后是工作负载名、占位镜像、探针路径和内存上限。说明见 `docs/k8s-ui.md`。`form-render` 把 `classpath*:forms/*.form.yaml` 全部载入 `FormCatalog`（目前是 `endpoint-publication` 与 `config-override` 两份），各自变成校验过的字段列表，并各有一个检入仓库的记录（如 `EndpointPublication`）。合法提交经 `POST/GET /api/v1/forms/{formKey}/submissions` 写入共享表 `form_submission`（`V4`）；每份表单各有写权限：`endpoint-publication` 登记服务（`registry.write`，审计 `registry.register`），`config-override` 写配置覆盖（`config.write`，审计 `config.override`）。`FormRecordWriteMain` 是从 YAML 重写已检入记录的命令行，构建不运行它。`GET /forms` 是只读页：一句话说明这是字段列表，不是设计器，然后是每个字段的名字、类型和是否必填。说明见 `docs/form-ui.md`。`GET /codegen` 是只读页：一句话说明这一页展示从表单生成的类型，不是在浏览器里运行的生成器，然后是记录名和每个组件的类型。值来自已检入的记录，并与同一份 YAML 核对。说明见 `docs/codegen-ui.md`。

## Language and skin / 语言与外观

`page-language` holds the operator page titles in Chinese and English. `platform-app` reads them with a Spring `MessageSource` named `messageSource`. `?lang=zh` or `?lang=en` wins, then `Accept-Language`, otherwise Chinese. `GET /language` is a read-only page: one sentence, the current language, then those titles in plain words. Notes are in `docs/i18n-ui.md`. `page-skin` holds three named skins, plain, high contrast, and calm, as the same four CSS variables. `GET /skin` shows the current name and three links. A link sets a `skin` cookie. `/services`, `/config`, `/deploy`, `/forms`, `/codegen`, and `/language` use that skin. Notes are in `docs/skin-ui.md`.

`page-language` 保存操作页的中文和英文标题。`platform-app` 用名为 `messageSource` 的 Spring `MessageSource` 读取。`?lang=zh` 或 `?lang=en` 优先，然后是 `Accept-Language`，否则是中文。`GET /language` 是只读页：一句话、当前语言，然后是这些标题的人话。说明见 `docs/i18n-ui.md`。`page-skin` 保存三个具名外观：朴素、高对比、沉静，用同样的四个 CSS 变量。`GET /skin` 显示当前名字和三个链接。链接会写入 `skin` cookie。`/services`、`/config`、`/deploy`、`/forms`、`/codegen` 和 `/language` 使用这个外观。说明见 `docs/skin-ui.md`。

## Entry gateway / 入口网关

`entry-gateway` is a separate process that forwards HTTP to `platform-app` and applies coarse in-process rate limiting (429 when exceeded). It does not replace operator authentication. The client id is the remote address; `X-Forwarded-For` is read only when the direct peer is listed in `gateway.trusted-proxies` (env `GATEWAY_TRUSTED_PROXIES`, comma-separated IPs/CIDRs, default empty) — see `SECURITY.md`. Counters are per gateway process.

Local stack: `deploy/compose/docker-compose.yml` starts PostgreSQL 16, Redis 7, `platform-app` (scale to 2 with `--scale platform-app=2`), `sample-consumer` (outbox delivery via `SAMPLE_CONSUMER_HOST`/`PORT`, same as k8s), and the gateway on port 8088. It does **not** include `web/`. See `deploy/compose/README.md`.

`entry-gateway` 是独立进程：把 HTTP 转发到 `platform-app`，并做进程内粗粒度限流（超限 429）。它不替代操作员认证。客户端标识默认是远端地址；只有直连方列在 `gateway.trusted-proxies`（环境变量 `GATEWAY_TRUSTED_PROXIES`，逗号分隔 IP/CIDR，默认空）里时才读 `X-Forwarded-For`（见 `SECURITY.md`）。计数按网关进程各算各的。

本地编排 `deploy/compose/docker-compose.yml` 起 PostgreSQL 16、Redis 7、`platform-app`（`--scale platform-app=2` 起两个副本）、`sample-consumer`（出箱投递，`SAMPLE_CONSUMER_HOST`/`PORT` 与 k8s 一致）和端口 8088 的网关。**不含** `web/`。见 `deploy/compose/README.md`。

## Observability / 可观测

`platform-app`, `sample-consumer` and `entry-gateway` expose `/actuator/prometheus` (Micrometer), anonymous like the liveness/readiness probes — keep them on an internal network. Traces use a discarding exporter by default (trace ids are still generated and `traceparent` crosses the outbox socket); set `PLATFORM_OTLP_ENDPOINT` (or `OTEL_EXPORTER_OTLP_ENDPOINT`) to export over OTLP/HTTP. There are no dashboards or alert rules in the repository.

三个进程都暴露 `/actuator/prometheus`（Micrometer），与存活/就绪探针一样匿名，应放在内网。追踪默认用丢弃导出器（仍生成 trace id，`traceparent` 仍穿过出箱套接字）；设置 `PLATFORM_OTLP_ENDPOINT`（或 `OTEL_EXPORTER_OTLP_ENDPOINT`）后经 OTLP/HTTP 导出。仓库里没有看板或告警规则。

## Optional model gateway / 可选模型网关

`model-gateway` is not a dependency of `platform-app`. It registers a model provider and records one invocation: provider id, model id, and input digest. There is no vendor SDK and no database.

`model-gateway` 不是 `platform-app` 的依赖。它登记模型提供者，并记录一次调用：提供者标识、模型标识、输入摘要。没有厂商 SDK，也没有数据库。

## Images and CI / 镜像与持续集成

`platform-app/Dockerfile`, `sample-consumer/Dockerfile` and `entry-gateway/Dockerfile` are multi-stage builds run from the repository root (`docker build -f platform-app/Dockerfile -t subjex/platform-app:0.1.0-SNAPSHOT .`). The runtime stage runs as uid 10001. The image names match `deploy/k8s/`. Images are not published to any registry; `web/` has no Dockerfile yet. `mvn test` does not build images. CI lives at [`.github/workflows/build.yml`](.github/workflows/build.yml): `mvn -B test` plus `web/` `npm ci` / typecheck / test / build. Pushing that path needs a token with the `workflow` scope (see `docs/ci/README.md`).

`platform-app/Dockerfile`、`sample-consumer/Dockerfile` 与 `entry-gateway/Dockerfile` 是在仓库根目录执行的多阶段构建，运行阶段以 uid 10001 运行，镜像名与 `deploy/k8s/` 一致。镜像未推送到任何仓库；`web/` 还没有 Dockerfile。`mvn test` 不构建镜像。CI 在 [`.github/workflows/build.yml`](.github/workflows/build.yml)：`mvn -B test`，并覆盖 `web/` 的 typecheck/test/build。写入该路径需要令牌带 `workflow` 权限（见 `docs/ci/README.md`）。

## License / 许可证

Apache License 2.0. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).

按 Apache License 2.0 授权，见 [`LICENSE`](LICENSE) 和 [`NOTICE`](NOTICE)。

## Operator console — 操作员控制台

独立 Next.js 控制台在 [`web/`](web/)，浏览器只持有 httpOnly 会话 cookie，由服务端代理访问 `/api/v1`。控制台可以读服务、配置、审计、清单，也可以写配置覆盖、经两份表单登记服务或写配置（按具名权限）。会话默认放进程内存；设置 `SESSION_REDIS_URL` 后放 Redis（8 小时过期），多副本共享登录态。启用 Redis 时须配置 `OPERATOR_SESSION_SECRET`（≥32 字符），Basic 认证头以 AES-256-GCM 加密后写入 Redis，见 `SECURITY.md`。本地运行见 [`web/README.md`](web/README.md)；设计取舍与截图见 [`web/docs/design-notes.md`](web/docs/design-notes.md)。

The console signs in once, keeps a random session id in an httpOnly `sameSite=strict` cookie, and calls `/api/v1` through its server-side proxy. It can read services, config, audit and manifests, and can write config overrides and submit both forms — registering a service or writing config (subject to named permissions). Sessions live in process memory, or in Redis when `SESSION_REDIS_URL` is set (8-hour expiry). Login calls `/api/v1/auth/login` and stores only encrypted opaque access/refresh tokens (AES-256-GCM via `OPERATOR_SESSION_SECRET` when Redis is used); the proxy sends Bearer — see `SECURITY.md`.
