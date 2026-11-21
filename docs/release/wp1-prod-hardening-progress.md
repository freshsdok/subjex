# WP1 生产加固进度 / Production hardening progress

- 日期：2026-11-17（Asia/Shanghai）
- 对照需求：[Subjex 底座工作需求](https://cursor.com/canvas/store-5f540262-7e16-4688-bb35-3d6a1e4e77a3/f6a5159d-9b63-4008-8a99-8114183be420) WP1
- 基线 tip：`2f23be8`（Q7，未推送；其下 `6a7d7bc` Q6；`github/main` 仍为 `718000f`）
- 已完成提交：`9040fc7` / `12c4441`（WP1-0/1）；`533d5a7` / `2ea2fb6`（WP1-2）；`69ec5ee` / `b0a7924`（WP1-3）；`9c333c0` / `f567e25` / `18e1ff2`（WP1-4）
- 本切片提交：`8d6165c`（`8d6165c679b03924d61dab5417a3499fa28baab1`；WP1-5 生产部署边界与发布检查表）

本页只做事实对照，不把医院目录、预约分润、签证陪诊或保险写进平台内核。
This page is a factual map only. Medical-domain features stay out of the kernel.

## WP1 要求对照 / Requirement map

| # | 要求 / Requirement | 当前代码与文档 / Current code and docs | 本切片 / This slice | 后续切片 / Later slices |
| --- | --- | --- | --- | --- |
| 1 | 生产配置禁止启用 `local` 与本地种子账号；默认口令不出现在任何非开发清单中。Production config must not enable `local` or laptop seed accounts; default passwords must not appear in non-dev manifests. | 种子仅 `@Profile("local")`：`LocalOperatorConfiguration`、`LocalOperatorSeeder`。一次性开通默认关：`platform.operator.bootstrap`（`OperatorBootstrapConfiguration`）、`super-admin-bootstrap`。`SecretPlaceholderGuard` 已拒绝非 local 的 change-me HMAC/MFA 密钥。compose（明确本机栈 `deploy/compose/docker-compose.yml`）启用 `local`，并记录本机 HMAC。k8s 清单原先未设 profile，也不种操作员（`deploy/k8s/README.md`、`deploy/k8s/platform-app.yaml`）。基础 `application.yml` **曾**带 `platform.local-operator` 默认 `platform-operator` / `change-me`（即使用不到也会出现在全 profile 配置里）。文档：`SECURITY.md` §7、`docs/quickstart.md`、`docs/operator-permissions.md`。 | **已关闭 WP1-1。** 新增专用 profile `application-prod.yml` / `application-production.yml`；`ProductionProfileGuard` 在 `local` 与 `prod`/`production` 同时激活时启动失败（`ProductionProfileGuardConfiguration` + 种子 runner 再检一次）。默认口令迁到 `application-local.yml`，从 `application.yml` 删除。k8s `platform-app` 设 `SPRING_PROFILES_ACTIVE=prod`。测试：`ProductionProfileGuardTest`。仓库无 Helm chart。 | 无（本项本切片收口）。compose / quickstart 仍可保留本机口令。 |
| 2 | 业务进程前由边缘终结 TLS；管理端口不经 Ingress 暴露，探针仅限集群网段。Terminate TLS at the edge; management port not on Ingress; probes limited to cluster CIDRs. | 进程本身不提供 TLS。出箱非 local 须 TLS（k8s 已开）。管理口默认 8089。配方：`docs/release/probes-and-network.md`、`deploy/k8s/networkpolicy-sample.yaml`。`entry-gateway` 是 HTTP 入口转发器，不是 TLS 网关产品。 | **已关闭 WP1-5（文档与检查表）。** `SECURITY.md` 部署边界改为「边缘 TLS + 生产配置下可靠公网面向评估能力」；发布检查表要求边缘终结 TLS（Nginx 或 APISIX）、管理口不经 Ingress、探针限集群网段。未写完整边缘配方（WP9）。 | WP9 边缘配方（Nginx 或 APISIX）；按需收紧 NetworkPolicy CIDR。 |
| 3 | 生产环境默认要求操作员开启 MFA；HTTP Basic 仍仅本机与脚本可选。Production must require operator MFA by default; HTTP Basic remains local/script opt-in. | 实现仍是 `JdbcOperatorMfaStore`、`OperatorAuthEndpoint`（未登记且 `enrollmentRequired` 时 403 `mfa-enrollment-required`）、`OperatorMfaEndpoint`。HTTP Basic 默认关，`application-local.yml` 打开，或 `PLATFORM_AUTH_HTTP_BASIC_ENABLED=true`；生产 profile **不**打开 Basic。 | **已关闭 WP1-2。** `application-prod.yml` / `application-production.yml` 默认 `platform.mfa.required=true`；`application-local.yml` 显式 `required: false`；基础 `application.yml` 仍为 false。测试：`ProductionMfaDefaultTest`、`OperatorMfaRequiredSecurityTest`（及原 `OperatorMfaSecurityTest`）。 | 无（本项本切片收口）。未登记操作员须先用已有 Bearer（强制开启前的会话或 OIDC）调用 `/api/v1/auth/mfa/totp/start`+`confirm`。 |
| 4 | 关闭「首次提交任务自动建租户」；生产仅能由已授权管理员显式创建。Disable auto-create tenant on first task submit; production creates tenants only via authorized admin. | 实现：`JdbcTaskMessagePort.ensureTenant`（受 `platform.tenant.auto-create-on-submit` 门控）；`JdbcTenantAdmin.requireActiveForTaskWrite` 在缺失时抛 `TenantNotFoundException`；显式管理仍是 `/api/v1/tenants` 与控制台 `/tenants`。文档：`SECURITY.md` §5、`docs/auth-roadmap.md`。 | **已关闭 WP1-3。** 开关默认 **false**（`application.yml` 与 `prod`/`production`），仅 `application-local.yml` 为 true（本机逃生）。缺租户提交失败关闭（403 `reason=tenant-not-found`），不插行。测试：`TenantAutoCreateOnSubmitTest`、`ProductionTenantAutoCreateDefaultTest`；`TenantManagementSecurityTest` 写门禁已改为缺失即拒绝。选择说明：基础配置失败关闭，避免忘开 production profile 时误自动建户；本机/测试可用 `local` 或测试构造参数打开。现有投递测试只走 `relayPending`，未改其构造（默认 false）。 | 无（本项本切片收口）。 |
| 5 | 生产默认对象存储改为 S3 兼容（如 MinIO）；本地盘仅用于开发，不得作为多实例方案。Production default object storage is S3-compatible; local disk is dev-only. | 实现已有：`S3ObjectStorage`（AWS SDK v2）与 `LocalDirectoryObjectStorage`；`PlatformWiring` 经 `ObjectStorageFactory` 选择。文档：`docs/storage/object-storage.md`、`docs/release/single-replica-gate.md`。 | **已关闭 WP1-4。** `prod`/`production` 默认 `platform.storage.type=s3`；缺 endpoint/bucket/凭证启动失败，生产拒绝 `type=local`，不回退到 `object-store/`。基础 `application.yml` 与 `local` 仍为目录存储（避免大批测试与未开 profile 的本机失败；生产安全靠 `prod` profile + k8s `SPRING_PROFILES_ACTIVE=prod`）。k8s 用 `platform-object-storage` Secret 占位，无真实密钥；compose 仍用本地盘。测试：`ObjectStorageFactoryTest`、`ProductionStorageDefaultTest`；租户键隔离仍由既有 `S3ObjectStorageTest` / `LocalDirectoryObjectStorageTest` 覆盖。未引入 Testcontainers / 直播云账号。 | 无（本项本切片收口）。 |
| 6 | 多副本宣称前，限流与投递熔断须切到共享 JDBC 后端；备份恢复纳入发布门禁。Before advertising multi-replica, rate-limit and delivery circuit-breaker must use shared JDBC; backup/restore is a release gate. | 默认仍为 `process`；JDBC 实现已存在。门禁文档：`docs/release/single-replica-gate.md`、`SECURITY.md` §10；默认清单 `replicas: 1`；可选片段 `deploy/k8s/platform-app-shared-backends.snippet.yaml`。备份：`docs/release/backup-restore.md`，演练 PASS `docs/release/drills/2026-10-09-backup-restore.md`。 | **已关闭 WP1-5。** [`docs/release/production-config-checklist.md`](production-config-checklist.md) 把「对外 replicas>1 须双 JDBC 后端」和「面向网络的发布必须有已记录的恢复演练」列为明确门禁。**未**把运行时默认改成 `jdbc`，以免单副本生产被静默切换。测试：`ProductionConfigChecklistTest` 锁定 prod yaml 与默认 k8s 清单（`prod` profile、replicas:1、S3 Secret 占位、未静默写 jdbc 后端）。 | 无（本项本切片收口）。WP1 文档项已全部关闭。 |

## 已关闭 / Closed

- **WP1-0** 盘点（本页）。
- **WP1-1** 生产配置门禁：`local` 与 `prod`/`production` 互斥且失败关闭；生产启动不种入 `LocalOperatorSeeder`；本机 `local` 路径仍可写入默认操作员；非开发 k8s 清单不含 `change-me` / 默认 `platform-operator` 口令。提交 `9040fc7` / `12c4441`。
- **WP1-2** 生产配置默认强制操作员 MFA：`prod`/`production` 下 `platform.mfa.required=true`；`local` 仍可选；未登记口令登录 403，已登记 TOTP 校验不变；生产不打开 HTTP Basic。提交 `533d5a7` / `2ea2fb6`。
- **WP1-3** 关闭首次提交自动建租户：默认与生产关闭；仅 `local` 可打开；缺租户 403 `tenant-not-found`；显式 `/api/v1/tenants` 与 SUSPENDED 拒绝不变。
- **WP1-4** 生产默认 S3 兼容对象存储：`prod`/`production` 默认 `s3`（复用 `S3ObjectStorage` / MinIO 兼容）；配置缺失失败关闭；生产拒绝本地盘；本机与基础配置仍用目录；k8s Secret 占位。
- **WP1-5** 生产部署边界与发布检查表：`SECURITY.md` 改为「边缘 TLS + 生产配置下可靠公网面向评估能力」；[`docs/release/production-config-checklist.md`](production-config-checklist.md) 将生产 profile、MFA、禁止本地种子、关闭自动建户、S3、边缘 TLS、管理口隔离、多副本前 JDBC 共享后端、备份演练列为发布门禁。

## 仍留给后续 / Deferred

- WP1 本工作包已关闭。下一步是 **WP2 终端用户身份**（本切片不开始）。
- WP9：Nginx 或 APISIX 边缘 TLS / 限流完整配方；按需收紧 NetworkPolicy CIDR。

## 扫描备注 / Scan notes

- `deploy/compose/` 是明确的本机栈（`SPRING_PROFILES_ACTIVE: local`，HMAC `change-me-outbox-hmac-secret-local!!`）。按要求保留。
- `deploy/k8s/platform-app.yaml` 仍以明文写出 JDBC `subjex` / `subjex`（不是 `change-me` 或本地操作员口令）。本切片未改；生产部署应改为 Secret。入口网关与 sample-consumer 的 k8s 清单未种操作员。
- 仓库没有 Helm chart。
- `docs/quickstart.md` 与 `SECURITY.md` §7 仍记载本机种子，作为本机说明而非非开发运行手册默认值。

## 测试 / Tests

WP1-5 聚焦 `ProductionConfigChecklistTest`（并回归已有 Production*Default / ProductionProfileGuard 断言，由本测试集中锁定检查表声称的 prod yaml 与 k8s 清单）。

## SHA

- WP1-0/1：`9040fc7` / `12c4441`
- WP1-2：`533d5a7` / `2ea2fb6`
- WP1-3：`69ec5ee` (`69ec5ee8ae8cdec27995f2748ad0b9e3cc3676ae`)
- WP1-4：`9c333c0` (`9c333c00a4a5780ac714255b2d80e487de18ccbe`)
- WP1-5：`8d6165c` (`8d6165c679b03924d61dab5417a3499fa28baab1`)
