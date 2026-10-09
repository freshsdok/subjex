# O8 FULL PASS — Organization model migration

**Status:** **FULL PASS**  
**Date:** 2026-10-09 (Asia/Shanghai)  
**Commit:** `4d6a8d6` — `test(ontology): O8-6 architecture gates FULL PASS`  
**Base chain:** Inventory → O8-1…O8-5 → O8-4 → **O8-6**

## Declaration

Organization model migration meets all O8 DONE criteria. Formal production domain is the six-concept ontology (Subject, Organization, Tenant, Membership without tenant_id, OrganizationRelation, TenantOrganization). Legacy `OrgUnit` / `OrgMembership` live only under `org.legacy`. Tables `org_unit` / `org_membership` dropped (V24). Map table kept with **no formal runtime consumers**. Zero-code uses only `subjectRef` / `organizationRef`. Relationship ≠ Authorization is automated (AUTH-01 + E2E-02).

## Final five metrics

| Metric | Target | Actual | Evidence |
| --- | ---: | ---: | --- |
| Production ontology count | **1** | **1** | `O8-METRIC-01`; OrgUnit/OrgMembership only under `org.legacy` |
| Legacy domain consumers | **0** | **0** | `O8-METRIC-02` / `O8-ARCH-02`; no New→Legacy imports in formal pkgs |
| Legacy SQL consumers (formal) | **0** | **0** | `O8-METRIC-03` / `O8-MAP-01`; map SQL only in Backfill + V24 |
| Policy legacy dependency | **0** | **0** | `O8-METRIC-04`; `ATTR_ORG_UNIT_ID` removed; no `orgUnitId`/`rootUnitIds`/`unitIds` in security |
| Zero-code legacy refs | **0** | **0** | `O8-METRIC-05` / DECL-01/02; aliases rejected; pickers deleted |

## Machine gates (O8)

| Gate | Status |
| --- | --- |
| O8-METRIC-01…05 | PASS (`O8MetricGatesTest`) |
| O8-ARCH-01 / O8-ARCH-03 | PASS (`O8ArchitectureGatesTest` ArchUnit) |
| O8-ARCH-02 | PASS (`O8MetricGatesTest` + `LegacyPackageBoundaryTest`) |
| O8-MAP-01 | PASS |
| AUTH-01 Relationship ≠ Authorization | PASS (`OrgAuthorizationGatesTest` + E2E-02) |
| E2E-01 / E2E-02 / E2E-03 | PASS (`OrganizationOntologyCutoverE2ETest`) |
| MODEL / MEM / ORG / TENANT / MIG / DECL | PASS (prior slices; still green in focused suite) |

## Residuals (intentional, not blockers)

- `org.legacy` package: deprecated `/api/v1/org/**` adapter; write-through may upsert map aliases
- `OrganizationOntologyBackfill`: migration / legacy write-through only (V24 + explicit upgrade)
- `org_unit_organization_map` **table kept** (no formal consumers)
- Phrase i18n keys may still be named `orgUnit*` (user-visible copy cleaned in O8-5)

## Verification

- Focused org/organization/security/cutover + O8 gates: green
- `web`: typecheck + Vitest **91** + build: green
- `mvn -pl platform-app -am test`: **460** tests, **0** failures, **0** errors, 4 skipped

## Slice commits (local, not pushed)

| Slice | Commit (short) |
| --- | --- |
| Inventory | `2a5ad6d` |
| O8-1 | `37890a7` |
| O8-2 | `0d166c3` |
| O8-3 | `bd6b879` |
| O8-5 | `5626b46` |
| O8-4 | `f9ef74a` |
| O8-6 | `4d6a8d6` |

## Next (optional)

Pushed squash to `github/main` as `1e431b2` (2026-10-09 CST). Alpha tag still optional / do not tag until asked.
