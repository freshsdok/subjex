# Changelog / 变更记录

Format loosely follows [Keep a Changelog](https://keepachangelog.com/). No version has been tagged yet; the tree is `0.1.0-SNAPSHOT`.
格式大致参照 Keep a Changelog。尚未打任何 tag，当前版本 `0.1.0-SNAPSHOT`。

## [Unreleased] — planned as `v0.1.0-alpha.1` (pre-release)

Contract preview with thin runtime slices, for evaluation on a trusted network. See `SECURITY.md` before exposing anything.
契约预览 + 运行面薄切片，仅供受信网络内评估。对外暴露前请先读 `SECURITY.md`。

### Added / 新增
- **Contracts** (`platform-contract`): Account / Subject / Identity split, fail-closed tenant guard, single `AuditPort`, deterministic and non-deterministic tasks on one table, outbox, single idempotency / rate-limit / message ports, `DistributedLockPort`, object-storage SPI, compile-time extensions.
- **Database**: JDBC + Flyway `V1`–`V4` shared by MySQL 8.4 and PostgreSQL 16; migration guard refuses a vendor mismatch; no ORM (enforced by ArchUnit). Live startup proof via Testcontainers when Docker is present.
- **Operators**: table-backed HTTP Basic, seven named permissions, read-only admin pages, audit for config overrides, registrations and task submission.
- **Shared registry / config / lock** in the platform database (`service_endpoint`, `config_override`, `platform_lock`), with static fallback.
- **Outbox delivery** from `platform-app` to `sample-consumer` over a TCP socket, with circuit breaker and `traceparent` propagation.
- **Entry gateway** (`entry-gateway`): HTTP forwarder with coarse in-process rate limiting (429).
- **Observability**: `/actuator/prometheus` on all three processes; optional OTLP/HTTP trace export (`PLATFORM_OTLP_ENDPOINT`).
- **Multi-replica slice**: two `platform-app` replicas in `deploy/k8s` and `deploy/compose`; `deploy/load/smoke-load.sh`.
- **JSON API** under `/api/v1` with OpenAPI at `/api/v1/openapi.json`.
- **Forms**: `FormCatalog` over `forms/*.form.yaml` (`endpoint-publication`, `config-override`), `form_submission` store, `POST/GET /api/v1/forms/{formKey}/submissions`, record generator and `FormRecordWriteMain` CLI.
- **Language / skin** modules (zh/en titles, three skins).
- **Optional `model-gateway`** (registers a provider, records one invocation; not on the startup path).
- **Operator console** (`web/`, Next.js): server-side proxy, httpOnly session cookie, optional Redis session store (`SESSION_REDIS_URL`).
- **Images**: multi-stage, non-root Dockerfiles for `platform-app`, `sample-consumer`, `entry-gateway` (not published).
- `SECURITY.md`, this changelog.

### Fixed / 修复
- **Security — console Redis sessions encrypt the Basic header at rest.** When `SESSION_REDIS_URL` (or `REDIS_URL`) is set, `web/` requires `OPERATOR_SESSION_SECRET` (≥ 32 characters) and stores `credentialHeaderEnc` (AES-256-GCM) instead of the plaintext `Authorization: Basic …` value. Process-memory sessions (no Redis) are unchanged for local/dev/tests. Multi-replica consoles must share the same secret.
  控制台 Redis 会话对 Basic 头做静态加密：启用 Redis 时必填 `OPERATOR_SESSION_SECRET`（≥32 字符），只存 AES-256-GCM 密文；无 Redis 的进程内存路径不变。多副本须共用同一密钥。
- **Security — `entry-gateway` no longer trusts `X-Forwarded-For` unconditionally.** The rate-limit client id is now the remote address unless the direct peer matches the new `gateway.trusted-proxies` setting (env `GATEWAY_TRUSTED_PROXIES`, IPs/CIDRs, default empty); then the header is read right to left, skipping trusted hops. `server.forward-headers-strategy` is pinned to `none` so Tomcat does not rewrite the remote address under Kubernetes. **Behaviour change:** deployments behind a load balancer must list it in `GATEWAY_TRUSTED_PROXIES`, or all clients share the proxy's bucket.
  入口网关不再无条件信任 `X-Forwarded-For`：默认按远端地址限流；仅当直连方命中新配置 `gateway.trusted-proxies`（默认空）时才从右往左读该头。在负载均衡后面部署时需配置 `GATEWAY_TRUSTED_PROXIES`。
- Full `mvn test` reactor was red: the `ModuleBoundaryArchTest` copies in `sample-consumer` and `entry-gateway` carried rules with no matching classes on that module's classpath (ArchUnit `failOnEmptyShould`). Each copy now keeps only the rules its module can check; all 160 tests pass.
- README / ARCHITECTURE brought in line with the tree (Redis use, multiple forms, three images, duplicated §13–15 headings).

### Known limitations / 已知限制
See `SECURITY.md` for the security-relevant ones. Also:
- Outbox delivery is attempted once, synchronously after commit; there is no background relay, so failed rows stay `PENDING`.
- Dead letters are read-only (no replay).
- Object storage has only a local-directory implementation and no HTTP endpoint uses it.
- Rate limit, circuit breaker and object storage are per-process state.
- `deploy/compose` includes `sample-consumer` (wired like k8s) but not `web/`; full compose image build / end-to-end smoke still optional and not always run in this release cycle.
- `web/` has no Dockerfile or manifests and is not covered by CI.
- CI workflow is parked in `docs/ci/build.yml` and is not active until moved to `.github/workflows/`.
- No operator provisioning API/CLI: outside the `local` profile, operators are inserted as rows (`docs/operator-permissions.md`).
