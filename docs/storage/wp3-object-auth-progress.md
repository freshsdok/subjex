# WP3 authorized object access progress / 对象存储授权访问进度

- Date: 2026-11-17 (Asia/Shanghai)
- ADR: [`docs/adr/0015-object-storage-authorization.md`](../adr/0015-object-storage-authorization.md)
- Baseline tip when this note was opened: `c27d2f6` (WP2 closed; feature `5ed46c1`)
- 已完成：`3ed0006` / `5ce7e96`（WP3-0 盘点 + ADR 0015）
- 已完成：`0760e74` / `be2a29c`（WP3-1 授权表与默认拒绝判定）
- 已完成：`646e8ab` / `f2c2cc5` / `22bb8b1`（WP3-2 短时签名下载 URL）
- 已完成：`b2dc1cc` / `460bc8e`（WP3-3 单文件上限 50 MiB）
- 已完成：`3ca4514` / `424f022`（WP3-4 上传/签发/授权审计与 MIME 白名单）
- 本切片提交：`b296420`（`b296420ef03478f64f9201ec22270312dcb9e4dd`；WP3-5 正式 HTTP 隔离与审计追溯套件；**WP3 包关闭**）

This track is **generic authorized object access** on top of the existing `ObjectStorage` port. It is not a medical document product, not a CDN, and not a rewrite of the platform. `web/` stays the operator console; end-user clients stay `clients/uni-app/`.

本轨是通用对象授权访问，叠在已有对象存储端口之上。不是医疗文档产品，不是 CDN，也不重写平台。

## WP3-0 [done] — inventory + ADR

Docs-only. No presigned download, ACL/grant schema, multipart 50 MiB raise, or audit hooks in this slice.

本切片只写文档，不实现签名下载、授权表、50 MiB 上限或审计挂钩。

### Inventory / 盘点（事实与路径）

