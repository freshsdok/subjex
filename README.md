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
export SAMPLE_CONSUMER_URL=http://127.0.0.1:8081
mvn -pl platform-app spring-boot:run
```

MySQL:

```shell
export PLATFORM_CONNECTION_VENDOR=mysql
export PLATFORM_JDBC_URL='jdbc:mysql://127.0.0.1:3306/subjex?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8'
export PLATFORM_JDBC_USERNAME=subjex
export PLATFORM_JDBC_PASSWORD=subjex
export PLATFORM_JDBC_DRIVER=com.mysql.cj.jdbc.Driver
export SAMPLE_CONSUMER_URL=http://127.0.0.1:8081
mvn -pl platform-app spring-boot:run
```

Create the empty database yourself. Flyway applies `V1__platform_schema.sql` on startup. There is no database console.

请自行建空库。启动时 Flyway 执行 `V1__platform_schema.sql`。没有数据库控制台。

Sample consumer (second process / 第二个进程):

```shell
mvn -pl sample-consumer spring-boot:run
```

`platform-app` publishes one event, `TaskRecorded`, to `sample-consumer` over HTTP. That HTTP call is the v1 stand-in for a broker: the event still leaves one application and enters the other. It is not an in-process method call.

`platform-app` 通过 HTTP 向 `sample-consumer` 发布唯一事件 `TaskRecorded`。这次 HTTP 是 v1 的消息中间件替身：事件离开一个应用、进入另一个应用，不是进程内方法调用。

Operator HTTP Basic (security is on by default / 安全默认开启): `platform-operator` / `change-me`, unless `PLATFORM_OPERATOR_NAME` and `PLATFORM_OPERATOR_PASSWORD` are set. Probes `GET /actuator/health/liveness` and `GET /actuator/health/readiness` are anonymous. Read-only admin: `GET /admin/tenants`, `GET /admin/tasks`, `GET /admin/dead-letters`, `GET /admin/health`.

## Tests / 测试

`mvn test` always runs the contract, lock, rate-limit, breaker, trace-header, and architecture tests.

Live MySQL and PostgreSQL startup tests use Testcontainers and run only when Docker is available. Without Docker they are skipped, not faked. This workspace had no Docker daemon, so those tests were not executed against live databases here.

`mvn test` 总会跑契约、锁、限流、熔断、追踪头和架构测试。

MySQL 与 PostgreSQL 的启动测试使用 Testcontainers，只有本机有 Docker 时才执行。没有 Docker 时跳过，不用别的库冒充。当前构建机没有 Docker，因此这里没有对真实数据库执行这两项测试。
