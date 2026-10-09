/**
 * Declaration draft helpers — tenant cookie deep-link, kind validation, classpath sample keys,
 * promote/migrate offer gates (why buttons hide), migration status→action, path checklist chips.
 * 声明草稿辅助：租户 cookie 深链、种类校验、样例键、晋升/迁移展示门禁（为何隐藏按钮）、
 * 迁移状态→动作、路径清单芯片。
 */

export const declarationTenantCookieName = "subjex_declaration_tenant";

export const DECLARATION_KINDS = ["entity", "form", "flow"] as const;
export type DeclarationKind = (typeof DECLARATION_KINDS)[number];

/** Known classpath sample keys by kind — 回归样例键（非用户起点；列表标「样例」）。 */
export const CLASSPATH_SAMPLE_KEYS: Record<DeclarationKind, readonly string[]> = {
  entity: ["demo-ticket", "service-note"],
  form: ["demo-ticket", "service-note", "endpoint-publication", "config-override"],
  flow: ["demo-ticket", "service-note", "endpoint-publication"],
};

/** Whether key is a known classpath regression sample — 是否为 classpath 回归样例键。 */
export function isClasspathSampleKey(kind: DeclarationKind, key: string): boolean {
  return (CLASSPATH_SAMPLE_KEYS[kind] as readonly string[]).includes(key);
}

const oneYearSeconds = 365 * 24 * 60 * 60;

/** Parse tenant id from cookie (trim; blank → empty) — 从 cookie 解析租户 ID。 */
export function parseDeclarationTenantCookie(raw: string | undefined): string {
  if (raw == null || raw === "") return "";
  let decoded = raw;
  try {
    decoded = decodeURIComponent(raw);
  } catch {
    decoded = raw;
  }
  return decoded.trim();
}

/** Validate kind wire name — 校验种类落库名。 */
export function isDeclarationKind(value: string): value is DeclarationKind {
  return (DECLARATION_KINDS as readonly string[]).includes(value);
}

/** Normalize kind; unknown → null — 规范化种类；未知返回 null。 */
export function normalizeDeclarationKind(raw: string | undefined | null): DeclarationKind | null {
  if (raw == null) return null;
  const trimmed = raw.trim().toLowerCase();
  return isDeclarationKind(trimmed) ? trimmed : null;
}

/** Cookie write string for client — 客户端写入 cookie 的字符串。 */
export function declarationTenantCookieWrite(tenantId: string): string {
  const value = encodeURIComponent(tenantId.trim());
  return `${declarationTenantCookieName}=${value}; path=/; max-age=${oneYearSeconds}; samesite=strict`;
}

/** Minimal YAML stubs for new drafts — 新建草稿的最小 YAML 模板。 */
export function templateYamlFor(kind: DeclarationKind, key: string): string {
  const k = key.trim() || "example-key";
  switch (kind) {
    case "entity":
      return [
        `entityKey: ${k}`,
        `tableName: ${k.replace(/-/g, "_")}`,
        "version: 1",
        "permission: page.read",
        "tenantScoped: false",
        "fields:",
        "  - name: id",
        "    kind: text",
        "    required: true",
        "    maxLength: 64",
        "",
      ].join("\n");
    case "form":
      return [
        `formKey: ${k}`,
        "titleEn: Untitled",
        "titleZh: 未命名",
        "version: 1",
        "permission: page.read",
        "tenantScoped: false",
        "domainAction: entity.record.upsert",
        `entityKey: ${k}`,
        "fields:",
        "  - name: id",
        "    kind: text",
        "    required: true",
        "    maxLength: 64",
        "",
      ].join("\n");
    case "flow":
      return [
        `flowKey: ${k}`,
        "titleEn: Untitled",
        "titleZh: 未命名",
        `formKey: ${k}`,
        `entityKey: ${k}`,
        "version: 1",
        "permission: page.read",
        "tenantScoped: false",
        "list:",
        `  path: /pages/${k}`,
        `  apiPath: /api/v1/entities/${k}/records`,
        "  itemsKey: records",
        "detail:",
        `  path: /pages/${k}/{id}`,
        `  apiPath: /api/v1/entities/${k}/records`,
        "  itemsKey: records",
        "  idField: id",
        "submit:",
        `  path: /pages/${k}/new`,
        `  apiPath: /api/v1/forms/${k}/submissions`,
        `  redirectTo: /pages/${k}`,
        "",
      ].join("\n");
  }
}

