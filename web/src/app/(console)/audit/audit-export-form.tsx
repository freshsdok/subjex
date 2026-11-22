"use client";

import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";
import {
  auditExportFilename,
  auditExportWindowError,
  buildAuditExportPath,
  clampAuditExportLimit,
  localDateTimeToIso,
} from "@/lib/audit-export";
import { downloadBlob, filenameFromContentDisposition } from "@/lib/download-blob";

type TenantOption = { tenantId: string; tenantName: string };

/**
 * Audit export form — downloads NDJSON for one granted tenant in a ≤31-day window.
 * 审计导出表单：按已授权租户下载不超过 31 天窗口的 NDJSON。
 */
export function AuditExportForm({
  phrases,
  tenants,
  canExport,
}: {
  phrases: PhraseBook;
  tenants: TenantOption[];
  canExport: boolean;
}) {
  const router = useRouter();
  const defaults = useMemo(() => defaultLocalWindow(), []);
  const [tenantId, setTenantId] = useState(tenants[0]?.tenantId ?? "");
  const [fromLocal, setFromLocal] = useState(defaults.from);
  const [toLocal, setToLocal] = useState(defaults.to);
  const [limit, setLimit] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  if (!canExport) {
    return <p className="mt-2 text-sm text-muted">{phrases.auditExportPermissionHint}</p>;
  }

  async function runExport() {
    setProblem(null);
    setNotice(null);
    const fromIso = localDateTimeToIso(fromLocal);
    const toIso = localDateTimeToIso(toLocal);
    const windowError = auditExportWindowError(fromIso ?? "", toIso ?? "");
    if (!tenantId.trim()) {
      setProblem(phrases.auditExportTenantRequired);
      return;
    }
    if (windowError === "missing" || windowError === "invalid") {
      setProblem(phrases.auditExportRangeRequired);
      return;
    }
    if (windowError === "order") {
      setProblem(phrases.auditExportRangeOrder);
      return;
    }
    if (windowError === "tooLong") {
      setProblem(phrases.auditExportRangeTooLong);
      return;
    }
    const capped = clampAuditExportLimit(limit);
    const path = buildAuditExportPath({
      tenantId,
      fromIso: fromIso!,
      toIso: toIso!,
      limit: capped,
    });
    setBusy(true);
    const reply = await fetch(path, {
      method: "POST",
      headers: {
        accept: "application/x-ndjson, application/json",
        "X-Tenant-Id": tenantId.trim(),
      },
    }).catch(() => null);
    setBusy(false);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply?.ok) {
      const reason = await readReason(reply);
      setProblem(
        fillPhrase(phrases.auditExportFailed, {
          status: String(reply?.status ?? 0),
          reason: reason ? ` ${reason}` : "",
        }),
      );
      return;
    }
    const blob = await reply.blob();
    const fallback = auditExportFilename(tenantId, fromIso!, toIso!);
    downloadBlob(blob, filenameFromContentDisposition(reply.headers.get("content-disposition"), fallback));
    setNotice(phrases.auditExportDone);
  }

  return (
    <div className="mt-6 max-w-xl rounded-lg border border-border bg-surface p-4">
      <h2 className="text-sm font-medium">{phrases.auditExportTitle}</h2>
      <p className="mt-1 text-xs text-muted">{phrases.auditExportHint}</p>
      {tenants.length === 0 ? (
        <p className="mt-3 text-xs text-muted">{phrases.auditExportNoTenants}</p>
      ) : (
        <div className="mt-3 flex flex-col gap-3">
          <label className="flex flex-col gap-1 text-xs">
            {phrases.auditExportTenantLabel}
            <select
              value={tenantId}
              onChange={(event) => setTenantId(event.target.value)}
              className="rounded-md border border-border bg-background px-2 py-1"
            >
              {tenants.map((tenant) => (
                <option key={tenant.tenantId} value={tenant.tenantId}>
                  {tenant.tenantName
                    ? `${tenant.tenantName} (${tenant.tenantId})`
                    : tenant.tenantId}
                </option>
              ))}
            </select>
          </label>
          <div className="flex flex-wrap gap-3">
            <label className="flex min-w-[12rem] flex-1 flex-col gap-1 text-xs">
              {phrases.auditExportFromLabel}
              <input
                type="datetime-local"
                value={fromLocal}
                onChange={(event) => setFromLocal(event.target.value)}
                className="rounded-md border border-border bg-background px-2 py-1"
              />
            </label>
            <label className="flex min-w-[12rem] flex-1 flex-col gap-1 text-xs">
              {phrases.auditExportToLabel}
              <input
                type="datetime-local"
                value={toLocal}
                onChange={(event) => setToLocal(event.target.value)}
                className="rounded-md border border-border bg-background px-2 py-1"
              />
            </label>
          </div>
          <label className="flex flex-col gap-1 text-xs">
            {phrases.auditExportLimitLabel}
            <input
              value={limit}
              onChange={(event) => setLimit(event.target.value)}
              inputMode="numeric"
              className="max-w-[8rem] rounded-md border border-border bg-background px-2 py-1 font-mono"
              placeholder="1000"
              autoComplete="off"
            />
          </label>
        </div>
      )}
      {problem && (
        <p role="alert" className="mt-2 text-xs text-red-700">
          {problem}
        </p>
      )}
      {notice && (
        <p role="status" className="mt-2 text-xs text-green-700">
          {notice}
        </p>
      )}
      <div className="mt-3">
        <button
          type="button"
          onClick={runExport}
          disabled={busy || tenants.length === 0}
          className="rounded-md bg-foreground px-3 py-1.5 text-xs text-background disabled:opacity-60"
        >
          {busy ? phrases.auditExportWorking : phrases.auditExportAction}
        </button>
      </div>
    </div>
  );
}

function defaultLocalWindow(): { from: string; to: string } {
  const to = new Date();
  const from = new Date(to.getTime() - 7 * 24 * 60 * 60 * 1000);
  return { from: toLocalInput(from), to: toLocalInput(to) };
}

function toLocalInput(date: Date): string {
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

async function readReason(reply: Response | null): Promise<string> {
  if (!reply) return "";
  try {
    const body = (await reply.json()) as { reason?: unknown };
    return typeof body.reason === "string" ? body.reason : "";
  } catch {
    return "";
  }
}
