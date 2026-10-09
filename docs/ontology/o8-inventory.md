# O8 Inventory — leftover OrgUnit / OrgMembership consumers

**Status:** Inventory historical; **O8 FULL PASS** — see [`o8-full-pass.md`](o8-full-pass.md)  
**Date:** 2026-10-09 (Asia/Shanghai / CST)  
**HEAD at inventory:** `226e808`  
**Tree:** clean (`git status -sb` → `## main`)

Formal ontology (must remain): Subject, Organization, Tenant, Membership
(Subject↔Organization, **no** `tenant_id`), OrganizationRelation, TenantOrganization.  
Do **not** reintroduce OrganizationUnit / TenantOrgUnit / TenantMembership.

## Gate baseline snapshot (pre–O8-1)

| Metric | Count | Notes |
| --- | ---: | --- |
| Production ontology count | **2** | New `organization.*` types live **and** legacy `OrgUnit` / `OrgMembership` still production domain types under `app.org` |
| Legacy domain consumers | **8** | Main sources defining or wiring `OrgUnit` / `OrgMembership` / `JdbcOrgDirectory` (see below) |
| Legacy SQL consumers | **2** | Runtime Java still containing SQL for `org_unit` / `org_membership` and/or `org_unit_organization_map` |
| Policy legacy dependency | **7** | Policy/access stack still on `OrgScope` + `orgUnitId` / `resourceOrgUnitId` naming (no `OrganizationScope` type yet) |
| Zero-code legacy refs | **13** | Still accept `userRef` / `orgRef` / `UserPicker` / `OrgPicker` aliases (or demo field name `orgUnit`) |

Target when O8 DONE: all five metrics **0** except Production ontology count **= 1**.

## O7 DROP evidence (tables)

**Yes — Flyway V24 drops `org_unit` / `org_membership`; remap kept.**

| Evidence | Detail |
| --- | --- |
| Migration | `platform-app/src/main/java/db/migration/V24__DropLegacyOrgUnit.java` — `DROP TABLE IF EXISTS org_membership` / `org_unit`; keeps `org_unit_organization_map` |
| Docs | `docs/ontology/o7-e2e-pass.md`, `docs/ontology/README.md`, `migration-mapping.md` |
| Runtime guard | `JdbcOrgDirectory.preferOntology` → `true` when `!backfill.legacyOrgTablesPresent()` |

**Caveat:** `JdbcOrgDirectory` / `OrganizationOntologyBackfill` still **contain** SQL text against `org_unit` / `org_membership` for pre-DROP / dual-read branches. After V24 those branches are skipped via `legacyOrgTablesPresent()`, but the strings remain (O8-3/O8-4 cleanup).

---

## 1. Production domain consumers (legacy)

| Path | Symbol / role | Note |
| --- | --- | --- |
| `platform-app/.../org/OrgUnit.java` | `OrgUnit` record | Legacy domain type (`orgUnitId`, `parentOrgUnitId`, …) |
| `platform-app/.../org/OrgMembership.java` | `OrgMembership` record | Legacy membership shape (`orgUnitId` + **tenantId**) |
| `platform-app/.../org/JdbcOrgDirectory.java` | `JdbcOrgDirectory` | Thin adapter: ontology + **map**; still has dead legacy-table SQL; **scope resolution** for both APIs |
| `platform-app/.../org/OrgApiEndpoint.java` | `/api/v1/org/**` | Deprecated; projects `OrgUnit` / `OrgMembership`; uses `OrgScope` + `directory.resolveSelfAndDescendants` |
| `platform-app/.../org/LegacyOrgApiDeprecationFilter.java` | Deprecation headers | Marks legacy `/api/v1/org` responses |
| `platform-app/.../organization/OrganizationApiEndpoint.java` | `/api/v1/organizations/**` | **New API** but depends on `JdbcOrgDirectory` + `OrganizationOntologyBackfill` (map reverse-sync) |
| `platform-app/.../organization/OrganizationOntologyBackfill.java` | Backfill + map + write-through | Still on **runtime** path for Organization API writes (`syncOrganizationToLegacy`, map upserts) |
| `platform-app/.../PlatformWiring.java` | Beans | Wires `JdbcOrgDirectory`, `OrganizationOntologyBackfill` |

**Legacy domain consumers = 8** (rows above).

