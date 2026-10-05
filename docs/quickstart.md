# Quickstart / 快速开始

This page gets `platform-app` and `sample-consumer` running on one laptop. It is for local use only.

这一页让 `platform-app` 和 `sample-consumer` 在一台电脑上跑起来，只用于本地。

## 1. What you need / 需要什么

- JDK 21 and Maven 3.9+ / JDK 21 与 Maven 3.9 以上。
- One empty database: PostgreSQL 14+ or MySQL 8+. Docker is not required; a locally installed server is fine.
  一个空库：PostgreSQL 14 以上或 MySQL 8 以上。不需要 Docker，本机装的数据库就行。

```shell
# PostgreSQL example / PostgreSQL 示例
createuser -P subjex          # password / 口令: subjex
createdb -O subjex subjex
```

## 2. Build / 构建

```shell
mvn test                       # contract, page, and architecture tests; no Docker needed
mvn -DskipTests package        # builds the two runnable jars / 打出两个可运行 jar
```

The runnable jars carry the `exec` classifier:

可运行的 jar 带 `exec` 分类名：

- `platform-app/target/platform-app-0.1.0-SNAPSHOT-exec.jar`
- `sample-consumer/target/sample-consumer-0.1.0-SNAPSHOT-exec.jar`

## 3. Start platform-app / 启动 platform-app

```shell
export PLATFORM_CONNECTION_VENDOR=postgresql
export PLATFORM_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/subjex
export PLATFORM_JDBC_USERNAME=subjex
export PLATFORM_JDBC_PASSWORD=subjex
export PLATFORM_JDBC_DRIVER=org.postgresql.Driver
export SPRING_PROFILES_ACTIVE=local   # LOCAL ONLY: seeds the local operator / 仅限本地：写入本地操作员
java -jar platform-app/target/platform-app-0.1.0-SNAPSHOT-exec.jar
```

For MySQL use `PLATFORM_CONNECTION_VENDOR=mysql`, `jdbc:mysql://127.0.0.1:3306/subjex?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8`, and `com.mysql.cj.jdbc.Driver`. Flyway creates the tables on first start. The process refuses to serve when the live database product is not the configured vendor.

用 MySQL 时改成 `PLATFORM_CONNECTION_VENDOR=mysql`、上面的 MySQL 地址和 `com.mysql.cj.jdbc.Driver`。首次启动时 Flyway 建表。真实库产品与配置的厂商不一致时，进程拒绝提供服务。

HTTP listens on `8080`. Liveness is `http://127.0.0.1:8080/actuator/health/liveness`; readiness is `http://127.0.0.1:8080/actuator/health/readiness`.

HTTP 监听 `8080`。存活与就绪探针见上面两个地址。

## 4. Start sample-consumer / 启动 sample-consumer

In a second terminal / 第二个终端：

```shell
java -jar sample-consumer/target/sample-consumer-0.1.0-SNAPSHOT-exec.jar
```

It listens for outbox frames on TCP `19081`, serves probes on HTTP `8081`, registers itself on platform-app, and reads its config from platform-app. When platform-app is unreachable it falls back to `PLATFORM_APP_HOST` / `PLATFORM_APP_PORT` and its own `application.yml`.

它在 TCP `19081` 接收出箱帧，在 HTTP `8081` 提供探针，到 platform-app 登记自己，并从 platform-app 读配置。platform-app 连不上时，回退到 `PLATFORM_APP_HOST` / `PLATFORM_APP_PORT` 和自己的 `application.yml`。

## 5. Pages to open / 打开哪些页面

All of these are on platform-app (`http://127.0.0.1:8080`) and ask for the operator login.

下面都在 platform-app 上，都要求操作员登录。

| Path / 路径 | What it shows / 内容 |
| --- | --- |
| `/services` | registered services and `up` / `unknown` / 已登记服务与状态词 |
| `/config` | watched keys, effective value, plain source / 关注的键、生效值、来源 |
| `/deploy` | Kubernetes manifests, read only, not applied / 清单，只读，未应用 |
| `/forms` | the endpoint-publication field list / 字段列表 |
| `/codegen` | the record generated from that form / 由表单生成的记录 |
| `/language` | page titles in the current language (`?lang=en`) / 当前语言的标题 |
| `/skin` | three named skins / 三个具名外观 |
| `/audit` | audit entries, newest first / 审计记录，最新在前 |
| `/admin/health`, `/admin/tenants`, `/admin/tasks`, `/admin/dead-letters`, `/admin/audit` | read-only JSON for the operator / 只读 JSON |

## 6. Operator credentials are local only / 操作员口令只用于本地

The default operator is `platform-operator` / `change-me`. It is written into the tables only under `SPRING_PROFILES_ACTIVE=local`, so a laptop works on the first start. Do not use that profile or that password anywhere else. Set `PLATFORM_OPERATOR_NAME` and `PLATFORM_OPERATOR_PASSWORD` on both processes before the process is reachable by anyone else. Outside a laptop, provision operators as rows; see [`operator-permissions.md`](operator-permissions.md).

默认操作员是 `platform-operator` / `change-me`，只在 `SPRING_PROFILES_ACTIVE=local` 下写入表里，让本机第一次就能启动。不要在别处使用这个 profile 或这个口令。进程能被别人访问之前，在两个进程上都设置 `PLATFORM_OPERATOR_NAME` 和 `PLATFORM_OPERATOR_PASSWORD`。开发机以外，以表行开通操作员，见 [`operator-permissions.md`](operator-permissions.md)。

```shell
curl -u platform-operator:change-me http://127.0.0.1:8080/admin/health
```

## 7. Containers (optional) / 容器（可选）

With a Docker daemon, from the repository root / 有 Docker 时，在仓库根目录：

```shell
docker build -f platform-app/Dockerfile -t subjex/platform-app:0.1.0-SNAPSHOT .
docker build -f sample-consumer/Dockerfile -t subjex/sample-consumer:0.1.0-SNAPSHOT .
```

Pass the same `PLATFORM_*` variables with `-e`. The images run as uid 10001.

用 `-e` 传入同样的 `PLATFORM_*` 变量。镜像以 uid 10001 运行。

## 8. Stop / 停止

`Ctrl+C` in each terminal. Tables stay in the database; the next start applies only new migrations.

在每个终端按 `Ctrl+C`。表留在库里，下次启动只执行新的迁移。
