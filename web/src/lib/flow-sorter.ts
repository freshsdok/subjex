// Flow sorter helpers — 流程分拣器：从声明路径拼出默认分支选项（缺路径则省略，失败关闭）。

export type FlowSorterOption = {
  key: string;
  label: string;
  href: string;
};

/** Paths + titles from a declared flow summary — 声明式流程摘要里的路径与标题。 */
export type FlowSorterSummary = {
  listPath?: string | null;
  submitPath?: string | null;
  /** Optional detail path; patterns with `{id}` are omitted until resolved. */
  detailPath?: string | null;
};

export type FlowSorterLabels = {
  list: string;
  newItem: string;
  detail?: string;
};

/**
 * Build default sorter options (List / New / Detail when concrete).
 * Missing or blank paths → omit that option. Detail paths containing `{` are omitted.
 * 默认分拣选项；缺路径省略；含 `{` 的详情模板暂不列。
 */
export function buildDefaultFlowSorterOptions(
  summary: FlowSorterSummary,
  labels: FlowSorterLabels,
): FlowSorterOption[] {
  const options: FlowSorterOption[] = [];
  const list = trimPath(summary.listPath);
  if (list) {
    options.push({ key: "list", label: labels.list, href: list });
  }
  const submit = trimPath(summary.submitPath);
  if (submit) {
    options.push({ key: "new", label: labels.newItem, href: submit });
  }
  const detail = trimPath(summary.detailPath);
  if (detail && !detail.includes("{") && labels.detail) {
    options.push({ key: "detail", label: labels.detail, href: detail });
  }
  return options;
}

function trimPath(value: string | null | undefined): string | null {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  return trimmed === "" ? null : trimmed;
}