| Area | What exists | Paths |
| --- | --- | --- |
| Port | `ObjectStorage`: `put` / `find` / `delete`. `StoredObject` is tenantId + objectKey + bytes + mediaType. Object key is a **single name**, not a caller path. | `platform-contract/src/main/java/com/subjex/platform/contract/storage/ObjectStorage.java`, `StoredObject.java`, `package-info.java` |
| Local disk | `LocalDirectoryObjectStorage`: `{root}/{tenantId}/{objectKey}` plus a sibling `{objectKey}.type` for media type. Rejects blank tenant/key and `..` or slash in names. Default root `object-store`. **Dev / `local` / base default only** — not a multi-instance plan. | `platform-app/src/main/java/com/subjex/platform/app/storage/LocalDirectoryObjectStorage.java` |
| S3 | `S3ObjectStorage` (AWS SDK v2, MinIO-compatible). Bucket key `{tenantId}/{objectKey}` via `s3Key`. Same single-name rules. **No presigner / signed URL in this class today.** | `platform-app/src/main/java/com/subjex/platform/app/storage/S3ObjectStorage.java` |
| Factory / WP1-4 | `ObjectStorageFactory` selects `local` or `s3`. Under `prod`/`production`, `type=local` is refused; missing endpoint/bucket/access-key/secret-key fail fast; **no fallback** to `object-store/`. Base `application.yml` and `application-local.yml` stay `type: local` so tests and a no-profile laptop still start. `application-prod.yml` / `application-production.yml` default `type: s3`. | `ObjectStorageFactory.java`; `platform-app/src/main/resources/application.yml` (`platform.storage.*`); `application-prod.yml`; `application-local.yml`; k8s Secret placeholders `platform-object-storage` in `deploy/k8s/platform-app.yaml`; checklist `docs/release/production-config-checklist.md` §5; `SECURITY.md` §10 |
| SPI descriptor | ServiceLoader lists only the local implementation (proves the descriptor; the process uses the Spring bean). | `platform-app/src/main/resources/META-INF/services/com.subjex.platform.contract.storage.ObjectStorage` |
| Wiring | `PlatformWiring.objectStorage(...)` calls the factory; `entityBlobStore`, `chunkedBlobUploadStore`, `inboxAttachmentStagingStore` all take the same `ObjectStorage` bean. | `platform-app/src/main/java/com/subjex/platform/app/PlatformWiring.java` |
| Tenant key layout | Documented and implemented as `{tenantId}/{objectKey}`. Entity blob `storage_key` is the object key (usually the `blobId`); the tenant namespace is applied by the port. Blank metadata tenant maps to object tenant `_platform_` (`JdbcEntityBlobStore.OBJECT_TENANT_PLATFORM`), not reserved identity tenant `platform`. | `docs/storage/object-storage.md`; `JdbcEntityBlobStore.objectTenant` |
| Entity blob metadata | Table `entity_blob` (V31): blob id, tenant, entity_key, record_id, field_name, content_type, byte_size, storage_key, checksum_sha256, created_at. **No owner subject, no ACL/grant columns.** Bytes stay out of `attrs` (ADR 0002 / `AttrsPayloadLimits`). | `platform-app/src/main/resources/db/migration/V31__entity_blob.sql`; `EntityBlobMetadata.java`; `JdbcEntityBlobStore.java`; `AttrsPayloadLimits.java` |
| Entity blob HTTP | `GET/POST/DELETE /api/v1/entities/{entityKey}/records/{id}/blobs` and `.../blobs/{blobId}`. Upload: `application/octet-stream`, multipart part `file` (SHARED-3), or CHUNK-1 sessions. **Download streams bytes** on authenticated GET (not a signed URL). AuthZ: `OperatorPrincipal` + `DeclarationAccess.requireVia` / `PolicyEngine` actions `blob-read` / `blob-write` (plus tenant overlay grant when requested). | `EntityBlobEndpoint.java` |
| Size cap (~5 MiB) | `JdbcEntityBlobStore.MAX_BYTES = 5 * 1024 * 1024`. Spring `spring.servlet.multipart.max-file-size: 5MB` (request 6MB). Inbox `InboxAttachmentBind.MAX_BYTES` is the same. Console: `web/src/lib/inbox-compose.ts` `INBOX_ATTACH_MAX_BYTES`; i18n `blobTooLarge` / `inboxComposeAttachTooBig` (“上限 5 MiB”). ADR 0009 recorded 5 MiB as the MVP cap (“raise = later product”). | `application.yml`; `InboxAttachmentBind.java`; `web/src/lib/inbox-compose.ts`; `web/src/i18n/phrases.ts`; `docs/adr/0009-inbox-attachments-and-group-broadcast.md` |
| Chunked / multipart session | CHUNK-1: `ChunkedBlobUploadStore` + `LocalDirChunkedBlobUploadStore`. Temp layout `root/uploadId/meta.txt` + `root/uploadId/c/{index}`. Default directory `platform.storage.chunk-upload-directory` / `PLATFORM_STORAGE_CHUNK_UPLOAD_DIRECTORY` (`object-store-chunks`). Default chunk 256 KiB; session TTL 3600s; in-memory index plus disk; `purgeExpired`. Complete **assembles** then `EntityBlobStore.put` → `ObjectStorage.put`. **AWS S3 multipart API is not used.** Inbox has a parallel chunked staging path in `InboxService` / `InboxEndpoint` (`/api/v1/inbox/attachments/uploads/...`), same 5 MiB cap. | `ChunkedBlobUploadStore.java`; `LocalDirChunkedBlobUploadStore.java`; `docs/storage/object-storage.md` §Chunked upload |
| MIME whitelist | **Inbox only:** `InboxAttachmentContentTypes` allowlist — `application/pdf`, `image/png`, `image/jpeg`, `image/webp`, `text/plain`, and Office Open XML (docx/xlsx/pptx). Entity blob upload does **not** use this list; it accepts any content type (default `application/octet-stream`) with a 128-char length check only. | `InboxAttachmentContentTypes.java`; `JdbcEntityBlobStore.put`; ADR 0009 |
| Authorization / ACL | **No object-grant table.** Entity blobs: declaration permission + `blob-read`/`blob-write` for an `OperatorPrincipal`. Inbox download: `inbox.read` + own entry (`recipientSubjectId` must match the operator subject; “own entry only”). Ontology already states membership ≠ authorization (ADR 0001 / 0006). | `EntityBlobEndpoint.requireContext`; `InboxService.downloadAttachment`; `docs/adr/0001-o1-organization-ontology.md` |
| Audit | Single `AuditPort` / `AuditEntry` / `AuditOutcome` (ADR 0004). Operator query: `GET /api/v1/audit` (`AuditApiEndpoint`) with tenant/actor/action/time filters. `OperatorActionAudit` records various operator actions. **Entity blob upload/download and inbox attachment download do not currently call `AuditPort`.** Tenant quota overflow on storage rows can audit `REFUSED` (TN-1). | `platform-contract/.../audit/AuditPort.java`, `AuditEntry.java`; `AuditApiEndpoint.java`; `OperatorActionAudit.java` |
| Tenant lifecycle | Tenant export/erase can list `entity_blob` and `ObjectStorage.delete` committed keys, then delete metadata. | `TenantLifecycleService.java` |
| Inbox staging | Bytes already in `ObjectStorage` (keys like `inbox-staging-{uuid}`); `InboxAttachmentStagingStore` is an **in-memory** map with 1h TTL (“多副本共享后置”). Staging records an `uploaderSubjectId`. | `InboxAttachmentStagingStore.java`; `InboxService` |
| Tests (existing) | `LocalDirectoryObjectStorageTest`, `S3ObjectStorageTest` (tenant key isolation), `ObjectStorageFactoryTest`, `ObjectStorageSpiTest`, `ProductionStorageDefaultTest`, `EntityBlobStoreTest`, `EntityBlobEndpointTest`, inbox attachment API/security tests, `ProductionConfigChecklistTest`. | `platform-app/src/test/java/com/subjex/platform/app/storage/`; `.../entity/`; `.../inbox/` |
| Docs | Backend and CHUNK-1 narrative; WP1-4 default. No authorization ADR before this slice. | `docs/storage/object-storage.md`; `docs/release/wp1-prod-hardening-progress.md` |

