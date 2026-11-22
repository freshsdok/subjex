# Local compose — 本地编排

Starts PostgreSQL 16, Redis 7, **one** `platform-app` (P4/Scale-4d default), `sample-consumer` (outbox delivery), and `entry-gateway`.
启动 PostgreSQL 16、Redis 7、**一个** `platform-app`（P4/Scale-4d 默认）、`sample-consumer`（出箱投递）和 `entry-gateway`。

**Default stack does not start the console.** Use profile `console` or run `web/` with `npm run dev` (see [`docs/quickstart.md`](../../docs/quickstart.md) golden path).
**默认栈不起控制台。** 用 profile `console`，或本机 `web/` 跑 `npm run dev`（见 [`docs/quickstart.md`](../../docs/quickstart.md) 黄金路径）。

```bash
# Default (no console) / 默认（无控制台）
docker compose -f deploy/compose/docker-compose.yml up --build

# Optional console on :3000 / 可选控制台 :3000
docker compose -f deploy/compose/docker-compose.yml --profile console up --build
```

- Gateway: `http://127.0.0.1:8088` (single upstream by default)
- Console (profile `console`): `http://127.0.0.1:3000` with `SUBJEX_API_BASE=http://platform-app:8080`
- Multi-replica (WP9-3, optional): do **not** `--scale platform-app=2` on the default file alone. Use the override that sets both shared JDBC backends, then scale:
  多副本（WP9-3，可选）：不要只在默认文件上 `--scale platform-app=2`。请使用已配齐两个共享 JDBC 后端的覆盖文件，再扩容：
  ```bash
  docker compose -f deploy/compose/docker-compose.yml \
    -f deploy/compose/docker-compose.shared-backends.yml \
    up --build --scale platform-app=2
  ```
  The override is [`docker-compose.shared-backends.yml`](docker-compose.shared-backends.yml). Defaults stay one `platform-app` and `process` backends. Object store stays per-container disk without a shared volume — do not advertise shared blobs. `entry-gateway` rate limits stay per process. Checking the override in is **not** a production deploy. See [`docs/release/single-replica-gate.md`](../../docs/release/single-replica-gate.md).
  覆盖文件见上。默认仍是一个 `platform-app` 与 `process` 后端。无共享卷时对象存储仍按容器本地，不要宣称共享对象。入口转发器限流仍按进程。写入覆盖文件不等于生产发布。详见单副本门禁文档。
- Redis: `127.0.0.1:16379` (console sessions when `SESSION_REDIS_URL=redis://127.0.0.1:16379`)
- Postgres: `127.0.0.1:15432` (user/password/db: `subjex`)
- Platform is not published on the host; use the gateway (avoids port clash when scaled). Host `npm run dev` against this stack: `SUBJEX_API_BASE=http://127.0.0.1:8088`.
- `sample-consumer` is on the compose network only (`SAMPLE_CONSUMER_HOST=sample-consumer`, `SAMPLE_CONSUMER_PORT=19081` on platform-app, same as k8s). Task outbox delivery can reach it; HTTP probes on 8081, delivery TCP on 19081.

Local operator (profile `local`): `platform-operator` / `change-me`.
本地操作员（`local` profile）：`platform-operator` / `change-me`。

Outbox: both processes share `OUTBOX_HMAC_SECRET` (compose sets the local default). Profile `local` allows plaintext TCP; non-local requires TLS — see `SECURITY.md`.
出箱：两进程共用 `OUTBOX_HMAC_SECRET`（compose 写入本地默认）。`local` 允许明文 TCP；非 local 须 TLS，见 `SECURITY.md`。

Optional OTLP: `PLATFORM_OTLP_ENDPOINT=http://host.docker.internal:4318 docker compose ...`
可选 OTLP：如上设置环境变量。

`mvn test` does not build these images. Stop with Ctrl-C or `docker compose -f deploy/compose/docker-compose.yml down -v`.
`mvn test` 不构建这些镜像。用 Ctrl-C 或 `down -v` 停掉。

Short load smoke through the gateway:

```bash
./deploy/load/smoke-load.sh
```
