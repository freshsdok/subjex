# Migration mapping (O1 plan) / 迁移映射（O1 规划）

**Mapping + O3 status.** Formal DDL is O2 (`V22`). Remap + dual-read are O3 (`V23`, `OrganizationOntologyBackfill`).  
**映射与 O3 状态。** 正式 DDL 为 O2（V22）。旁路映射与双读为 O3（V23、Backfill）。详见 [`o3-dual-read.md`](o3-dual-read.md)。

## Hard rules / 硬规则

1. **Forbid auto cross-tenant Organization merge.** Same `unit_name` in tenant A and tenant B → **two** Organization rows (distinct ids).  
   **禁止跨租户自动合并 Organization。** 同名不同租户 → 两个 Organization。
2. **O7 done:** `org_unit` / `org_membership` dropped (V24). Remap kept.  
   **O7 完成：** 旧表已删；映射保留。
3. Post-O7 rollback of *data* is restore-from-backup only (tables gone).  
   O7 后数据回滚只能从备份恢复。

## Table mapping / 表映射

| Legacy (V13+) | New concept / table (O2) | Mapping rule |
| --- | --- | --- |
| `org_unit` | `organization` + `tenant_organization` | For each `(tenant_id, org_unit_id)`: create (or reuse **same-tenant** deterministic) `organization_id`; copy name/state; insert `tenant_organization(tenant_id, organization_id)`. Prefer stable id strategy: e.g. keep `org_unit_id` as `organization_id` **only when globally unique**; if collision across tenants, mint `organization_id = tenant_id + ":" + org_unit_id` (or hash) and record a side map. **Never** merge rows that only share `unit_name`. |
| `org_unit.parent_org_unit_id` | `organization_relation` | For each non-null parent in the **same tenant**: edge `CONTAINS` from parent’s Organization → child’s Organization. Skip / fail closed if parent missing. No cross-tenant parent edges. |
| `org_membership` | `membership` | For each `(tenant_id, subject_id, org_unit_id)`: resolve Organization via the unit map above; insert `membership(subject_id, organization_id, state)`. **Drop `tenant_id` from the membership row.** Tenant participation remains only on `tenant_organization`. |
| *(implicit)* tenant hosts units | `tenant_organization` | Every distinct `tenant_id` that owned an `org_unit` gets ACTIVE links to those Organizations. |

## Id collision policy / ID 冲突策略

| Case | Action |
| --- | --- |
| `org_unit_id` unique across all tenants | May reuse as `organization_id` |
| Same `org_unit_id` string in two tenants | **Do not merge**; mint distinct Organization ids; keep remap table for dual-read |
| Same `unit_name`, different tenants | **Do not merge** |

## Dual-read (O3) / 双读（O3）

Exact strategy: [`o3-dual-read.md`](o3-dual-read.md).

| Phase | Read | Write |
| --- | --- | --- |
| Pre-backfill | Legacy only | Legacy + optional write-through on API writes |
| O3 fully backfilled tenant | Prefer ontology (project via map); MIG-* tests | Legacy + write-through |
| Post-O7 | New only (+ map projection for deprecated API) | New only; legacy tables dropped |

## Out of scope here / 此处不做

- Automated merge UI
- SCIM / cross-org identity sync
- ~~Deleting `org_unit` / `org_membership`~~ **done in O7** after MIG/AUTH/API/console/declaration/E2E PASS
