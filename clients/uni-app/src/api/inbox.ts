/**
 * END_USER inbox client (IM-6) — 终端收件客户端。
 * Uses {@code /api/v1/me/inbox/**} (realm END_USER), not operator {@code /inbox}.
 * 走 /me/inbox（END_USER 域），不是操作员 /inbox。
 */

import { platformFetch } from "./platform";
import { readBearer } from "./auth-token";

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

export async function listMyInbox(opts: {
  tenantId: string;
  unreadOnly?: boolean;
}): Promise<InboxEntry[]> {
  const q = new URLSearchParams();
  q.set("tenantId", opts.tenantId);
  if (opts.unreadOnly) q.set("unreadOnly", "true");
  const doc = await platformFetch<{ entries: InboxEntry[] }>({
    path: `/api/v1/me/inbox/entries?${q.toString()}`,
    bearer: readBearer(),
    headers: { "X-Tenant-Id": opts.tenantId },
  });
  return doc.entries || [];
}

export async function unreadCount(tenantId: string): Promise<number> {
  const doc = await platformFetch<{ count: number }>({
    path: `/api/v1/me/inbox/unread-count?tenantId=${encodeURIComponent(tenantId)}`,
    bearer: readBearer(),
    headers: { "X-Tenant-Id": tenantId },
  });
  return doc.count ?? 0;
}

export async function markRead(tenantId: string, entryId: string): Promise<void> {
  await platformFetch({
    method: "POST",
    path: `/api/v1/me/inbox/entries/${encodeURIComponent(entryId)}/read?tenantId=${encodeURIComponent(tenantId)}`,
    bearer: readBearer(),
    headers: { "X-Tenant-Id": tenantId },
  });
}
