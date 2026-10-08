import { describe, expect, it } from "vitest";
import {
  CLASSPATH_SAMPLE_KEYS,
  canApplyMigration,
  canOfferDeclarationMigrate,
  canOfferDeclarationPromote,
  canReviewMigration,
  declarationMigrateEnqueueBody,
  declarationPromoteRequestBody,
  declarationTenantCookieName,
  declarationTenantCookieWrite,
  isDeclarationKind,
  mergeDraftAndClasspathKeys,
  migrationBlocksPromote,
  normalizeDeclarationKind,
  parseDeclarationTenantCookie,
  templateYamlFor,
} from "@/lib/declaration-draft";

describe("declaration-draft — 声明草稿辅助", () => {
  it("parses tenant cookie — 解析租户 cookie", () => {
    expect(declarationTenantCookieName).toBe("subjex_declaration_tenant");
    expect(parseDeclarationTenantCookie(undefined)).toBe("");
    expect(parseDeclarationTenantCookie("")).toBe("");
    expect(parseDeclarationTenantCookie("  tenant-north  ")).toBe("tenant-north");
    expect(parseDeclarationTenantCookie(encodeURIComponent("tenant-a"))).toBe("tenant-a");
  });

  it("validates kind — 校验种类", () => {
    expect(isDeclarationKind("entity")).toBe(true);
    expect(isDeclarationKind("form")).toBe(true);
    expect(isDeclarationKind("flow")).toBe(true);
    expect(isDeclarationKind("page")).toBe(false);
    expect(normalizeDeclarationKind(" ENTITY ")).toBe("entity");
    expect(normalizeDeclarationKind("nope")).toBeNull();
    expect(normalizeDeclarationKind(undefined)).toBeNull();
  });

  it("merges draft keys with classpath hints — 合并草稿与 classpath 提示", () => {
    const rows = mergeDraftAndClasspathKeys(["demo-ticket"], "entity");
    expect(rows.find((r) => r.key === "demo-ticket")).toEqual({ key: "demo-ticket", hasDraft: true });
    expect(rows.find((r) => r.key === "service-note")).toEqual({ key: "service-note", hasDraft: false });
    expect(CLASSPATH_SAMPLE_KEYS.form).toContain("config-override");
  });

  it("builds cookie write and templates — 生成 cookie 与模板", () => {
    expect(declarationTenantCookieWrite("tenant-x")).toContain("subjex_declaration_tenant=tenant-x");
    expect(templateYamlFor("entity", "my-thing")).toContain("entityKey: my-thing");
    expect(templateYamlFor("form", "my-thing")).toContain("formKey: my-thing");
    expect(templateYamlFor("flow", "my-thing")).toContain("flowKey: my-thing");
  });

  it("gates promote affordance — 晋升入口门闩", () => {
    expect(
      canOfferDeclarationPromote({
        canPromote: true,
        tenantId: "tenant-a",
        selectedKey: "demo-ticket",
        loadedRevision: 2,
      }),
    ).toBe(true);
    expect(
      canOfferDeclarationPromote({
        canPromote: false,
        tenantId: "tenant-a",
        selectedKey: "demo-ticket",
        loadedRevision: 2,
      }),
    ).toBe(false);
    expect(
      canOfferDeclarationPromote({
        canPromote: true,
        tenantId: "  ",
        selectedKey: "demo-ticket",
        loadedRevision: 2,
      }),
    ).toBe(false);
    expect(
      canOfferDeclarationPromote({
        canPromote: true,
        tenantId: "tenant-a",
        selectedKey: null,
        loadedRevision: 2,
      }),
    ).toBe(false);
    expect(
      canOfferDeclarationPromote({
        canPromote: true,
        tenantId: "tenant-a",
        selectedKey: "demo-ticket",
        loadedRevision: null,
      }),
    ).toBe(false);
  });

  it("builds promote request body — 组装晋升请求正文", () => {
    expect(declarationPromoteRequestBody(null)).toBeUndefined();
    expect(declarationPromoteRequestBody(undefined)).toBeUndefined();
    expect(declarationPromoteRequestBody(3)).toEqual({ revision: 3 });
  });
});

describe("declaration-draft migration helpers — 迁移辅助", () => {
  it("gates migrate affordance to entity — 仅 entity 可迁表", () => {
    expect(
      canOfferDeclarationMigrate({
        canMigrate: true,
        kind: "entity",
        tenantId: "tenant-a",
        selectedKey: "demo-ticket",
      }),
    ).toBe(true);
    expect(
      canOfferDeclarationMigrate({
        canMigrate: true,
        kind: "form",
        tenantId: "tenant-a",
        selectedKey: "demo-ticket",
      }),
    ).toBe(false);
    expect(
      canOfferDeclarationMigrate({
        canMigrate: false,
        kind: "entity",
        tenantId: "tenant-a",
        selectedKey: "demo-ticket",
      }),
    ).toBe(false);
    expect(
      canOfferDeclarationMigrate({
        canMigrate: true,
        kind: "entity",
        tenantId: "  ",
        selectedKey: "demo-ticket",
      }),
    ).toBe(false);
    expect(
      canOfferDeclarationMigrate({
        canMigrate: true,
        kind: "entity",
        tenantId: "tenant-a",
        selectedKey: null,
      }),
    ).toBe(false);
  });

  it("builds enqueue body — 组装入队正文", () => {
    expect(declarationMigrateEnqueueBody({ revision: 2, sqlText: "  ALTER TABLE t ADD c INT  " })).toEqual({
      revision: 2,
      sqlText: "ALTER TABLE t ADD c INT",
    });
    expect(declarationMigrateEnqueueBody({ revision: null, sqlText: "CREATE TABLE t (id INT)" })).toEqual({
      sqlText: "CREATE TABLE t (id INT)",
    });
    expect(declarationMigrateEnqueueBody({ revision: 1, sqlText: "   " })).toBeNull();
  });

  it("gates review/apply by status — 按状态门闩审阅/执行", () => {
    expect(canReviewMigration("PENDING")).toBe(true);
    expect(canReviewMigration("REVIEWED")).toBe(false);
    expect(canApplyMigration("REVIEWED")).toBe(true);
    expect(canApplyMigration("PENDING")).toBe(false);
    expect(migrationBlocksPromote("PENDING")).toBe(true);
    expect(migrationBlocksPromote("FAILED")).toBe(true);
    expect(migrationBlocksPromote("APPLIED")).toBe(false);
    expect(migrationBlocksPromote("CANCELLED")).toBe(false);
  });
});
