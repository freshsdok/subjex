# Object storage / 对象存储

Entity blobs (`entity_blob` + `ObjectStorage`) keep large/binary payloads **out of** `attrs` jsonb (ADR 0002). Hybrid and document modes share this path.

实体附件经 `entity_blob` + `ObjectStorage` 外置，**不进** `attrs` jsonb。hybrid 与 document 共用。

## Backends / 后端（SHARED-2）

| `platform.storage.type` | Impl | Notes |
| --- | --- | --- |
| `local` (default) | `LocalDirectoryObjectStorage` | Files under `platform.storage.directory` (default `object-store`) |
| `s3` | `S3ObjectStorage` | S3-compatible (AWS, MinIO, …); AWS SDK v2 |

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
      endpoint: http://127.0.0.1:9000   # blank = AWS default endpoint
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

**MinIO quick check / MinIO 快检：** create bucket `subjex-blobs`, set type `s3` + endpoint/keys above, restart `platform-app`, upload via `POST …/entities/{key}/records/{id}/blobs`.

Console multipart upload UX is **SHARED-3** (done): browser → `/api/platform` proxy (binary multipart) →
`POST …/blobs` with part `file` (also still accepts `application/octet-stream`). Max 5 MiB. No resumable/chunked S3 multipart protocol.

控制台 multipart（SHARED-3 已完成）：浏览器 → 代理透传 → `POST …/blobs` 部件 `file`（仍支持 octet-stream）。上限 5 MiB。无可续传分片 S3 协议。