New ontology (keep): `Organization`, `Membership`, `OrganizationRelation`, `TenantOrganization`, `JdbcOrganizationStore`, etc. under `.../organization/`.

---

## 2. Policy / AccessChecker legacy deps

No `OrganizationScope` type exists yet (`rg OrganizationScope` → empty).

| Path | Legacy surface |
| --- | --- |
| `security/OrgScope.java` | Record `OrgScope(mode, rootUnitIds, unitIds)`; JSON/API still emit these names |
| `security/AccessChecker.java` | Params `resourceOrgUnitId`; `applyOrgScope` / `requireOrgScope` |
| `security/PolicyContext.java` | Field `OrgScope orgScope` |
| `security/AccessDecision.java` | Carries `OrgScope`; `withOrgScope` |
| `security/PolicyResource.java` | `ATTR_ORG_UNIT_ID = "orgUnitId"`; `orgUnitId()` |
| `security/SqlRbacPolicyEngine.java` | Passes `resource.orgUnitId()` into AccessChecker |
| `form/FormProblemDocument.java` | Serializes `OrgScope` (mode + roots + unitIds) |

**Policy legacy dependency = 7** (files above).

**Resolution call sites (not in policy package, but block O8-1):**

- `OrgApiEndpoint.resolveScope` → `JdbcOrgDirectory.resolveSelfAndDescendants`
- `OrganizationApiEndpoint.resolveScope` → `JdbcOrgDirectory.resolveOrganizationSelfAndDescendants` (unit scope → **map** → organization ids)

Policy itself does not import `JdbcOrgDirectory`, but **cannot** get Membership+CONTAINS scope without it today.

---

## 3. Legacy SQL / map consumers

| Path | Tables touched in source |
| --- | --- |
| `JdbcOrgDirectory.java` | `org_unit`, `org_membership` (guarded / post-DROP skipped), **`org_unit_organization_map`** (live joins for list/project/scope map) |
| `OrganizationOntologyBackfill.java` | `org_unit` / `org_membership` when present; **`org_unit_organization_map`** upsert/find (live for API reverse-sync) |

Historical Flyway (allowed exceptions, do **not** edit executed migrations):

- `V13__org_unit.sql` — creates legacy tables  
- `V23__org_unit_organization_map.sql` — creates map  
- `V24__DropLegacyOrgUnit.java` — DROP legacy tables  

**Legacy SQL consumers = 2** (runtime Java). Map still has formal runtime consumers → O8-4.

---

## 4. Zero-code / declaration refs

Canonical kinds exist (`subjectRef` / `organizationRef`); **aliases still accepted**:

| Path | Legacy |
| --- | --- |
| `entity-declare/.../EntityFieldKind.java` | Parses `userRef`→subjectRef, `orgRef`→organizationRef |
| `form-render/.../FieldKind.java` | Same |
| `entity-declare/.../demo-ticket.entity.yaml` | Field **name** `orgUnit` (kind already `organizationRef`) |
| `form-render/.../demo-ticket.form.yaml` | Same |
| `web/src/lib/form-field-input.ts` | Accepts `userRef`/`orgRef`; special-cases name `orgUnit` |
| `web/src/lib/form-wizard.ts` | Normalizes aliases on write |
| `web/src/lib/entity-wizard.ts` | Same |
| `web/src/lib/page-blocks-catalog.ts` | Maps `UserPicker`/`OrgPicker` → Subject/Organization |
| `web/src/components/page-blocks/subject-picker.tsx` | Exports deprecated `UserPicker` |
| `web/src/components/page-blocks/organization-picker.tsx` | Exports deprecated `OrgPicker` |
| `web/src/components/page-blocks/user-picker.tsx` | Alias re-export file |
| `web/src/components/page-blocks/org-picker.tsx` | Alias re-export file |
| `web/src/components/page-blocks/index.ts` | Re-exports aliases |

**Zero-code legacy refs = 13.**

---

## 5. Console / frontend leftovers

| Path | Note |
| --- | --- |
| `web/src/app/(console)/org/org-console.tsx` | Calls **`/api/platform/organizations`** (good); local state still named `unitId` / `memberUnit` / `ORG_UNIT_STATE_*` |
| `web/src/lib/org-console.ts` | Comments/helpers still say “org unit” |
| `web/src/i18n/phrases.ts` | Mixed: titles say Organization; several zh/en strings still **组织单元** / “org unit” / “unit id” (`orgUnitUpsertReviewTitle`, `orgMembershipUpsertHint`, …) |
| Phrase / var keys | Many `orgUnit*` key names (cosmetic; O8-5) |

