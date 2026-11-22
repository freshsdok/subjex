/**
 * Audit NDJSON export helpers — window cap, query path, and a safe download name.
 * 审计 NDJSON 导出辅助：时间窗上限、查询路径与安全文件名。
 */

/** Inclusive max window, matching AuditExportService.MAX_WINDOW — 与后端一致的含端点最大时间窗。 */
export const AUDIT_EXPORT_MAX_WINDOW_MS = 31 * 24 * 60 * 60 * 1000;
export const AUDIT_EXPORT_DEFAULT_LIMIT = 1000;
export const AUDIT_EXPORT_MAX_LIMIT = 5000;

export type AuditExportWindowError = "missing" | "invalid" | "order" | "tooLong";

/**
 * Validate {@code [from, to]} instants. A window longer than 31 days is rejected (exact 31 days is allowed).
 * 校验起止时刻。超过 31 天拒绝；刚好 31 天可以通过。
 */
export function auditExportWindowError(fromIso: string, toIso: string): AuditExportWindowError | null {
  const fromText = fromIso.trim();
  const toText = toIso.trim();
  if (!fromText || !toText) return "missing";
  const from = Date.parse(fromText);
  const to = Date.parse(toText);
  if (!Number.isFinite(from) || !Number.isFinite(to)) return "invalid";
  if (from > to) return "order";
  if (to - from > AUDIT_EXPORT_MAX_WINDOW_MS) return "tooLong";
  return null;
}

/**
 * Clamp a typed limit; blank uses the service default. Values above the max are clamped, never rejected.
 * 整理条数上限：空白用服务默认；超过最大值只钳制、不拒绝（与后端一致）。
 */
export function clampAuditExportLimit(raw: string): number {
  const trimmed = raw.trim();
  if (!trimmed) return AUDIT_EXPORT_DEFAULT_LIMIT;
  const parsed = Number.parseInt(trimmed, 10);
  if (!Number.isFinite(parsed) || parsed <= 0) return AUDIT_EXPORT_DEFAULT_LIMIT;
  return Math.min(parsed, AUDIT_EXPORT_MAX_LIMIT);
}

/** Console proxy path for POST /api/v1/audit/export — 控制台代理路径。 */
export function buildAuditExportPath(input: {
  tenantId: string;
  fromIso: string;
  toIso: string;
  limit?: number;
}): string {
  const tenantId = input.tenantId.trim();
  const query = new URLSearchParams();
  query.set("tenantId", tenantId);
  query.set("from", input.fromIso.trim());
  query.set("to", input.toIso.trim());
  if (input.limit != null && input.limit > 0) {
    query.set("limit", String(clampAuditExportLimit(String(input.limit))));
  }
  return `/api/platform/audit/export?${query.toString()}`;
}

/** Safe filename (no path separators or quotes) — 去掉路径分隔与引号的安全文件名。 */
export function auditExportFilename(tenantId: string, fromIso: string, toIso: string): string {
  const safeTenant = sanitizeFilenamePart(tenantId) || "tenant";
  const fromDay = isoDateStamp(fromIso);
  const toDay = isoDateStamp(toIso);
  return `audit-${safeTenant}-${fromDay}-to-${toDay}.ndjson`;
}

function isoDateStamp(iso: string): string {
  const parsed = Date.parse(iso);
  if (!Number.isFinite(parsed)) return "date";
  return new Date(parsed).toISOString().slice(0, 10);
}

export function sanitizeFilenamePart(raw: string): string {
  return raw.trim().replace(/[^A-Za-z0-9._-]+/g, "_");
}

/**
 * Convert a {@code datetime-local} value to an ISO instant, or null when blank/invalid.
 * 把本地日期时间框的值转成 ISO 时刻；空白或无效则返回 null。
 */
export function localDateTimeToIso(value: string): string | null {
  const trimmed = value.trim();
  if (!trimmed) return null;
  const parsed = new Date(trimmed);
  if (Number.isNaN(parsed.getTime())) return null;
  return parsed.toISOString();
}
