"use client";

import Link from "next/link";
import { useCallback, useState } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";
import { orgTenantCookieWrite } from "@/lib/org-console";

/** One mailbox row from IM-2 API — IM-2 接口的一条收件。 */
export type InboxEntryRow = {
  entryId?: string;
  messageId?: string;
  tenantId?: string;
  channel?: string;
  title?: string;
  body?: string;
  severity?: string;
  readAt?: string | null;
  deepLink?: string | null;
  createdAt?: string;
  refKind?: string | null;
  refId?: string | null;
};

type Props = {
  phrases: PhraseBook;
  initialTenantId: string;
};

/**
 * InboxConsole — list / filter / mark-read / deep-link (client; tenant + Bearer via BFF).
 * 站内信控制台：列表、筛选、已读、深链（客户端；租户与 Bearer 经 BFF）。
 */
export function InboxConsole({ phrases, initialTenantId }: Props) {
  const [tenantId, setTenantId] = useState(initialTenantId);
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [entries, setEntries] = useState<InboxEntryRow[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [status, setStatus] = useState<"idle" | "loading" | "ok" | "error">("idle");
  const [errorStatus, setErrorStatus] = useState(0);
  const [notice, setNotice] = useState<string | null>(null);
  const [marking, setMarking] = useState(false);

  const tenantHeaders = useCallback((): Record<string, string> => {
    const headers: Record<string, string> = { accept: "application/json" };
    const tid = tenantId.trim();
    if (tid) headers["X-Tenant-Id"] = tid;
    return headers;
  }, [tenantId]);

  const load = useCallback(async () => {
    const tid = tenantId.trim();
    if (!tid) {
      setStatus("error");
      setErrorStatus(0);
      setEntries([]);
      return;
    }
    setStatus("loading");
    setNotice(null);
    const params = new URLSearchParams({
      tenantId: tid,
      unreadOnly: unreadOnly ? "true" : "false",
      limit: "50",
    });
    const reply = await fetch(`/api/platform/inbox/entries?${params}`, {
      cache: "no-store",
      headers: tenantHeaders(),
    }).catch(() => null);
    if (!reply?.ok) {
      setEntries([]);
      setSelectedId(null);
      setErrorStatus(reply?.status ?? 0);
      setStatus("error");
      return;
    }
    const body = (await reply.json()) as { entries?: InboxEntryRow[] };
    const rows = body.entries ?? [];
    setEntries(rows);
    setSelectedId((prev) => {
      if (prev && rows.some((r) => r.entryId === prev)) return prev;
      return rows[0]?.entryId ?? null;
    });
    // Persist tenant for header badge poll (IM-5) — 写入租户 cookie 供顶栏角标轮询。
    document.cookie = orgTenantCookieWrite(tid);
    setStatus("ok");
  }, [tenantId, unreadOnly, tenantHeaders]);

  async function markRead(entryId: string) {
    const tid = tenantId.trim();
    if (!tid || !entryId) return;
    setMarking(true);
    setNotice(null);
    const reply = await fetch(`/api/platform/inbox/entries/${encodeURIComponent(entryId)}/read`, {
      method: "POST",
      cache: "no-store",
      headers: tenantHeaders(),
    }).catch(() => null);
    setMarking(false);
    if (!reply?.ok) {
      setNotice(fillPhrase(phrases.inboxMarkReadFailed, { status: reply?.status ?? 0 }));
      return;
    }
    setNotice(phrases.inboxMarkReadDone);
    setEntries((prev) =>
      prev.map((row) =>
        row.entryId === entryId ? { ...row, readAt: row.readAt ?? new Date().toISOString() } : row,
      ),
    );
  }

  function selectEntry(entryId: string) {
    setSelectedId(entryId);
    const row = entries.find((e) => e.entryId === entryId);
    if (row && !row.readAt) {
      void markRead(entryId);
    }
  }

  const selected = entries.find((e) => e.entryId === selectedId) ?? null;

  function channelLabel(channel: string | undefined): string {
    switch ((channel ?? "").toUpperCase()) {
      case "SYSTEM":
        return phrases.inboxChannelSystem;
      case "WORKFLOW":
        return phrases.inboxChannelWorkflow;
      case "ORG_BROADCAST":
        return phrases.inboxChannelBroadcast;
      default:
        return channel ?? "—";
    }
  }

  function severityLabel(severity: string | undefined): string {
    switch ((severity ?? "").toUpperCase()) {
      case "INFO":
        return phrases.inboxSeverityInfo;
      case "WARN":
        return phrases.inboxSeverityWarn;
      case "ACTION":
        return phrases.inboxSeverityAction;
      default:
        return severity ?? "—";
    }
  }

  function formatTime(iso: string | undefined): string {
    if (!iso) return "—";
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return iso;
    return d.toLocaleString();
  }

  /** Console-relative deep link only — 仅允许控制台相对深链. */
  function safeDeepLink(raw: string | null | undefined): string | null {
    const link = (raw ?? "").trim();
    if (!link.startsWith("/") || link.startsWith("//")) return null;
    return link;
  }

  return (
    <div className="mt-4 flex flex-col gap-4">
      <div className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-sm">
          {phrases.inboxTenantLabel}
          <input
            value={tenantId}
            onChange={(e) => setTenantId(e.target.value)}
            placeholder={phrases.inboxTenantPlaceholder}
            className="rounded-md border border-border bg-background px-2 py-1 font-mono text-sm"
            autoComplete="off"
          />
        </label>
        <label className="flex items-center gap-2 text-sm pb-1">
          <input
            type="checkbox"
            checked={unreadOnly}
            onChange={(e) => setUnreadOnly(e.target.checked)}
          />
          {phrases.inboxUnreadOnly}
        </label>
        <button
          type="button"
          onClick={() => void load()}
          className="rounded-md bg-foreground px-3 py-1.5 text-sm text-background hover:opacity-90"
        >
          {status === "loading" ? phrases.inboxRefreshAction : phrases.inboxLoadAction}
        </button>
        {status === "ok" ? (
          <button
            type="button"
            onClick={() => void load()}
            className="rounded-md border border-border bg-surface px-3 py-1.5 text-sm hover:bg-background"
          >
            {phrases.inboxRefreshAction}
          </button>
        ) : null}
      </div>

      {status === "error" ? (
        <p role="alert" className="text-sm text-red-700 dark:text-red-400">
          {errorStatus === 0
            ? phrases.inboxNeedTenant
            : fillPhrase(phrases.inboxLoadFailed, { status: errorStatus })}
        </p>
      ) : null}
      {notice ? (
        <p role="status" className="text-sm text-muted">
          {notice}
        </p>
      ) : null}

      {status === "ok" && entries.length === 0 ? (
        <p className="text-sm text-muted">{unreadOnly ? phrases.inboxEmptyUnread : phrases.inboxEmpty}</p>
      ) : null}

      {entries.length > 0 ? (
        <div className="grid gap-4 lg:grid-cols-[minmax(0,1.2fr)_minmax(0,1fr)]">
          <div className="overflow-hidden rounded-lg border border-border bg-surface">
            <table className="w-full border-collapse text-sm">
              <thead className="bg-background text-left text-muted">
                <tr>
                  <th className="px-3 py-2 font-medium">{phrases.inboxStateColumn}</th>
                  <th className="px-3 py-2 font-medium">{phrases.inboxChannelColumn}</th>
                  <th className="px-3 py-2 font-medium">{phrases.inboxSeverityColumn}</th>
                  <th className="px-3 py-2 font-medium">{phrases.inboxTitleColumn}</th>
                  <th className="px-3 py-2 font-medium">{phrases.inboxTimeColumn}</th>
                </tr>
              </thead>
              <tbody>
                {entries.map((row) => {
                  const id = row.entryId ?? "";
                  const unread = !row.readAt;
                  const active = id === selectedId;
                  return (
                    <tr
                      key={id}
                      className={`cursor-pointer border-t border-border ${
                        active ? "bg-background" : "hover:bg-background/60"
                      } ${unread ? "font-semibold" : ""}`}
                      onClick={() => id && selectEntry(id)}
                    >
                      <td className="px-3 py-2">
                        <span
                          className={`rounded px-1.5 py-0.5 text-xs ${
                            unread
                              ? "bg-amber-100 text-amber-900 dark:bg-amber-900/40 dark:text-amber-100"
                              : "bg-background text-muted"
                          }`}
                        >
                          {unread ? phrases.inboxUnreadBadge : phrases.inboxReadBadge}
                        </span>
                      </td>
                      <td className="px-3 py-2">{channelLabel(row.channel)}</td>
                      <td className="px-3 py-2">{severityLabel(row.severity)}</td>
                      <td className="px-3 py-2">{row.title ?? "—"}</td>
                      <td className="px-3 py-2 whitespace-nowrap text-muted">
                        {formatTime(row.createdAt)}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          <aside className="rounded-lg border border-border bg-surface p-4 text-sm">
            {selected ? (
              <div className="flex flex-col gap-3">
                <div>
                  <h2 className="text-base font-semibold text-foreground">{selected.title}</h2>
                  <p className="mt-1 text-xs text-muted">
                    {channelLabel(selected.channel)} · {severityLabel(selected.severity)} ·{" "}
                    {formatTime(selected.createdAt)}
                  </p>
                </div>
                <div>
                  <h3 className="text-xs font-medium uppercase tracking-wide text-muted">
                    {phrases.inboxDetailBody}
                  </h3>
                  <p className="mt-1 whitespace-pre-wrap text-foreground">{selected.body || "—"}</p>
                </div>
                <div className="flex flex-wrap gap-2">
                  {!selected.readAt ? (
                    <button
                      type="button"
                      disabled={marking}
                      onClick={() => selected.entryId && void markRead(selected.entryId)}
                      className="rounded-md border border-border bg-background px-3 py-1.5 hover:bg-surface disabled:opacity-60"
                    >
                      {marking ? phrases.inboxMarkingRead : phrases.inboxMarkReadAction}
                    </button>
                  ) : null}
                  {safeDeepLink(selected.deepLink) ? (
                    <Link
                      href={safeDeepLink(selected.deepLink)!}
                      className="rounded-md bg-foreground px-3 py-1.5 text-background hover:opacity-90"
                    >
                      {phrases.inboxOpenLinkAction}
                    </Link>
                  ) : null}
                </div>
              </div>
            ) : (
              <p className="text-muted">{phrases.emptyList}</p>
            )}
          </aside>
        </div>
      ) : null}
    </div>
  );
}