No console calls to `/api/v1/org/**` found under `web/src`.

---

## 6. Tests / migration / docs exceptions (allowed)

| Kind | Paths (non-exhaustive) |
| --- | --- |
| Tests | `JdbcOrgDirectoryTest`, `OrgApiSecurityTest`, `OrgWriteSecurityTest`, `AccessCheckerTest`, `SqlRbacPolicyEngineTest`, `Organization*Gates*`, `OrganizationOntologyCutoverE2ETest`, `DeclarationRefGatesTest`, entity/form catalog tests with `userRef`/`orgRef` |
| Migrations | `V13__org_unit.sql`, `V23__org_unit_organization_map.sql`, `V24__DropLegacyOrgUnit.java` — **do not modify** |
| Docs | `docs/ontology/o3-dual-read.md`, `o7-e2e-pass.md`, `migration-mapping.md`, ADR, historical PROGRESS lines |
| Draft SQL | `entity-declare/.../migration-draft/V1__demo_ticket.sql` column `org_unit` (sample) |

---

## 7. Suggested O8 slice order (concrete files)

Execution order (instruction §4):  
`Inventory → Policy直连新模型 → OrganizationScope收口 → Legacy隔离 → 前端/零代码清理 → Map消费者清理 → Architecture Gates → E2E`

### Inventory (this slice) — done

- `docs/ontology/o8-inventory.md`, `web/PROGRESS.md`

### O8-1 — Policy直连新模型

Goal: `Membership + OrganizationRelation(CONTAINS) → OrganizationScope` without `JdbcOrgDirectory` / map / OrgUnit projection.

| Touch | Why |
| --- | --- |
| New: scope resolver on/near `JdbcOrganizationStore` (or dedicated `OrganizationScopeResolver`) | Direct Membership + CONTAINS; TenantOrganization filter; fail-closed NONE |
| `OrganizationApiEndpoint.java` | `resolveScope` → new resolver (drop `JdbcOrgDirectory` for scope) |
| Optionally start `OrganizationScope` type (or alias wrap) | O8-1 may introduce type; O8-2 finishes field renames |
| `OrgAuthorizationGatesTest` / E2E auth steps | Assert no map for scope |

**Do not** yet delete `JdbcOrgDirectory` (still needed for deprecated `/org` until O8-3).

### O8-2 — OrganizationScope收口 (naming)

| Touch | Rename |
| --- | --- |
| `OrgScope.java` → `OrganizationScope.java` | `rootUnitIds`→`rootOrganizationIds`, `unitIds`→`organizationIds` |
| `AccessChecker.java` | `resourceOrgUnitId`→`resourceOrganizationId` |
| `PolicyContext`, `AccessDecision`, `FormProblemDocument` | Type + JSON field names |
| `PolicyResource.java` | `orgUnitId` → `organizationId` attr |
| `SqlRbacPolicyEngine.java` | Call sites |
| Tests asserting `$.orgScope.rootUnitIds` | Update expectations |

### O8-3 — Legacy隔离

| Touch | Action |
| --- | --- |
| `org/OrgUnit.java`, `OrgMembership.java`, `JdbcOrgDirectory.java`, `OrgApiEndpoint.java`, `LegacyOrgApiDeprecationFilter.java` | Move/mark as **legacy compatibility** only; no new API / policy / zero-code imports |
| `OrganizationApiEndpoint.java` | Remove `JdbcOrgDirectory` dependency entirely (list/scope via store only) |
| `PlatformWiring.java` | Wire legacy beans only for `/api/v1/org/**` |
| Legacy API rule | `Legacy → New` only (project from ontology + map if needed) |

### O8-5 — 前端/零代码清理 *(before map per §4)*

| Touch | Action |
| --- | --- |
| `EntityFieldKind`, `FieldKind` | Drop `userRef`/`orgRef` accept (or keep parse-only behind explicit legacy flag — prefer delete once no consumers) |
| `form-field-input.ts`, wizards, `page-blocks-catalog.ts` | Remove aliases |
| Delete `user-picker.tsx` / `org-picker.tsx`; drop exports | Only SubjectPicker / OrganizationPicker |
| `phrases.ts`, `org-console.tsx`, `org-console.ts` | 组织 / Organization wording; rename unit* locals |
| Demo YAML | Rename field `orgUnit` → e.g. `organization` (kind already organizationRef) |

