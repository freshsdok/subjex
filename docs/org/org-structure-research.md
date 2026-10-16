# 组织架构管理模式调研 / Org-structure management research

- Date: 2026-10-16 (Asia/Shanghai)
- Purpose: 为 subjex **组织架构构建器（Org Builder）** 定靶标；仅依据公开文档/产品资料与可核验社区反馈，**不臆造功能**。
- Audience: 产品与平台实现；对照现有本体 O1–O8（Organization / Membership / OrganizationRelation / TenantOrganization）。

## 1. 结论摘要 / Executive summary

主流产品把「组织」拆成几类正交能力，而不是单一部门树：

| 模式 Mode | 典型产品 | 核心表达 |
| --- | --- | --- |
| **Supervisory / 汇报树** | Workday Supervisory、BambooHR/Personio People 视图 | 人→经理或岗→岗 |
| **Department tree / 部门通讯录树** | 钉钉、飞书、企业微信 | `parent` + `order`，拖拽改挂载/排序 |
| **Position-led / 岗位主导** | SAP SuccessFactors Position Management、Workday Position | 岗位空缺、编制、虚线矩阵 |
| **Parallel dimensions / 平行维度** | Workday Cost Center / Company / Region | 成本中心 ≠ 法人 ≠ 地理 ≠ 汇报 |
| **Matrix / 矩阵虚线** | Workday Matrix、SF Matrix Manager | 实线之外的协作/虚线汇报 |
| **Delegation scope / 授权范围（非树）** | Microsoft Entra Administrative Units、Okta Groups | **扁平**范围容器，**不嵌套** |

对 subjex：**MVP 应先对齐「部门通讯录树 + 拖拽改 CONTAINS + 搜索定位 + 按组织范围授权」**（钉钉/飞书/企微与控制台运维最接近）；画布式 org chart、矩阵、岗位编制、Org Studio 级「草稿重组」放后续。

## 2. 产品对照（公开来源） / Product survey

### 2.1 Workday（HCM / Org Studio）

**公开能力（文档与 datasheet）：**

