import { describe, expect, it } from "vitest";
import {
  isOrgDisabledState,
  normalizeOptionalParentId,
  normalizeOrgId,
  orgTenantCookieName,
  orgTenantCookieWrite,
  parseOrgTenantCookie,
  toggledOrgState,
} from "@/lib/org-console";

describe("org-console — 组织控制台辅助", () => {
  it("parses tenant cookie", () => {
    expect(parseOrgTenantCookie(undefined)).toBe("");
    expect(parseOrgTenantCookie("")).toBe("");
    expect(parseOrgTenantCookie("  tenant-north  ")).toBe("tenant-north");
    expect(parseOrgTenantCookie(encodeURIComponent("tenant-a"))).toBe("tenant-a");
  });

  it("writes tenant cookie string", () => {
    const written = orgTenantCookieWrite(" tenant-x ");
    expect(written.startsWith(`${orgTenantCookieName}=`)).toBe(true);
    expect(written).toContain(encodeURIComponent("tenant-x"));
    expect(written).toContain("samesite=strict");
  });

  it("normalizes org ids", () => {
    expect(normalizeOrgId("  eng  ")).toBe("eng");
    expect(normalizeOrgId("")).toBeNull();
    expect(normalizeOrgId("a b")).toBeNull();
    expect(normalizeOrgId("a/b")).toBeNull();
  });

  it("normalizes optional parent (blank = root)", () => {
    expect(normalizeOptionalParentId("")).toBe("");
    expect(normalizeOptionalParentId("  ")).toBe("");
    expect(normalizeOptionalParentId(null)).toBe("");
    expect(normalizeOptionalParentId(" parent ")).toBe("parent");
    expect(normalizeOptionalParentId("a b")).toBeNull();
  });

  it("detects disabled and toggle target", () => {
    expect(isOrgDisabledState("DISABLED")).toBe(true);
    expect(isOrgDisabledState("disabled")).toBe(true);
    expect(isOrgDisabledState("ACTIVE")).toBe(false);
    expect(toggledOrgState("ACTIVE")).toBe("DISABLED");
    expect(toggledOrgState("DISABLED")).toBe("ACTIVE");
  });
});
