/**
 * End-user inbox client (WP7-3) — 终端收件客户端。
 * Uses {@code /api/v1/end-user/inbox/**}, not leftover {@code /me/inbox} or operator {@code /inbox}.
 * 走终端收件路径，不再使用 /me/inbox 或操作员 /inbox。
 * Attachments on a received message are listed and downloaded as raw bytes (not signed URLs).
 * 本人收到的信件附件可列出并以字节直接下载（不是签名链接）。
 */

import { authedBinaryFetch, authedFetch } from "./platform";
import { requireBusinessTenantId } from "./tenant";

export type InboxEntry = {
  entryId: string;
  messageId?: string;
  title?: string;
  body?: string;
  severity?: string;
  channel?: string;
  deepLink?: string;
  readAt?: string | null;
  createdAt?: string;
};

function tenant(tenantId?: string): string {
  return requireBusinessTenantId(tenantId);
}

export async function listMyInbox(opts: {
  tenantId?: string;
  unreadOnly?: boolean;
}): Promise<InboxEntry[]> {
  const tid = tenant(opts.tenantId);
  const q = new URLSearchParams();
  if (opts.unreadOnly) q.set("unreadOnly", "true");
  const suffix = q.toString() ? `?${q.toString()}` : "";
  const doc = await authedFetch<{ entries: InboxEntry[] }>({
    path: `/api/v1/end-user/inbox/entries${suffix}`,
    tenantId: tid,
  });
  return doc.entries || [];
}

export async function unreadCount(tenantId?: string): Promise<number> {
  const tid = tenant(tenantId);
  const doc = await authedFetch<{ count: number }>({
    path: `/api/v1/end-user/inbox/unread-count`,
    tenantId: tid,
  });
  return doc.count ?? 0;
}

export async function getEntry(tenantId: string | undefined, entryId: string): Promise<InboxEntry> {
  const tid = tenant(tenantId);
  return authedFetch<InboxEntry>({
    path: `/api/v1/end-user/inbox/entries/${encodeURIComponent(entryId)}`,
    tenantId: tid,
  });
}

export async function markRead(tenantId: string | undefined, entryId: string): Promise<void> {
  const tid = tenant(tenantId);
  await authedFetch({
    method: "POST",
    path: `/api/v1/end-user/inbox/entries/${encodeURIComponent(entryId)}/read`,
    tenantId: tid,
  });
}

export type InboxAttachment = {
  attachmentId: string;
  messageId?: string;
  fileName: string;
  contentType?: string;
  byteSize?: number;
  sortOrder?: number;
};

export async function listAttachments(
  tenantId: string | undefined,
  entryId: string
): Promise<InboxAttachment[]> {
  const tid = tenant(tenantId);
  const id = entryId.trim();
  if (!id) throw new Error("请选择一封站内信");
  const doc = await authedFetch<{ attachments?: InboxAttachment[] }>({
    path: `/api/v1/end-user/inbox/entries/${encodeURIComponent(id)}/attachments`,
    tenantId: tid,
  });
  return doc.attachments ?? [];
}

function safeFileName(name: string | undefined, fallback: string): string {
  const raw = (name || fallback || "附件").replace(/[\\/:*?"<>|]/g, "_").replace(/\.\./g, "_").trim();
  return raw || "附件";
}

function userDataPath(): string | null {
  const wxEnv = (globalThis as unknown as { wx?: { env?: { USER_DATA_PATH?: string } } }).wx?.env;
  if (wxEnv && typeof wxEnv.USER_DATA_PATH === "string") return wxEnv.USER_DATA_PATH;
  const uniEnv = (uni as unknown as { env?: { USER_DATA_PATH?: string } }).env;
  if (uniEnv && typeof uniEnv.USER_DATA_PATH === "string") return uniEnv.USER_DATA_PATH;
  return null;
}

function getFileSystemManager(): { writeFile: Function } | null {
  const uniFs = (uni as unknown as { getFileSystemManager?: () => { writeFile: Function } }).getFileSystemManager;
  if (typeof uniFs === "function") return uniFs.call(uni);
  const wxObj = (globalThis as unknown as { wx?: { getFileSystemManager?: () => { writeFile: Function } } }).wx;
  if (wxObj && typeof wxObj.getFileSystemManager === "function") return wxObj.getFileSystemManager();
  return null;
}

/**
 * Download an attachment on an own entry and open or save it.
 * 下载本人收件上的附件并打开或保存；走带登录凭证的直接字节接口。
 */
export async function openAttachment(
  tenantId: string | undefined,
  entryId: string,
  attachment: InboxAttachment
): Promise<void> {
  const tid = tenant(tenantId);
  const id = entryId.trim();
  const attachmentId = (attachment.attachmentId || "").trim();
  if (!id || !attachmentId) throw new Error("请选择要打开的附件");
  const res = await authedBinaryFetch({
    path: `/api/v1/end-user/inbox/entries/${encodeURIComponent(id)}/attachments/${encodeURIComponent(attachmentId)}`,
    tenantId: tid,
  });
  const fileName = safeFileName(res.fileName || attachment.fileName, "附件");
  const contentType = (res.contentType || attachment.contentType || "application/octet-stream").split(";")[0].trim();

  const fs = getFileSystemManager();
  const root = userDataPath();
  if (fs && root) {
    const filePath = `${root}/${fileName}`;
    await new Promise<void>((resolve, reject) => {
      fs.writeFile({
        filePath,
        data: res.data,
        success: () => {
          uni.openDocument({
            filePath,
            showMenu: true,
            success: () => resolve(),
            fail: () => reject(new Error("附件已下载，但当前环境无法直接打开")),
          });
        },
        fail: (err: { errMsg?: string }) => reject(new Error(err.errMsg || "无法保存附件")),
      });
    });
    return;
  }

  if (typeof document !== "undefined" && typeof URL !== "undefined" && typeof URL.createObjectURL === "function") {
    const blob = new Blob([res.data], { type: contentType || "application/octet-stream" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = fileName;
    link.style.display = "none";
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    setTimeout(() => URL.revokeObjectURL(url), 1500);
    return;
  }

  throw new Error("当前环境无法打开这个附件");
}