- 多种 **organization types**：Supervisory、Company、Cost Center、Geographic、Custom、**Matrix**、Pay Group、Regions 等；组织用于 BP 路由、安全、分析与报表。  
  来源：[Setup Considerations: Organizations](https://doc.workday.com/admin-guide/en-us/manage-workday/organizations/ivq1568755825744.html)
- **Org charts**：org-view / people-view / hierarchy / metrics；可导出 PDF/Excel（再进 Visio）等。  
  来源：[Concept: Organization Charts](https://doc.workday.com/admin-guide/en-us/manage-workday/organizations/organization-charts/lah1480533274925.html)
- **Org Studio + Mass Actions**：拖拽规划重组、协作审批，再转入 Worksheets 做 **mass action** 落地；子域细到 Move Worker、Assign Superior、Inactivate Org、Create Subordinate 等。  
  来源：[Org Studio and Mass Actions](https://doc.workday.com/admin-guide/en-us/manage-workday/organizations/org-studio-and-mass-actions/prq1551821683080.html)、[Organization Management datasheet (PDF)](https://www.workday.com/content/dam/web/en-us/documents/datasheets/organization-management-in-workday-datasheet-en-us.pdf)
- **Staffing models**：Position management vs Job management；矩阵组织用于虚线/协作可见性。

**对构建器的启示：** 「看图」与「批量落地」分离；大重组需要 **预览影响 → 审批 → 批量执行**，而不是每次拖一次就立即写库无回滚叙事。

**社区痛点（非官方、仅作风险信号）：** Reddit 等讨论提到 Org Studio 校验/多变更排序/培训成本等问题（见 §5）。不可当作产品规格。

### 2.2 SAP SuccessFactors（Position / Org Chart）

**公开能力：**

- **Position Organization Chart**：以 **岗位（position）** 为主视图；可看占岗人、空缺（To Be Hired）、停用岗位、矩阵岗位（虚线）、effective-dated 历史/未来。  
  来源：[Navigating the Position Organization Chart](https://learning.sap.com/courses/sap-successfactors-employee-central-position-management-academy/navigating-the-position-organization-chart_e6c44680-cacf-4f6c-9e6a-03c5908643f4)
- **Leading hierarchy**：推荐以 Position Hierarchy 同步 Reporting Hierarchy；Matrix Synchronization 可把岗位矩阵关系同步到在岗人的 job relationships。  
  来源：[Configuring Position Management Settings](https://learning.sap.com/learning-journeys/configure-sap-successfactors-employee-central-core/configuring-position-management-settings_e1975254-41b7-4758-8e4c-19ad99aea088)
- 人员 Org Chart 默认可显示 matrix managers（虚线）；custom managers 需额外配置。  
  来源：[KBA 2087507](https://userapps.support.sap.com/sap/support/knowledge/en/2087507)

**启示：** 「人树」与「岗树」是两条产品线；subjex 当前 **无 Position 对象**（ADR 0001 non-goal），构建器 MVP **不要**伪装成编制系统。

### 2.3 钉钉 DingTalk / 飞书 Feishu / 企业微信 WeCom（通讯录部门树）

**共同模式（东亚协作套件主流）：**

| 能力 | 公开依据 |
| --- | --- |
| 部门树 `parent_id` / 排序 `order` | 钉钉 [更新部门 API](https://open.dingtalk.com/document/orgapp/update-a-department-v2.md)：`parent_id`、`order`、根部门不可更新、循环拒绝 |
| 拖拽改部门/排序 | 飞书人事「修改组织架构」说明支持**拖拽移动部门**（[基础设置/组织架构](https://docs.feishu.cn/article/wiki/NDRzwUiG9i3alZkalf6cpH30nFg)）；钉钉管理材料提及拖动部门更改排序（钉钉公开培训 PDF） |
| 隐藏部门 / 限制查看外部门 | 钉钉 API：`hide_dept`、`outer_dept`、`outer_dept_only_self` + permits 白名单 |
| 部门主管 | 钉钉 `dept_manager_userid_list`（须在本部门内） |
| 批量导入导出 | 飞书 Admin：[Batch export organization structure](https://www.feishu.cn/hc/en-US/articles/360049067524-admin-batch-export-organization-structure-information)；飞书「更新组织架构」走导出→改部门/岗位→导入（大批量），「修改组织架构」走小改/拖拽 |
| 通讯录可见性与第三方权限分级 | 企微 [通讯录权限体系](https://developer.work.weixin.qq.com/document/path/91143)：基本信息只读 vs 架构只读；写权限单独、多方编辑与同步约束 |
| 目录同步 | 飞书可从钉钉/企微/Entra/Okta 等同步（Admin Directory Sync 目录） |

**启示：** 对中国大陆客户，「部门树 + 拖拽 + 批量 Excel + 可见性/隐藏」是 **默认心智模型**；矩阵/岗位是加分项。

### 2.4 Microsoft Entra ID（原 Azure AD）Administrative Units

**公开能力：**

- AU 是 **管理权限范围容器**，可含 users/groups/devices；用于把 Helpdesk 等角色限制在区域/事业部。  
  来源：[Administrative units](https://learn.microsoft.com/en-us/entra/identity/role-based-access-control/administrative-units)
- **约束：AU 不能嵌套（can't be nested）**；加 group 进 AU **不会**自动把组成员纳入 AU 管理范围。

**启示：** IAM 的「组织」经常是 **扁平委派范围**，不是 org chart。subjex 已有 `OrganizationScope`（SELF / SELF_AND_DESCENDANTS…）更接近「按树委派」；不要把 AU 模型照搬成部门树。

### 2.5 Okta

**公开能力：**

- Org = 租户级容器；**Groups 扁平**，官方明确 **不支持 nested groups**。  
  来源：[Okta Data Model](https://developer.okta.com/docs/concepts/okta-data-model/)、[Manage groups](https://help.okta.com/en-us/content/topics/users-groups-profiles/usgp-groups-main.htm)
- 委派：用 group 边界 + Group Administrator 做分部 IT 隔离。  
  来源：[Guidance for structuring Okta groups](https://help.okta.com/en-us/Content/topics/security/administrators-structure-groups.htm)

**启示：** 纯 IAM 产品常 **用组模拟部门**；subjex 已有真树（CONTAINS），构建器应发挥树优势，同时保留「组/角色」与树的边界（关系 ≠ 授权，见 ADR 0001）。

### 2.6 Personio / BambooHR（中端 HRIS org chart）

**Personio（官方帮助，2026 更新页）：**

- Org chart 可按 **People / Departments / Teams** 排布；搜索、zoom、Spotlight（含虚线 additional supervisors）、Highlight、导出 JPEG/PNG/SVG/PDF；权限不足时用 **placeholder cards** 保链路。  
  来源：[Overview of the Org chart](https://support.personio.de/hc/en-us/articles/360017540757-Overview-of-the-Org-chart)
- 卡片属性可用拖拽**重排显示字段**（文档描述为属性顺序，不是改汇报结构）。

**BambooHR：**

- 内置 org chart 由 **manager 字段**驱动；支持搜索、层级、导出等（帮助文章需登录，Marketplace 另有拖拽规划类 OrgChart 集成）。  
  公开入口：[View Company Directory and Org Chart](https://help.bamboohr.com/s/article/587751)、[OrgChart marketplace](https://www.bamboohr.com/integrations/listings/orgchart)

**启示：** 中端产品常把 **只读可视化 chart** 与 **可写管理台（表格/表单）** 分开；「拖拽改结构」要么在管理树，要么在专门的 planning 工具。

## 3. 模式清单 → 需求解读 / Modes → requirements

| 模式 | 主流需求 | subjex 现状（O1–O8） | 构建器建议 |
| --- | --- | --- | --- |
| Tree hierarchy | 父子、防环、排序、禁用子树策略 | `OrganizationRelation(CONTAINS)` 无环；无 sibling `order` | **Must**：树+拖拽改 CONTAINS |
| Matrix / dual reporting | 虚线经理、多人协作汇报 | ADR 0001 **non-goal**：matrix；仅 CONTAINS | **Later**：新 `relation_kind`（见 ADR 0006 草案） |
| Cost center vs legal entity | 多棵平行树/多类型组织 | 无 `organization_kind`；单 CONTAINS 图 | **Later**：类型标签或多图命名空间 |
| Position vs person | 空缺岗、编制、岗→岗 | 无 Position；Membership=人↔组织 | **Later / 非 MVP** |
| Temporary project orgs | 项目组、起止时间 | Membership/Relation 有 state，无 effective dating 产品化 | **Should/Later**：临时组织 + ENDED |
| Dept disable / merge / split | 停用、合并、拆分 | 有 organization state；无 merge/split 事务 API | **Must**：disable；**Should**：merge/split 向导 |
| Batch import | Excel 导出改导入 | 无标准批量管线 | **Should**（飞书心智） |
| Search / locate | 按名定位并展开路径 | 控制台表格过滤弱 | **Must** |
| Permission-by-org-scope | 只能管本部门及下级 | `OrganizationScope` + `org.read`/`org.write` | **Must**：构建器尊重 scope |

## 4. 主流 UX 模式 / Common UX

| UX | 谁在用 | 用途 |
| --- | --- | --- |
| **缩进树 / indented tree** | 钉钉/飞书/企微管理台 | 高频 CRUD、拖拽改挂载与排序 |
| **Org chart canvas** | Workday、SF、Personio、BambooHR | 导航、汇报可视化、演示导出 |
| **Drag reparent** | 飞书小改、Workday Org Studio、钉钉排序/企微拖成员 | 改 parent；需防环与权限 |
| **Multi-select move** | HR 批量改部门（飞书批量操作） | 多节点一次挂到新父 |
| **Preview impact** | Workday Org Studio / Mass Actions | 影响人数、子树、角色/授权范围 |
| **Audit of moves** | Workday 审计域；飞书 Admin logs；subjex 已有审计 API | 谁在何时把谁从 A 移到 B |

**推荐组合（调研结论）：** **左侧缩进树（主编辑面）+ 可选右侧/全屏 canvas（只读或弱编辑）** — 即 tree+canvas hybrid；编辑以树为准，canvas 服务定位与讲解。

## 5. 负面反馈与可改进点 / Pain points（改进靶）

仅引用可核验公开材料；社区帖作「风险」标注：

1. **大重组工具学习与校验成本高** — Workday Org Studio 需单独安全域与 Mass Action 流水线；社区帖反馈复杂场景校验/排序困难（[r/workday Org Studio](https://www.reddit.com/r/workday/comments/1fdc8ze/org_studio_pros_cons/) 等，**非官方**）。  
   → 改进：MVP 先做 **单次移动 + 影响预览 + 两步确认**，不做完整 Studio。
2. **嵌套过深 / 自定义层级过多影响性能** — Workday 文档明确警告大量 custom/nested hierarchies 影响 eligibility/performance。  
   → 改进：深度软上限、懒加载子树、closure 查询可测门禁。
3. **看图与改结构割裂或误操作** — 中端产品常只读 chart；用户拖拽期望「立刻生效」却误伤授权。  
   → 改进：拖拽默认 **review→confirm**；展示受影响 Membership 数与 OrgScope 变化。
4. **可见性与管理权限混淆** — 企微/钉钉区分「谁能看见通讯录」vs「谁能改架构」；Entra AU 只管管理权限不管浏览。  
   → 改进：构建器只做 **管理面**；通讯录可见性另议，勿绑死 Membership。
5. **岗位空缺与人树混谈** — SF/Workday 用 Position 表达空编；无岗位时硬做「空节点」易脏数据。  
   → 改进：MVP 不引入空岗；用 DISABLED 组织或后续 Position。
6. **批量导入无预检** — 飞书大批量走导出再导入；失败成本高。  
   → 改进：dry-run、环检测、孤儿成员报告。

## 6. 与 subjex 控制台现状差距 / Gap vs today

当前 `/org`（`org-console.tsx`）：租户选择、组织/成员 **表格**、表单 PUT（parent 字段）、启停、两步确认。

**缺：** 树视图、画布、拖拽、搜索定位路径、批量、移动影响预览、兄弟排序、矩阵边、组织类型。

**已有可复用：** Organization API、`CONTAINS` 防环、`OrganizationScope`、审计事件、两步确认 UX。

## 7. 来源索引 / Sources

1. Workday Organizations setup — https://doc.workday.com/admin-guide/en-us/manage-workday/organizations/ivq1568755825744.html  
2. Workday Org Charts — https://doc.workday.com/admin-guide/en-us/manage-workday/organizations/organization-charts/lah1480533274925.html  
3. Workday Org Studio — https://doc.workday.com/admin-guide/en-us/manage-workday/organizations/org-studio-and-mass-actions/prq1551821683080.html  
4. Workday Org Management datasheet PDF — https://www.workday.com/content/dam/web/en-us/documents/datasheets/organization-management-in-workday-datasheet-en-us.pdf  
5. SAP SF Position Org Chart — https://learning.sap.com/courses/sap-successfactors-employee-central-position-management-academy/navigating-the-position-organization-chart_e6c44680-cacf-4f6c-9e6a-03c5908643f4  
6. SAP SF Position Management Settings — https://learning.sap.com/learning-journeys/configure-sap-successfactors-employee-central-core/configuring-position-management-settings_e1975254-41b7-4758-8e4c-19ad99aea088  
7. DingTalk update department — https://open.dingtalk.com/document/orgapp/update-a-department-v2.md  
8. Feishu org basics (拖拽) — https://docs.feishu.cn/article/wiki/NDRzwUiG9i3alZkalf6cpH30nFg  
9. Feishu batch export — https://www.feishu.cn/hc/en-US/articles/360049067524-admin-batch-export-organization-structure-information  
10. WeCom contact permission — https://developer.work.weixin.qq.com/document/path/91143  
11. Entra Administrative units — https://learn.microsoft.com/en-us/entra/identity/role-based-access-control/administrative-units  
12. Okta data model / groups — https://developer.okta.com/docs/concepts/okta-data-model/ 、https://help.okta.com/en-us/content/topics/users-groups-profiles/usgp-groups-main.htm  
13. Personio Org chart — https://support.personio.de/hc/en-us/articles/360017540757-Overview-of-the-Org-chart  
14. BambooHR org chart help — https://help.bamboohr.com/s/article/587751  
15. subjex ADR 0001 — `docs/adr/0001-o1-organization-ontology.md`  
16. subjex ER — `docs/ontology/er-and-cardinality.md`

