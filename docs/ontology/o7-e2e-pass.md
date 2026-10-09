# O7 E2E-PASS — Ontology cutover gate

**Status:** PASS (machine gate `E2E-01` in `OrganizationOntologyCutoverE2ETest`)  
**状态：** PASS（机器门禁 `E2E-01`）

**Date:** 2026-10-09 (Asia/Shanghai)

## Covered path / 覆盖路径

| Step | Assert |
| --- | --- |
| Backfill / seed | Legacy `org_unit` → `OrganizationOntologyBackfill` (pre-DROP) **or** ontology+map via `JdbcOrgDirectory` (post-DROP) |
| Org surface | Directory lists units/memberships; `JdbcOrganizationStore` links + memberships in organization id space |
| Auth scope | `resolveSelfAndDescendants` / `resolveOrganizationSelfAndDescendants` from Membership + CONTAINS; missing subject → NONE |
| Declaration | `subjectRef` / `organizationRef` render on entity + form |
| Console smoke | `web/.../org/org-console.tsx` calls `/api/platform/organizations` (not legacy `/org/units`) |

## Gates required before DROP / DROP 前门禁

All of: migration (MIG-*), authorization (AUTH-*), API (OrganizationApiSecurityTest), console (path smoke above), declaration (DECL-* + E2E), **E2E-01**.

## After DROP / DROP 后

- Tables `org_unit` / `org_membership` gone.
- Remap `org_unit_organization_map` **kept**.
- Deprecated `/api/v1/org/**` remains as thin adapters over ontology + map.


## DROP record / 删除记录

| Item | Result |
| --- | --- |
| Flyway | `db.migration.V24__DropLegacyOrgUnit` — backfillAll then `DROP TABLE org_membership` / `org_unit` |
| Remap | **Kept** `org_unit_organization_map` |
| Legacy API | `/api/v1/org/**` retained; `JdbcOrgDirectory` writes ontology + map only |
| Old Java store | No separate OrgUnit JDBC store; directory is the adapter |

Gates after DROP: MIG-* (rewritten), AUTH-*, API, DECL-*, **E2E-01** green on H2 PG+MySQL modes.
