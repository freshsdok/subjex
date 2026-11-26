"use client";

import { fillPhrase, type PhraseBook } from "@/i18n/phrases";
import {
  canDecideExport,
  canRetryExport,
  canIssueExportDownload,
  exportQueueErrorPhraseKey,
  isOwnExportRequest,
  resolveIssuedDownloadHref,
  type ExportQueueRow,
} from "@/lib/export-queue";
import { useState } from "react";

/**
 * Row actions for the export queue: approve, reject, retry a failed generation, or issue a short-lived link.
 * 导出队列行内操作：批准、驳回、失败后重试，或签发已有的短时下载链接。不能代审自己的申请。批准后自动开始生成。
 */
export function ExportQueueActions({
  row,
  tenantId,
  operatorSubjectId,
  canApprove,
  phrases,
  onUpdated,
}: {
  row: ExportQueueRow;
  tenantId: string;
  operatorSubjectId: string;
  canApprove: boolean;
  phrases: PhraseBook;
  onUpdated: (updated: ExportQueueRow) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [downloadHref, setDownloadHref] = useState<string | null>(null);
  const requestId = (row.requestId ?? "").trim();
  const ownRequest = isOwnExportRequest(row.requesterId, operatorSubjectId);
  const showDecide = canDecideExport(row.status, row.requesterId, operatorSubjectId, canApprove);
  const showRetry = canRetryExport(row.status, canApprove);
  const generating = (row.status ?? "").toUpperCase() === "GENERATING";
  const showDownload = canIssueExportDownload(row.status, canApprove);

  if (!requestId || (!canApprove && !ownRequest)) {
    return null;
  }

  async function postAction(action: "approve" | "reject" | "generate" | "download-link"): Promise<Response | null> {
    setBusy(true);
    setNotice(null);
    setDownloadHref(null);
    const headers: Record<string, string> = {
      Accept: "application/json",
      "X-Tenant-Id": tenantId,
    };
    let body: string | undefined;
    if (action === "download-link") {
      headers["Content-Type"] = "application/json";
      body = "{}";
    }
    const reply = await fetch(
      `/api/platform/exports/${encodeURIComponent(requestId)}/${action}`,
      { method: "POST", headers, body },
    ).catch(() => null);
    setBusy(false);
    return reply;
  }

  async function readReason(reply: Response): Promise<string> {
    try {
      const body = (await reply.json()) as { reason?: unknown };
      return typeof body.reason === "string" ? body.reason : "";
    } catch {
      return "";
    }
  }

  function fail(reply: Response | null, reason: string) {
    const key = exportQueueErrorPhraseKey(reason);
    setNotice(fillPhrase(phrases[key], { status: reply?.status ?? 0 }));
  }

  async function decide(action: "approve" | "reject") {
    if (!showDecide) return;
    const reply = await postAction(action);
    if (!reply?.ok) {
      fail(reply, reply ? await readReason(reply) : "");
      return;
    }
    const updated = (await reply.json()) as ExportQueueRow;
    setNotice(action === "approve" ? phrases.exportsApprovedNotice : phrases.exportsRejectedNotice);
    onUpdated(updated);
  }

  async function retry() {
    if (!showRetry) return;
    const reply = await postAction("generate");
    if (!reply?.ok) {
      fail(reply, reply ? await readReason(reply) : "");
      return;
    }
    const updated = (await reply.json()) as ExportQueueRow;
    setNotice(phrases.exportsRetriedNotice);
    onUpdated(updated);
  }

  async function issueDownload() {
    if (!showDownload) return;
    const reply = await postAction("download-link");
    if (!reply?.ok) {
      fail(reply, reply ? await readReason(reply) : "");
      return;
    }
    const body = (await reply.json()) as { url?: unknown; expiresAt?: unknown };
    const href = resolveIssuedDownloadHref(typeof body.url === "string" ? body.url : "");
    if (!href) {
      fail(reply, "export-artifact-unavailable");
      return;
    }
    const expiresAt =
      typeof body.expiresAt === "string" && body.expiresAt ? body.expiresAt : "";
    setDownloadHref(href);
    setNotice(
      expiresAt
        ? fillPhrase(phrases.exportsDownloadIssuedNotice, { expiresAt })
        : phrases.exportsDownloadReadyNotice,
    );
    window.open(href, "_blank", "noopener,noreferrer");
  }

  return (
    <div className="flex flex-col items-start gap-1">
      <div className="flex flex-wrap gap-2">
        {showDecide ? (
          <>
            <button
              type="button"
              disabled={busy}
              onClick={() => void decide("approve")}
              className="rounded-md bg-accent px-2 py-1 text-xs text-white disabled:opacity-60"
            >
              {phrases.exportsApproveAction}
            </button>
            <button
              type="button"
              disabled={busy}
              onClick={() => void decide("reject")}
              className="rounded-md border border-border px-2 py-1 text-xs hover:bg-background disabled:opacity-60"
            >
              {phrases.exportsRejectAction}
            </button>
          </>
        ) : null}
        {showRetry ? (
          <button
            type="button"
            disabled={busy}
            onClick={() => void retry()}
            className="rounded-md bg-accent px-2 py-1 text-xs text-white disabled:opacity-60"
          >
            {phrases.exportsRetryAction}
          </button>
        ) : null}
        {showDownload ? (
          <button
            type="button"
            disabled={busy}
            onClick={() => void issueDownload()}
            className="rounded-md bg-accent px-2 py-1 text-xs text-white disabled:opacity-60"
          >
            {phrases.exportsDownloadAction}
          </button>
        ) : null}
      </div>
      {ownRequest && (row.status ?? "").toUpperCase() === "REQUESTED" ? (
        <p className="text-xs text-muted">{phrases.exportsSelfApprovalHint}</p>
      ) : null}
      {generating ? <p className="text-xs text-muted">{phrases.exportsGeneratingHint}</p> : null}
      {(row.status ?? "").toUpperCase() === "FAILED" && !notice ? (
        <p className="text-xs text-muted">{phrases.exportsFailedHint}</p>
      ) : null}
      {notice ? <p className="text-xs text-muted">{notice}</p> : null}
      {downloadHref ? (
        <a href={downloadHref} target="_blank" rel="noreferrer" className="text-xs text-accent underline">
          {phrases.exportsOpenDownloadLink}
        </a>
      ) : null}
    </div>
  );
}
