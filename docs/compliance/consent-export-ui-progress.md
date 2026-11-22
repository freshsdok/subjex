# 同意与导出页面进度 / Consent and export UI progress

- 日期：2026-11-22（Asia/Shanghai）
- 基线：GitHub `main` `8c19a7c`（CI 全绿）；本地 tip 开工于 `eaae327`（与 `8c19a7c` 同树）
- 依据：ADR [`0020`](../adr/0020-consent-and-export-hooks.md)、[`wp8-consent-export-progress.md`](wp8-consent-export-progress.md)
- 范围：只补平台公共界面，不改医院/预约/医疗/签证/保险，也不重做 WP8 后端

本笔记盘点已有接口与权限，并记下控制台 / uni-app 的界面计划。WP8 关闭时明确未做页面；本轨把已有接口接到操作员控制台（`web/`）和终端 uni-app（`clients/uni-app`）。

## 盘点 / Inventory（2026-11-22）

### 已有后端（复用，不重设计）

| 能力 | 路径与实现 | 权限 / 门禁 | 界面现状 |
| --- | --- | --- | --- |
| 终端同意：列出当前、授予、按目的撤回、历史 | `GET/POST /api/v1/end-user/consents`，`POST .../{purposeKey}/withdraw`，`GET .../history`（`EndUserConsentEndpoint`） | 终端本人；租户失败关闭；不能指定他人 | uni-app **无**同意页 |
| 同意台账 | `ConsentPort` / `JdbcConsentPort`（`consent_event` 只追加，`consent_current` 投影） | 内核不存法律正文；撤销按目的；`require` 在给出版本时必须完全一致 | 无操作员查询接口 |
| 终端导出：申请、查看自己的申请、短时下载链接 | `GET/POST /api/v1/end-user/exports`，`GET .../{id}`，`POST .../{id}/download-link` | 只针对本人；产物默认 P7D 过期 | uni-app **无**导出页 |
| 操作员导出：代申请、队列、批准、驳回、生成、短时链接 | `GET/POST /api/v1/exports`，`.../{id}/approve\|reject\|generate\|download-link`（`OperatorExportEndpoint`） | `export.request` / `export.approve`；禁止自己批准自己；生成与操作员下载复用 `export.approve` | 控制台队列页已落地（第 2 刀）；本切片未做「代申请」表单 |
| 审批钩子 | `workflows/data-export.workflow.yaml` + `ExportApprovalGate` | 未批准不可生成；敏感级别 NORMAL/SENSITIVE | — |
| 易混淆的既有能力（不要挪用） | OAuth 客户端同意：`web/src/app/(console)/oauth/consent`；租户 ZIP：`POST /api/v1/tenants/{id}/export`；问卷 CSV；审计 NDJSON | 各自原权限 | 已有页面/接口，与本轨无关 |

操作员侧此前 **没有** 同意记录的只读查询，也没有 `consent.read` 权限。控制台侧栏与 `ui-authz/console-ui.yaml` 均未挂同意或导出菜单。

### 本切片已落地（第 1 刀）

1. 新增操作员只读查询：`GET /api/v1/consents`（当前有效同意）、`GET /api/v1/consents/history`（含撤回的历史）。可按用户编号、用途、时间筛选。无授予/撤回接口。
2. 新增权限 `consent.read`，授予 `platform-operator` 与 `platform-reader`。
3. 控制台新增「同意记录」页（`/consents`）：只读列表 + 筛选；文案面向非开发人员；不能代用户同意或撤回。
4. uni-app 同意/导出页、控制台导出审批队列：**未做**，留给后续切片。

### 本切片已落地（第 2 刀）

1. 控制台新增「导出申请」页（`/exports`）：按租户列出待审批 / 已批准 / 已驳回 / 已生成 / 已过期；展示用户编号、申请人、范围、状态、过期时间。
2. 持有 `export.approve` 的操作员可批准或驳回（本人提交的申请只显示提示，不能点批准/驳回）；已批准后可调用现有 `POST /api/v1/exports/{id}/generate` 生成文件；下载只签发并打开现有短时签名链接，不另开文件通道。
3. 菜单与页面门禁为 `export.request` 或 `export.approve` 任一即可进入；审批/生成/下载按钮走 `btn.exports.approve`（`export.approve`）。未改后端生命周期，也未做操作员「代申请」表单。
4. uni-app 同意页与导出页：**仍未做**。

## 界面计划 / UI plan

### 控制台（操作员，`web/`）

- **同意记录（第 1 刀，已落地）**：在当前业务租户内查询某用户（或按用途、时间筛选）的同意与撤回。只读。操作员不能代为同意或撤回。菜单需 `consent.read`，与 Manifest `nav.consents` / `page.consents` 对齐。
- **导出申请队列（第 2 刀，已落地）**：列出待审批 / 已批准 / 已驳回 / 已生成 / 已过期；展示申请人、状态、过期时间；批准或驳回（不能审自己的申请）；已批准后可触发生成；下载只走现有短时签名链接，不直接暴露原始文件。菜单为 `export.request` 或 `export.approve` 任一即可进入，批准/生成/下载按钮另做 `export.approve` 门禁。

### uni-app（终端用户，H5 + 微信小程序）

- **同意页（下一刀）**：列出当前用途与文本版本，允许同意或撤回；用语清楚、面向普通人；走现有 `/api/v1/end-user/consents`。平台不提供法律正文，用途键与版本由租户/业务提供——页面需说明「请确认你正在同意的文本版本」，并复用 WP7 的登录与导航（资料 / 站内信 / 文件）。
- **导出页（再下一刀）**：提交申请、查看状态；就绪后获取下载链接并尊重过期时间。申请人看不到批准按钮。

导航与构建：保持 HBuilderX 兼容，H5 与 `mp-weixin` 可 `vue-tsc` / 构建通过。不另开第三套终端前端。

## 后续切片 / Remaining slices in this backlog item

1. uni-app 同意页（列出当前用途、同意与撤回）。
2. uni-app 导出页（申请、状态、到期前的下载链接）。
