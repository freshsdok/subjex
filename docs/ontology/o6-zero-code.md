# O6 — Zero-code refs & pickers / 零代码引用与选人

**Status:** O6 landed; **O8-5** removed dual-accept aliases. Canonical only: `subjectRef` → Subject, `organizationRef` → Organization.  
**状态：** O6 已落地；**O8-5** 已去掉双接受别名。仅规范种类。

## Field kinds / 字段种类

| Canonical | Legacy (rejected O8-5) | Resolves | SQL |
| --- | --- | --- | --- |
| `subjectRef` | `userRef` | **Subject** id only | VARCHAR |
| `organizationRef` | `orgRef` | **Organization** id only | VARCHAR |

- Wizards and samples **write** canonical names only.
- Parsers **reject** `userRef` / `orgRef` (clear error pointing at canonical).
- **Forbidden:** `organizationUnitRef`, `tenantOrgUnitRef` (rejected at parse).

## Pickers / 积木

| Catalog id | Removed alias (O8-5) | Data source when `tenantId` set |
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

## Related / 相关

Entity **row storage** default (hybrid B vs optional physical tables): [`../adr/0002-entity-storage-hybrid-b.md`](../adr/0002-entity-storage-hybrid-b.md) — docs decision only; not implemented in O6.
实体行存储默认策略见 ADR 0002（文档决策；O6 未实现存储切换）。