### Key decisions recorded in ADR 0015 / ADR 已锁定

- Both backends kept; authorization sits **above** the blob port (D1).
  保留本地盘与 S3；授权在端口之上。
- Download allowed for the **owner subject** or an **explicit grant** to a subject or an organization; deny by default; membership/relationship alone is not authorization (D2).
  所有者或显式授权（主体或组织）；默认拒绝；关系本身不是授权。
- Short-lived signed download: default TTL **PT15M**, hard cap **PT1H**; S3 presign in production; app-issued signed URL on local disk; audit at issue time (D3, D5).
  短时签名链接；生产 S3 预签名；本地应用签名 URL。
- Per-file cap at least **50 MiB**; reuse CHUNK-1 assemble-then-`put`; no CDN; AWS S3 multipart protocol not required to hit 50 MiB (D4).
  单文件至少 50 MiB；仍组装后 put；不做 CDN。
- Audit upload, signed-link issue, and grant changes via existing `AuditPort`; keep/share the ADR 0009 MIME allowlist on the authorized-object track (D5).
  审计走现有端口；保留 MIME 白名单。
- Operator vs end-user shells stay split; later end-user download HTTP must not open operator management APIs (D6, ADR 0014 / WP2-5).
  控制台与终端壳分离；终端下载不得打开操作员管理接口。

## WP3-1 [done] — grant schema + deny-by-default

Flyway `V50__object_grant.sql` adds `object_descriptor` (tenant + object key + required `owner_subject_id`) and `object_grant` (in-tenant grant to a `SUBJECT` or `ORGANIZATION`, optional `expires_at`). The layer sits **above** `ObjectStorage`: `ObjectGrantStore` / `JdbcObjectGrantStore` plus `ObjectAccessAuthorizer.decide(tenantId, objectKey, actorSubjectId)` → `ALLOW` / `DENY`. Both backends see the same decision because they share this authorizer, not a storage-adapter check.

