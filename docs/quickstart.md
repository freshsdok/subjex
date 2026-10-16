# Quickstart / 快速开始

This page gets a local evaluation stack running. **The product operator UI is the Next.js console in `web/`**, not the HTML pages on `platform-app`.

这一页把本机评估栈跑起来。**产品操作员界面是 `web/` 里的 Next.js 控制台**，不是 `platform-app` 上的 HTML 页。

## Golden path / 黄金路径（先做这个）

Goal: `platform-app` + console at <http://localhost:3000>. `sample-consumer` is optional (outbox demo only).

目标：`platform-app` + 控制台 <http://localhost:3000>。`sample-consumer` 可选（仅出箱演示）。

### A. Database / 数据库

One empty database: PostgreSQL 14+ or MySQL 8+ (local install or Docker). Example:

一个空库即可。示例：

```shell
createuser -P subjex          # password / 口令: subjex
createdb -O subjex subjex
```

Or use compose for Postgres only (host port `15432`) / 或只用 compose 起库（宿主机 `15432`）：

```shell
docker compose -f deploy/compose/docker-compose.yml up -d postgres
# Then JDBC URL / 然后 JDBC：
# jdbc:postgresql://127.0.0.1:15432/subjex  (user/password: subjex)
```

### B. Build and start platform-app / 构建并启动 platform-app

```shell
mvn -DskipTests package
export PLATFORM_CONNECTION_VENDOR=postgresql
export PLATFORM_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/subjex
# If Postgres came from compose / 若库来自 compose：
# export PLATFORM_JDBC_URL=jdbc:postgresql://127.0.0.1:15432/subjex
export PLATFORM_JDBC_USERNAME=subjex
export PLATFORM_JDBC_PASSWORD=subjex
export PLATFORM_JDBC_DRIVER=org.postgresql.Driver
export SPRING_PROFILES_ACTIVE=local   # LOCAL ONLY: seeds operator / 仅限本地：写入操作员
java -jar platform-app/target/platform-app-0.1.0-SNAPSHOT-exec.jar
```

HTTP listens on `8080`. Liveness: `http://127.0.0.1:8080/actuator/health/liveness`.

HTTP 监听 `8080`。存活探针见上。

MySQL: set `PLATFORM_CONNECTION_VENDOR=mysql`, a MySQL JDBC URL, and `com.mysql.cj.jdbc.Driver`. Flyway creates tables on first start.

用 MySQL 时改厂商、URL 与驱动。首次启动 Flyway 建表。

### C. Start the console / 启动控制台

In a second terminal / 第二个终端：

```shell
cd web
npm install          # or npm ci / 或 npm ci
npm run dev
```

Open <http://localhost:3000>. Sign in with the local operator (profile `local` only):

打开控制台，用本地操作员登录（仅 `local` profile）：

| Login / 登录名 | Password / 口令 |
| --- | --- |
| `platform-operator` | `change-me` |

API base defaults to `http://127.0.0.1:8080`. Override with `SUBJEX_API_BASE` if needed.

平台根地址默认 `http://127.0.0.1:8080`；需要时用 `SUBJEX_API_BASE` 覆盖。

Console details (sessions, Redis, commands): [`web/README.md`](../web/README.md). Progress notes: [`web/PROGRESS.md`](../web/PROGRESS.md).

控制台细节（会话、Redis、命令）见 [`web/README.md`](../web/README.md)；进度见 [`web/PROGRESS.md`](../web/PROGRESS.md)。

## Clients / 终端客户端（H5 + 微信小程序）

Does **not** replace the console golden path above. Product end-user UI is [`clients/uni-app/`](../clients/uni-app/) (uni-app Vue3 + Vite; HBuilderX-compatible). See ADR [`0007-client-triad.md`](adr/0007-client-triad.md).

**不替代**上面的控制台黄金路径。终端用户面在 `clients/uni-app/`。

```shell
cd clients/uni-app
npm install
npm run dev:h5          # browser H5
# optional / 可选:
# npm run build:h5
# npm run dev:mp-weixin # then open dist/dev/mp-weixin in WeChat DevTools
```

Default API base: `http://127.0.0.1:8080` (`VITE_SUBJEX_API_BASE` to override). HBuilderX: import the `clients/uni-app` folder (details in [`clients/uni-app/README.md`](../clients/uni-app/README.md)).



### D. Optional: sample-consumer / 可选：sample-consumer

Only needed to demo cross-process outbox delivery. Not required for console work.

仅演示跨进程出箱；做控制台开发不必启动。

```shell
export SPRING_PROFILES_ACTIVE=local
java -jar sample-consumer/target/sample-consumer-0.1.0-SNAPSHOT-exec.jar
```

See §4 below for ports, HMAC, and Kafka notes / 端口、HMAC、Kafka 见下文 §4。

---

## HTML pages are demos only / HTML 页仅为演示

`platform-app` still serves a few **read-only HTML** routes (`GET /services`, `/config`, `/deploy`, `/forms`, `/codegen`, `/language`, `/skin`, `/audit`, …). They are **not** the product console: no namespace UX, no modern edit flows, and they lag the Next.js UI.

`platform-app` 仍提供若干**只读 HTML**路由。它们**不是**产品控制台：无命名空间体验、无现代编辑流，且落后于 Next.js 界面。

| Use / 用途 | Where / 位置 |
| --- | --- |
| **Product console / 产品控制台** | `web/` → <http://localhost:3000> |
| Read-only HTML demos / 只读 HTML 演示 | `http://127.0.0.1:8080/...` (operator login) |
| JSON API / OpenAPI | `http://127.0.0.1:8080/api/v1/...` |

