# O8-4 pass — exit map and backfill from runtime

**Date:** 2026-10-09 (Asia/Shanghai)  
**Commit:** `f9ef74a` — `refactor(ontology): O8-4 exit map and backfill from runtime`  
**Base:** O8-5 `5626b46` / `0768c59`

## Goal

`org_unit_organization_map` only for migration / legacy compatibility. Policy, formal Organization API, and zero-code runtime do **not** depend on it. `OrganizationOntologyBackfill` exits the normal business write path.

## Changes

| Area | Change |
| --- | --- |
| `OrganizationApiEndpoint` | Removed `OrganizationOntologyBackfill` dependency; upsert/membership writes ontology tables only (no `sync*ToLegacy`) |
| `OrganizationOntologyBackfill` | Documented migration/legacy-only; removed `syncOrganizationToLegacy` / `syncMembershipToLegacy` / `endMembershipToLegacy`; kept `backfillAll`/`backfillTenant` (V24) + write-through for `JdbcOrgDirectory` |
| `JdbcOrgDirectory` | Ontology-first reads (organization / tenant_organization / membership / CONTAINS); **no map joins**; stripped dead `org_unit`/`org_membership` SQL; wire id = `organization_id`; map only via write-through upserts |
| `PlatformWiring` | Backfill bean remains for V24 / legacy adapter — **not** injected into Organization API |
| Tests | Dropped API reverse-sync verifies; MIG-03 expects ontology ids on listUnits |

## How upgrade still works

1. **Flyway V24** (`V24__DropLegacyOrgUnit`): calls `OrganizationOntologyBackfill.backfillAll()` then DROP legacy tables (unchanged; do not edit).
2. **Explicit migrate:** construct `OrganizationOntologyBackfill` and call `backfillAll()` / `backfillTenant(tenantId)` (CLI / admin / one-shot). No-op when `org_unit` already absent.
3. **Map table kept** (V23): legacy adapter write-through may still upsert alias rows; formal runtime never queries it.

## Gate deltas

| Metric | After O8-5 | After O8-4 |
| --- | ---: | ---: |
| Zero-code legacy refs | 0 | **0** |
| Formal/policy/new API map SQL | (API reverse-sync) | **0** |
| Legacy SQL consumers (formal) | 2 | **0** (map SQL only in Backfill + legacy write-through) |
| Map table | kept | **kept** (no DROP this slice) |

### Residual map use (intentional)

- `OrganizationOntologyBackfill` — migration + `upsertMap` / find for legacy write-through
- `JdbcOrgDirectory` write path — `syncUnitWriteThrough` still writes map aliases; reads do not join map
- Flyway V23/V24 — historical; not edited

## Next

**O8-6** — ArchUnit / machine gates FULL PASS + E2E extensions (Relationship ≠ Authorization; scope without map already true for formal path).
