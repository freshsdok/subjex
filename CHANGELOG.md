# Changelog / 变更记录

Format loosely follows [Keep a Changelog](https://keepachangelog.com/). No version has been tagged yet; the tree is `0.1.0-SNAPSHOT`.
格式大致参照 Keep a Changelog。尚未打任何 tag，当前版本 `0.1.0-SNAPSHOT`。

## [Unreleased] — planned as `v0.1.0-alpha.1` (pre-release)

Contract preview with thin runtime slices, for evaluation on a trusted network. See `SECURITY.md` before exposing anything.
契约预览 + 运行面薄切片，仅供受信网络内评估。对外暴露前请先读 `SECURITY.md`。

### Added / 新增
- **Console debug UX (stage 6)**: form submit API returns structured success (`submissionId`, `declarationVersion`, `submittedAt`, `effects[]`) and problems (`validation` + fieldErrors, or `permission_denied` + permission name); console `FormDebugPanel` on `/forms` and `/pages/.../new` shows them in one place (zh/en). No designer / online schema edit. See `docs/lowcode-roadmap.md`.
  控制台调试 UX（阶段 6）：提交成功/失败结构化；控制台同处展示校验、权限、落库与副作用摘要；无设计器。

- **Declaration versioning (stage 5)**: required integer `version` (≥ 1) on form / entity / flow YAML (fail-closed); catalog JSON exposes `version`; form submissions persist `declaration_version` (Flyway `V5`); process notes in `docs/declaration-migration.md` (bump version on field change; entity → new migration draft; read old submissions against old versions; rollback = redeploy prior declaration, no auto-drop). No online schema edit. See `docs/lowcode-roadmap.md`.
  声明版本化（阶段 5）：YAML 必填整数 `version`；目录暴露版本；提交落库记下声明版本；迁移策略文档；无在线改 schema。

- **Side-effect catalog (stage 4)**: checked-in `effects/side-effect-catalog.yaml` + `SideEffectKey` (`audit.write`, `task.enqueue`, `extension.invoke`); forms may declare `effects` (samples updated); `FormSideEffectRunner` runs them after validate/domain action via existing Audit / Task / extension ports under the same subject/tenant; unknown effect keys rejected at render (fail-closed). No online designer. See `docs/lowcode-roadmap.md`.
  副作用目录（阶段 4）：检入目录与枚举；表单可声明 `effects`；提交后经已有端口执行；未知键渲染即拒；无设计器。

- **Permission / tenant on declarations (stage 3)**: form / entity / flow YAML require `permission` (fail-closed if missing) and optional `tenantScoped`; samples updated; catalog JSON exposes flags; `DeclarationAccess` enforces declared permission (+ tenant header when scoped) on form/page detail and submissions without new security-config matchers per form. Console greys out entries the operator lacks. See `docs/lowcode-roadmap.md`.
  声明上的权限/租户（阶段 3）：YAML 必填 `permission`、可选 `tenantScoped`；目录暴露标志；API 按声明强制；控制台无权限置灰。

- **Page / flow declare (stage 2)** (`page-declare`): checked-in `flows/*.flow.yaml` (sample `endpoint-publication`) → validated list / detail / submit specs (console paths, `/api/v1/...` apiPaths, post-submit `redirectTo`). `PageCatalog` + `GET /api/v1/pages` / `{flowKey}`; console `/pages` renders from the declaration (list, detail, submit-then-redirect). No visual designer. See `docs/lowcode-roadmap.md`.
  页面/流程声明（阶段 2）：检入 YAML → 校验列表/详情/提交描述；目录与 JSON API；控制台按声明渲染；无设计器。

- **Entity declare (stage 1)** (`entity-declare`): checked-in `entities/*.entity.yaml` (sample `service-note`) → validated render, Flyway-style `CREATE TABLE` draft under `db/migration-draft/`, Java record + CRUD port stub + JDBC sketch (`com.subjex.entity.generated`), and `EntityDraftWriteMain` CLI. Drafts are human-editable and not applied by `platform-app` Flyway; no online schema edit; not wired into `platform-app` yet. See `docs/lowcode-roadmap.md`.
  实体声明（阶段 1）：检入 YAML → 校验渲染、Flyway 风格建表草稿、记录/CRUD 端口/JDBC 草图与 CLI；草稿可人工编辑，未接入 `platform-app` Flyway，无在线改 schema。
- **Contracts** (`platform-contract`): Account / Subject / Identity split, fail-closed tenant guard, single `AuditPort`, deterministic and non-deterministic tasks on one table, outbox, single idempotency / rate-limit / message ports, `DistributedLockPort`, object-storage SPI, compile-time extensions.
- **Database**: JDBC + Flyway `V1`–`V4` shared by MySQL 8.4 and PostgreSQL 16; migration guard refuses a vendor mismatch; no ORM (enforced by ArchUnit). Live startup proof via Testcontainers when Docker is present.
- **Operators**: table-backed HTTP Basic, seven named permissions, read-only admin pages, audit for config overrides, registrations and task submission.
- **Operator bootstrap (one-shot)**: `platform-app` can create/update one operator without hand-written SQL when `--platform.operator.bootstrap=true` (off by default); env `PLATFORM_OPERATOR_LOGIN` / `PLATFORM_OPERATOR_PASSWORD` / optional `PLATFORM_OPERATOR_ROLE`; idempotent by login; password min length 8; process exits after upsert. See `docs/operator-permissions.md`.
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
- No operator admin UI / password-change / disable API yet; one-shot bootstrap covers create/update by login (`docs/operator-permissions.md`).
