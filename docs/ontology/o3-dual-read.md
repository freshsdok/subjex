# O3 Dual-read & backfill strategy / O3 双读与回填策略

**Status:** Implemented with O3 (`OrganizationOntologyBackfill` + `JdbcOrgDirectory`).  
**状态：** 随 O3 落地。

## Hard rules (unchanged) / 硬规则（不变）

1. **No automatic cross-tenant Organization merge** (MIG-03).
2. Legacy `org_unit` / `org_membership` **stay**; O7 only after all PASS gates.
3. Rollback before O7: stop preferring new reads (or skip backfill); keep serving legacy tables. **Do not DROP** legacy tables.

## Id minting / ID 生成

| Case | `organization_id` |
| --- | --- |
| `org_unit_id` appears in **exactly one** tenant | Reuse `org_unit_id` |
| Same `org_unit_id` in **≥2** tenants | `SHA-256` hex of `tenantId + NUL + orgUnitId` (64 chars) |
| Same `unit_name`, different tenants | **Never** merge — two Organization rows |

Side map: `org_unit_organization_map(tenant_id, org_unit_id, organization_id)` (Flyway V23).

## Backfill job / 回填任务

Class: `com.subjex.platform.app.organization.OrganizationOntologyBackfill`

| Method | Effect |
| --- | --- |
| `backfillTenant(tenantId)` | Upsert `organization` + `tenant_organization` + map; CONTAINS from parent; `membership` without tenant |
| `backfillAll()` | Distinct tenants that own `org_unit` rows |
| Idempotent | Re-run safe; preserve existing map `organization_id` when present |

Ops: call the Spring bean (or construct with `JdbcTemplate`) after deploy. Not an automatic Flyway data migration (logic + collision policy live in Java).

## Dual-read (exact) / 双读（精确策略）

**Gate:** tenant is *fully backfilled* iff

```text
COUNT(org_unit WHERE tenant) > 0
AND COUNT(org_unit WHERE tenant) = COUNT(org_unit_organization_map WHERE tenant)
```

| Phase | Read path | Write path |
| --- | --- | --- |
| Not fully backfilled | **Legacy only** (`org_unit` / `org_membership`) | Legacy always; write-through creates/updates map+ontology for touched rows |
| Fully backfilled | **Ontology prefer**: project via map → `OrgUnit` / `OrgMembership` shapes for existing `/api/v1/org/**` + `OrgScope` | Legacy always + write-through (unit/membership sync; membership delete → `ENDED` on new) |
| Rollback | Treat as not backfilled / ignore map; legacy remains source of truth | Legacy only |

`ENDED` memberships are hidden on ontology read projection (legacy delete already removed the old row).

Auth (O4): `resolveSelf` / `resolveSelfAndDescendants` — when fully backfilled, ACTIVE Membership (joined to ACTIVE TenantOrganization for the tenant) + `JdbcOrganizationStore.containsSelfAndDescendants` (CONTAINS only), then map organization ids back to `org_unit_id`. Empty → `OrgScope.none()`. Legacy path when not fully backfilled.

## Rollback note / 回滚说明

- Keep `org_unit` / `org_membership` / `org_unit_organization_map` / ontology tables.
- Disable dual-read preference (or delete map rows for a tenant) to force legacy reads.
- **Do not** delete legacy tables in O3.

## Gates / 门禁

`MIG-01` / `MIG-02` / `MIG-03` in `OrganizationOntologyBackfillGatesTest`.
