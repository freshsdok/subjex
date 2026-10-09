# Machine gates (planned CI names) / 机器门禁（计划 CI 名）

Names are stable. **MODEL / MEM / ORG / TENANT** → `JdbcOrganizationStoreGatesTest` (O2).  
**MIG** → `OrganizationOntologyBackfillGatesTest` (O3). **AUTH** → `OrgAuthorizationGatesTest` (O4). **API** → `OrganizationApiSecurityTest` (O5). **DECL** → `DeclarationRefGatesTest` (O6). **E2E** → `OrganizationOntologyCutoverE2ETest` (O7/O8-6). **O8** → `O8ArchitectureGatesTest` (O8-6 FULL PASS).  
名称稳定。MODEL…DECL 均已实现。建议成为 CI 必过项。

## MODEL — existence independence *(O2 PASS)*

| Gate | Assert |
| --- | --- |
| `MODEL-01` | Organization exists without Tenant |
| `MODEL-02` | Tenant exists without Organization |
| `MODEL-03` | Subject exists without Organization |

## MEM — membership purity *(O2 PASS)*

| Gate | Assert |
| --- | --- |
| `MEM-01` | Membership contains no tenant semantics |
| `MEM-02` | Membership requires existing Subject |
| `MEM-03` | Membership requires existing Organization |

## ORG — relation graph *(O2 PASS)*

| Gate | Assert |
| --- | --- |
| `ORG-01` | Organization may contain Organization (CONTAINS) |
| `ORG-02` | Organization relation cannot self-reference |
| `ORG-03` | CONTAINS relation cannot form cycle |

## TENANT — association ≠ authz *(O2 PASS)*

| Gate | Assert |
| --- | --- |
| `TENANT-01` | Organization may join multiple Tenants |
| `TENANT-02` | Tenant may contain multiple Organizations |
| `TENANT-03` | TenantOrganization does not grant Permission |

## AUTH — scope fail-closed *(O4 PASS)*

| Gate | Assert |
| --- | --- |
| `AUTH-01` | Relationship does not imply authorization |
| `AUTH-02` | Missing scope is fail-closed |
| `AUTH-03` | NONE != UNRESTRICTED |
| `AUTH-04` | Tenant mismatch denies |
| `AUTH-05` | Organization descendant resolution cannot escape relation graph |

## MIG — backfill integrity *(O3 PASS; O7 rewritten for post-DROP)*

| Gate | Assert |
| --- | --- |
| `MIG-01` | Existing org_unit data backfills without loss |
| `MIG-02` | Existing membership backfills without loss |
| `MIG-03` | Same-name orgs across tenants are not auto-merged |

## DECL — zero-code refs *(O6 PASS; O8-5 aliases removed)*

| Gate | Assert |
| --- | --- |
| `DECL-01` | subjectRef resolves Subject only; `userRef` rejected |
| `DECL-02` | organizationRef resolves Organization only; `orgRef` / invented kinds rejected |

## Suggested JUnit display names / 建议展示名

Use the gate id as the test method prefix or `@DisplayName`, e.g. `MODEL_01_organizationExistsWithoutTenant`.




## O8 — migration FULL PASS metrics *(O8-6)*

| Gate | Assert |
| --- | --- |
| `O8-METRIC-01` | Production ontology count = 1 (OrgUnit/OrgMembership only under `org.legacy`) |
| `O8-METRIC-02` | Legacy domain consumers = 0 outside `org.legacy` |
| `O8-METRIC-03` | Legacy SQL consumers = 0 in formal/policy/new API/zero-code |
| `O8-METRIC-04` | Policy legacy dependency = 0 (`orgUnitId` / `rootUnitIds` / `unitIds` / `ATTR_ORG_UNIT_ID` absent in security) |
| `O8-METRIC-05` | Zero-code legacy refs = 0 (only `subjectRef` / `organizationRef`; aliases rejected) |

## O8 — architecture *(O8-6)*

| Gate | Assert |
| --- | --- |
| `O8-ARCH-01` | No production dependency on `OrgUnit` / `OrgMembership` outside `org.legacy` |
| `O8-ARCH-02` | New packages do not import `org.legacy` (Legacy → New only) |
| `O8-ARCH-03` | Types `TenantMembership` / `TenantOrgUnit` / `OrganizationUnit` absent |
| `O8-MAP-01` | Formal runtime has no `org_unit_organization_map` SQL |

Tests: `O8ArchitectureGatesTest`, `LegacyPackageBoundaryTest`.

## E2E — cutover *(O7 + O8-6)*

| Gate | Assert |
| --- | --- |
| `E2E-01` | Backfill/seed → org surface → auth scope → declaration refs → console path smoke |
| `E2E-02` | Relationship ≠ Authorization (Membership alone does not grant `org.write`) |
| `E2E-03` | Formal scope via `OrganizationScopeResolver` without map |

Record: [`o7-e2e-pass.md`](o7-e2e-pass.md), [`o8-full-pass.md`](o8-full-pass.md).
