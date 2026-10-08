// Tenant admin helpers — 租户管理辅助：校验租户 ID / 显示名。

export const RESERVED_PLATFORM_TENANT = "platform";

export function normalizeTenantAdminId(raw: string): string | null {
  const trimmed = raw.trim();
  if (!trimmed) return null;
  if (/\s/.test(trimmed) || trimmed.includes("/")) return null;
  if (trimmed.length > 64) return null;
  return trimmed;
}

export function normalizeTenantDisplayName(raw: string): string | null {
  const trimmed = raw.trim();
  if (!trimmed) return null;
  if (trimmed.length > 256) return null;
  return trimmed;
}

export function isReservedTenant(tenantId: string): boolean {
  return tenantId === RESERVED_PLATFORM_TENANT;
}
