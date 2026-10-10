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

### ES-3 [next] — hybrid list filters + form upsert route + blob HTTP (optional)

- Hybrid sort/filter (or documented subset); `FormDomainActionRunner` route by `storageMode`.
- Thin REST for blob upload/download bound to entity record + AuthZ; cascade blobs on record delete.
- Optional: promote hot attrs path → core column notes.

## Notes / 说明

- Non-scoped hybrid rows stamp `tenant_id = ''` (platform sentinel).
- `attrs` column is VARCHAR JSON text for H2 MySQL/PostgreSQL dual-mode (same idea as `form_submission.values_json`); native jsonb/JSON type optimization later.
