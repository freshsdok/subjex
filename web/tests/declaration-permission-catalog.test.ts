import { describe, expect, it } from "vitest";
import {
  DECLARATION_PERMISSION_CATALOG,
  DEFAULT_DECLARATION_PERMISSION,
  isKnownDeclarationPermission,
} from "@/lib/declaration-permission-catalog";
import { BUSINESS_TABLE_PERMISSION_OPTIONS } from "@/lib/business-table-wizard";

describe("declaration-permission-catalog — 声明权限只选目录", () => {
  it("includes page.read and matches business-table options — 含 page.read 且与业务表选项一致", () => {
    expect(DECLARATION_PERMISSION_CATALOG).toContain("page.read");
    expect(DEFAULT_DECLARATION_PERMISSION).toBe("page.read");
    expect(BUSINESS_TABLE_PERMISSION_OPTIONS).toEqual(DECLARATION_PERMISSION_CATALOG);
    expect(isKnownDeclarationPermission("page.read")).toBe(true);
    expect(isKnownDeclarationPermission("invented.perm")).toBe(false);
  });

  it("is pick-only (no free invent) — 只选不造", () => {
    for (const name of DECLARATION_PERMISSION_CATALOG) {
      expect(name).toMatch(/^[a-z]+(\.[a-z]+)+$/);
    }
    expect(DECLARATION_PERMISSION_CATALOG.length).toBeGreaterThanOrEqual(1);
  });
});
