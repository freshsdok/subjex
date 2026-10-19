# 提案 — 基于现有上下文体系的 UI 权限配置（页面 / 菜单 / 按钮）

- **状态：** **Accepted-ready**（2026-10-18 Asia/Shanghai；五项默认 + 可视化编辑器已锁死；正式 ADR：[`docs/adr/0010-ui-permission-manifest.md`](../adr/0010-ui-permission-manifest.md)）
- **日期：** 2026-10-18（Asia/Shanghai）
- **关联：** [`docs/operator-permissions.md`](../operator-permissions.md)、[`docs/authz/cedar-or-casbin-adr.md`](../authz/cedar-or-casbin-adr.md)、[`docs/ontology/o4-authorization.md`](../ontology/o4-authorization.md)、[`docs/adr/0001-o1-organization-ontology.md`](../adr/0001-o1-organization-ontology.md)、[`docs/adr/0007-client-triad.md`](../adr/0007-client-triad.md)、声明双轨（Z3–Z5）、`web/src/app/(console)/console-sections.ts`
- **进度：** [`docs/authz/ui-permission-progress.md`](../authz/ui-permission-progress.md) · [`web/PROGRESS.md`](../../web/PROGRESS.md) § UI permission Manifest

---

## 1. 结论

**可以，且已拍板。** 在现有 **具名权限目录 + PolicyEngine（Cedar 默认）+ PolicyContext（tenant / OrganizationScope）** 之上，增加：

1. **Git YAML Manifest**（真源 / schema）— 菜单 / 页面 / 按钮 → `permission` 或 `anyOf`
2. **运行时 `GET` 投影** — 前端消费清单；与 `me.permissions` 求交做 UX
3. **控制台可视化编辑器** — 改 UI 绑定（非改谁拥有什么权限）；草稿 → 晋升写回 Manifest（对齐声明双轨）

后端继续强制校验；FE 永不作为安全边界。

---

## 2. 已锁定决策（原开放项全部关闭）

| # | 题 | 锁定 |
| --- | --- | --- |
| 1 | 声明载体 | **A — Git YAML Manifest** 为 schema / 可克隆真源 |
| 2 | 无权限菜单 UX | **置灰可见**（不隐藏）；`title` 写明缺权限 |
| 3 | OrgScope | **3a** — 第一波 Manifest **仅** `permission` / `anyOf`；不写 `orgScopeMode` |
| 4 | 按钮缺权 | **disable**（保留占位） |
| 5 | 清单下发 | **5a** — 运行时 `GET /api/v1/ui-permissions?client=…` |
| **+** | 可视化编辑器 | **要做** — 控制台编辑 UI 绑定；见 §5（覆盖原「第一波不做在线编辑器」非目标，**仅限 UI 绑定**） |

草案默认（A / 置灰 / 3a / disable / 5a）全部采纳；另加编辑器。

---

## 3. 现状盘点（摘要）

完整盘点见 ADR 0010 § Inventory。要点：

- 权限真源：`OperatorPermission` + SQL；判定：`PolicyEngine` / `AccessChecker` + tenant grant + OrganizationScope（API 竖切已有）。
- 控制台菜单：`console-sections.ts` 硬编码；layout 置灰不隐藏。
- 页/按钮：散落 `me.permissions.has`；声明 YAML 顶层 `permission:`；capability YAML 另轨。
- 声明双轨已存在：`declaration_revision` 草稿 → `declaration.promote` → 内部 git YAML。
- Cedar ADR 曾写「不上线权限/角色编辑器」— **本提案不开放角色/Cedar 策略在线编辑**；仅开放 **UI 绑定** 可视化编辑，且 permission **只选目录**。

---

## 4. 目标与分层

```
Git YAML Manifest (classpath baseline + promoted overlay)
        │
        ├─◄── 可视化编辑器：DRAFT 保存 → promote（declaration.*）
        │
        ▼
GET /api/v1/ui-permissions  （有效清单；可带 allowed 布尔）
        │
   ┌────┴────┐
   ▼         ▼
  FE UX     BE enforce（既有 PolicyEngine / hasAuthority）
  置灰/禁用  fail-closed 403
```

Manifest **只绑定** UI 单元 → 已有 permission；**不**表达角色授予、不写 OrgScope（本波）、不发明权限名。

### 4.1 Manifest 形状（示意）

```yaml
version: 1
client: console   # console | uni-app（uni-app 后置）
defaults:
  lockedMenuEffect: grey
  missingButtonEffect: disable
entries:
  - id: nav.org
    kind: menu
    href: /org
    permission: org.read
  - id: page.org
    kind: page
    route: /org
    permission: org.read
  - id: btn.org.writeUnit
    kind: button
    permission: org.write
  - id: nav.inbox.compose
    kind: menu
    href: /inbox/compose
    anyOf: [inbox.send.system, inbox.broadcast]
```

校验：所有 `permission` / `anyOf[]` ⊆ `OperatorPermission` 目录；未知 → 保存/晋升/加载 **fail-closed**。

---

## 5. 可视化编辑器（新增锁定）

### 5.1 范围

| 做 | 不做 |
| --- | --- |
| 编辑 **UI 绑定**（menu / page / button → permission / anyOf） | 编辑 `role_permission` / 谁拥有什么 |
| 权限 **下拉只选** `OperatorPermission` 目录 | 自由文本发明权限名 |
| 第一波：**console** client | uni-app 编辑器（后置切片） |
| 草稿可预览「置灰/禁用」效果 | 改 Cedar 策略 / 自研 DSL |

