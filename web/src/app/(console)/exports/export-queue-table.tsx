"use client";

import type { PhraseBook } from "@/i18n/phrases";
import {
  filterExportQueueRows,
  sortExportQueueRows,
  type ExportQueueRow,
} from "@/lib/export-queue";
import { useState } from "react";
import { ExportQueueActions } from "./export-queue-actions";

/**
 * Client table for the export queue so row actions can update status without a full reload.
 * 导出队列的客户端表格，便于行内操作后就地更新状态，不必整页重载。
 */
export function ExportQueueTable({
  initialRows,
  statusFilter,
  tenantId,
  operatorSubjectId,
  canApprove,
  phrases,
  language,
}: {
  initialRows: ExportQueueRow[];
  statusFilter: string;
  tenantId: string;
  operatorSubjectId: string;
  canApprove: boolean;
  phrases: PhraseBook;
  language: "zh" | "en";
}) {
  const [rows, setRows] = useState(initialRows);
  const visible = sortExportQueueRows(filterExportQueueRows(rows, statusFilter));
  const timeFormat = new Intl.DateTimeFormat(language === "zh" ? "zh-CN" : "en", {
    dateStyle: "medium",
    timeStyle: "medium",
  });

  function statusLabel(status: string | undefined): string {
    switch ((status ?? "").toUpperCase()) {
      case "REQUESTED":
        return phrases.exportsStatusRequested;
      case "APPROVED":
        return phrases.exportsStatusApproved;
      case "GENERATING":
        return phrases.exportsStatusGenerating;
      case "FAILED":
        return phrases.exportsStatusFailed;
      case "REJECTED":
        return phrases.exportsStatusRejected;
      case "READY":
        return phrases.exportsStatusReady;
      case "EXPIRED":
        return phrases.exportsStatusExpired;
      default:
        return status ?? "";
    }
  }

  function requesterLabel(row: ExportQueueRow): string {
    const realm =
      (row.requesterRealm ?? "").toUpperCase() === "END_USER"
        ? phrases.exportsRequesterEndUser
        : phrases.exportsRequesterOperator;
    const id = (row.requesterId ?? "").trim();
    return id ? `${realm} · ${id}` : realm;
  }

  function formatTime(value: string | undefined): string {
    if (!value) return "—";
    const parsed = new Date(value);
    if (Number.isNaN(parsed.getTime())) return value;
    return timeFormat.format(parsed);
  }

  if (visible.length === 0) {
    return <p className="text-sm text-muted">{phrases.exportsEmpty}</p>;
  }

  return (
    <table className="w-full border-collapse overflow-hidden rounded-lg border border-border bg-surface text-sm">
      <thead className="bg-background text-left text-muted">
        <tr>
          <th className="px-3 py-2 font-medium">{phrases.exportsCreatedAtLabel}</th>
          <th className="px-3 py-2 font-medium">{phrases.exportsSubjectLabel}</th>
          <th className="px-3 py-2 font-medium">{phrases.exportsRequesterLabel}</th>
          <th className="px-3 py-2 font-medium">{phrases.exportsScopeLabel}</th>
          <th className="px-3 py-2 font-medium">{phrases.exportsStatusLabel}</th>
          <th className="px-3 py-2 font-medium">{phrases.exportsExpiresAtLabel}</th>
          <th className="px-3 py-2 font-medium">{phrases.exportsActionsLabel}</th>
        </tr>
      </thead>
      <tbody>
        {visible.map((row) => (
          <tr key={row.requestId} className="border-t border-border align-top">
            <td className="whitespace-nowrap px-3 py-2">{formatTime(row.createdAt)}</td>
            <td className="px-3 py-2 font-mono text-xs">{row.subjectId}</td>
            <td className="px-3 py-2 font-mono text-xs">{requesterLabel(row)}</td>
            <td className="px-3 py-2">{row.scope || "—"}</td>
            <td className="px-3 py-2">{statusLabel(row.status)}</td>
            <td className="whitespace-nowrap px-3 py-2">{formatTime(row.expiresAt)}</td>
            <td className="px-3 py-2">
              <ExportQueueActions
                row={row}
                tenantId={tenantId}
                operatorSubjectId={operatorSubjectId}
                canApprove={canApprove}
                phrases={phrases}
                onUpdated={(updated) => {
                  setRows((current) =>
                    current.map((item) =>
                      item.requestId && item.requestId === updated.requestId ? updated : item,
                    ),
                  );
                }}
              />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
