# Local compose — 本地编排

Starts PostgreSQL 16, `platform-app`, and `entry-gateway` for node 4.
启动 PostgreSQL 16、`platform-app` 和 `entry-gateway`，供节点四使用。

```bash
docker compose -f deploy/compose/docker-compose.yml up --build
```

- Gateway: `http://127.0.0.1:8088` (forwards to platform-app)
- Platform: `http://127.0.0.1:8080`
- Postgres: `127.0.0.1:15432` (user/password/db: `subjex`)

Local operator (profile `local`): `platform-operator` / `change-me`.
本地操作员（`local` profile）：`platform-operator` / `change-me`。

`mvn test` does not build these images. Stop with Ctrl-C or `docker compose -f deploy/compose/docker-compose.yml down -v`.
`mvn test` 不构建这些镜像。用 Ctrl-C 或 `down -v` 停掉。
