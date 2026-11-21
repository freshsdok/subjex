/**
 * End-user tenant context (WP2-6 / ADR 0014) — 终端租户上下文。
 * The client must send an explicit existing business tenantId. Never default to
 * reserved tenant {@code platform}, and never create a tenant from the client.
 * 必须给出已存在的业务租户；永不默认 platform，客户端不创建租户。
 */

const STORAGE_KEY = "subjex_end_user_tenant";
export const RESERVED_PLATFORM_TENANT = "platform";
export const TENANT_HEADER = "X-Tenant-Id";

/** True when the id is the reserved operator tenant — 是否为保留的操作员租户。 */
export function isReservedTenant(tenantId: string | null | undefined): boolean {
  return (tenantId || "").trim().toLowerCase() === RESERVED_PLATFORM_TENANT;
}

/**
 * Normalize a business tenant id, or null if blank/reserved.
 * 规范化业务租户 id；空白或 platform 返回 null。
 */
export function normalizeBusinessTenantId(
  tenantId: string | null | undefined
): string | null {
  const id = (tenantId || "").trim();
  if (!id || isReservedTenant(id)) return null;
  return id;
}

/**
 * Require a business tenant id; throw if missing or reserved.
 * 要求业务租户 id；缺失或 platform 则抛错。
 */
export function requireBusinessTenantId(tenantId: string | null | undefined): string {
  const id = normalizeBusinessTenantId(tenantId);
  if (id) return id;
  if (isReservedTenant(tenantId)) {
    throw new Error(
      "tenantId 不能是保留租户 platform / tenantId must not be reserved tenant platform"
    );
  }
  throw new Error("请填写已存在的业务租户 tenantId / business tenantId is required");
}

function envTenantId(): string | null {
  const env =
    typeof import.meta !== "undefined"
      ? (import.meta as ImportMeta & { env?: Record<string, string> }).env
      : undefined;
  return normalizeBusinessTenantId(env?.VITE_SUBJEX_TENANT_ID);
}

/** Persist a business tenant id — 持久化业务租户 id。 */
export function writeTenantId(tenantId: string): string {
  const id = requireBusinessTenantId(tenantId);
  try {
    uni.setStorageSync(STORAGE_KEY, id);
  } catch {
    // Storage may be unavailable — 部分运行时无存储。
  }
  return id;
}

/** Clear stored tenant — 清除已存租户。 */
export function clearTenantId(): void {
  try {
    uni.removeStorageSync(STORAGE_KEY);
  } catch {
    // ignore
  }
}

/**
 * Read tenant: storage, then optional Vite env. Never returns {@code platform}.
 * 读取租户：先本地存储，再环境变量。永不返回 platform。
 */
export function readTenantId(): string | null {
  try {
    const stored = uni.getStorageSync(STORAGE_KEY);
    const normalized = normalizeBusinessTenantId(
      typeof stored === "string" ? stored : null
    );
    if (normalized) return normalized;
    if (typeof stored === "string" && stored.trim()) {
      // Drop a leftover reserved value — 丢掉遗留的 platform。
      clearTenantId();
    }
  } catch {
    // ignore
  }
  return envTenantId();
}

/**
 * Apply tenantId/tenant from a launch or page query, if it is a business tenant.
 * 若启动或页面查询带有业务租户，则写入上下文。
 */
export function applyTenantFromQuery(
  query: Record<string, unknown> | undefined | null
): string | null {
  if (!query) return null;
  const raw = query.tenantId ?? query.tenant;
  const value = Array.isArray(raw) ? raw[0] : raw;
  if (typeof value !== "string" || !value.trim()) return null;
  if (isReservedTenant(value)) return null;
  return writeTenantId(value);
}
