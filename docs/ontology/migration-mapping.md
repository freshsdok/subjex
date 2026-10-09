# Migration mapping (O1 plan) / 迁移映射（O1 规划）

**This file is a mapping table only.** Dual-read implementation is **O3**. Formal DDL is **O2** after O1 review PASS.  
**本文件仅为映射表。** 双读实现属 **O3**。正式 DDL 在 O1 内审通过后的 **O2**。

## Hard rules / 硬规则

1. **Forbid auto cross-tenant Organization merge.** Same `unit_name` in tenant A and tenant B → **two** Organization rows (distinct ids).  
   **禁止跨租户自动合并 Organization。** 同名不同租户 → 两个 Organization。
2. Legacy tables stay writable for bugfixes until O7; no structural feature adds on `org_unit` / `org_membership` after O1 freeze.  
   旧表在 O7 前可修 bug；O1 冻结后不对旧表加结构性能力。
3. Rollback before O7: disable new stores; continue serving `org_unit` / `org_membership`.  
   O7 前的回滚：关掉新 Store，继续服务旧表。

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

## Dual-read (later O3) / 双读（后置 O3）

| Phase | Read | Write |
| --- | --- | --- |
| Pre-O3 | Legacy only | Legacy only |
| O3 dual-read | Prefer new; fallback legacy; compare in tests (MIG-*) | Write both or write new + async backfill (decide in O3 ADR) |
| Post-O7 | New only | New only; drop legacy tables |

## Out of scope here / 此处不做

- Flyway scripts
- Automated merge UI
- SCIM / cross-org identity sync
- Deleting `org_unit` / `org_membership` (O7 only after MIG/AUTH/API/console/declaration/E2E PASS)
