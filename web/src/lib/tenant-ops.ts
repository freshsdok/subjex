/**
 * Tenant quota / zip export / hard-erase helpers for the operator console.
 * 操作员控制台的租户配额、备份下载与硬删除辅助。
 */

import { isReservedTenant } from "@/lib/tenant-admin";

/** Quota document from GET/PUT /api/v1/tenants/{id}/quota — 配额用量文档。 */
export type TenantQuotaDocument = {
  tenantId?: string;
  dailySubmitCount?: number;
  storageRowCount?: number;
  effectiveDailySubmitLimit?: number;
  effectiveStorageRowLimit?: number;
  globalDailySubmitLimit?: number;
  globalStorageRowLimit?: number;
  overrideDailySubmitLimit?: number | null;
  overrideStorageRowLimit?: number | null;
};

export type EraseChallengeDocument = {
  challengeId?: string;
  token?: string;
  expiresAt?: string;
};

export type EraseResultDocument = {
  tenantId?: string;
  rowsDeleted?: number;
  objectStorageDeletes?: number;
};

/**
 * Hard erase is allowed only after soft-disable, and never on the reserved platform tenant.
 * 仅已软禁用的业务租户可申请硬删除；保留租户 platform 永远不行。
 */
export function canStartHardErase(tenantId: string, tenantState: string): boolean {
  return !isReservedTenant(tenantId) && tenantState === "SUSPENDED";
}

/** Typed confirmation must match the tenant id exactly (trimmed) — 确认输入须与租户 ID 完全一致（去首尾空白）。 */
export function eraseIdConfirmationMatches(typed: string, tenantId: string): boolean {
  return typed.trim() === tenantId.trim() && tenantId.trim().length > 0;
}

export function tenantZipFilename(tenantId: string): string {
  const safe = tenantId.trim().replace(/[^A-Za-z0-9._-]+/g, "_") || "tenant";
  return `tenant-${safe}-export.zip`;
}

/**
 * Parse a quota-limit field: blank clears the per-tenant cap (null); a set value must be an integer ≥ 1.
 * 解析配额上限：留空表示清除该租户单独上限（null）；填写时必须是 ≥ 1 的整数。
 */
export function parseQuotaLimitInput(raw: string): { ok: true; value: number | null } | { ok: false } {
  const trimmed = raw.trim();
  if (!trimmed) return { ok: true, value: null };
  if (!/^\d+$/.test(trimmed)) return { ok: false };
  const parsed = Number(trimmed);
  if (!Number.isSafeInteger(parsed) || parsed < 1) return { ok: false };
  return { ok: true, value: parsed };
}

/** PUT body: null fields clear that override; both null deletes the override row — 字段为 null 清除该项；两项都空则删覆盖行。 */
export function buildQuotaOverrideBody(
  dailySubmitLimit: number | null,
  storageRowLimit: number | null,
): { dailySubmitLimit: number | null; storageRowLimit: number | null } {
  return { dailySubmitLimit, storageRowLimit };
}

export function buildEraseConfirmBody(challengeId: string, token: string): { challengeId: string; token: string } {
  return { challengeId: challengeId.trim(), token: token.trim() };
}
