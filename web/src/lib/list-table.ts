// ListTable helpers — 列表表辅助：行摘要与列取值，供组件与单测共用。

export type ListRow = Record<string, unknown>;

// Picks a human summary when columns are not declared.
// 未声明列时挑一条可读摘要。
export function rowSummary(row: ListRow, id: string): string {
  if (typeof row.title === "string" && row.title !== "") return row.title;
  if (typeof row.resultSummary === "string" && row.resultSummary !== "") return row.resultSummary;
  if (typeof row.valuesJson === "string" && row.valuesJson !== "") return row.valuesJson;
  return id;
}

export function formatCellValue(value: unknown): string {
  if (value == null) return "";
  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") {
    return String(value);
  }
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}
