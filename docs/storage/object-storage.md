# Object storage / 对象存储

Entity blobs (`entity_blob` + `ObjectStorage`) keep large/binary payloads **out of** `attrs` jsonb (ADR 0002). Hybrid and document modes share this path.

实体附件经 `entity_blob` + `ObjectStorage` 外置，**不进** `attrs` jsonb。hybrid 与 document 共用。

## Backends / 后端（SHARED-2 / WP1-4）

| `platform.storage.type` | Impl | Notes |
| --- | --- | --- |
| `local` | `LocalDirectoryObjectStorage` | **Dev / `local` profile / base default only.** Files under `platform.storage.directory` (default `object-store`). Not a multi-instance plan. 仅开发 / `local` / 基础默认。不是多实例方案。 |
| `s3` | `S3ObjectStorage` | **Production default** (`application-prod.yml` / `application-production.yml`). S3-compatible (MinIO or any S3 API); AWS SDK v2. 生产默认。 |

A forgotten production profile still sees the base `local` default so unit tests keep working; k8s sets `SPRING_PROFILES_ACTIVE=prod`. Under `prod`/`production`, `type=local` is refused and missing S3 endpoint/bucket/credentials fail fast (no fallback to `object-store/`).
未开生产 profile 时基础配置仍为 local，以免测试失败；k8s 已设 `prod`。生产配置下禁止 local，S3 配置缺失则启动失败，不回退到本地目录。

### Local (dev / tests)

```yaml
platform:
  storage:
    type: local
    directory: object-store
```

Env: `PLATFORM_STORAGE_TYPE=local`, `PLATFORM_STORAGE_DIRECTORY=…`.

### S3 / MinIO

```yaml
platform:
  storage:
    type: s3
    s3:
      endpoint: http://127.0.0.1:9000   # required when type=s3 (factory fail-fast; MinIO or S3 API URL)
      region: us-east-1
      bucket: subjex-blobs              # must already exist
      access-key: minioadmin
      secret-key: minioadmin
      path-style-access: true           # typically true for MinIO
```

Env aliases:

- `PLATFORM_STORAGE_TYPE=s3`
- `PLATFORM_STORAGE_S3_ENDPOINT`
- `PLATFORM_STORAGE_S3_REGION`
- `PLATFORM_STORAGE_S3_BUCKET`
- `PLATFORM_STORAGE_S3_ACCESS_KEY` / `PLATFORM_STORAGE_S3_SECRET_KEY`
- `PLATFORM_STORAGE_S3_PATH_STYLE` (default `true`)

Object key layout: `{tenantId}/{objectKey}` inside the bucket (same tenant isolation idea as local directories).

桶内键：`{tenantId}/{objectKey}`（与本地按租户分目录一致）。

Production (`prod` / `production`) already defaults `type` to `s3`. Set the env vars (or Secret keys in `deploy/k8s/platform-app.yaml`) before starting; the process will not write to a local directory if they are missing.
生产配置已默认 `type=s3`。启动前设置环境变量（或 k8s Secret）；缺失时进程失败关闭，不会写本地目录。

**MinIO quick check / MinIO 快检：** create bucket `subjex-blobs`, set type `s3` + endpoint/keys above, restart `platform-app`, upload via `POST …/entities/{key}/records/{id}/blobs`.

Console multipart upload UX is **SHARED-3** (done): browser → `/api/platform` proxy (binary multipart) →
`POST …/blobs` with part `file` (also still accepts `application/octet-stream`). Max 50 MiB (`platform.storage.upload.max-bytes` / `PLATFORM_STORAGE_UPLOAD_MAX_BYTES`). Larger files should use CHUNK-1 assemble-then-`put`. No AWS S3 multipart protocol.

控制台 multipart（SHARED-3 已完成）：浏览器 → 代理透传 → `POST …/blobs` 部件 `file`（仍支持 octet-stream）。上限 50 MiB（`platform.storage.upload.max-bytes`）。更大文件走 CHUNK-1 组装后 put。不使用 AWS S3 multipart 协议。

## Chunked upload / 分片上传（CHUNK-1）

Sessions live under `platform.storage.chunk-upload-directory` (temp files). Complete assembles
bytes then calls `ObjectStorage.put` — same path for `local` and `s3`. True AWS S3 multipart
API is not used. The in-process session index is **not** multi-replica safe; sticky routing
or a later shared session store is still required across replicas (ADR 0015 open point).
Committed bytes already belong on shared object storage (S3 in production).

会话在 `chunk-upload-directory` 临时文件中；完成时组装后 `ObjectStorage.put`（local/s3 相同）。
不调用 AWS S3 multipart API。分片会话索引仍是进程内的；多副本仍需粘性路由或后续共享会话存储。
已提交字节在生产应落在共享对象存储（S3）上。

## Authorized access (WP3) / 授权访问（WP3）

Direction is in [ADR 0015](../adr/0015-object-storage-authorization.md). Progress: [`wp3-object-auth-progress.md`](wp3-object-auth-progress.md).

WP3-1 added `object_descriptor` / `object_grant` and `ObjectAccessAuthorizer` (deny by default; owner or explicit grant). WP3-2 issues a short-lived signed download **only after** that authorizer allows the actor.

- Operator issue: `POST /api/v1/objects/download-links` (`OperatorPrincipal`).
- End-user issue: `POST /api/v1/end-user/objects/download-links` (`EndUserPrincipal`).
- Local-disk redeem: `GET /api/v1/end-user/objects/content?tenantId=&objectKey=&expires=&sig=` (HMAC is the credential).
- S3 / production: presigned GET via AWS SDK v2 (`S3ObjectStorage.presignGet`); the client then fetches from the bucket. Credentials are not shipped to the client.
- TTL: `platform.storage.download.ttl` / `PLATFORM_STORAGE_DOWNLOAD_TTL` (default `PT15M`); hard cap `PT1H` (`ttl-max` / `PLATFORM_STORAGE_DOWNLOAD_TTL_MAX` may only tighten it). Local HMAC: `platform.storage.download.signing-key` / `PLATFORM_STORAGE_DOWNLOAD_SIGNING_KEY` and `public-base-url` / `PLATFORM_STORAGE_DOWNLOAD_PUBLIC_BASE_URL`.

WP3-3 raised the generic single-file cap to 50 MiB (see `platform.storage.upload.max-bytes` above). New tenant-scoped entity-blob uploads (direct, multipart, or CHUNK-1 complete) are registered with `ObjectGrantStore` so signed download uses owner-or-grant. Unscoped `/_platform_/` sentinel objects and historical `entity_blob` rows are not backfilled. Inbox still uses “own entry” for download (cap is aligned).

WP3-4 records `object.upload`, `object.download.issue`, `object.grant.create`, and `object.grant.revoke` on the existing `AuditPort` (query: `GET /api/v1/audit`). Signed-download audit is at issue time. The authorized-object track shares the ADR 0009 MIME allowlist (`ObjectContentTypes`); non-whitelisted types are refused and not stored. WP3-5 formal MockMvc suite `ObjectAccessIsolationTest` locks isolation and audit-trace acceptance (WP3 package closed).

授权访问见 ADR 0015。WP3-1 默认拒绝；WP3-2 短时签名下载；WP3-3 将通用单文件上限提高到 50 MiB；WP3-4 把上传/签发/授权变更写入既有审计端口，并在授权轨共用 MIME 白名单。WP3-5 正式 HTTP 隔离与审计追溯套件已锁定验收（WP3 包关闭）。
