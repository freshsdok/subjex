"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import {
  formatInboxUnreadCount,
  INBOX_UNREAD_POLL_MS,
  resolveInboxTenantId,
} from "@/lib/inbox-console";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";

type Props = {
  phrases: PhraseBook;
  /** Server cookie hint (org / declaration) — 服务端 cookie 初值. */
  initialTenantId: string;
};

/**
 * InboxUnreadBadge — IM-5 header badge: ~60s poll + pause when tab hidden; click → /inbox.
 * 顶栏未读角标：约 60s 轮询；页签隐藏暂停；点击进站内信。
 */
export function InboxUnreadBadge({ phrases, initialTenantId }: Props) {
  const [count, setCount] = useState<number | null>(null);
  const [tenantId, setTenantId] = useState(initialTenantId.trim());

  const refresh = useCallback(async () => {
    if (typeof document !== "undefined" && document.hidden) {
      return;
    }
    const cookieHeader = typeof document !== "undefined" ? document.cookie : "";
    const tid = resolveInboxTenantId(initialTenantId, cookieHeader);
    setTenantId(tid);
    if (!tid) {
      setCount(null);
      return;
    }
    const params = new URLSearchParams({ tenantId: tid });
    const reply = await fetch(`/api/platform/inbox/unread-count?${params}`, {
      cache: "no-store",
      headers: { accept: "application/json", "X-Tenant-Id": tid },
    }).catch(() => null);
    if (!reply?.ok) {
      setCount(null);
      return;
    }
    const body = (await reply.json()) as { count?: unknown };
    const n = typeof body.count === "number" ? body.count : Number(body.count);
    setCount(Number.isFinite(n) && n >= 0 ? Math.floor(n) : null);
  }, [initialTenantId]);

  useEffect(() => {
    let cancelled = false;
    const safeRefresh = () => {
      if (!cancelled) void refresh();
    };
    safeRefresh();
    const intervalId = window.setInterval(safeRefresh, INBOX_UNREAD_POLL_MS);
    const onVisibility = () => {
      if (!document.hidden) safeRefresh();
    };
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      cancelled = true;
      window.clearInterval(intervalId);
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [refresh]);

  const badgeText = count != null && count > 0 ? formatInboxUnreadCount(count) : "";
  const title = tenantId
    ? count != null && count > 0
      ? fillPhrase(phrases.inboxBadgeUnreadTitle, { count })
      : phrases.inboxBadgeEmptyTitle
    : phrases.inboxBadgeNeedTenantTitle;

  return (
    <Link
      href="/inbox"
      title={title}
      className="relative inline-flex items-center gap-1 rounded-md border border-border px-2 py-1 text-sm hover:bg-background"
      aria-label={title}
    >
      <span>{phrases.navInbox}</span>
      {badgeText ? (
        <span className="min-w-[1.25rem] rounded-full bg-amber-500 px-1.5 py-0.5 text-center text-xs font-semibold text-white">
          {badgeText}
        </span>
      ) : null}
    </Link>
  );
}
