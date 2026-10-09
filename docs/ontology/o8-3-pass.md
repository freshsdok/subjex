# O8-3 PASS — Isolate OrgUnit legacy compatibility

**Status:** PASS  
**Date:** 2026-10-09 (Asia/Shanghai / CST)  
**Parent:** O8-2 @ `0d166c3`

## Package move

| Before | After |
| --- | --- |
| `com.subjex.platform.app.org.*` | `com.subjex.platform.app.org.legacy.*` |
| `security.OrgScope` | `org.legacy.OrgScope` |

Types: `OrgUnit`, `OrgMembership`, `JdbcOrgDirectory`, `OrgApiEndpoint`, `LegacyOrgApiDeprecationFilter`, `OrgScope` (+ `package-info` dependency rule).

## Dependency direction

- **Legacy → New:** `JdbcOrgDirectory` / `OrgApiEndpoint` → `JdbcOrganizationStore`, `OrganizationOntologyBackfill`, `OrganizationScope`
- **Forbidden New → Legacy:** enforced for `organization` / `security` / `form` main sources (`LegacyPackageBoundaryTest`)
- `OrganizationOntologyBackfill` no longer imports `OrgUnit`/`OrgMembership` (primitive sync + private `LegacyUnitRow`)
- `OrganizationScope.fromOrgScope` removed (was New → Legacy); convert via `OrgScope.toOrganizationScope()`
- `OrganizationApiEndpoint` has **zero** `JdbcOrgDirectory` dependency (O8-1+O8-3)
- `PlatformWiring` documents `JdbcOrgDirectory` bean as legacy-only for `/api/v1/org/**`

## Who still imports legacy (main)

| Consumer | Why allowed |
| --- | --- |
| `org.legacy.*` | The compatibility layer itself |
| `PlatformWiring` | Registers legacy bean for `/api/v1/org/**` only |

Tests under `organization/` may import legacy for cutover / AUTH seeding (exception).

## Gate deltas (vs O8-2)

| Metric | O8-2 | After O8-3 |
| --- | ---: | ---: |
| Production ontology count | 2 | **1** (OrgUnit types quarantined in legacy) |
| Legacy domain consumers | 7 | **0** (new API/policy/form) |
| Legacy SQL consumers | 2 | **2** (directory + backfill map/pre-DROP; O8-4) |
| Policy legacy dependency | ~1 | **0** (ATTR_ORG_UNIT_ID constant only; no OrgScope in security) |
| Zero-code legacy refs | 13 | **13** (next O8-5) |

## Residuals → O8-5 then O8-4

- Next per inventory order: **O8-5** frontend/zero-code (`userRef`/`orgRef`/`UserPicker`/`OrgPicker`, 组织单元 copy)
- Then **O8-4** map/backfill off Organization API write path
- Dead `org_unit` SQL in `JdbcOrgDirectory` quarantined behind `legacyOrgTablesPresent()`

## Tests

```text
mvn -pl platform-app -am test \
  -Dtest=OrganizationScopeResolverTest,OrgAuthorizationGatesTest,AccessCheckerTest,\
OrganizationApiSecurityTest,SqlRbacPolicyEngineTest,OrgApiSecurityTest,\
OrgWriteSecurityTest,OrganizationOntologyCutoverE2ETest,JdbcOrgDirectoryTest,\
OrganizationOntologyBackfillGatesTest,LegacyPackageBoundaryTest \
  -Dsurefire.failIfNoSpecifiedTests=false
# → Tests run: 88, Failures: 0, Errors: 0
```
