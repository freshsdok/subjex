# Entity storage hybrid B — progress / 实体存储混合 B 进度

Tracks ADR [`adr/0002-entity-storage-hybrid-b.md`](adr/0002-entity-storage-hybrid-b.md).

按 ADR 0002 跟踪实现切片。

## Slice log / 切片记录

### ES-1 [done] — shared `entity_record` + HybridEntityStore sketch

- Flyway `V30__entity_record.sql`: `tenant_id`, `entity_key`, `record_id`, `record_state`, `attrs` (JSON text VARCHAR for dual-mode), `created_at`, `updated_at`.
- `EntityStorageMode` (`hybrid`|`table`); YAML `storageMode`; **omit → hybrid** (zero-code default). Samples `service-note` / `demo-ticket`: **HY-1** → `storageMode: hybrid` (was table).
- `HybridEntityStore` + `JdbcHybridEntityStore` (save / findById / list); bean in `PlatformWiring`. Physical path: `GenericEntityStore` unchanged.
- Tests: `HybridEntityStoreTest` insert/list (+ tenant isolation, table-mode reject, default hybrid).
- **Not in ES-1:** migrate existing entities; endpoint/form auto-dispatch to hybrid; attachments/blobs.

### ES-2 [done] — blob / attachment metadata + ObjectStorage

- Flyway `V31__entity_blob.sql`: blob_id, tenant_id, entity_key, record_id, field_name, content_type, byte_size, storage_key, checksum_sha256, created_at.
- Reuses existing `ObjectStorage` / `LocalDirectoryObjectStorage`; added `delete`. Blank metadata tenant maps to object tenant `_platform_`.
- `EntityBlobStore` + `JdbcEntityBlobStore` (put/find/list/delete/deleteForRecord); `AttrsPayloadLimits` on hybrid save (string/JSON caps; reject `byte[]` in attrs).
- `GenericEntityEndpoint` routes by `storageMode` (table ↔ `GenericEntityStore`, hybrid ↔ `HybridEntityStore`); hybrid list is record_id ASC only (no sort/filter yet). `HybridEntityStore.deleteById` added.
- Tests: `EntityBlobStoreTest`, ObjectStorage delete, hybrid large-string reject.

### ES-3 [done] — form route + blob REST + cascade + hybrid filter

- `FormDomainActionRunner` routes `entity.record.upsert` by `storageMode` (table / hybrid).
- `EntityBlobEndpoint`: list / upload (octet-stream) / download / delete under `/api/v1/entities/{entityKey}/records/{id}/blobs`; AuthZ = entity declaration permission; parent record must exist.
- `GenericEntityEndpoint` delete cascades `EntityBlobStore.deleteForRecord`.
- Hybrid list: `record_id` ASC/DESC; PK filter in SQL; other declared fields equality via capped in-memory scan (ES-3 interim).
- Tests: form hybrid upsert, `EntityBlobEndpointTest`, hybrid filter/order, cascade deleteForRecord.

### ADR 0002 implementation track — **DONE** (MVP)

Core ADR decisions are now coded: hybrid default + shared table, optional physical tables, externalized blobs via ObjectStorage + metadata, CRUD/form routing by `storageMode`.

**Optional later:** per-path expression indexes; MySQL JSON indexes. Console multipart = SHARED-3 (done).

### HY-1 [done] — migrate classpath samples off `table` onto hybrid

- **Inventory:** only two classpath samples used `storageMode: table` —
  `entity-declare/.../entities/service-note.entity.yaml` (`service-note`) and
  `demo-ticket.entity.yaml` (`demo-ticket`). Dual-track table demos remain via **test fixtures**
  (inline `storageMode: table`) + Flyway physical tables `service_note` / `demo_ticket` (kept, unused by sample runtime).
- **Migrated:** both samples → `storageMode: hybrid` (entity_record + attrs).
- **Kept on table:** none of the classpath samples. Dual-track still demonstrated by GenericEntityStore tests (table-mode clones) and HybridEntityStore reject/table fixtures.
- **Routing:** `ServiceNoteEntityEndpoint` `/notes` shim routes by `storageMode`; form upsert + `/records` already did.
- **Not in HY-1:** hot-field promote, document mode, GIN, S3, multipart.

