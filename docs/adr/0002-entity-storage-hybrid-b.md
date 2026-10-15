# ADR 0002 — Entity row storage: hybrid B default (core columns + jsonb)

- Status: **Accepted** — … + SHARED-1 (jsonb/GIN) + **SHARED-2** (S3-compatible ObjectStorage)
- Date: 2026-10-10 (Asia/Shanghai)
- Scope: Zero-code / generic entity **physical row layout**; dual-track with optional per-entity tables

## Context / 背景

Today’s Z1 path maps each entity declaration to a **physical table** named by `tableName`:

1. YAML → `EntityMigrationGenerator` → `CREATE TABLE` / `ALTER … ADD COLUMN`
2. Migration queue (review → apply) then promote bind
3. `GenericEntityStore` runs typed SQL against that table

Forms already use a **shared** submission store (`values_json`). At tenant × entity scale, **one table per entity** explodes DDL, catalogs, backups, and ops — catastrophic for zero-code growth.

今日 Z1：一实体一物理表（CREATE/加列队列 + GenericEntityStore）。表单已是共享 JSON 提交表。租户×实体膨胀时表数/DDL 不可接受。

## Decision / 决策

### Default track — Hybrid B / 默认轨：混合 B

New zero-code entities **default** to a **shared hybrid row store**:

| Piece | Role |
| --- | --- |
| **Core columns** | Stable platform columns: e.g. `tenant_id`, `entity_key`, `record_id` (PK), lifecycle/state, timestamps, optional hot typed dimensions |
| **`attrs` jsonb** | Declaration-defined fields (text/int/bool/enum/date/refs, …) as JSON document |
| **Indexes** | BTREE on core keys; selective GIN / expression indexes on hot `attrs` paths as needed |

- Field add/rename in the **default track** is primarily a **declaration / metadata** change (plus optional index jobs) — **not** a new `CREATE TABLE` per entity.
- Tenant isolation stays **row-level** (`tenant_id`), consistent with today’s `tenantScoped` semantics.
- Declaration versioning, promote, and AuthZ ports stay as today; only the **storage adapter** behind generic CRUD changes when implemented.

零代码新实体**默认**落共享混合表：固定核心列 + `attrs` jsonb。加字段以声明/元数据为主，不再默认一实体一表。租户仍行级隔离。

### Attachments & rich text — externalized / 附件与富文本外置

Large or binary payloads **must not** live in `attrs` jsonb:

- **Object store** (S3-compatible / local blob backend) for bytes
- **Metadata / blob table** (id, tenant, entity_key, record_id, content-type, size, storage key, checksum, …)

Rich-text bodies follow the same rule when large: store pointer + optional excerpt in `attrs`, body in blob/object store.

附件/大富文本：对象存储 + 元数据/blob 表；禁止塞进 jsonb 主行。

Object bytes: `ObjectStorage` SPI — default local directory; optional **`platform.storage.type=s3`** → `S3ObjectStorage` (SHARED-2, MinIO/AWS). Console multipart is a later slice.

对象字节：`ObjectStorage` SPI——默认本地目录；可选 **`platform.storage.type=s3`** → `S3ObjectStorage`（SHARED-2）。控制台 multipart 后续切片。


### PostgreSQL attrs indexing (SHARED-1) / PostgreSQL attrs 索引

On PostgreSQL, `entity_record.attrs` is native **jsonb** with GIN **`jsonb_path_ops`** (Flyway V32) so hybrid/document equality filters can use `attrs @> '{"k":v}'`. MySQL keeps VARCHAR JSON text without GIN this slice.

PostgreSQL 上 `attrs` 为原生 jsonb + GIN jsonb_path_ops（V32），hybrid/document 等值过滤可走 `@>`；MySQL 本片仍 VARCHAR 文本、无 GIN。


### Optional track — physical per-entity tables / 可选轨：实体物理表

Keep today’s **one table per entity** path as an **opt-in** for **strong-constraint** entities (strict typed columns, unique constraints, heavy relational reporting, regulated schemas):

- Still uses migration queue + promote bind (no casual console DDL)
- Explicit declaration flag / product gate (to be named at implementation time)
- Samples `service-note` / `demo-ticket` migrated to hybrid in **HY-1**; keep this track for strong-constraint opt-in only

强约束实体可继续走一实体一表（受控迁移）；须显式选择，不作零代码默认。

### Selectable document track / 可选全 JSON 档（DOC-1）

Zero-code may also choose **`storageMode: document`**: same shared `entity_record` as hybrid, but **all declaration fields stay in `attrs` JSON** — no `promote` / `hp_*` columns. Default remains **hybrid**. Physical `table` stays the strong-constraint opt-in.

零代码可选 **`storageMode: document`**：与 hybrid 同用 `entity_record`，但字段几乎全在 `attrs`；不可升格。默认仍为 hybrid；`table` 仍为强约束可选轨。


## Non-goals this ADR / 本 ADR 明确不做

- Original ADR slice was docs-only; **ES-1** adds Flyway `entity_record` + `HybridEntityStore` (no forced cutover)
- No pure EAV-as-primary, no schema-per-tenant as default, no forced move of all existing tables in one cutover
- Does not weaken dual-track promote or “no casual online DDL”

本切片只落文档决策；不改存储代码；不把 EAV/每租户 schema 定为默认；不削弱双轨晋升与禁随意 DDL。

## Consequences / 后果

**Positive**

- Zero-code scale without table explosion
- Aligns entity default with form-style document flexibility while keeping queryable core columns
- Clear home for attachments without jsonb bloat

**Cost / follow-ups (later slices)**

- ~~Design shared table DDL + hybrid adapter~~ → ES-1…ES-3 done
- ~~Migration of classpath samples off table~~ → HY-1 done
- ~~Rules for promoting a hot `attrs` path to a typed column~~ → HY-2 (`promote: true` → `hp_*` on `entity_record`)
- Migration guide for optional-track table ↔ hybrid (optional later)
- ~~Native jsonb + GIN on attrs (PostgreSQL)~~ → SHARED-1 done
- Index / vacuum / jsonb size limits ops notes (optional later)

## Links / 链接

- Low-code roadmap (Z1 / strict schema): [`docs/lowcode-roadmap.md`](../lowcode-roadmap.md)
- Declaration migration process: [`docs/declaration-migration.md`](../declaration-migration.md)
- Ontology zero-code refs: [`docs/ontology/o6-zero-code.md`](../ontology/o6-zero-code.md)
- Prior ADR: [`0001-o1-organization-ontology.md`](0001-o1-organization-ontology.md)