### 5.2 持久化（对齐声明双轨）

仓库已有 **draft → promote → 内部 git YAML**（`DeclarationKind` = entity|form|flow，`InternalDeclarationGit`）。

**锁定持久化：**

1. 扩展声明种类（或等价专用表，语义同双轨）：**`ui-authz`**，固定业务键按 client（如 `console`）。
2. 控制台编辑器写 **DRAFT**（需 `declaration.write`）。
3. **Promote**（需 `declaration.promote`，含既有双人确认门禁若适用）写入内部 git / 有效层，形状即 Manifest YAML。
4. 运行时有效顺序：**租户/平台 DRAFT（仅预览可选）→ PROMOTED → classpath 基线**（与声明 hot-reload 精神一致；第一波可简化为 PROMOTED > classpath，编辑器预览走 DRAFT）。
5. 审计：保存/晋升记 `OperatorActionAudit`（如 `ui_authz.save` / `ui_authz.promote`）。
6. **Export：** promote 产物可导出/检出为仓库内 YAML，保持 Git 可克隆真源。

不采用「仅 DB、永不回 YAML」；不采用自由在线改 AuthZ 引擎。

### 5.3 编辑器 UX（控制台）

- 新栏目建议：`/declarations` 高级区旁或独立 `/ui-permissions`（PC-6 定路由）；侧栏绑 `declaration.read`。
- 表格式：id / kind / href|route / permission|anyOf / effect 只读默认。
- 新增/编辑 entry；删除需确认；保存草稿 → 晋升按钮。
- 只读对照：当前 classpath 基线 vs DRAFT vs PROMOTED。

### 5.4 权限（编辑器门禁）

| 动作 | 权限 |
| --- | --- |
| `GET /api/v1/ui-permissions`（运行时清单） | 已登录即可（各页 UX 需要） |
| 打开编辑器 / 列草稿 | `declaration.read` |
| 保存草稿 | `declaration.write` |
| 晋升 | `declaration.promote` |

不新增权限名（第一波）；若日后要与业务声明隔离，再开 ADR 加 `ui.authz.*`。

---

## 6. 多前端

| 客户端 | 第一波 |
| --- | --- |
| `web/` 控制台 | Manifest + GET + **可视化编辑器** |
| `clients/uni-app` | schema 预留；壳消费 GET 后置（PC-5）；**无编辑器** |

---

## 7. 明确不做（修订后）

- 自研 AuthZ DSL / 替代 Cedar；在线编辑 Cedar 策略或角色授予。
- Manifest 第一波写入 OrgScope。
- 无权限菜单默认改为隐藏（可保留 per-entry 覆盖为后话，默认 grey）。
- 按钮缺权默认 hide。
- 构建期把清单打进前端作为唯一下发方式（GET 为真；构建生成仅可选优化）。
- uni-app 第一波编辑器与完整业务页门禁迁完。
- FE 作为安全边界。

~~原「第一波不做在线编辑器」~~ → **已撤销**，改为「只做 UI 绑定可视化编辑，不做角色/策略编辑器」。

---

## 8. 切片计划（PC）

| 切片 | 交付 | Done when |
| --- | --- | --- |
| **PC-0** | 本提案收口 + ADR 0010 Accepted + progress | ADR Accepted；无运行时代码 |
| **PC-1** | **done** Manifest schema + classpath 样例（迁入现 `console-sections`）+ 加载校验 + 单测 | 与现网栏目等价；未知 permission fail-closed |
| **PC-2** | **done** `GET /api/v1/ui-permissions?client=console`（登录；与 me 求交或返回 raw+客户端求交） | MockMvc 安全测试绿 |
| **PC-3** | **done** `web` layout 改读 GET；硬编码表降为兼容/删除真源地位 | 侧栏置灰行为与现网一致 |
| **PC-4** | **done** 1～2 个按钮改绑 button entry（inbox compose / org write） | 按钮与 API 权限名单源 |
| **PC-5** | uni-app 壳消费同一 GET schema | H5 可读清单；不克隆控制台 |
| **PC-6** | **可视化编辑器**：DRAFT CRUD UI + API；permission `<select>` | 可改绑定并保存草稿；审计 |
| **PC-7** | Promote `ui-authz` → 内部 git / 有效层；export YAML；热读 PROMOTED | 晋升后 GET 反映新绑定；克隆路径可导出 |

依赖：PC-6 可在 PC-2 之后与 PC-3/4 **并行**（编辑器写草稿不依赖侧栏已迁完）；PC-7 依赖 PC-6 + 现有 promote 设施。

**下一实现切片：PC-5 / PC-6**（PC-4 已收工）。

---

## 9. 成功标准

- 新增控制台栏目：改 Manifest（编辑器或 YAML）+ phrases，无需改 layout 门禁逻辑。
- 权限字符串非目录内 → 不能保存/晋升/加载。
- 编辑器不能授予角色；只能改绑定。
- FE/BE 对同一 id 使用同一 permission；缺 BE 的「仅 FE 门禁」视为缺陷。

---

## 10. 中文摘要（用户）

已拍板：YAML Manifest 真源、菜单置灰、第一波不绑 OrgScope、按钮 disable、运行时 GET；并增加控制台 **可视化编辑器**（只改菜单/页/按钮绑定，权限下拉只选目录，草稿→晋升回 YAML，对齐声明双轨）。不做角色/Cedar 在线编辑。PC-4 已收工；下一步 PC-5（uni-app）或 PC-6（编辑器）。
