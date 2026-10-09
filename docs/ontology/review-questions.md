# O1 review — ten questions / O1 评审十问

Any ambiguous answer **blocks** formal migration (O2 Flyway).  
任一项答案不明确，**不得**进入正式 migration。

| # | Question / 问题 | Answer / 答案 |
| --- | --- | --- |
| 1 | Subject 是否始终独立于 Tenant 和 Organization？ / Is Subject always independent of Tenant and Organization? | **Yes.** Subject is a first-class identity; Membership and TenantOrganization are optional links, never identity prerequisites. / Subject 是一等身份；Membership 与 TenantOrganization 是可选关联，不是身份前提。 |
| 2 | Organization 是否可以在没有 Tenant 的情况下存在？ / Can Organization exist without a Tenant? | **Yes.** Organization is world-model data; TenantOrganization is how it later joins a digital space. / Organization 属世界模型；通过 TenantOrganization 再进入数字空间。 |
| 3 | Organization 是否可以同时参与多个 Tenant？ / Can one Organization join multiple Tenants? | **Yes.** Multiple ACTIVE `TenantOrganization` rows for the same `organization_id` are allowed. / 同一 `organization_id` 允许多条 ACTIVE 关联。 |
| 4 | Tenant 是否可以同时关联多个 Organization？ / Can one Tenant associate multiple Organizations? | **Yes.** A Tenant may link zero or many Organizations via TenantOrganization. / 一个 Tenant 可通过 TenantOrganization 关联零个或多个 Organization。 |
| 5 | Membership 是否完全不包含 Tenant？ / Does Membership contain no Tenant semantics? | **Yes.** Membership tuple is only `(subject_id, organization_id)` (+ state); no `tenant_id` column or meaning. / 元组仅为 `(subject_id, organization_id)`（加状态）；无 `tenant_id`。 |
| 6 | Membership 是否完全不承担 Permission？ / Does Membership grant no Permission? | **Yes.** Named permissions and OrgScope stay in the Authorization layer; Membership is relationship only. / 具名权限与 OrgScope 属授权层；Membership 只表关系。 |
| 7 | Organization 层级是否完全通过 OrganizationRelation 表达？ / Is org hierarchy only via OrganizationRelation? | **Yes.** No `parent_id` on Organization; CONTAINS (and future kinds) live on OrganizationRelation. / Organization 无 parent_id；CONTAINS 等边在 OrganizationRelation。 |
| 8 | 推导出的 Tenant 关联是否明确不等于访问授权？ / Is derived Tenant association explicitly ≠ authorization? | **Yes.** TenantOrganization and Membership never imply AccessDecision; Context + named permission + OrgScope do. / TenantOrganization/Membership 不蕴含 AccessDecision；须 Context+具名权限+OrgScope。 |
| 9 | 无组织范围是否 fail-closed，而不是自动 unrestricted？ / Is missing org scope fail-closed, not auto-unrestricted? | **Yes.** Unspecified/missing scope on an org-scoped check fails closed (`NONE`-like); `UNRESTRICTED` requires an explicit grant. / 组织范围检查缺省 fail-closed；`UNRESTRICTED` 必须显式授予。 |
| 10 | 旧 org_unit 数据是否有无损迁移和回滚方案？ / Is there lossless migration and rollback for org_unit data? | **Yes (planned).** Per-tenant 1:1 backfill into Organization + Membership + TenantOrganization; dual-read in O3; **no auto cross-tenant merge**; rollback = keep legacy tables until O7 and stop reading new tables (see [`migration-mapping.md`](migration-mapping.md)). / 按租户 1:1 回填；O3 双读；**禁止跨租户自动合并**；回滚=保留旧表至 O7 并停读新表。 |

## Sign-off / 签署

- Model freeze document set: `docs/ontology/*` + ADR 0001
- Next gate: human review PASS → then O2 Persistence only
