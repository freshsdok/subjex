# O6 — Zero-code refs & pickers / 零代码引用与选人

**Status:** O6 landed (local). Canonical field kinds: `subjectRef` → Subject, `organizationRef` → Organization.  
**状态：** O6 已落地（本地）。规范字段种类见上。

## Field kinds / 字段种类

| Canonical | Legacy alias (dual-accept) | Resolves | SQL |
| --- | --- | --- | --- |
| `subjectRef` | `userRef` | **Subject** id only | VARCHAR |
| `organizationRef` | `orgRef` | **Organization** id only | VARCHAR |

- Wizards and new samples **write** canonical names.
- Parsers (entity + form) **still accept** legacy aliases during transition.
- **Forbidden:** `organizationUnitRef`, `tenantOrgUnitRef` (rejected at parse).

## Pickers / 积木

| Catalog id | Legacy alias | Data source when `tenantId` set |
| --- | --- | --- |
| `SubjectPicker` | `UserPicker` | `GET /api/v1/organizations/memberships` → unique `subjectId` |
| `OrganizationPicker` | `OrgPicker` | `GET /api/v1/organizations` → `organizationId` / name |

One pair only; **Context** (tenant, later descendant / membership) narrows candidates — do not invent more org pickers.

## Gates / 门禁

| Gate | Test |
| --- | --- |
| `DECL-01` | `DeclarationRefGatesTest` — subjectRef → Subject only |
| `DECL-02` | `DeclarationRefGatesTest` — organizationRef → Organization only; invented kinds rejected |

## Out of scope / 非本轮

No O7 DROP of `org_unit` / `org_membership`. No HR / Position / SCIM.
