import { describe, expect, it } from "vitest";
import {
  addTenantGrant,
  grantsEqual,
  isOperatorRoleName,
  normalizeTenantId,
  removeTenantGrant,
} from "@/lib/operator-grants";

describe("operator-grants — 租户授权辅助", () => {
  it("normalizes tenant ids and wildcard — 规范化租户与通配", () => {
    expect(normalizeTenantId("  *  ")).toBe("*");
    expect(normalizeTenantId("tenant-north")).toBe("tenant-north");
    expect(normalizeTenantId("")).toBeNull();
    expect(normalizeTenantId("a b")).toBeNull();
  });

  it("adds and refuses duplicates — 增加并拒绝重复", () => {
    expect(addTenantGrant([], "tenant-a")).toEqual({ next: ["tenant-a"] });
    expect(addTenantGrant(["tenant-a"], "tenant-a").error).toBe("duplicate");
    expect(addTenantGrant([], "  ").error).toBe("blank");
    expect(addTenantGrant([], "bad id").error).toBe("invalid");
  });

  it("removes a grant and compares lists — 移除并比较列表", () => {
    expect(removeTenantGrant(["a", "b", "*"], "b")).toEqual(["a", "*"]);
    expect(grantsEqual(["b", "a"], ["a", "b"])).toBe(true);
    expect(grantsEqual(["a"], ["a", "*"])).toBe(false);
  });

  it("recognizes known roles — 识别已知角色", () => {
    expect(isOperatorRoleName("platform-operator")).toBe(true);
    expect(isOperatorRoleName("platform-reader")).toBe(true);
    expect(isOperatorRoleName("other")).toBe(false);
  });
});
