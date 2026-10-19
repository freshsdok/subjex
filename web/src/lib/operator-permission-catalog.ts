// Operator permission catalog — 操作员权限目录：与 OperatorPermission 枚举对齐（只选不造）。

/**
 * Full platform permission names for UI binding selects (ADR 0010 / PC-6a).
 * Must stay in sync with {@code OperatorPermission}; server rejects unknown on draft save.
 * 与平台枚举对齐；草稿保存时服务端拒绝未知权限。
 */
export const OPERATOR_PERMISSION_CATALOG = [
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
  "workflow.read",
  "workflow.write",
  "workflow.approve",
  "inbox.read",
  "inbox.send.system",
  "inbox.broadcast",
  "inbox.group.admin",
] as const;

export type OperatorPermissionName = (typeof OPERATOR_PERMISSION_CATALOG)[number];

export function isKnownOperatorPermission(value: string): boolean {
  return (OPERATOR_PERMISSION_CATALOG as readonly string[]).includes(value.trim());
}
