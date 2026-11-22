/**
 * Export queue helpers — status filter, self-approval visibility, and issued-link href.
 * 导出队列辅助：状态筛选、自己不能审批的按钮可见性，以及已签发下载链接的打开地址。
 */

/** Lifecycle values returned by GET /api/v1/exports — 导出申请接口返回的生命周期值. */
export const EXPORT_QUEUE_STATUSES = [
  "REQUESTED",
  "APPROVED",
  "REJECTED",
  "READY",
  "EXPIRED",
] as const;

export type ExportQueueStatus = (typeof EXPORT_QUEUE_STATUSES)[number];

/** One row from the operator export list — 操作员导出列表中的一行. */
export type ExportQueueRow = {
  requestId?: string;
  tenantId?: string;
  subjectId?: string;
  requesterRealm?: string;
  requesterId?: string;
  scope?: string;
  status?: string;
  approverSubjectId?: string;
  workflowInstanceId?: string;
  createdAt?: string;
  updatedAt?: string;
  decidedAt?: string;
  expiresAt?: string;
  artifactObjectKey?: string;
};

const STATUS_RANK: Record<string, number> = {
  REQUESTED: 0,
  APPROVED: 1,
  READY: 2,
  REJECTED: 3,
  EXPIRED: 4,
};

export function isExportQueueStatus(value: string): value is ExportQueueStatus {
  return (EXPORT_QUEUE_STATUSES as readonly string[]).includes(value);
}

/**
 * Filter by status; blank or {@code all} keeps every row. Unknown values keep every row too.
 * 按状态筛选；空白或 all 保留全部。未知值也保留全部，避免错筛把队列清空。
 */
export function filterExportQueueRows<T extends { status?: string }>(rows: T[], statusFilter: string): T[] {
  const wanted = statusFilter.trim().toUpperCase();
  if (!wanted || wanted === "ALL" || !isExportQueueStatus(wanted)) {
    return rows;
  }
  return rows.filter((row) => (row.status ?? "").toUpperCase() === wanted);
}

/**
 * Queue order: pending, approved, ready, rejected, expired; newest created first within a status.
 * 队列排序：待审批、已批准、已生成、已驳回、已过期；同一状态里按创建时间从新到旧。
 */
export function sortExportQueueRows<T extends { status?: string; createdAt?: string }>(rows: T[]): T[] {
  return [...rows].sort((left, right) => {
    const leftRank = STATUS_RANK[left.status ?? ""] ?? 5;
    const rightRank = STATUS_RANK[right.status ?? ""] ?? 5;
    if (leftRank !== rightRank) return leftRank - rightRank;
    return (right.createdAt ?? "").localeCompare(left.createdAt ?? "");
  });
}

/** True when this operator is the requester — 当前操作员就是申请人. */
export function isOwnExportRequest(requesterId: string | undefined, operatorSubjectId: string): boolean {
  const requester = (requesterId ?? "").trim();
  const self = operatorSubjectId.trim();
  return requester.length > 0 && self.length > 0 && requester === self;
}

/** Approve/reject only on pending requests the operator did not submit — 仅待审批且非本人提交的申请可审批. */
export function canDecideExport(
  status: string | undefined,
  requesterId: string | undefined,
  operatorSubjectId: string,
  canApprove: boolean,
): boolean {
  return canApprove && (status ?? "").toUpperCase() === "REQUESTED" && !isOwnExportRequest(requesterId, operatorSubjectId);
}

/** Generate is allowed only after approval, before the artifact exists — 仅已批准、尚未生成时可生成. */
export function canGenerateExport(status: string | undefined, canApprove: boolean): boolean {
  return canApprove && (status ?? "").toUpperCase() === "APPROVED";
}

/** Download link is issued only when the artifact is ready (not expired) — 仅已生成且未过期时可签发下载链接. */
export function canIssueExportDownload(status: string | undefined, canApprove: boolean): boolean {
  return canApprove && (status ?? "").toUpperCase() === "READY";
}

/**
 * Open the issued URL as-is when it is absolute; map a platform {@code /api/v1/} redeem path
 * through the existing console proxy. Do not invent a second download product.
 * 绝对地址原样打开；平台 {@code /api/v1/} 兑换路径改走现有控制台代理。不另做一套下载。
 */
export function resolveIssuedDownloadHref(url: string): string {
  const trimmed = url.trim();
  if (!trimmed) return "";
  if (/^https?:\/\//i.test(trimmed)) return trimmed;
  if (trimmed.startsWith("/api/v1/")) {
    return `/api/platform/${trimmed.slice("/api/v1/".length)}`;
  }
  return trimmed;
}

/** Phrase key for a failed queue action — 队列操作失败时使用的文案键. */
export function exportQueueErrorPhraseKey(
  reason: string,
):
  | "exportsSelfApprovalForbidden"
  | "exportsExpiredForbidden"
  | "exportsNotApprovedForbidden"
  | "exportsArtifactUnavailable"
  | "exportsActionFailed" {
  switch (reason.trim()) {
    case "export-self-approval-forbidden":
      return "exportsSelfApprovalForbidden";
    case "export-expired":
      return "exportsExpiredForbidden";
    case "export-not-approved":
      return "exportsNotApprovedForbidden";
    case "export-artifact-unavailable":
    case "export-cross-tenant-denied":
      return "exportsArtifactUnavailable";
    default:
      return "exportsActionFailed";
  }
}