授权叠在对象存储端口之上。登记必须有所有者；判定默认拒绝。

- Allow: the owner subject, or an unexpired explicit grant to that subject, or an unexpired org grant when the actor has **current ACTIVE Membership** in that org **and** the org has an **ACTIVE `tenant_organization`** link in the object's tenant. Membership or a relationship alone is denied.
  允许所有者、未过期主体授权，或「当前 ACTIVE 成员 + 该租户 ACTIVE 组织关联」的组织授权。仅有成员关系拒绝。
- Fail-closed: missing tenant, reserved `platform` (never a default home), unknown tenant, `SUSPENDED` tenant, blank actor/key, unregistered object, missing/inactive org link, ended membership, expired or revoked grant, and cross-tenant lookup/grant (a grant cannot be written for a tenant where the object is not registered).
  缺租户、platform、未知/停用租户、未登记对象、组织范围缺失、跨租户一律失败关闭。
- **Legacy gap:** existing `entity_blob` rows have no owner and are **not** backfilled. `ObjectAccessAuthorizer` denies unregistered keys (including a legacy `storage_key`). Current entity-blob HTTP still uses `PolicyEngine` `blob-read`/`blob-write`; inbox still uses “own entry”. Migration of those paths stays later (open point in ADR 0015).
  既有 entity_blob 无所有者，本切片不做回填；经本判定函数一律拒绝。现有 HTTP 路径尚未迁到本模型。
- No signed/presigned download, no 50 MiB cap raise, no audit event wiring (WP3-2/3/4).
- Tests: `ObjectGrantAccessTest` (18/18, H2 PostgreSQL + MySQL modes).

## WP3-2 [done] — short-lived signed download URLs

`ObjectDownloadLinkService.issue(...)` calls `ObjectAccessAuthorizer.decide` first and issues a URL only on `ALLOW`. Otherwise it fails closed with `ObjectDownloadDeniedException` (`reason=object-access-denied`) and no usable URL (same generic denial for unregistered / stranger / expired / revoked / cross-tenant, so existence is not leaked beyond that style). Both backends go through the same authorizer. No unauthenticated public share links; reserved `platform` is not a default; inbox / `entity_blob` are not migrated in this slice.

仅在判定为允许时签发短时 URL；否则失败关闭且不返回可用地址。S3 与本地盘走同一判定。本切片不迁 inbox / entity_blob。

- **TTL:** default `PT15M`, hard cap `PT1H` (code-enforced; `ttl-max` may only tighten it). Keys: `platform.storage.download.ttl` / `PLATFORM_STORAGE_DOWNLOAD_TTL`, `platform.storage.download.ttl-max` / `PLATFORM_STORAGE_DOWNLOAD_TTL_MAX`, `platform.storage.download.public-base-url` / `PLATFORM_STORAGE_DOWNLOAD_PUBLIC_BASE_URL`, `platform.storage.download.signing-key` / `PLATFORM_STORAGE_DOWNLOAD_SIGNING_KEY`. Local HMAC key is required when `type=local` (laptop default only in `application-local.yml`); prod S3 may leave it empty.
- **S3 / production:** `S3ObjectStorage.presignGet` uses AWS SDK v2 `S3Presigner` (`AwsS3GetPresigner`) for a presigned GET of `{tenantId}/{objectKey}`. `S3GetObjectPresigner` is fakeable in tests (no live cloud account). Bucket credentials are not shipped to the client.
- **Local disk:** HMAC-SHA256 over tenant, object key, and expiry; redeemed at the platform endpoint which verifies the signature and streams `ObjectStorage.find` bytes. Redeem does **not** re-run the authorizer (parity with S3 GET). Expired or tampered tokens return `reason=object-download-invalid`; missing bytes after a valid signature are 404.
- **HTTP (principals are not mixed):**
  - Operator issue: `POST /api/v1/objects/download-links` (`OperatorPrincipal` only; **not** added to the end-user realm-gate allow-list).
  - End-user issue: `POST /api/v1/end-user/objects/download-links` (`EndUserPrincipal`; already under `/api/v1/end-user/`).
  - Local redeem: `GET /api/v1/end-user/objects/content?tenantId=&objectKey=&expires=&sig=` — `permitAll` (the HMAC is the credential). No new realm-gate allow-list entries; only a Security matcher for this GET.
