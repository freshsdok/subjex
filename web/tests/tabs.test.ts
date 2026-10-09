import { describe, expect, it } from "vitest";
import { resolveActiveTabId } from "@/lib/tabs";

describe("tabs helpers — 标签页辅助", () => {
  const tabs = [
    { id: "fields", label: "Fields" },
    { id: "raw", label: "Raw" },
  ];

  it("defaults to first tab when no request — 无请求时默认第一项", () => {
    expect(resolveActiveTabId(tabs)).toBe("fields");
    expect(resolveActiveTabId(tabs, null)).toBe("fields");
    expect(resolveActiveTabId(tabs, "")).toBe("fields");
  });

  it("keeps requested id when present — 请求 id 存在时保留", () => {
    expect(resolveActiveTabId(tabs, "raw")).toBe("raw");
  });

  it("falls back when requested id missing — 请求 id 不在列表时回退", () => {
    expect(resolveActiveTabId(tabs, "missing")).toBe("fields");
  });

  it("returns null for empty tabs — 空列表返回 null", () => {
    expect(resolveActiveTabId([])).toBeNull();
    expect(resolveActiveTabId([], "raw")).toBeNull();
  });
});
