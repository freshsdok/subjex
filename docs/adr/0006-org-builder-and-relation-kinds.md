# ADR 0006 — Org Builder model gaps (relation kinds & move)

- Status: **Accepted**（MVP Must + Should S1–S6；D3 Later L1 `MATRIX_OF` sketch landed — not in OrgScope。L2 Position 仍待开闸）
- Date: 2026-10-16 (Asia/Shanghai)
- Relates: ADR 0001（O1 ontology）、[`docs/org/org-structure-research.md`](../org/org-structure-research.md)、[`docs/org/org-builder-target.md`](../org/org-builder-target.md)

## Context / 背景

Org Builder（可视化组织架构管理）要以主流产品为靶标。调研结论：

- MVP 只需强化 **CONTAINS 树** 的拖拽改挂载、预览与审计（钉钉/飞书/企微心智）。
- 矩阵汇报、岗位编制、成本中心平行树、兄弟排序等在公开产品中常见，但 **超出 ADR 0001 当时 non-goals**，需显式开闸才可改模型。

当前：`OrganizationRelation.relation_kind` 生产仅用 **`CONTAINS`**；无 sibling `order`；无 Position；控制台已有 move/preview、树/画布与侧栏写（ORG-UI-1…5）。

## Decision / 决策（草案）

### D1 — MVP 不新增 relation_kind

Org Builder **Must** 切片（ORG-UI-1…4）仅使用现有 `CONTAINS` + Membership + TenantOrganization。  
通过 **move 事务**（替换一条 ACTIVE CONTAINS 边）表达 reparent；循环检测沿用 ORG-03。

### D2 — 增加显式 move / preview 能力（API，非新表）

```text
POST /api/v1/organizations/{organizationId}/move?tenantId=
  { "newParentOrganizationId": "<id>|null" }

POST /api/v1/organizations/{organizationId}/move/preview?tenantId=
  → { subtreeOrganizationCount, directMembershipCount, wouldCycle, ... }
```

- `newParentOrganizationId = null`：升为租户森林根（仅去掉 CONTAINS 父边，不删组织）。  
- 必须 `org.write` + OrganizationScope 覆盖被移动节点（建议含旧父与新父）。  
- 审计事件建议：`org.organization.move`。

### D3 — 后续 kind / 字段开闸条件（Later）

| 扩展 | 何时 Accepted | 备注 |
| --- | --- | --- |
| `relation_kind = MATRIX_OF`（或 `DOTTED_REPORTS`） | **L1 sketch opened** — API + dashed canvas + side panel | 不参与 OrgScope 闭包，除非另有 AUTH ADR |
| sibling `sort_order` on CONTAINS | **Opened for Should S2** (V40) | `organization_relation.sort_order` + children/order API |
| `organization_kind`（SUPERVISORY / COST_CENTER / LEGAL / PROJECT…） | 需要平行维度树 | 同 kind 内 CONTAINS；跨 kind 禁止默认可拖 |
| Position 实体 | 明确做编制/空缺 | 新 ADR；勿塞进 Organization |

### D5 — 部门主管独立表（Should S6）

`OrganizationRelation` 仅 org→org，不能指向 Subject。新增 `organization_manager(organization_id, manager_subject_id, link_state)`（一组织一主管）。
指定时 upsert ACTIVE Membership，以便 OrgScope 闭包纳入主管；清除主管不结束 Membership。**不**引入新授权语义。

### D4 — UX 形态

接受靶标文档的 **tree（主编辑）+ canvas（辅导航）**；canvas 默认只读，直到 ORG-UI-5+。

## MVP Must status / Must 落地状态

ORG-UI-0…5 complete; Should S1–S6 complete; L1 `MATRIX_OF` sketch (API + overlay). Sibling order opened for S2. Position remains D3 Later.

ORG-UI-0…5 与 Should 已完成；L1 MATRIX_OF 草图已开。岗位仍属 D3 Later。

## Consequences / 后果

**Positive**

- 构建器可立即开工且不破坏 O1 冻结边界。  
- move/preview 补齐「拖拽」所需服务端语义，避免前端多次 DELETE/PUT 竞态。

**Negative / cost**

- ~~无 order~~ S2 已加 `sort_order`（默认 0，可上/下移）。  
- ~~矩阵~~ L1 草图已开（虚线/侧栏；不入 OrgScope）；岗位仍待 Later；文档须写清以免期望失控。

**Non-goals this ADR**

- Effective dating、Org Studio 草稿对象、通讯录隐藏部门策略。

## Links

- Research: `docs/org/org-structure-research.md`  
- Target: `docs/org/org-builder-target.md`  
- Ontology: `docs/adr/0001-o1-organization-ontology.md`