Prefer opaque Bearer tokens for API scripts. HTTP Basic (`curl -u`) is a **local/script opt-in**: on under `local`, **off by default** elsewhere (`PLATFORM_AUTH_HTTP_BASIC_ENABLED=true` to re-enable).

脚本优先 Bearer。HTTP Basic 为**本地/脚本可选**：`local` 打开，其它环境默认关。

```shell
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"loginName":"platform-operator","password":"change-me"}' | jq -r .accessToken)
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/v1/me
```

---

## 0. Optional init (skippable) / 可选初始化（可跳过）

You can skip this: copy env vars from this page, or start from `deploy/compose`. Init only writes local stubs; it does not start Postgres/Redis or bootstrap an operator.

可整段跳过。初始化只写本地 stub，不启库、不开通操作员。

```shell
mvn -pl tools/subjex-init -am -DskipTests package
java -jar tools/subjex-init/target/subjex-init-0.1.0-SNAPSHOT.jar --yes
```

Useful flags: `--dir <path>` (default `./subjex-dev`), `--yes` / `-y`, `--skip`, `--help`.

Writes `.env.example`, `application-local.yml`, `INIT-NOTES.md` under the target dir. Then continue with the golden path (or compose).

写入目标目录后继续黄金路径（或 compose）。

## 1. What you need / 需要什么

- JDK 21 and Maven 3.9+ / JDK 21 与 Maven 3.9 以上
- Node.js 20+ (for `web/`) / Node.js 20 以上（控制台）
- One empty database: PostgreSQL 14+ or MySQL 8+ / 一个空库

## 2. Build / 构建

```shell
mvn test                       # contract, page, and architecture tests; no Docker needed
mvn -DskipTests package        # runnable jars / 可运行 jar
```

Runnable jars (`exec` classifier) / 可运行 jar：

- `platform-app/target/platform-app-0.1.0-SNAPSHOT-exec.jar`
- `sample-consumer/target/sample-consumer-0.1.0-SNAPSHOT-exec.jar`

## 3. Start platform-app / 启动 platform-app

Same as golden path §B. `local` also defaults `OUTBOX_HMAC_SECRET` (see `application-local.yml`); override before shared use.

同黄金路径 §B。`local` 亦默认出箱 HMAC；上共享网络前请覆盖。

## 4. Start sample-consumer (optional) / 启动 sample-consumer（可选）

Same as golden path §D. Listens for outbox frames on TCP `19081` (HMAC; plaintext only under `local`), probes on HTTP `8081`, registers on platform-app, reads config from platform-app.

同黄金路径 §D。TCP `19081` 收出箱帧，HTTP `8081` 探针，向 platform-app 登记并读配置。

**Demo limits / 演示限制：** socket outbox is single-address, body ≤ 4000 bytes, shared HMAC — not a multi-consumer bus. Default `platform.delivery.transport=socket`. Optional Kafka: module `subjex-outbox-kafka`, env `PLATFORM_DELIVERY_TRANSPORT=kafka`, `OUTBOX_KAFKA_*` (non-`local` needs TLS/SASL).

套接字出箱为演示限制；Kafka 为可选适配器。

## 5. Operator credentials are local only / 操作员口令只用于本地

Default: `platform-operator` / `change-me`, seeded only under `SPRING_PROFILES_ACTIVE=local`. Do not use that profile or password elsewhere. Set `PLATFORM_OPERATOR_NAME` / `PLATFORM_OPERATOR_PASSWORD` before the process is reachable by others. Outside a laptop, provision operators as rows; see [`operator-permissions.md`](operator-permissions.md).

默认口令只在 `local` 写入。别处勿用。对外可达前请改环境变量。开发机以外以表行开通，见 [`operator-permissions.md`](operator-permissions.md)。

## 6. Containers and compose (optional) / 容器与编排（可选）

### Images / 镜像

```shell
docker build -f platform-app/Dockerfile -t subjex/platform-app:0.1.0-SNAPSHOT .
docker build -f sample-consumer/Dockerfile -t subjex/sample-consumer:0.1.0-SNAPSHOT .
docker build -f web/Dockerfile -t subjex/web:0.1.0-SNAPSHOT .   # optional console image
```

Pass the same `PLATFORM_*` / `SUBJEX_API_BASE` with `-e`. Java images run as uid 10001.

用 `-e` 传入变量。Java 镜像以 uid 10001 运行。

### Compose stack / 编排栈

Default stack (no console): Postgres, Redis, platform-app, sample-consumer, entry-gateway.

默认栈**不含**控制台：

```shell
docker compose -f deploy/compose/docker-compose.yml up --build
```

Gateway: <http://127.0.0.1:8088>. Details: [`deploy/compose/README.md`](../deploy/compose/README.md).

**Optional console profile** (adds `web/` on port 3000, talks to `platform-app` on the compose network):

**可选控制台 profile**（起 `web/`，端口 3000，编排网络内访问 platform-app）：

```shell
docker compose -f deploy/compose/docker-compose.yml --profile console up --build
```

Then open <http://localhost:3000>. Inside the container, `SUBJEX_API_BASE=http://platform-app:8080`.

打开控制台；容器内 API 基址为 `http://platform-app:8080`。

Host `npm run dev` against a compose-only platform (no published 8080): point at the gateway, e.g. `SUBJEX_API_BASE=http://127.0.0.1:8088`.

宿主机 `npm run dev` 对着未映射 8080 的 compose 栈时，把 `SUBJEX_API_BASE` 指到网关 `8088`。

## 7. Stop / 停止

`Ctrl+C` in each terminal (or `docker compose ... down`). Tables stay in the database; the next start applies only new migrations.

各终端 `Ctrl+C`（或 compose `down`）。表留在库里，下次只跑新迁移。