### HY-2 [done] — hot-field promotion (hybrid)

- **Mark a field:** YAML `promote: true` on a non-PK field when `storageMode: hybrid` (rejected on `table` / primary key).
- **Physical column:** `hp_<entityKey_underscored>_<field_snake>` on shared `entity_record` (nullable ADD for existing-row safety).
- **MigUX:** `DeclarationMigrationAutoEnqueueService` enqueues `ALTER TABLE entity_record ADD COLUMN …` when a field newly gains `promote: true` (relative to PROMOTED/classpath baseline). Hybrid no longer auto-`CREATE`s per-entity tables; table track still does (explicit `storageMode: table`).
- **Store:** `JdbcHybridEntityStore` writes promoted values to columns (stripped from `attrs`); reads merge column over attrs; filter on promoted field uses SQL equality.
- **Not in HY-2:** demote/DROP, NOT NULL, GIN, document, S3, multipart (then done in later slices). **HY-2b** = demote + attrs backfill.

### DOC-1 [done] — selectable full-JSON `document` storageMode

- **Enum:** `storageMode`: `hybrid` (default) | `document` | `table`.
- **Document:** shared `entity_record` + attrs JSON for almost all fields; **`promote: true` rejected** (hybrid-only).
- **Routing:** `sharedRecordStorage()` → `HybridEntityStore` (endpoint, form upsert, `/notes` shim, blob parent check). Table → `GenericEntityStore`.
- **MigUX:** document like hybrid (no per-entity CREATE); no `hp_*` enqueue; hybrid→document refused if promoted fields remain.
- **Not in DOC-1:** GIN, native jsonb, S3, multipart, console wizard UI (YAML/API enough this slice).

### SHARED-1 [done] — PostgreSQL jsonb + GIN on `entity_record.attrs`

- **Before:** V30 `attrs VARCHAR(16000)` JSON text (dual-mode H2/MySQL/PG).
- **Migration:** Java Flyway `V32__EntityRecordAttrsJsonbGin` — on **PostgreSQL** only:
  `ALTER … attrs TYPE jsonb USING attrs::jsonb` (idempotent if already jsonb);
  `CREATE INDEX IF NOT EXISTS entity_record_attrs_gin ON entity_record USING GIN (attrs jsonb_path_ops)`.
- **Why jsonb_path_ops:** smaller/faster for containment `@>` (field equality filters); not for `?` existence ops (acceptable for MVP).
- **MySQL / H2:** V32 no-op (VARCHAR text remains); filters stay capped in-memory scan.
- **Store:** `JdbcHybridEntityStore` + `AttrsJsonbQueries` — when vendor is PostgreSQL, non-promoted equality uses `attrs @> CAST(? AS jsonb)` (hybrid + document). Promoted `hp_*` columns unchanged.
- **Not in SHARED-1:** S3, multipart, expression indexes per path, MySQL multi-valued indexes.

### SHARED-2 [done] — S3-compatible ObjectStorage

- **Port:** existing `ObjectStorage` (`put` / `find` / `delete`) + `JdbcEntityBlobStore` / `entity_blob` unchanged.
- **Impl:** `LocalDirectoryObjectStorage` (default) | `S3ObjectStorage` (AWS SDK v2) selected by `platform.storage.type=local|s3`.
- **S3 config:** `platform.storage.s3.{endpoint,region,bucket,access-key,secret-key,path-style-access}` (MinIO-friendly path-style default true).
- **Keys:** `{tenantId}/{objectKey}` in bucket; Content-Type = media type.
- **Tests:** `S3ObjectStorageTest` with mocked `S3Client` (no live AWS). Ops note: [`docs/storage/object-storage.md`](storage/object-storage.md).
- **Not in SHARED-2:** console multipart (SHARED-3); auto-create bucket; Testcontainers MinIO in CI.


