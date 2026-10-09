# Machine gates (planned CI names) / 机器门禁（计划 CI 名）

Doc stubs for O2+ tests. Names are stable; implementations land with the matching slice.  
文档桩；名称稳定，实现随对应切片落地。建议成为 CI 必过项。

## MODEL — existence independence

| Gate | Assert |
| --- | --- |
| `MODEL-01` | Organization exists without Tenant |
| `MODEL-02` | Tenant exists without Organization |
| `MODEL-03` | Subject exists without Organization |

## MEM — membership purity

| Gate | Assert |
| --- | --- |
| `MEM-01` | Membership contains no tenant semantics |
| `MEM-02` | Membership requires existing Subject |
| `MEM-03` | Membership requires existing Organization |

## ORG — relation graph

| Gate | Assert |
| --- | --- |
| `ORG-01` | Organization may contain Organization (CONTAINS) |
| `ORG-02` | Organization relation cannot self-reference |
| `ORG-03` | CONTAINS relation cannot form cycle |

## TENANT — association ≠ authz

| Gate | Assert |
| --- | --- |
| `TENANT-01` | Organization may join multiple Tenants |
| `TENANT-02` | Tenant may contain multiple Organizations |
| `TENANT-03` | TenantOrganization does not grant Permission |

## AUTH — scope fail-closed

| Gate | Assert |
| --- | --- |
| `AUTH-01` | Relationship does not imply authorization |
| `AUTH-02` | Missing scope is fail-closed |
| `AUTH-03` | NONE != UNRESTRICTED |
| `AUTH-04` | Tenant mismatch denies |
| `AUTH-05` | Organization descendant resolution cannot escape relation graph |

## MIG — backfill integrity

| Gate | Assert |
| --- | --- |
| `MIG-01` | Existing org_unit data backfills without loss |
| `MIG-02` | Existing membership backfills without loss |
| `MIG-03` | Same-name orgs across tenants are not auto-merged |

## DECL — zero-code refs

| Gate | Assert |
| --- | --- |
| `DECL-01` | subjectRef resolves Subject only |
| `DECL-02` | organizationRef resolves Organization only |

## Suggested JUnit display names / 建议展示名

Use the gate id as the test method prefix or `@DisplayName`, e.g. `MODEL_01_organizationExistsWithoutTenant`.
