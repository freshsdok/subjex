# O8-5 pass — frontend / zero-code cleanup

**Date:** 2026-10-09 (Asia/Shanghai)  
**Commit:** `5626b46` — `refactor(ontology): O8-5 subjectRef organizationRef only`  
**Base:** O8-3 `bd6b879`

## Goal

Console and zero-code use **Organization / 组织** only. Formal ref kinds: `subjectRef`, `organizationRef`. Formal pickers: `SubjectPicker`, `OrganizationPicker`. Delete `UserPicker` / `OrgPicker` aliases after no consumers.

## Changes

| Area | Change |
| --- | --- |
| `EntityFieldKind` / `FieldKind` | Reject `userRef` / `orgRef` (explicit error → use canonical) |
| `PageRenderer` | Drop `UserPicker` / `OrgPicker` from catalog block ids |
| Web libs | Remove alias normalization in `form-field-input`, `form-wizard`, `entity-wizard`, `page-blocks-catalog` |
| Pickers | Delete `user-picker.tsx` / `org-picker.tsx`; remove deprecated exports from subject/organization pickers + index |
| Phrases / org-console helpers | User-visible copy: 组织单元 / “org unit” → 组织 / organization (phrase **keys** still `orgUnit*` — cosmetic) |
| Demo YAML | Field `orgUnit` → `organization` (entity + form) |
| Draft SQL | `migration-draft/V1__demo_ticket.sql` column `organization` |
| Flyway | **New** `V25__demo_ticket_organization_column.sql` renames `demo_ticket.org_unit` → `organization` (does **not** edit executed V12) |
| Tests | Catalog / FormRenderer / DeclarationRefGates reject aliases; declaration + GenericEntityStore use `organization` |

## Gate deltas (inventory metrics)

| Gate | Before (O8-3) | After O8-5 |
| --- | --- | --- |
| Zero-code legacy refs | **13** | **0** |
| Production ontology count | (unchanged) | — |
| Legacy domain / SQL / Policy | (unchanged; O8-4/O8-6) | — |

### Deleted aliases

- Wire kinds: `userRef`, `orgRef` (reject-only residual in parse for clear errors)
- Page blocks: `UserPicker`, `OrgPicker` (files + exports + `PageRenderer` accept set)
- Catalog mapping: `LEGACY_PAGE_BLOCK_ALIASES` removed

### Intentional residuals

- Phrase / state **key names** still `orgUnit*` / `unitId` locals in `org-console.tsx` (user-visible strings cleaned; rename keys later if desired)
- Executed **V12** historically created `org_unit`; **V25** renames for runtime alignment
- Map/backfill write path (O8-4); ArchUnit full bans (O8-6); `org.legacy` package untouched

## Next

**O8-4** — map / backfill write-path cleanup (per inventory order after O8-5).
