# subjex 组织架构构建器靶标 / Org Builder target brief

- Date: 2026-10-16 (Asia/Shanghai)
- Based on: [`org-structure-research.md`](org-structure-research.md)
- Ontology: O1–O8 — `Organization` · `Membership` · `OrganizationRelation(CONTAINS)` · `TenantOrganization` · `OrganizationScope`
- Console today: `/org` 表格 + 表单（无树/画布/拖拽）

## 1. 产品定位 / Positioning

subjex Org Builder = **租户上下文内的组织世界模型可视化编辑器**：

- 编辑的是 **Organization + CONTAINS**（及 Membership），不是 Tenant，也不是权限授予本身。
- 授权仍走既有 `org.read` / `org.write` + `OrganizationScope`（关系 ≠ 授权，ADR 0001）。
- UX 靶标对齐 **钉钉/飞书/企微管理台** 的部门树心智，并吸收 Workday/Personio 的 **搜索定位、影响预览、画布只读导航**；**不**在 MVP 复制 Workday Org Studio 全量或 SF Position Management。

**推荐形态：tree + canvas hybrid（树主编辑 + 画布辅导航）**

```text
┌─────────────────────────────┬──────────────────────┐
│  Search / Locate            │  Side panel          │
├──────────────┬──────────────┤  - name / state      │
│ Indented     │ Optional     │  - parent            │
│ Tree (edit)  │ Canvas view  │  - members           │
│ DnD reparent │ (locate)     │  - impact preview    │
└──────────────┴──────────────┴──────────────────────┘
```

## 2. 能力分级 / Must · Should · Later

### Must（MVP，对齐主流「能管部门树」）

| ID | 能力 | 映射到本体 / API |
| --- | --- | --- |
| M1 | **缩进树**展示租户内 ACTIVE TenantOrganization 下的 CONTAINS 森林 | GET organizations → 客户端建树；懒加载可选 |
| M2 | **拖拽改挂载（reparent）** | 事务性「move」：ENDED 旧 CONTAINS + ACTIVE 新 CONTAINS；服务端防环（已有 ORG-03） |
| M3 | **搜索/定位**组织（及可选成员→所在组织）并展开路径 | 前端 filter + scroll-into-view；API 可后补 `q=` |
| M4 | **侧栏**创建/重命名/启停组织；展示直接成员 | 现有 PUT organization / membership |
| M5 | **移动影响预览**（子树节点数、直接成员数、是否环） | 新只读 `POST .../moves:preview` 或等价 |
| M6 | **两步确认**后再写（沿用 `/org` FormStep） | 控制台 UX |
| M7 | **审计** move/upsert/disable（沿用审计管线） | `org.organization.move` 等事件名 |
| M8 | **OrgScope 门禁**：超出 SELF_AND_DESCENDANTS 不可拖/不可写 | AccessChecker 已有模式 |

### Should（MVP+1，对齐飞书批量与可用性）

| ID | 能力 | 说明 |
| --- | --- | --- |
| S1 | ~~多选移动~~ **done** | `POST /moves` + `/moves/preview`；树多选勾选 |
| S2 | ~~兄弟排序~~ **done** | V40 `sort_order`；`POST .../children/order`；侧栏上/下移 |
| S3 | ~~画布导出 SVG/PNG~~ **done** | ORG-UI-5 图 + 客户端 SVG/PNG 下载 |
| S4 | ~~批量 CSV~~ **done** | 导出 + dry-run 导入 + PUT 应用 |
| S5 | ~~合并/拆分向导~~ **done** | `merge`/`split` + preview；侧栏确认；成员重挂 + CONTAINS 重写 |
| S6 | ~~部门主管~~ **done** | V41 `organization_manager`；PUT/DELETE manager；确保 Membership 映射 OrgScope |

### Later（主流有、但超出现有本体非目标）

| ID | 能力 | 阻塞 / 依赖 |
| --- | --- | --- |
| L1 | **矩阵/虚线汇报** | 新 `relation_kind`（如 `MATRIX_OF`）+ 画布虚线；ADR 0006 |
| L2 | **岗位 Position / 空缺** | 新实体；ADR 0001 曾列为 non-goal |
| L3 | **平行维度树**（成本中心 vs 法人） | `organization_kind` 或多命名空间图 |
| L4 | **Org Studio 风格草稿重组** | 草稿设计对象 + 审批 + mass apply（可接薄工作流） |
| L5 | **Effective dating**（as-of 历史组织图） | 时态边；工作量大 |
| L6 | **通讯录可见性**（隐藏部门/限制外部门） | 属目录产品，非 AuthZ OrgScope；可另 ADR |

## 3. 明确不做（本波） / Non-goals this wave

- 把 Membership 写成权限；跨租户自动合并组织。
- 一次性上 Position 编制或财务成本中心主数据。
- 在 canvas 上无预览直接「拖了就永久生效」的大重组。
- 复制 Entra AU 扁平模型替代 CONTAINS 树。

## 4. API / 模型缺口（实现前）

| 缺口 | 建议 |
| --- | --- |
| ~~缺少显式 move API~~ | **done** `POST .../move` + `.../move/preview` |
| ~~缺少 preview~~ | **done** |
| ~~无 sibling order~~ | **done** V40 + children/order (S2) |
| 树查询 N+1 | 可选 `GET .../tree?tenantId=` 一次返回森林（仍可先前端组装） |

详情见 [`docs/adr/0006-org-builder-and-relation-kinds.md`](../adr/0006-org-builder-and-relation-kinds.md)。

## 5. 切片计划 / Proposed slices

| Slice | 内容 | 完成标准 |
| --- | --- | --- |
| **ORG-UI-0** | 本调研 + 靶标 + ADR 0006 草案（本文档波次） | 文档合入；无代码强制 |
| **ORG-UI-1** | ~~只读缩进树 + 搜索定位~~ **done** | `/org` Tree tab；搜索高亮+展开路径；侧栏只读 |
| **ORG-UI-2** | ~~拖拽 reparent + move API~~ **done** | `move` + `move/preview`；HTML5 DnD；防环/OrgScope/审计 |
| **ORG-UI-3** | ~~move preview + OrgScope~~ **done (in UI-2)** | MockMvc preview/cycle/403 |
| **ORG-UI-4** | ~~侧栏写~~ **done** | 创建子组织/启停/成员增删；两步确认；刷新树 |
| **ORG-UI-5** | ~~只读 canvas~~ **done** | SVG 分层图；与树/侧栏选中同步；滚动查看 |
| **ORG-UI-6** | 多选移动 +（可选）CSV 导出 | Should 起步 |
| **ORG-UI-7+** | order / merge-split / matrix / 批量导入 dry-run | 按 ADR 与优先级 |

**Must MVP 已收口；Should S1–S6 已落地（本地 tip）。** 待 squash-push 至 github/main。

## 6. 成功度量 / Success criteria（MVP）

1. 运营人员可在 **不手填 parentId** 的情况下，通过拖拽完成部门改挂载。  
2. 每次移动可见 **影响预览** 且写入可审计。  
3. 越权组织节点不可编辑（OrgScope）。  
4. 门禁：CONTAINS 无环；与现有 ORG-01/03 一致。  
5. 文档与 PROGRESS 可回溯到研究来源（不臆造对标功能）。

## 7. 参考

- 调研：[`org-structure-research.md`](org-structure-research.md)  
- 本体：[`docs/adr/0001-o1-organization-ontology.md`](../adr/0001-o1-organization-ontology.md)、[`docs/ontology/er-and-cardinality.md`](../ontology/er-and-cardinality.md)  
- 控制台现状：`web/src/app/(console)/org/`
