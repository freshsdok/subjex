# ADR 0002 — Entity row storage: hybrid B default (core columns + jsonb)

- Status: **Accepted** (ES-1 + ES-2: `entity_record` / `HybridEntityStore`; `entity_blob` + `ObjectStorage`; endpoint routes by `storageMode`)
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

### Optional track — physical per-entity tables / 可选轨：实体物理表

Keep today’s **one table per entity** path as an **opt-in** for **strong-constraint** entities (strict typed columns, unique constraints, heavy relational reporting, regulated schemas):

- Still uses migration queue + promote bind (no casual console DDL)
- Explicit declaration flag / product gate (to be named at implementation time)
- Samples such as `service_note` may remain on this track until deliberately migrated

强约束实体可继续走一实体一表（受控迁移）；须显式选择，不作零代码默认。

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

- Design shared table DDL + `GenericEntityStore` (or sibling) hybrid adapter
- Rules for promoting a hot `attrs` path to a core/typed column
- Migration guide for moving an optional-track table → hybrid (and reverse, rare)
- Index / vacuum / jsonb size limits ops notes

## Links / 链接

- Low-code roadmap (Z1 / strict schema): [`docs/lowcode-roadmap.md`](../lowcode-roadmap.md)
- Declaration migration process: [`docs/declaration-migration.md`](../declaration-migration.md)
- Ontology zero-code refs: [`docs/ontology/o6-zero-code.md`](../ontology/o6-zero-code.md)
- Prior ADR: [`0001-o1-organization-ontology.md`](0001-o1-organization-ontology.md)
