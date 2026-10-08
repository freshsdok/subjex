import { describe, expect, it } from "vitest";
import {
  isGenericRecordsCollectionPath,
  itemsFromBody,
  platformPathFromApi,
  recordDetailPlatformPath,
} from "@/lib/page-flow";

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

  it("detects generic /records collection paths — 识别通用 /records 集合路径", () => {
    expect(isGenericRecordsCollectionPath("/api/v1/entities/demo-ticket/records")).toBe(true);
    expect(isGenericRecordsCollectionPath("/api/v1/entities/service-note/records")).toBe(true);
    expect(isGenericRecordsCollectionPath("/api/v1/entities/service-note/notes")).toBe(false);
    expect(isGenericRecordsCollectionPath("/api/v1/forms/service-note/submissions")).toBe(false);
  });

  it("builds platform path for one record — 拼出单条记录平台路径", () => {
    expect(recordDetailPlatformPath("/api/v1/entities/demo-ticket/records", "t-1")).toBe(
      "entities/demo-ticket/records/t-1",
    );
    expect(recordDetailPlatformPath("/api/v1/entities/demo-ticket/records", "a/b")).toBe(
      "entities/demo-ticket/records/a%2Fb",
    );
  });
});
