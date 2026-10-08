import { describe, expect, it } from "vitest";
import {
  isReservedTenant,
  normalizeTenantAdminId,
  normalizeTenantDisplayName,
} from "@/lib/tenant-admin";

describe("tenant-admin — 租户管理辅助", () => {
  it("normalizes ids and names — 规范化标识与显示名", () => {
    expect(normalizeTenantAdminId("  tenant-north  ")).toBe("tenant-north");
    expect(normalizeTenantAdminId("")).toBeNull();
    expect(normalizeTenantAdminId("a b")).toBeNull();
    expect(normalizeTenantAdminId("a/b")).toBeNull();
    expect(normalizeTenantDisplayName("  North  ")).toBe("North");
    expect(normalizeTenantDisplayName("   ")).toBeNull();
  });

  it("recognizes reserved platform tenant — 识别保留租户", () => {
    expect(isReservedTenant("platform")).toBe(true);
    expect(isReservedTenant("tenant-north")).toBe(false);
  });
});