### SHARED-3 [done] — console multipart blob UX

- **API:** `POST /api/v1/entities/{entityKey}/records/{id}/blobs` now accepts
  `multipart/form-data` (part `file`, optional `fieldName`, default `attachment`)
  in addition to existing `application/octet-stream`. Size ≤ `JdbcEntityBlobStore.MAX_BYTES` (5 MiB);
  Spring `spring.servlet.multipart.max-file-size=5MB`.
- **Console:** `EntityBlobPanel` on declared detail pages when flow has `entityKey`
  (`web/.../pages/[flowKey]/[id]/page.tsx`); list / multipart upload / download / delete.
- **Proxy:** `/api/platform/[...segments]` forwards multipart as `ArrayBuffer` and preserves
  boundary `Content-Type` (was JSON-only `request.text()`).
- **Tests:** `EntityBlobEndpointTest.uploadMultipart` (+ empty-file 400).
- **Chain complete:** agreed HY→DOC→shared order finished
  (HY-1 samples→hybrid → HY-2 promote → DOC-1 document → SHARED-1 jsonb/GIN → SHARED-2 S3 → SHARED-3 multipart).
- **Not in SHARED-3:** resumable/chunked S3 multipart protocol; attachment field kind in form wizard;
  greenfield blob app.


### HY-2b [done] — demote DROP + attrs→column backfill

- **Demote:** removing `promote: true` enqueues `ALTER TABLE entity_record DROP COLUMN hp_*` (was refuse).
  Apply allowlists only this DROP form; merges column→attrs before DROP.
- **After ADD:** `HybridPromoteColumnMigrator.backfillFromAttrs` copies attrs→column (null only) and strips attrs key.
- **Not in HY-2b:** NOT NULL after backfill; chunked upload / wizard storageMode are later slices.

### WIZ-SM [done] — entity wizard storageMode picker

- Console entity wizard: choose `hybrid` | `document` | `table` (default `hybrid`); parse/emit YAML.
- 控制台实体向导可选存储轨；默认 hybrid。

### CHUNK-1 [done] — chunked/resumable blob upload MVP

- **API:** `POST …/blobs/uploads` (init) → `PUT …/uploads/{id}/chunks/{i}` → `POST …/complete`; `DELETE` abort.
- **Store:** `LocalDirChunkedBlobUploadStore` temp chunks; assemble → `EntityBlobStore.put` (local or S3 ObjectStorage).
- **Limits:** total ≤ 5 MiB; default chunk 256 KiB; session TTL 1h + purge on init.
- **Console:** `EntityBlobPanel` uses chunked path when file > 256 KiB; proxy forwards octet-stream binary.
- **Not in CHUNK-1:** true S3 multipart protocol; cross-replica shared chunk store; raising max above 5 MiB.

### Squash on github/main — 已压到 github/main（2026-10-15 CST）

- Remote `github/main` = `d753992` (FF from ES-3 `4c431ce`).
- Squashed HY-1, HY-2, DOC-1, SHARED-1, SHARED-2, SHARED-3 into one commit:
  `feat(entity-storage): HY→DOC→SHARED chain (hybrid deepen through console multipart)`.
- Agreed HY→DOC→shared chain is on main. HY-2b + WIZ-SM + CHUNK-1 done locally (ahead of github/main d753992).
- 约定链已上 main。HY-2b + 向导 + 分片上传已本地完成（尚未 squash-push）。

## Notes / 说明

- Non-scoped hybrid rows stamp `tenant_id = ''` (platform sentinel).
- `attrs`: V30 VARCHAR JSON text for dual-mode; **PostgreSQL** upgraded to jsonb + GIN in V32 (SHARED-1). MySQL/H2 remain VARCHAR text.

### Search read path MVP [done] 2026-10-16 CST

- Read-only `GET /api/v1/search/records` over `entity_record` (substring + optional PG `@>`).
- Console `/search`. Writes unchanged. Full Elasticsearch remains Later.
- See [`docs/search/search-read-path.md`](../search/search-read-path.md).
