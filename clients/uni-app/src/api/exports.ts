/**
 * End-user personal-data export requests (WP8) — 终端本人资料导出申请。
 * Reuses {@code /api/v1/end-user/exports}; download via the existing short-lived signed link.
 * 复用终端导出接口；下载只走已有短时签名链接，不另开文件通道。
 */

import { apiBase, authedFetch, PlatformHttpError } from "./platform";
import type { IssuedDownloadLink } from "./objects";

/** Scope meaning all of this subject's own records in the tenant — 范围：本人在本组织的全部资料. */
export const SCOPE_ALL_OWN = "ALL_OWN";

export type ExportStatus = "REQUESTED" | "APPROVED" | "REJECTED" | "READY" | "EXPIRED" | string;

export type EndUserExport = {
  requestId: string;
  tenantId?: string;
  subjectId?: string;
  requesterRealm?: string;
  requesterId?: string;
  scope?: string;
  status?: ExportStatus;
  createdAt?: string;
  updatedAt?: string;
  decidedAt?: string;
  expiresAt?: string;
  artifactObjectKey?: string | null;
};

const EXPORTS_PATH = "/api/v1/end-user/exports";

/** List own requests (server returns oldest first) — 列出本人申请（服务端按时间正序）. */
export async function listOwnExports(): Promise<EndUserExport[]> {
  const rows = await authedFetch<EndUserExport[]>({
    path: EXPORTS_PATH,
    tenantId: true,
  });
  return Array.isArray(rows) ? rows : [];
}

/**
 * Submit an export request. Default scope is {@link SCOPE_ALL_OWN}.
 * 提交导出申请；默认范围为本人在本组织的全部资料。
 */
export async function createExportRequest(scope: string = SCOPE_ALL_OWN): Promise<EndUserExport> {
  const trimmed = scope.trim() || SCOPE_ALL_OWN;
  return authedFetch<EndUserExport>({
    method: "POST",
    path: EXPORTS_PATH,
    tenantId: true,
    body: { scope: trimmed },
  });
}

export async function getExport(requestId: string): Promise<EndUserExport> {
  const id = requestId.trim();
  if (!id) throw new Error("请选择一份导出申请");
  return authedFetch<EndUserExport>({
    path: `${EXPORTS_PATH}/${encodeURIComponent(id)}`,
    tenantId: true,
  });
}

/**
 * Issue the existing short-lived signed download (READY only; server forbids self-approval).
 * 签发已有短时签名下载（仅 READY；自行批准仍由后端禁止）。
 */
export async function issueExportDownloadLink(requestId: string): Promise<IssuedDownloadLink> {
  const id = requestId.trim();
  if (!id) throw new Error("请选择一份导出申请");
  return authedFetch<IssuedDownloadLink>({
    method: "POST",
    path: `${EXPORTS_PATH}/${encodeURIComponent(id)}/download-link`,
    tenantId: true,
  });
}

/**
 * Map a signed-link URL to an absolute address the uni downloader can open.
 * Absolute URLs stay as issued; {@code /api/v1/…} redeem paths are prefixed with the platform public base.
 * 把签名链接变成 uni 下载器能打开的绝对地址：已是绝对地址则原样使用；相对 {@code /api/v1/…} 兑付路径则补上平台根。
 */
export function resolveIssuedDownloadHref(url: string): string {
  const trimmed = (url || "").trim();
  if (!trimmed) return "";
  if (/^https?:\/\//i.test(trimmed)) return trimmed;
  if (trimmed.startsWith("/")) return `${apiBase().replace(/\/$/, "")}${trimmed}`;
  return trimmed;
}

/** Download the issued URL and open it — 下载已签发地址并打开. */
export async function openIssuedDownload(url: string): Promise<void> {
  const href = resolveIssuedDownloadHref(url);
  if (!href) throw new Error("暂时无法打开这份导出");
  await new Promise<void>((resolve, reject) => {
    uni.downloadFile({
      url: href,
      success: (res) => {
        if (res.statusCode && res.statusCode !== 200) {
          reject(new Error("下载失败，请稍后重试"));
          return;
        }
        uni.openDocument({
          filePath: res.tempFilePath,
          showMenu: true,
          success: () => resolve(),
          fail: () => reject(new Error("文件已下载，但当前环境无法直接打开")),
        });
      },
      fail: (err) => reject(new Error(err.errMsg || "下载失败，请稍后重试")),
    });
  });
}

/** Newest first for the request list — 列表按申请时间倒序. */
export function sortExportsNewestFirst(rows: EndUserExport[]): EndUserExport[] {
  return [...rows].sort((a, b) => {
    const ta = Date.parse(a.createdAt || "") || 0;
    const tb = Date.parse(b.createdAt || "") || 0;
    if (ta !== tb) return tb - ta;
    return (b.requestId || "").localeCompare(a.requestId || "");
  });
}

export function isExportExpired(row: EndUserExport, now: Date = new Date()): boolean {
  if ((row.status || "").toUpperCase() === "EXPIRED") return true;
  const raw = row.expiresAt;
  if (!raw) return false;
  const at = Date.parse(raw);
  return Number.isFinite(at) && at <= now.getTime();
}

/** READY and the artifact has not passed its expiry — 已就绪且产物尚未过期，才可申请下载链接. */
export function canDownloadExport(row: EndUserExport, now: Date = new Date()): boolean {
  return (row.status || "").toUpperCase() === "READY" && !isExportExpired(row, now);
}

export function isExportInProgress(row: EndUserExport): boolean {
  const status = (row.status || "").toUpperCase();
  return status === "REQUESTED" || status === "APPROVED";
}

export function exportStatusLabel(status?: string): string {
  switch ((status || "").toUpperCase()) {
    case "REQUESTED":
      return "等待审核";
    case "APPROVED":
      return "已通过，正在准备文件";
    case "REJECTED":
      return "未通过";
    case "READY":
      return "已准备好，可以下载";
    case "EXPIRED":
      return "已过期，无法再下载";
    default:
      return status ? `状态：${status}` : "状态未知";
  }
}

export function exportScopeLabel(scope?: string): string {
  const trimmed = (scope || "").trim();
  if (!trimmed || trimmed === SCOPE_ALL_OWN) return "我在本组织的全部个人资料";
  return `指定范围：${trimmed}`;
}

/** Friendly copy for export HTTP errors — 导出接口错误的面向用户说明. */
export function exportMessage(err: unknown): string {
  if (err instanceof PlatformHttpError) {
    switch (err.reason) {
      case "export-expired":
        return "这份导出已经过期，无法再下载。如需文件，请再提交一份申请";
      case "export-not-approved":
        return "这份导出还在审核，或文件尚未准备好";
      case "export-artifact-unavailable":
        return "导出文件暂时无法打开，请稍后重试";
      case "export-cross-tenant-denied":
        return "当前账号无法访问该组织的导出申请";
      case "end-user-tenant-access-denied":
        return "当前账号无法访问该组织的内容";
      default:
        break;
    }
    if (err.status === 400) return "申请没有提交成功，请稍后重试";
    if (err.status === 401) return "登录已过期，请重新登录";
    if (err.status === 403) return "没有权限完成这项操作";
    if (err.status === 404) return "没有找到这份导出申请";
    return "操作没有成功，请稍后重试";
  }
  if (err instanceof Error && err.message && !err.message.startsWith("platform ")) {
    return err.message;
  }
  return "出了一点问题，请稍后重试";
}
