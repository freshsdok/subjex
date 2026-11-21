/**
 * End-user inbox client (WP7-3) — 终端收件客户端。
 * Uses {@code /api/v1/end-user/inbox/**}, not leftover {@code /me/inbox} or operator {@code /inbox}.
 * 走终端收件路径，不再使用 /me/inbox 或操作员 /inbox。
 */

import { authedFetch } from "./platform";
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
