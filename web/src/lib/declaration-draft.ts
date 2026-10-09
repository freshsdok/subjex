// Declaration draft helpers — 声明草稿辅助：租户 cookie、种类校验、classpath 样例键。

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

/** Whether the console may show Promote for the open revision — 是否可对当前打开修订显示晋升。 */
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

/** Whether the console may show migration queue actions — 是否可展示迁移队列操作（仅 entity）。 */
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

/** Statuses that block entity promote — 会挡住实体晋升的状态。 */
export function migrationBlocksPromote(status: string | undefined | null): boolean {
  return status === "PENDING" || status === "REVIEWED" || status === "FAILED";
}
