import { describe, expect, it } from "vitest";
import { formatCellValue, rowSummary } from "@/lib/list-table";

describe("list-table helpers — 列表表辅助", () => {
  it("prefers title then resultSummary then valuesJson — 摘要优先级", () => {
    expect(rowSummary({ title: "Hello", noteId: "1" }, "1")).toBe("Hello");
    expect(rowSummary({ resultSummary: "ok", noteId: "1" }, "1")).toBe("ok");
    expect(rowSummary({ valuesJson: '{"a":1}', noteId: "1" }, "1")).toBe('{"a":1}');
    expect(rowSummary({ noteId: "1" }, "1")).toBe("1");
  });

  it("formats cells — 格式化单元格", () => {
    expect(formatCellValue(null)).toBe("");
    expect(formatCellValue(42)).toBe("42");
    expect(formatCellValue({ a: 1 })).toBe('{"a":1}');
  });
});
