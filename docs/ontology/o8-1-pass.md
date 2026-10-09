# O8-1 PASS — OrganizationScope from Membership + CONTAINS

**Status:** PASS  
**Date:** 2026-10-09 (Asia/Shanghai / CST)  
**Parent inventory:** [`o8-inventory.md`](o8-inventory.md) @ `2a5ad6d`

## What landed

| Item | Detail |
| --- | --- |
| `OrganizationScope` | Canonical scope type (`rootOrganizationIds` / `organizationIds`); JSON still emits `rootUnitIds`/`unitIds` until O8-2 |
| `OrganizationScopeResolver` | Membership (via TenantOrganization filter) + ACTIVE CONTAINS; **no** `JdbcOrgDirectory`, **no** map |
| `OrganizationApiEndpoint` | `resolveScope` → resolver; directory dependency removed |
| Policy stack | `PolicyContext` / `AccessDecision` / `AccessChecker` use `OrganizationScope`; `PolicyResource.organizationId()` prefers `organizationId` attr (legacy `orgUnitId` fallback) |
| `OrgScope` | Deprecated thin legacy shape for `JdbcOrgDirectory` / `/api/v1/org/**` |

## Gates exercised (OrganizationScopeResolverTest + AUTH)

- SELF = direct Membership orgs only  
- SELF_AND_DESCENDANTS via CONTAINS only  
- no Membership → NONE (never UNRESTRICTED)  
- CONTAINS cycle rejected at write  
- fail-closed NONE vs resource organization  
- Relationship ≠ Authorization (membership without permission)  
- other-tenant membership does not expand  

## Gate count deltas (vs inventory)

| Metric | Inventory | After O8-1 | Notes |
| --- | ---: | ---: | --- |
| Production ontology count | 2 | **2** | OrgUnit types still exist (O8-3) |
| Legacy domain consumers | 8 | **7** | OrganizationApiEndpoint no longer depends on JdbcOrgDirectory |
| Legacy SQL consumers | 2 | **2** | map still on backfill + directory (O8-4) |
| Policy legacy dependency | 7 | **~3** | Policy uses OrganizationScope; residual: JSON `rootUnitIds`/`unitIds`, `ATTR_ORG_UNIT_ID` fallback, FormProblem JSON names (O8-2) |
| Zero-code legacy refs | 13 | **13** | O8-5 |

## Residuals for later slices

- **O8-2:** rename wire fields `rootUnitIds`→`rootOrganizationIds`, `resourceOrganizationId` already preferred; drop `ATTR_ORG_UNIT_ID` fallback; delete `OrgScope` accessors aliases  
- **O8-3:** isolate `JdbcOrgDirectory` / `OrgUnit` / legacy `/api/v1/org`  
- **O8-4:** remove map from Organization API write path (`OrganizationOntologyBackfill.sync*`)  
- **OrgApiEndpoint** still resolves scope via `JdbcOrgDirectory` (legacy API only)

## Tests run

```text
mvn -pl platform-app -am test \
  -Dtest=OrganizationScopeResolverTest,OrgAuthorizationGatesTest,AccessCheckerTest,\
OrganizationApiSecurityTest,SqlRbacPolicyEngineTest,OrgApiSecurityTest,\
OrgWriteSecurityTest,OrganizationOntologyCutoverE2ETest \
  -Dsurefire.failIfNoSpecifiedTests=false
# → Tests run: 75, Failures: 0, Errors: 0
```

Skipped: full platform-app suite / other modules’ full surefire.
