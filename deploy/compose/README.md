# Local compose — 本地编排

Starts PostgreSQL 16, Redis 7, two `platform-app` replicas, `sample-consumer` (outbox delivery), and `entry-gateway` for node 5.
启动 PostgreSQL 16、Redis 7、两个 `platform-app` 副本、`sample-consumer`（出箱投递）和 `entry-gateway`，供节点五使用。
`web/` is not part of this stack.
本栈不含 `web/`。

```bash
docker compose -f deploy/compose/docker-compose.yml up --build --scale platform-app=2
```

- Gateway: `http://127.0.0.1:8088` (DNS round-robin to platform-app replicas)
- Redis: `127.0.0.1:16379` (console sessions when `SESSION_REDIS_URL=redis://127.0.0.1:16379`)
- Postgres: `127.0.0.1:15432` (user/password/db: `subjex`)
- Platform is not published on the host; use the gateway (avoids port clash when scaled).
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
