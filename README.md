# subjex

Semantic modular platform: subject identity, tenant isolation, and a single task contract.

第一版按 `ARCHITECTURE.md` 实现平台契约，不包含竞赛领域。业务读写走 JDBC，不经过 ORM 模型层。MySQL 与 PostgreSQL 共用同一套迁移脚本，靠配置切换连接。

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

Create the empty database yourself. Flyway applies `V1__platform_schema.sql` on startup. There is no database console.

请自行建空库。启动时 Flyway 执行 `V1__platform_schema.sql`。没有数据库控制台。

Sample consumer (second process / 第二个进程):

```shell
export SAMPLE_DELIVERY_PORT=19081
mvn -pl sample-consumer spring-boot:run
```

`platform-app` writes `TaskRecorded` into the JDBC outbox, then pushes that row to `sample-consumer` on a local TCP socket (`127.0.0.1:19081` unless the variables above are set). That socket is the delivery path. The event leaves one application and enters the other. It is not an in-process method call. `traceparent` travels on the socket frame. The consumer's HTTP port is only for probes.

`platform-app` 把 `TaskRecorded` 写入 JDBC 出箱，再把这一行通过本地 TCP 套接字推到 `sample-consumer`（未改环境变量时是 `127.0.0.1:19081`）。这个套接字就是投递路径。事件离开一个应用、进入另一个应用，不是进程内方法调用。`traceparent` 在套接字帧上。消费者的 HTTP 端口只用于探针。

Operator HTTP Basic (security is on by default / 安全默认开启): `platform-operator` / `change-me`, unless `PLATFORM_OPERATOR_NAME` and `PLATFORM_OPERATOR_PASSWORD` are set. Probes `GET /actuator/health/liveness` and `GET /actuator/health/readiness` are anonymous. Read-only admin: `GET /admin/tenants`, `GET /admin/tasks`, `GET /admin/dead-letters`, `GET /admin/health`.

## Tests / 测试

`mvn test` always runs the contract, lock, rate-limit, breaker, outbox-socket, model-gateway, service-discovery, config-override, http-config, form-render, record-generation, and architecture tests.

Live MySQL and PostgreSQL startup tests use Testcontainers and run only when Docker is available. Without Docker they are skipped, not faked. The outbox socket test does not start a database.

`mvn test` 总会跑契约、锁、限流、熔断、出箱套接字、模型网关、服务发现、配置覆盖、表单渲染、记录生成和架构测试。

MySQL 与 PostgreSQL 的启动测试使用 Testcontainers，只有本机有 Docker 时才执行。没有 Docker 时跳过，不用别的库冒充。出箱套接字测试不启动数据库。


## Discovery, config, manifests, and one form / 发现、配置、清单与一份表单

`platform-app` keeps the `ServiceRegistry` and publishes it at `POST /registry/services` and `GET /registry/services`. `sample-consumer` registers itself there and resolves `platform-app` over HTTP. When that call cannot connect, static keys `platform.discovery.static.platform-app.host` and `.port` are still the fallback (`PLATFORM_APP_HOST`, `PLATFORM_APP_PORT`). `GET /services` is a read-only page for a person: one sentence, then service name, address, and the word `up` or `unknown`. The operator HTTP Basic gate applies. Notes on why the page is small are in `docs/discovery-ui.md`. Configuration is read through `ConfigSource`. `platform-app` keeps memory overrides and serves `GET/POST /config/entries` for one named key; `sample-consumer` reads the effective value over HTTP and falls back to local application config when unreachable. `GET /config` is a read-only page: key, effective value, and plain source (local file or memory override). Notes are in `docs/config-ui.md`. Kubernetes manifests under `deploy/k8s/` are files only; nothing applies them. `form-render` turns `endpoint-publication.form.yaml` into a validated field list and a checked-in record `EndpointPublication`.

`platform-app` 保存 `ServiceRegistry`，并在 `POST /registry/services` 与 `GET /registry/services` 公布。`sample-consumer` 在那里登记自己，并用 HTTP 解析 `platform-app`。调用连不上时，静态键 `platform.discovery.static.platform-app.host` 和 `.port` 仍是兜底（`PLATFORM_APP_HOST`、`PLATFORM_APP_PORT`）。`GET /services` 是给人看的只读页：一句话，然后是服务名、地址，以及 `up` 或 `unknown`。操作员 HTTP Basic 门禁同样适用。页面为什么做小，写在 `docs/discovery-ui.md`。配置经 `ConfigSource` 读取。`platform-app` 保存内存覆盖，并用 `GET/POST /config/entries` 提供一个具名键；`sample-consumer` 经 HTTP 读生效值，连不上时回退本地应用配置。`GET /config` 是只读页：键、生效值，以及直白来源（本地文件或内存覆盖）。说明见 `docs/config-ui.md`。`deploy/k8s/` 下的 Kubernetes 清单只是文件，不会被应用。`form-render` 把 `endpoint-publication.form.yaml` 变成校验过的字段列表，以及检入仓库的记录 `EndpointPublication`。

## Optional model gateway / 可选模型网关

`model-gateway` is not a dependency of `platform-app`. It registers a model provider and records one invocation: provider id, model id, and input digest. There is no vendor SDK and no database.

`model-gateway` 不是 `platform-app` 的依赖。它登记模型提供者，并记录一次调用：提供者标识、模型标识、输入摘要。没有厂商 SDK，也没有数据库。
