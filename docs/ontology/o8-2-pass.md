# O8-2 PASS — OrganizationScope field naming

**Status:** PASS  
**Date:** 2026-10-09 (Asia/Shanghai / CST)  
**Parent:** O8-1 @ `37890a7`

## Renames

| Before | After |
| --- | --- |
| JSON `rootUnitIds` | `rootOrganizationIds` |
| JSON `unitIds` | `organizationIds` |
| `OrganizationScope.rootUnitIds()` / `unitIds()` (deprecated aliases) | **removed** |
| `OrgScope(rootUnitIds, unitIds)` | `OrgScope(rootOrganizationIds, organizationIds)` (legacy mirror) |
| `PolicyResource.organizationId()` fallback to `orgUnitId` attr | **removed** — only `ATTR_ORGANIZATION_ID` |
| `ATTR_ORG_UNIT_ID` / `orgUnitId()` | constant kept `@Deprecated` (ignored by `organizationId()`); method removed |
| AccessChecker `resourceOrganizationId` | already named in O8-1; no `resourceOrgUnitId` in production |

## Gate count deltas (vs O8-1)

| Metric | O8-1 | After O8-2 | Notes |
| --- | ---: | ---: | --- |
| Production ontology count | 2 | **2** | OrgUnit types still in `app.org` (O8-3) |
| Legacy domain consumers | 7 | **7** | unchanged |
| Legacy SQL consumers | 2 | **2** | unchanged |
| Policy legacy dependency | ~3 | **~1** | only deprecated `ATTR_ORG_UNIT_ID` constant leftover; JSON/params are organization* |
| Zero-code legacy refs | 13 | **13** | O8-5 |

## Residuals → O8-3

- `JdbcOrgDirectory`, `OrgUnit`, `OrgMembership`, `OrgApiEndpoint`, `LegacyOrgApiDeprecationFilter` still live
- Legacy `/api/v1/org/**` still uses `OrgScope` (aligned field names) + domain `orgUnitId` on wire documents (legacy API shape — isolate in O8-3)
- Map / backfill write-through untouched (O8-4)

## Tests

```text
mvn -pl platform-app -am test \
  -Dtest=OrganizationScopeResolverTest,OrgAuthorizationGatesTest,AccessCheckerTest,\
OrganizationApiSecurityTest,SqlRbacPolicyEngineTest,OrgApiSecurityTest,\
OrgWriteSecurityTest,OrganizationOntologyCutoverE2ETest,JdbcOrgDirectoryTest,\
OrganizationOntologyBackfillGatesTest \
  -Dsurefire.failIfNoSpecifiedTests=false
# → Tests run: 87, Failures: 0, Errors: 0
```
