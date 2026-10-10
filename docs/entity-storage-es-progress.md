# Entity storage hybrid B — progress / 实体存储混合 B 进度

Tracks ADR [`adr/0002-entity-storage-hybrid-b.md`](adr/0002-entity-storage-hybrid-b.md).

按 ADR 0002 跟踪实现切片。

## Slice log / 切片记录

### ES-1 [done] — shared `entity_record` + HybridEntityStore sketch

- Flyway `V30__entity_record.sql`: `tenant_id`, `entity_key`, `record_id`, `record_state`, `attrs` (JSON text VARCHAR for dual-mode), `created_at`, `updated_at`.
- `EntityStorageMode` (`hybrid`|`table`); YAML `storageMode`; **omit → hybrid** (zero-code default). Samples `service-note` / `demo-ticket`: explicit `storageMode: table`.
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

**Optional later (not required for ADR DONE):** migrate sample entities off `table`; promote hot attrs → core columns; native jsonb; S3 ObjectStorage; GIN indexes; form multipart blob UX; tighten hybrid non-PK filter to SQL/json path.

## Notes / 说明

- Non-scoped hybrid rows stamp `tenant_id = ''` (platform sentinel).
- `attrs` column is VARCHAR JSON text for H2 MySQL/PostgreSQL dual-mode (same idea as `form_submission.values_json`); native jsonb/JSON type optimization later.
