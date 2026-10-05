# subjex

Semantic modular platform: subject identity, tenant isolation, and a single task contract.

第一版按 `ARCHITECTURE.md` 实现平台契约，不包含竞赛领域。业务读写走 JDBC，不经过 ORM 模型层。MySQL 与 PostgreSQL 共用同一套迁移脚本，靠配置切换连接。

Start here / 从这里开始: [`docs/quickstart.md`](docs/quickstart.md) builds the project, starts `platform-app` and `sample-consumer` locally, and lists the pages to open. The default operator login is for local use only.

[`docs/quickstart.md`](docs/quickstart.md) 讲怎么构建、在本机启动 `platform-app` 和 `sample-consumer`，以及打开哪些页面。默认操作员口令只用于本地。

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
mvn -pl platform-app spring-boot:run
```

Create the empty database yourself. Flyway applies `V1__platform_schema.sql` and the later `V*` scripts on startup. There is no database console.

请自行建空库。启动时 Flyway 执行 `V1__platform_schema.sql` 和之后的 `V*` 脚本。没有数据库控制台。

Sample consumer (second process / 第二个进程):

```shell
export SAMPLE_DELIVERY_PORT=19081
mvn -pl sample-consumer spring-boot:run
```

`platform-app` writes `TaskRecorded` into the JDBC outbox, then pushes that row to `sample-consumer` on a local TCP socket (`127.0.0.1:19081` unless the variables above are set). That socket is the delivery path. The event leaves one application and enters the other. It is not an in-process method call. `traceparent` travels on the socket frame. The consumer's HTTP port is only for probes.

`platform-app` 把 `TaskRecorded` 写入 JDBC 出箱，再把这一行通过本地 TCP 套接字推到 `sample-consumer`（未改环境变量时是 `127.0.0.1:19081`）。这个套接字就是投递路径。事件离开一个应用、进入另一个应用，不是进程内方法调用。`traceparent` 在套接字帧上。消费者的 HTTP 端口只用于探针。

Shared discovery, config overrides, and the lock live in the same database as the rest of the platform (`service_endpoint`, `config_override`, `platform_lock`). Two processes on that database see the same rows after a restart. Static host/port keys remain the fallback when the registry has no name. There is no Nacos and no Redis.

发现、配置覆盖和锁与平台其它表同库（`service_endpoint`、`config_override`、`platform_lock`）。连同一个库的两个进程重启后仍看到同样的行。登记簿没有名字时，静态 host/port 仍是兜底。没有 Nacos，也没有 Redis。

Operator HTTP Basic (security is on by default / 安全默认开启): operators sign in against the tables (`account`, `operator_credential`, `subject_identity` in tenant `platform`), and each path needs one named permission held through `subject_role` → `role_permission`. With `SPRING_PROFILES_ACTIVE=local` platform-app seeds `platform-operator` / `change-me` (or `PLATFORM_OPERATOR_NAME` / `PLATFORM_OPERATOR_PASSWORD`); that seed is local only. Probes `GET /actuator/health/liveness` and `GET /actuator/health/readiness` are anonymous. Read-only admin (`admin.read`): `GET /admin/tenants`, `GET /admin/tasks`, `GET /admin/dead-letters`, `GET /admin/health`, `GET /admin/audit`, and the page `GET /audit`. Config overrides and registry registrations leave audit entries. Details: `docs/operator-permissions.md`.

操作员 HTTP Basic：操作员对着表登录，每条路径需要一项经 `subject_role` → `role_permission` 持有的具名权限。`SPRING_PROFILES_ACTIVE=local` 时 platform-app 写入本地操作员，只用于本地。配置覆盖和服务登记会留下审计条目，`GET /admin/audit` 与 `GET /audit` 可读。详见 `docs/operator-permissions.md`。

## Tests / 测试

`mvn test` always runs the contract, operator-permission and audit, shared registry/config/lock (Flyway scripts on H2 in PostgreSQL and MySQL mode, test scope only), lock, rate-limit, breaker, outbox-socket, model-gateway, service-discovery, config-override, http-config, form-render, record-generation, deploy-page, forms-page, codegen-page, language-page, skin-page, and architecture tests.

Live MySQL and PostgreSQL startup tests use Testcontainers and run only when Docker is available. Without Docker they are skipped, not faked. The outbox socket test does not start a database. See `docs/local-docker.md`.

`mvn test` 总会跑契约、操作员权限与审计（H2 的 PostgreSQL 与 MySQL 兼容模式执行 Flyway 脚本，只在测试范围）、锁、限流、熔断、出箱套接字、模型网关、服务发现、配置覆盖、表单渲染、记录生成、部署清单页、字段列表页、生成类型页、语言页、外观页和架构测试。

MySQL 与 PostgreSQL 的启动测试使用 Testcontainers，只有本机有 Docker 时才执行。没有 Docker 时跳过，不用别的库冒充。出箱套接字测试不启动数据库。见 `docs/local-docker.md`。


## Discovery, config, manifests, and one form / 发现、配置、清单与一份表单

`platform-app` keeps the `ServiceRegistry` and publishes it at `POST /registry/services` and `GET /registry/services`. `sample-consumer` registers itself there and resolves `platform-app` over HTTP. When that call cannot connect, static keys `platform.discovery.static.platform-app.host` and `.port` are still the fallback (`PLATFORM_APP_HOST`, `PLATFORM_APP_PORT`). `GET /services` is a read-only page for a person: one sentence, then service name, address, and the word `up` or `unknown`. The operator HTTP Basic gate applies. Notes on why the page is small are in `docs/discovery-ui.md`. Configuration is read through `ConfigSource`. `platform-app` stores overrides in the shared database and serves `GET/POST /config/entries` for one named key; `sample-consumer` reads the effective value over HTTP and falls back to local application config when unreachable. `GET /config` is a read-only page: key, effective value, and plain source (local file or stored override). Notes are in `docs/config-ui.md`. Kubernetes manifests under `deploy/k8s/` are files only; nothing applies them. `GET /deploy` is a read-only page: one sentence that these manifests are not applied to a cluster, then workload name, placeholder image, probe paths, and memory limit. Notes are in `docs/k8s-ui.md`. `form-render` turns `endpoint-publication.form.yaml` into a validated field list and a checked-in record `EndpointPublication`. `GET /forms` is a read-only page: one sentence that this is the field list, not a designer, then each field name, type, and whether it is required. Notes are in `docs/form-ui.md`. `GET /codegen` is a read-only page: one sentence that this page shows the type generated from the form, not a generator you run from the browser, then the record name and each component with its type. The values come from the checked-in record and are checked against the same YAML. Notes are in `docs/codegen-ui.md`.

`platform-app` 保存 `ServiceRegistry`，并在 `POST /registry/services` 与 `GET /registry/services` 公布。`sample-consumer` 在那里登记自己，并用 HTTP 解析 `platform-app`。调用连不上时，静态键 `platform.discovery.static.platform-app.host` 和 `.port` 仍是兜底（`PLATFORM_APP_HOST`、`PLATFORM_APP_PORT`）。`GET /services` 是给人看的只读页：一句话，然后是服务名、地址，以及 `up` 或 `unknown`。操作员 HTTP Basic 门禁同样适用。页面为什么做小，写在 `docs/discovery-ui.md`。配置经 `ConfigSource` 读取。`platform-app` 把覆盖存进共享库，并用 `GET/POST /config/entries` 提供一个具名键；`sample-consumer` 经 HTTP 读生效值，连不上时回退本地应用配置。`GET /config` 是只读页：键、生效值，以及直白来源（本地文件或已存覆盖）。说明见 `docs/config-ui.md`。`deploy/k8s/` 下的 Kubernetes 清单只是文件，不会被应用。`GET /deploy` 是只读页：一句话说明这些清单没有应用到任何集群，然后是工作负载名、占位镜像、探针路径和内存上限。说明见 `docs/k8s-ui.md`。`form-render` 把 `endpoint-publication.form.yaml` 变成校验过的字段列表，以及检入仓库的记录 `EndpointPublication`。`GET /forms` 是只读页：一句话说明这是字段列表，不是设计器，然后是每个字段的名字、类型和是否必填。说明见 `docs/form-ui.md`。`GET /codegen` 是只读页：一句话说明这一页展示从表单生成的类型，不是在浏览器里运行的生成器，然后是记录名和每个组件的类型。值来自已检入的记录，并与同一份 YAML 核对。说明见 `docs/codegen-ui.md`。

## Language and skin / 语言与外观

`page-language` holds the operator page titles in Chinese and English. `platform-app` reads them with a Spring `MessageSource` named `messageSource`. `?lang=zh` or `?lang=en` wins, then `Accept-Language`, otherwise Chinese. `GET /language` is a read-only page: one sentence, the current language, then those titles in plain words. Notes are in `docs/i18n-ui.md`. `page-skin` holds three named skins, plain, high contrast, and calm, as the same four CSS variables. `GET /skin` shows the current name and three links. A link sets a `skin` cookie. `/services`, `/config`, `/deploy`, `/forms`, `/codegen`, and `/language` use that skin. Notes are in `docs/skin-ui.md`.

`page-language` 保存操作页的中文和英文标题。`platform-app` 用名为 `messageSource` 的 Spring `MessageSource` 读取。`?lang=zh` 或 `?lang=en` 优先，然后是 `Accept-Language`，否则是中文。`GET /language` 是只读页：一句话、当前语言，然后是这些标题的人话。说明见 `docs/i18n-ui.md`。`page-skin` 保存三个具名外观：朴素、高对比、沉静，用同样的四个 CSS 变量。`GET /skin` 显示当前名字和三个链接。链接会写入 `skin` cookie。`/services`、`/config`、`/deploy`、`/forms`、`/codegen` 和 `/language` 使用这个外观。说明见 `docs/skin-ui.md`。

## Optional model gateway / 可选模型网关

`model-gateway` is not a dependency of `platform-app`. It registers a model provider and records one invocation: provider id, model id, and input digest. There is no vendor SDK and no database.

`model-gateway` 不是 `platform-app` 的依赖。它登记模型提供者，并记录一次调用：提供者标识、模型标识、输入摘要。没有厂商 SDK，也没有数据库。

## Images and CI / 镜像与持续集成

`platform-app/Dockerfile` and `sample-consumer/Dockerfile` are multi-stage builds run from the repository root (`docker build -f platform-app/Dockerfile -t subjex/platform-app:0.1.0-SNAPSHOT .`). The runtime stage runs as uid 10001. The image names match `deploy/k8s/`. `mvn test` does not build images. The CI workflow that runs `mvn -B test` on push and pull request is at `docs/ci/build.yml` for now; it needs to be moved to `.github/workflows/` by someone whose token has the `workflow` scope (see `docs/ci/README.md`).

`platform-app/Dockerfile` 与 `sample-consumer/Dockerfile` 是在仓库根目录执行的多阶段构建，运行阶段以 uid 10001 运行，镜像名与 `deploy/k8s/` 一致。`mvn test` 不构建镜像。在 push 和 pull request 上跑 `mvn -B test` 的 CI 工作流暂放在 `docs/ci/build.yml`，需要由令牌有 `workflow` 权限的人移到 `.github/workflows/`（见 `docs/ci/README.md`）。

## License / 许可证

Apache License 2.0. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).

按 Apache License 2.0 授权，见 [`LICENSE`](LICENSE) 和 [`NOTICE`](NOTICE)。

## Operator console — 操作员控制台

独立 Next.js 控制台在 [`web/`](web/)，浏览器只持有 httpOnly 会话 cookie，由服务端代理访问 `/api/v1`。本地运行见 [`web/README.md`](web/README.md)；设计取舍与截图见 [`web/docs/design-notes.md`](web/docs/design-notes.md)。

