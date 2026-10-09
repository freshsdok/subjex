// Org console helpers — 组织控制台辅助：租户 cookie、状态文案、ID 校验。

export const orgTenantCookieName = "subjex_org_tenant";

export const ORG_UNIT_STATE_ACTIVE = "ACTIVE";
export const ORG_UNIT_STATE_DISABLED = "DISABLED";
export const ORG_MEMBERSHIP_STATE_ACTIVE = "ACTIVE";
export const ORG_MEMBERSHIP_STATE_DISABLED = "DISABLED";

const oneYearSeconds = 365 * 24 * 60 * 60;

/** Parse tenant id from cookie (trim; blank → empty) — 从 cookie 解析租户 ID。 */
export function parseOrgTenantCookie(raw: string | undefined): string {
  if (raw == null || raw === "") return "";
  let decoded = raw;
  try {
    decoded = decodeURIComponent(raw);
  } catch {
    decoded = raw;
  }
  return decoded.trim();
}

/** Cookie write string for client — 客户端写入 cookie 的字符串。 */
export function orgTenantCookieWrite(tenantId: string): string {
  const value = encodeURIComponent(tenantId.trim());
  return `${orgTenantCookieName}=${value}; path=/; max-age=${oneYearSeconds}; samesite=strict`;
}

/**
 * Normalize org unit / subject id for forms — 规范化组织单元或主体 ID。
 * Reject blank, whitespace, or slash. 拒绝空串、空白或斜杠。
 */
export function normalizeOrgId(raw: string | undefined | null): string | null {
  if (raw == null) return null;
  const trimmed = raw.trim();
  if (!trimmed) return null;
  if (/\s/.test(trimmed) || trimmed.includes("/")) return null;
  return trimmed;
}

/** Optional parent id: blank → empty string (root) — 可选父节点：空表示根。 */
export function normalizeOptionalParentId(raw: string | undefined | null): string | null {
  if (raw == null) return "";
  const trimmed = raw.trim();
  if (!trimmed) return "";
  if (/\s/.test(trimmed) || trimmed.includes("/")) return null;
  return trimmed;
}

/** Whether unit/membership state is disabled — 是否为禁用态。 */
export function isOrgDisabledState(state: string | undefined | null): boolean {
  return (state ?? "").trim().toUpperCase() === ORG_UNIT_STATE_DISABLED;
}

/** Wire state for toggle target — 切换目标状态（ACTIVE ↔ DISABLED）。 */
export function toggledOrgState(current: string | undefined | null): string {
  return isOrgDisabledState(current) ? ORG_UNIT_STATE_ACTIVE : ORG_UNIT_STATE_DISABLED;
}
