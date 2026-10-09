import { describe, expect, it } from "vitest";
import {
  CLASSPATH_SAMPLE_KEYS,
  canApplyMigration,
  canCancelMigration,
  canGuidedReviewApplyMigration,
  canOfferDeclarationMigrate,
  canOfferDeclarationPromote,
  canReviewMigration,
  filterMigrateAuditEntries,
  isFailedMigration,
  migrationPathChecklist,
  declarationMigrateEnqueueBody,
  declarationPromoteRequestBody,
  declarationTenantCookieName,
  declarationTenantCookieWrite,
  isDeclarationKind,
  isClasspathSampleKey,
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
    expect(isClasspathSampleKey("entity", "demo-ticket")).toBe(true);
    expect(isClasspathSampleKey("entity", "repair-ticket")).toBe(false);
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
    expect(canCancelMigration("PENDING")).toBe(true);
    expect(canCancelMigration("REVIEWED")).toBe(true);
    expect(canCancelMigration("FAILED")).toBe(false);
    expect(canCancelMigration("APPLIED")).toBe(false);
    expect(canGuidedReviewApplyMigration("PENDING")).toBe(true);
    expect(canGuidedReviewApplyMigration("REVIEWED")).toBe(false);
    expect(isFailedMigration("FAILED")).toBe(true);
    expect(isFailedMigration("PENDING")).toBe(false);
    expect(migrationBlocksPromote("PENDING")).toBe(true);
    expect(migrationBlocksPromote("FAILED")).toBe(true);
    expect(migrationBlocksPromote("APPLIED")).toBe(false);
    expect(migrationBlocksPromote("CANCELLED")).toBe(false);
  });
});

describe("declaration-draft migration path + audit filter — 路径清单与审计过滤", () => {
  it("builds path chips draft→promote — 推导路径芯片", () => {
    const empty = migrationPathChecklist({
      hasDraft: false,
      revision: null,
      migrations: null,
      promotedForRevision: false,
    });
    expect(empty.map((c) => c.state)).toEqual(["current", "todo", "todo", "todo", "todo"]);

    const drafted = migrationPathChecklist({
      hasDraft: true,
      revision: 2,
      migrations: [],
      promotedForRevision: false,
    });
    expect(drafted.find((c) => c.step === "draft")?.state).toBe("done");
    expect(drafted.find((c) => c.step === "PENDING")?.state).toBe("current");

    const pending = migrationPathChecklist({
      hasDraft: true,
      revision: 2,
      migrations: [{ status: "PENDING", declarationRevision: 2 }],
      promotedForRevision: false,
    });
    expect(pending.find((c) => c.step === "PENDING")?.state).toBe("done");
    expect(pending.find((c) => c.step === "REVIEWED")?.state).toBe("current");

    const applied = migrationPathChecklist({
      hasDraft: true,
      revision: 2,
      migrations: [{ status: "APPLIED", declarationRevision: 2 }],
      promotedForRevision: false,
    });
    expect(applied.find((c) => c.step === "REVIEWED")?.state).toBe("done");
    expect(applied.find((c) => c.step === "APPLIED")?.state).toBe("done");
    expect(applied.find((c) => c.step === "promote")?.state).toBe("current");

    const failed = migrationPathChecklist({
      hasDraft: true,
      revision: 2,
      migrations: [{ status: "FAILED", declarationRevision: 2 }],
      promotedForRevision: false,
    });
    expect(failed.find((c) => c.step === "APPLIED")?.state).toBe("blocked");
    expect(failed.find((c) => c.step === "promote")?.state).toBe("blocked");
  });

  it("filters migrate audit by entity key — 按实体键过滤迁移审计", () => {
    const rows = [
      { actionName: "declaration.migrate.apply", actionTarget: "entity/demo-ticket#mig-1" },
      { actionName: "declaration.migrate.enqueue", actionTarget: "entity/other@1#mig-2" },
      { actionName: "config.override", actionTarget: "entity/demo-ticket" },
      { actionName: "declaration.migrate.review", actionTarget: "entity/demo-ticket#mig-3" },
    ];
    expect(filterMigrateAuditEntries(rows, "demo-ticket").map((r) => r.actionName)).toEqual([
      "declaration.migrate.apply",
      "declaration.migrate.review",
    ]);
    expect(filterMigrateAuditEntries(rows, "")).toEqual([]);
  });
});