/** Keys present in drafts plus classpath hints not yet drafted — 草稿键 + 尚无草稿的 classpath 提示。 */
export function mergeDraftAndClasspathKeys(
  draftKeys: readonly string[],
  kind: DeclarationKind,
): { key: string; hasDraft: boolean }[] {
  const seen = new Set<string>();
  const rows: { key: string; hasDraft: boolean }[] = [];
  for (const key of draftKeys) {
    if (!key || seen.has(key)) continue;
    seen.add(key);
    rows.push({ key, hasDraft: true });
  }
  for (const key of CLASSPATH_SAMPLE_KEYS[kind]) {
    if (seen.has(key)) continue;
    seen.add(key);
    rows.push({ key, hasDraft: false });
  }
  return rows;
}

/**
 * Whether the console may show Promote for the open revision.
 * Why false: missing {@code declaration.promote}, blank tenant, no selected key, or no loaded revision.
 * 是否可对当前打开修订显示晋升。为 false：缺权限、无租户、无键、或未加载修订。
 */
export function canOfferDeclarationPromote(opts: {
  canPromote: boolean;
  tenantId: string;
  selectedKey: string | null;
  loadedRevision: number | null;
}): boolean {
  return (
    opts.canPromote &&
    opts.tenantId.trim() !== "" &&
    opts.selectedKey != null &&
    opts.selectedKey !== "" &&
    opts.loadedRevision != null
  );
}

/** Optional JSON body for POST promote (omit → latest) — 晋升可选正文（省略则最新修订）。 */
export function declarationPromoteRequestBody(
  revision: number | null | undefined,
): { revision: number } | undefined {
  if (revision == null || !Number.isFinite(revision)) return undefined;
  return { revision };
}


/** Migration queue statuses — 迁移队列状态（与 declaration_migration 一致）。 */
export const DECLARATION_MIGRATION_STATUSES = [
  "PENDING",
  "REVIEWED",
  "APPLIED",
  "FAILED",
  "CANCELLED",
] as const;
export type DeclarationMigrationStatus = (typeof DECLARATION_MIGRATION_STATUSES)[number];

/**
 * Whether the console may show migration queue actions (entity only).
 * Why false: missing {@code declaration.migrate}, kind !== entity, blank tenant/key.
 * 是否可展示迁移队列（仅实体）。为 false：缺权限、非实体、无租户/键。
 */
export function canOfferDeclarationMigrate(opts: {
  canMigrate: boolean;
  kind: DeclarationKind;
  tenantId: string;
  selectedKey: string | null;
}): boolean {
  return (
    opts.canMigrate &&
    opts.kind === "entity" &&
    opts.tenantId.trim() !== "" &&
    opts.selectedKey != null &&
    opts.selectedKey !== ""
  );
}

/** JSON body for POST enqueue; null if sql blank — 入队正文；SQL 空则 null。 */
export function declarationMigrateEnqueueBody(opts: {
  revision: number | null | undefined;
  sqlText: string;
}): { revision?: number; sqlText: string } | null {
  const sqlText = opts.sqlText.trim();
  if (!sqlText) return null;
  if (opts.revision == null || !Number.isFinite(opts.revision)) {
    return { sqlText };
  }
  return { revision: opts.revision, sqlText };
}

/** PENDING → may Review — 待审可点审阅。 */
export function canReviewMigration(status: string | undefined | null): boolean {
  return status === "PENDING";
}

/** REVIEWED → may Apply — 已审可点执行。 */
export function canApplyMigration(status: string | undefined | null): boolean {
  return status === "REVIEWED";
}

/** PENDING or REVIEWED → may Cancel — 待审/已审可取消。 */
export function canCancelMigration(status: string | undefined | null): boolean {
  return status === "PENDING" || status === "REVIEWED";
}

