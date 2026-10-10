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

### ES-2 [next] — blob / attachment metadata

- Metadata/blob table + object-store port; keep large payloads out of `attrs`.
- Wire GenericEntityEndpoint / form upsert to route by `storageMode` (optional small follow-up if not bundled).

## Notes / 说明

- Non-scoped hybrid rows stamp `tenant_id = ''` (platform sentinel).
- `attrs` column is VARCHAR JSON text for H2 MySQL/PostgreSQL dual-mode (same idea as `form_submission.values_json`); native jsonb/JSON type optimization later.
