import { describe, expect, it } from "vitest";
import { itemsFromBody, platformPathFromApi } from "@/lib/page-flow";

describe("page-flow helpers — 页面流程辅助", () => {
  it("strips the /api/v1 prefix — 去掉 /api/v1 前缀", () => {
    expect(platformPathFromApi("/api/v1/forms/endpoint-publication/submissions")).toBe(
      "forms/endpoint-publication/submissions",
    );
  });

  it("rejects non-/api/v1 paths — 拒绝非 /api/v1 路径", () => {
    expect(() => platformPathFromApi("/forms/x")).toThrow(/api\/v1/);
  });

  it("reads wrapped or bare arrays — 读取包裹或裸数组", () => {
    expect(itemsFromBody({ submissions: [{ submissionId: "a" }] }, "submissions")).toEqual([
      { submissionId: "a" },
    ]);
    expect(itemsFromBody([{ itemId: "1" }], null)).toEqual([{ itemId: "1" }]);
    expect(itemsFromBody({}, "submissions")).toEqual([]);
  });
});