/** PENDING → guided review+apply — 待审可一键审阅并执行（仍需确认 SQL）。 */
export function canGuidedReviewApplyMigration(status: string | undefined | null): boolean {
  return status === "PENDING";
}

/** FAILED row emphasis / re-queue hint — 失败行高亮。 */
export function isFailedMigration(status: string | undefined | null): boolean {
  return status === "FAILED";
}

/** Statuses that block entity promote — 会挡住实体晋升的状态。 */
export function migrationBlocksPromote(status: string | undefined | null): boolean {
  return status === "PENDING" || status === "REVIEWED" || status === "FAILED";
}

/** Path chip steps — 迁移路径步骤（草稿→入队→审阅→执行→晋升）。 */
export const MIGRATION_PATH_STEPS = ["draft", "PENDING", "REVIEWED", "APPLIED", "promote"] as const;
export type MigrationPathStep = (typeof MIGRATION_PATH_STEPS)[number];
export type MigrationPathChipState = "done" | "current" | "todo" | "blocked";

export type MigrationPathChip = {
  step: MigrationPathStep;
  state: MigrationPathChipState;
};

/**
 * Lightweight path checklist chips for entity migrate→promote (user-facing progress).
 * States: done / current / todo / blocked (FAILED or open jobs block promote).
 * 实体迁移→晋升路径芯片。状态：完成/当前/待办/阻断（FAILED 或未结任务挡晋升）。
 */
export function migrationPathChecklist(opts: {
  hasDraft: boolean;
  revision: number | null | undefined;
  migrations: { status?: string; declarationRevision?: number }[] | null | undefined;
  promotedForRevision: boolean;
}): MigrationPathChip[] {
  const rev = opts.revision;
  const rows =
    opts.migrations == null || rev == null
      ? []
      : opts.migrations.filter((m) => m.declarationRevision === rev);
  const statuses = new Set(rows.map((m) => m.status).filter(Boolean) as string[]);
  const hasPending = statuses.has("PENDING");
  const hasReviewed = statuses.has("REVIEWED");
  const hasApplied = statuses.has("APPLIED");
  const hasFailed = statuses.has("FAILED");
  const enqueued = rows.length > 0;
  const openBlockers = hasPending || hasReviewed || hasFailed;
  const appliedDone = hasApplied && !hasPending && !hasReviewed && !hasFailed;

  const doneFlags: Record<MigrationPathStep, boolean> = {
    draft: opts.hasDraft,
    PENDING: enqueued,
    REVIEWED: hasReviewed || hasApplied,
    APPLIED: appliedDone,
    promote: opts.promotedForRevision,
  };
  const blockedFlags: Partial<Record<MigrationPathStep, boolean>> = {
    REVIEWED: hasFailed && !hasReviewed && !hasApplied,
    APPLIED: hasFailed && !appliedDone,
    promote: openBlockers && !opts.promotedForRevision,
  };

  const order: MigrationPathStep[] = ["draft", "PENDING", "REVIEWED", "APPLIED", "promote"];
  let currentAssigned = false;
  return order.map((step) => {
    if (doneFlags[step]) return { step, state: "done" as const };
    if (blockedFlags[step]) return { step, state: "blocked" as const };
    if (!currentAssigned) {
      currentAssigned = true;
      return { step, state: "current" as const };
    }
    return { step, state: "todo" as const };
  });
}

/** Filter platform audit rows to declaration.migrate.* for an entity key — 过滤某实体的迁移审计。 */
export function filterMigrateAuditEntries<T extends { actionName?: string; actionTarget?: string }>(
  entries: T[] | null | undefined,
  declarationKey: string,
): T[] {
  const key = declarationKey.trim();
  if (!key || !entries) return [];
  const needle = `entity/${key}`;
  return entries.filter((e) => {
    const action = e.actionName ?? "";
    if (!action.startsWith("declaration.migrate.")) return false;
    const target = e.actionTarget ?? "";
    return target.includes(needle);
  });
}