- **Audit:** marked hook `ObjectDownloadIssueAuditHook` at “authorized and issued” time. `AuditPort` is **not** wired (WP3-4). 50 MiB cap is unchanged (WP3-3).
- Tests: `ObjectDownloadLinkServiceTest` 19/19 (H2 PostgreSQL + MySQL where grants matter); `S3ObjectStorageTest` 9/9 including fake presign. Related: `ObjectGrantAccessTest` 18/18, `ObjectStorageFactoryTest` 7/7, `ProductionStorageDefaultTest` 2/2.

## WP3-3 [done] — raise single-file cap to 50 MiB

Generic per-file cap is now **50 MiB**, driven by one key: `platform.storage.upload.max-bytes` / `PLATFORM_STORAGE_UPLOAD_MAX_BYTES` (Spring `DataSize`, default `50MB`). The same default is used by `ObjectUploadLimits.DEFAULT_MAX_BYTES`, `JdbcEntityBlobStore.MAX_BYTES`, and `InboxAttachmentBind.MAX_BYTES`. Spring `spring.servlet.multipart.max-file-size` follows the same env (default 50MB); `max-request-size` defaults to 52MB as envelope slack (`SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE`).

通用单文件上限现为 50 MiB，由同一配置键驱动。CHUNK-1 仍是本地临时目录组装后再 `ObjectStorage.put`，不上 AWS S3 multipart，也不做 CDN。

- **Paths lifted:** operator entity-blob octet-stream, multipart `file`, and CHUNK-1 init/complete (`/api/v1/entities/{entityKey}/records/{id}/blobs` and `.../blobs/uploads/**`); inbox attachment upload/complete (cap aligned). Operator console: `BLOB_MAX_BYTES` / `INBOX_ATTACH_MAX_BYTES` and i18n now say 50 MiB. **No new end-user upload route** in this slice; storage-layer cap still applies if a later end-user path reuses the same store.
- **Registration:** new **tenant-scoped** entity-blob uploads (direct / multipart / CHUNK-1 complete) call `ObjectGrantStore.register(tenant, storageKey, operator.subjectId())` so WP3-1/2 signed download applies. Registration failure rolls back the blob. Unscoped `_platform_` sentinel objects are **not** registered (reserved `platform` is never a default). Historical `entity_blob` rows are **not** backfilled. Inbox still uses “own entry” for download (open point).
- **Multi-replica:** chunk sessions remain process-local (in-memory index + local temp dir). Sticky routing or a later shared session store is still required across replicas; committed bytes already belong on shared object storage (S3 in production).
- **Not in this slice:** AuditPort / MIME (WP3-4); formal isolation/audit-trace suites (WP3-5); S3 multipart protocol; medical document types.
- Tests: `ObjectUploadCapTest` 5/5 (default cap ≥ 50 MiB + yaml `50MB`; CHUNK-1 assemble-then-put under a lowered 8 KiB cap; over-cap rejected; completed object registered so a non-owner cannot obtain a download link; tenant isolation). Related: `EntityBlobStoreTest` 7/7, `EntityBlobEndpointTest` 6/6, `LocalDirChunkedBlobUploadStoreTest` 3/3.

## WP3-4 [done] — AuditPort events + shared MIME allowlist

Upload, signed-download issue, and grant create/revoke are recorded on the **existing single** `AuditPort` / `audit_entry` (ADR 0004 / 0015 D5). Operators query them through the existing `GET /api/v1/audit` path (`JdbcAdminReader` + tenant/action filters). The WP2-5 realm gate is unchanged: end-user tokens still cannot call the operator audit API. Tenant is required on every object audit row and is never defaulted to reserved `platform`.

