# Ontology — O1 Model Freeze / 本体模型冻结（O1）

**Status:** Model freeze (docs only). No new Flyway tables until O1 review PASS.  
**状态：** 模型冻结（仅文档）。O1 内审通过前不写新表 Flyway。

**Baseline:** Owner V1.0 ontology revision (2026-10).  
**基线：** 业主 V1.0 本体修订。

## Six frozen concepts / 六个冻结概念

| # | Concept | One-line / 一句话 |
| --- | --- | --- |
| 1 | **Subject** | Platform actor; independent of Tenant and Organization. 平台主体；独立于租户与组织。 |
| 2 | **Organization** | Real-world / business org — **not** a tenant-scoped department alias. 现实/业务组织——**不是**租户内部门别名。 |
| 3 | **Tenant** | Digital isolation / governance space. 数字隔离与治理空间。 |
| 4 | **Membership** | Subject ∈ Organization; **no tenant**, **no permission**. 成员关系；不含租户、不授权限。 |
| 5 | **OrganizationRelation** | Directed org→org edges (CONTAINS, …); hierarchy lives here. 组织间有向边；层级在此表达。 |
| 6 | **TenantOrganization** | Tenant ↔ Organization M:N; link ≠ authorization. 租户↔组织多对多；关联≠授权。 |

## Pack contents / 本包文件

| File | Content |
| --- | --- |
| [`../adr/0001-o1-organization-ontology.md`](../adr/0001-o1-organization-ontology.md) | ADR — why replace thin `org_unit` |
| [`er-and-cardinality.md`](er-and-cardinality.md) | Mermaid ER + cardinality table |
| [`lifecycle-id-state.md`](lifecycle-id-state.md) | Lifecycle, ID rules, state rules |
| [`review-questions.md`](review-questions.md) | Ten review answers (gate for migration) |
| [`migration-mapping.md`](migration-mapping.md) | Legacy → new mapping (no auto-merge) |
| [`machine-gates.md`](machine-gates.md) | Planned CI gate names (stubs) |

## Locks / 锁定

- Do **not** invent `organizationUnitRef` / `tenantOrgUnitRef`.
- Pickers → **SubjectPicker** + **OrganizationPicker** (Context narrows candidates).
- Pause structural expansion of `org_unit` / `org_membership` until O1 PASS; bugfixes OK.
- Legacy thin model remains runtime until O7.

## Slice order / 切片顺序

```text
O1 Model Freeze  →  O2 Persistence  →  O3 Compatibility & Backfill
                 →  O4 Authorization  →  O5 API / Console
                 →  O6 Zero-code      →  O7 Legacy Removal
```

## Cross-links / 交叉引用

- Architecture §18: [`ARCHITECTURE.md`](../../ARCHITECTURE.md)
- Low-code roadmap (dim 0): [`../lowcode-roadmap.md`](../lowcode-roadmap.md)
- Security known limits: [`../../SECURITY.md`](../../SECURITY.md)