### O8-4 — Map消费者清理

| Touch | Action |
| --- | --- |
| `OrganizationOntologyBackfill.java` | Exit normal Organization API write path; migration/upgrade only |
| `JdbcOrgDirectory.java` | If retained for legacy API, map-only projection; strip dead `org_unit` SQL |
| `OrganizationApiEndpoint` write hooks | Stop `syncOrganizationToLegacy` / map upsert on happy path |
| Confirm | Policy + formal API + zero-code runtime **0** map SQL |

### O8-6 — Architecture Gates + E2E

| Touch | Action |
| --- | --- |
| `docs/ontology/machine-gates.md` | Add O8 gate ids (legacy consumer = 0, etc.) |
| New ArchUnit / test scan | Ban `OrgUnit`, `OrgMembership`, `orgUnitId`, `rootUnitIds`, `unitIds`, `TenantMembership`, `TenantOrgUnit`, `OrganizationUnit` in production domain (exceptions: migration, legacy adapter package, tests/docs) |
| `OrganizationOntologyCutoverE2ETest` | Extend: Relationship ≠ Authorization; scope without map; console/org path |
| Maven + `web` typecheck/test/build | All green |

---

## 8. Top risk production files (still on legacy)

1. `JdbcOrgDirectory.java` — scope + list + map + dead legacy SQL  
2. `OrganizationApiEndpoint.java` — new surface still New→Legacy via directory/backfill  
3. `OrganizationOntologyBackfill.java` — map on write path  
4. `OrgScope.java` — unit naming baked into auth JSON  
5. `AccessChecker.java` — `resourceOrgUnitId`  
6. `OrgApiEndpoint.java` — full legacy API surface  
7. `PolicyResource.java` — `orgUnitId` attribute  
8. `PlatformWiring.java` — dual wiring  
9. `SqlRbacPolicyEngine.java` — orgUnitId bridge  
10. `EntityFieldKind.java` / `FieldKind.java` — zero-code aliases (tie)

## 9. Blockers for O8-1

- Need a **first-class** Membership+CONTAINS scope API on the ontology store (today only inside `JdbcOrgDirectory`, and organization-id path remaps via **map**).  
- `OrganizationApiEndpoint` constructor requires `JdbcOrgDirectory` — must rewire before dropping directory from policy path.  
- Introducing `OrganizationScope` in O8-1 vs rename-in-place: prefer **add** `OrganizationScope` (org id semantics) and migrate AccessChecker call sites; finish JSON field renames in O8-2 to keep the slice small.  
- Do **not** edit V13/V23/V24.  
- Keep tests green each slice; no push until parent asks.


## O8-1 status (2026-10-09)

**Done.** See [`o8-1-pass.md`](o8-1-pass.md). Organization API + policy scope path use `OrganizationScopeResolver` (Membership + CONTAINS). `JdbcOrgDirectory` remains for legacy `/api/v1/org/**` only. Gate deltas recorded in o8-1-pass.


## O8-2 status (2026-10-09)

**Done.** See [`o8-2-pass.md`](o8-2-pass.md). Policy JSON emits `rootOrganizationIds` / `organizationIds`; `PolicyResource.organizationId()` no longer falls back to `orgUnitId`. Next = O8-3 Legacy isolation.


## O8-3 status (2026-10-09)

**Done.** See [`o8-3-pass.md`](o8-3-pass.md). Legacy types live under `org.legacy`; New → Legacy forbidden for organization/security/form. Next = **O8-5** (frontend/zero-code) then O8-4 (map).


## O8-4 status (2026-10-09)

**Done.** See [`o8-4-pass.md`](o8-4-pass.md). Formal API/Policy/zero-code exit map; Backfill migration/legacy-only.


## O8-5 status (2026-10-09)

**Done.** See [`o8-5-pass.md`](o8-5-pass.md). subjectRef/organizationRef only; zero-code refs → 0.


## O8-6 status / FULL PASS (2026-10-09)

**FULL PASS.** See [`o8-full-pass.md`](o8-full-pass.md). ArchUnit + metric gates; E2E Relationship≠Authorization + scope without map. Final metrics: ontology count=1; legacy domain/SQL/policy/zero-code = 0.
