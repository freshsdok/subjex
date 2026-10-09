// Declaration permission catalog — 声明权限目录：与平台 OperatorPermission 对齐（只选不造，RT-3）。

/**
 * Known platform permission names operators may pick on entity/form/flow drafts.
 * Must stay in sync with `OperatorPermission` (server rejects unknown on save/promote).
 * 与平台权限目录对齐；服务端保存/晋升时拒绝未知权限。
 */
export const DECLARATION_PERMISSION_CATALOG = [
  "admin.read",
  "page.read",
  "config.read",
  "config.write",
  "registry.read",
  "registry.write",
  "task.write",
  "operator.manage",
  "tenant.manage",
  "org.read",
  "org.write",
  "declaration.read",
  "declaration.write",
  "declaration.promote",
  "declaration.migrate",
] as const;

export type DeclarationPermissionName = (typeof DECLARATION_PERMISSION_CATALOG)[number];

/** Whether the value is in the pick-only catalog — 是否在只选目录中。 */
export function isKnownDeclarationPermission(value: string): boolean {
  return (DECLARATION_PERMISSION_CATALOG as readonly string[]).includes(value.trim());
}

/** Default permission for business-table / wizards — 业务表/向导默认权限。 */
export const DEFAULT_DECLARATION_PERMISSION: DeclarationPermissionName = "page.read";