上传、签发与授权变更写入既有单一审计端口；查询仍走 `GET /api/v1/audit`。不向终端令牌开放操作员审计接口。

- **Action names:** `object.upload`, `object.download.issue`, `object.grant.create`, `object.grant.revoke`. Outcomes use existing `AuditOutcome` (`ALLOWED` / `REFUSED`). `actionTarget` carries the object key (issue also appends `expiresAt=`); grant targets are `{objectKey}:{KIND}:{granteeId}` and revoke appends `:{grantId}`. Plain words added in `AuditPage.ACTION_WORDS`.
- **When recorded:** upload after a successful authorized-track put+register (`AuthorizedObjectRegistrar`, including CHUNK-1 complete and direct/multipart put). Download audit is recorded at **issue** time (the WP3-2 hook is now `ObjectAccessAudit`); S3 GET / local redeem are not a second audit channel (optional refused-issue rows use `REFUSED`). Grant create/revoke is recorded in `JdbcObjectGrantStore` after a successful write.
- **MIME:** shared allowlist `ObjectContentTypes`, starting from the ADR 0009 inbox set (`application/pdf`, `image/png`, `image/jpeg`, `image/webp`, `text/plain`, Office Open XML docx/xlsx/pptx). `InboxAttachmentContentTypes` now delegates to it — no second conflicting list. Medical/imaging types are not special-cased. New authorized-track uploads that are not on the list are refused fail-closed and **not stored**; a `object.upload` `REFUSED` row may still be written. Unscoped `_platform_` entity-blob uploads stay on the legacy any-type path (not silently tightened). No legacy `entity_blob` or inbox “own entry” migration.
- Tests: `ObjectAccessAuditTest` 5/5 (allowlist; upload/issue/grant events queryable via `JdbcAdminReader` scoped to the tenant and absent from another tenant; non-whitelisted MIME rejected, not stored, `REFUSED` audited). Regression: `ObjectGrantAccessTest` 18/18, `ObjectDownloadLinkServiceTest` 19/19, `EntityBlobEndpointTest` 6/6, `EntityBlobStoreTest` 7/7.

## WP3-5 [done] — formal HTTP isolation and audit-trace suite

Formal MockMvc suite `ObjectAccessIsolationTest` locks WP3 acceptance (ADR 0015). No production code and no grant-console HTTP were added; grants are still seeded via `JdbcObjectGrantStore`. MIME fail-closed over HTTP was already covered at the store/registrar layer (`ObjectAccessAuditTest`) and is not re-hit in this suite (entity-blob upload needs a heavier declaration/policy slice).

正式 HTTP 套件锁定 WP3 验收。本切片无生产代码改动，也未做授权管理台。未授权主体拿不到可用签名 URL（403 `object-access-denied`）；已授权签发可按租户在既有 `GET /api/v1/audit` 中追溯；生产仍默认共享 S3，拒绝回退本地盘。

- **Owner / subject grant:** owner can issue; a same-tenant subject without a grant cannot; after a subject grant they can; after revoke they cannot again.
- **Organization grant:** ACTIVE membership can issue once an org grant exists; membership alone cannot; after membership ends or the grant expires, cannot.
- **Cross-tenant:** a principal in tenant B cannot obtain a link for tenant A's object; `GET /api/v1/audit?tenantId=tenant-B` does not return tenant-A rows (existing `ADMIN_READ` permission is unchanged; omitting `tenantId` still returns all tenants, as before).
- **Realms:** end-user token cannot call `POST /api/v1/objects/download-links` (`operator-authentication-required`); operator token cannot call `POST /api/v1/end-user/objects/download-links`; end-user token cannot call `GET /api/v1/audit`. Local redeem still requires a valid HMAC (tampered / expired → 403 `object-download-invalid`).
- **Audit trace:** after an authorized issue, an operator with audit permission finds `object.download.issue` (and `object.upload` / `object.grant.create` when those writes were arranged). Denied issue returns no URL (a `REFUSED` row may still be written).
- **Legacy keys:** unregistered `entity_blob` storage keys remain deny-by-default on this authorizer path (no silent backfill; no `object_descriptor` row is created).
- **Shared storage checklist:** the suite asserts `prod` / `production` yaml still default `platform.storage.type=s3`, and `ObjectStorageFactory` still refuses `type=local` or a blank type under production (no local-disk fallback). Storage itself was not re-implemented.
- Tests: `ObjectAccessIsolationTest` 8/8. Focused regression 98/98 (`ObjectAccessAuditTest`, `ObjectGrantAccessTest`, `ObjectDownloadLinkServiceTest`, `ObjectUploadCapTest`, `ProductionStorageDefaultTest`, `ObjectStorageFactoryTest`, `EndUserCrossRealmIsolationTest`). Pre-existing `OperatorBootstrapTest.readerRoleGetsOnlyReadPermissions` was not run and was not fixed.

