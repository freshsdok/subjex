# Search read path MVP / 搜索读路径 MVP

Date: 2026-10-16. Local tip after Org Later L1.

## Inventory (before this slice)

| Surface | What it does | Gap |
| --- | --- | --- |
| `GET /api/v1/entities/{key}/records?filterField&filterValue` | Declared-field equality list (table SQL / hybrid promote / PG `attrs @>` GIN) | Per-entity only; no free-text; no cross-entity |
| `AttrsJsonbQueries` + V32 GIN `jsonb_path_ops` | PostgreSQL containment for hybrid/document equality | Equality only — not substring / full-text |
| H2/MySQL hybrid filter | Capped in-memory scan when jsonb SQL off | Same as list API |
| Console declared list filter bar | URL `searchParams` on generic `/records` pages | Not a global search |
| Elasticsearch | ADR 0002 Later | **Not in this MVP** |

## Delivered

- **Read-only** `GET /api/v1/search/records`
  - AuthZ: `page.read` + tenant grant (`tenantId` query or `X-Tenant-Id`)
  - `q`: substring on `record_id` + attrs text (portable `LOWER(...) LIKE`)
  - Optional `entityKey` (omit = all keys for tenant)
  - Optional `field`+`value`: PG uses `attrs @> CAST(? AS jsonb)` (GIN); H2/MySQL best-effort attrs text fragment
  - Limit 1..100 (default 50)
- Store: `JdbcEntityRecordSearchStore` — **no writes**
- Console: `/search` thin form + results table
- Writes remain on `GenericEntityEndpoint` / hybrid store only

## Feature flag

- `search.records` (default on): when false for the tenant, `GET /search/records` → 404 via `FeatureFlagService.isEnabled`.

## Non-goals / Later

- Elasticsearch / OpenSearch index, analyzers, ranking
- Table-mode (`storageMode=table`) physical tables outside `entity_record`
- AuthZ per declaration permission for each hit (MVP uses page.read + tenant only)
- Feature flags (next sequential slice)

## Tests

- `JdbcEntityRecordSearchStoreTest` (tenant isolation, substring, field fragment)
- `EntitySearchEndpointSecurityTest` (401 / happy path / header tenant)
