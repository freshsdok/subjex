// Page-block catalog (console mirror) — 页面积木目录（控制台镜像，与 page-block-catalog.yaml 手工对齐）。

export type PageBlockCatalogEntry = {
  id: string;
  titleEn: string;
  titleZh: string;
  summaryEn: string;
  summaryZh: string;
  status: "runtime" | "placeholder" | "runtime-thin";
};

/** First-wave catalog — 首波积木目录（id 顺序与 YAML 一致）。 */
export const PAGE_BLOCKS_CATALOG: readonly PageBlockCatalogEntry[] = [
  {
    id: "ListTable",
    titleEn: "List table",
    titleZh: "列表表",
    summaryEn: "Renders declared list rows with a detail link per id.",
    summaryZh: "按声明渲染列表行，并为每行提供详情链接。",
    status: "runtime",
  },
  {
    id: "FormFields",
    titleEn: "Form fields (from entity/form)",
    titleZh: "表单字段（来自实体/表单）",
    summaryEn: "Renders form/entity fields (text/number/date/enum/boolean; userRef/orgRef via pickers). Used by declared submit.",
    summaryZh: "按表单/实体字段渲染控件（文本/数字/日期/枚举/布尔；userRef/orgRef 走选人/选部门）。声明式提交页使用。",
    status: "runtime",
  },
  {
    id: "DetailReadonly",
    titleEn: "Detail (read-only)",
    titleZh: "只读详情",
    summaryEn: "Key/value view of one record.",
    summaryZh: "单条记录的键值只读展示。",
    status: "runtime",
  },
  {
    id: "Section",
    titleEn: "Section",
    titleZh: "分区",
    summaryEn: "Card-style layout wrapper for grouping blocks; optional title/hint.",
    summaryZh: "卡片式分区包裹；可选标题与提示。",
    status: "runtime",
  },
  {
    id: "Tabs",
    titleEn: "Tabs",
    titleZh: "标签页",
    summaryEn: "Client tablist; detail can show Fields/Raw when detail.blocks includes Tabs.",
    summaryZh: "客户端标签栏；detail.blocks 含 Tabs 时可展示字段/原始 JSON。",
    status: "runtime",
  },
  {
    id: "SubmitBar",
    titleEn: "Submit bar",
    titleZh: "提交栏",
    summaryEn: "Action bar for review/confirm/cancel on submit pages.",
    summaryZh: "提交页的核对/确认/取消操作栏。",
    status: "runtime",
  },
  {
    id: "UserPicker",
    titleEn: "User picker",
    titleZh: "选人",
    summaryEn: "Live thin-org memberships when tenantId is set; otherwise enabled subject-id text input.",
    summaryZh: "有 tenantId 时拉薄组织成员；否则可编辑主体 id 文本框。",
    status: "runtime-thin",
  },
  {
    id: "OrgPicker",
    titleEn: "Org unit picker",
    titleZh: "选部门",
    summaryEn: "Live thin-org units when tenantId is set; otherwise enabled text input.",
    summaryZh: "有 tenantId 时拉薄组织单元；否则可编辑文本框。",
    status: "runtime-thin",
  },
  {
    id: "FlowSorter",
    titleEn: "Flow sorter / router",
    titleZh: "流程分拣器",
    summaryEn: "Navigation chip row for declared list/new (and concrete detail) paths; show when list.blocks includes FlowSorter.",
    summaryZh: "声明式列表/新建（及具体详情）路径的导航芯片行；list.blocks 含 FlowSorter 时显示。",
    status: "runtime",
  },
] as const;

export const PAGE_BLOCK_IDS: readonly string[] = PAGE_BLOCKS_CATALOG.map((entry) => entry.id);

export const PAGE_BLOCK_ID_SET: ReadonlySet<string> = new Set(PAGE_BLOCK_IDS);

export function isPageBlockId(value: string): boolean {
  return PAGE_BLOCK_ID_SET.has(value);
}