### WP3 package closed / WP3 包关闭

WP3-0…WP3-5 are done. Next package would be **WP4 (thin workflow)**; it is **not** started in this slice.

WP3-0…WP3-5 已完成。下一包将是 WP4（薄工作流）；本切片未启动。

### Current status / 现行状态（2026-11-28，tip `7c1d7d7`）

Later product-deepening slices, after the package closed, added grant-management HTTP and thin UI, plus end-user upload. Historical WP3-0 inventory and WP3-5 “no grant-console HTTP” describe the package at close, not the current tree.

关包之后的产品加深切片补上了授权管理 HTTP 与薄界面，以及终端上传。WP3-0 盘点与 WP3-5「未做授权管理台」描述的是关包当时，不是当前树。

- Grant HTTP/UI (`30f9c9d`): operator `GET /api/v1/objects` and `GET/POST/DELETE /api/v1/objects/{objectId}/grants` (`object.grant.read` / `object.grant`); end-user owner-only `/api/v1/end-user/objects/{objectId}/grants`. Console `/objects`. Owner-only panel on the existing uni-app files page. Raw subject/organization ids; not a public unauthenticated share, people picker, folder, or previewer.
- End-user upload (including CHUNK-1 for larger files) later landed on `/api/v1/end-user/objects`.
- Inbox “own entry” download and legacy `entity_blob` are still not migrated onto `ObjectGrantStore`.
- Honest limit carried from WP3-2: `redeemLocal` / S3 GET do not re-run the authorizer. Revoke and expiry stop a **new** signed-download issue; an already-issued URL remains valid until its short TTL (default PT15M, cap PT1H). No denylist was added.

### Residual gaps / 仍开放的缺口（如实列出）

- Inbox “own entry” / self-recipient download is **not** migrated onto `ObjectGrantStore`.
- Unscoped `_platform_` (and reserved `platform`) uploads stay on the legacy any-type path and are not registered for this authorizer.
- No end-user upload route yet; operator entity-blob / inbox remain the write paths.
- CHUNK-1 sessions remain process-local (in-memory index + local temp dir); committed bytes already belong on shared object storage (S3 in production).
- Historical `entity_blob` rows are not backfilled; they stay deny-by-default on the authorized-download path.
- AWS S3 multipart protocol after 50 MiB, virus scan, previewer, and public unauthenticated share links remain out of scope.
- `actorIdentityId` on some object audit rows may be a subject id, so the login-name join can be empty; rows remain queryable by tenant / action / time.

## SHA

- WP3-0：`3ed0006` (`3ed000668281c69fe1d965bd86a213750ee205d9`)
- WP3-1：`0760e74` (`0760e7497d4a74fc7057f4c6ebff6ed3ddaf0670`)
- WP3-2：`646e8ab` (`646e8abf7e9197be3d410c794f9e13d5cbebb9bf`)
- WP3-3：`b2dc1cc` (`b2dc1cceea23fa6f865d39a7b875e730edfd6bcf`)
- WP3-4：`3ca4514` (`3ca4514208bcef605b65b386115a39cc74d8672e`)
- WP3-5：`b296420` (`b296420ef03478f64f9201ec22270312dcb9e4dd`)
