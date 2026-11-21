# Production-config release checklist / 生产配置发布检查表

WP1-5. Use this list before advertising a deployment that faces a network beyond localhost. It is the actionable companion to [`SECURITY.md`](../../SECURITY.md) **Deployment boundary** (edge TLS + reliable public-facing evaluation capability under production config). Completing it is **not** a claim of full production-readiness for every concern.

面向本机以外网络对外宣称部署前，按本表验收。这是 `SECURITY.md` 部署边界的可执行清单（边缘 TLS + 生产配置下可靠公网面向评估能力）。完成本表**并不**宣称所有关注点均已生产就绪。

The historical alpha cut record is [`v0.1.0-alpha.1-checklist.md`](v0.1.0-alpha.1-checklist.md) (tag `e7dc268`). Do not use that document’s “keep off the public internet” wording as the current boundary.
历史 alpha 检查表（tag `e7dc268`）不要再当作当前部署边界。

A fuller Nginx/APISIX edge recipe is later work (WP9). This list only requires TLS to be terminated at the edge; it does not turn `entry-gateway` into a gateway product.
更完整的 Nginx/APISIX 边缘配方属后续 WP9。本表只要求在边缘终结 TLS，不把 `entry-gateway` 当成网关产品。

---

## 1. Production profile / 生产 profile

- [ ] `SPRING_PROFILES_ACTIVE=prod` or `production` (k8s `platform-app` already sets `prod`).
- [ ] `local` is **not** combined with `prod`/`production`. `ProductionProfileGuard` fails fast if both are active.
- [ ] Production startup does **not** run `LocalOperatorSeeder` (seed is `@Profile("local")` only).

## 2. Secrets and operator accounts / 密钥与操作员账号

- [ ] No laptop seed accounts in the running process (`platform-operator` / `change-me` live only in `application-local.yml` and compose/quickstart).
- [ ] Non-dev manifests do not contain default operator passwords or `change-me` HMAC/MFA keys. k8s uses Secret references (`SecretPlaceholderGuard` rejects `change-me` outside `local`).
- [ ] Operators for a network-facing deploy are provisioned explicitly (gated bootstrap or `/api/v1/operators/**`), not by the local seeder.

## 3. Operator MFA and HTTP Basic / 操作员 MFA 与 HTTP Basic

- [ ] Under `prod`/`production`, `platform.mfa.required` is `true` (profile default). Unenrolled password login fail-closes with 403 `reason=mfa-enrollment-required`.
- [ ] HTTP Basic is **off** in production (base `application.yml` default `platform.auth.http-basic-enabled=false`; `local` / scripts may turn it on). Do not set `PLATFORM_AUTH_HTTP_BASIC_ENABLED=true` on a network-facing deploy.

## 4. Tenant create path / 租户创建路径

- [ ] `platform.tenant.auto-create-on-submit` is `false` (base and prod default). A granted submit to a missing tenant is 403 `reason=tenant-not-found` and does not insert a row.
- [ ] Tenants are created only by an authorized admin (`tenant.manage` via `/api/v1/tenants` or console `/tenants`).

## 5. Object storage / 对象存储

- [ ] Production uses S3-compatible storage (`platform.storage.type=s3`, e.g. MinIO). Endpoint, bucket, access-key, and secret-key are provided (k8s Secret `platform-object-storage` placeholders in `deploy/k8s/platform-app.yaml`).
- [ ] The bucket already exists (the adapter does not create it).
- [ ] `type=local` is not used under prod (factory refuses it; there is no fallback to `object-store/`). Local disk remains laptop/dev only and is not a multi-instance plan. See [`docs/storage/object-storage.md`](../storage/object-storage.md).

## 6. Edge TLS and management ports / 边缘 TLS 与管理端口

- [ ] TLS is terminated at the edge (Nginx or APISIX). The Java processes themselves do not serve TLS.
- [ ] `entry-gateway` is only an HTTP forwarder with coarse in-process rate limits — not the TLS terminator and not an API-gateway product.
- [ ] Management ports are **not** on Ingress (`management.server.port`; platform-app default `8089`).
- [ ] Liveness, readiness, and Prometheus are limited to the cluster network (see [`probes-and-network.md`](probes-and-network.md) and `deploy/k8s/networkpolicy-sample.yaml`).

## 7. Multi-replica gate / 多副本门禁

Default manifests stay at **`replicas: 1`**. Runtime defaults stay `platform.rate-limit.backend=process` and `platform.delivery.circuit-breaker.backend=process` so a single-replica production deploy is not surprised by a JDBC-backend flip.
默认清单保持 **`replicas: 1`**。运行时默认仍为进程内限流与熔断，避免单副本生产被静默改成 JDBC 后端。

**Before advertising `replicas > 1` / 对外宣称多副本之前：**

- [ ] `platform.rate-limit.backend=jdbc` (env `PLATFORM_RATE_LIMIT_BACKEND=jdbc`)
- [ ] `platform.delivery.circuit-breaker.backend=jdbc` (env `PLATFORM_DELIVERY_CIRCUIT_BREAKER_BACKEND=jdbc`)
- [ ] Both are set together. Optional patch: [`deploy/k8s/platform-app-shared-backends.snippet.yaml`](../../deploy/k8s/platform-app-shared-backends.snippet.yaml). Details: [`single-replica-gate.md`](single-replica-gate.md).
- [ ] Object storage is already S3-compatible under prod (section 5); do not treat local disk or a per-pod PVC as the multi-instance plan.
- [ ] `entry-gateway` rate limits remain per gateway process (no JDBC backend). Do not claim shared gateway buckets.

## 8. Backup and restore / 备份恢复（发布门禁）

A release that faces the network **must** have a recorded restore drill. Procedure: [`backup-restore.md`](backup-restore.md). Recorded PASS example: [`drills/2026-10-09-backup-restore.md`](drills/2026-10-09-backup-restore.md) (historical engine/schema; run a fresh drill against the schema you ship).
面向网络的发布**必须**有已记录的恢复演练。步骤见备份恢复文档。已有 PASS 样例见演练记录（历史引擎/schema；请对即将交付的 schema 重新演练并留痕）。

- [ ] Backup procedure is known and automated or runbooked for the target engine (PostgreSQL / MySQL).
- [ ] A restore into an empty database has been performed for this release (or an equivalent schema line).
- [ ] Flyway tip on the restore matches the backup source; a sampled historical record remains readable.
- [ ] The drill date, operator, backup checksum, and result are written under `docs/release/drills/` (or the operator’s change record).
