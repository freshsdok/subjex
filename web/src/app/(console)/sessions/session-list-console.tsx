"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";

export type SessionRow = {
  familyId: string;
  realm: string;
  subjectId: string;
  displayName: string;
  tenantId?: string;
  userAgent?: string;
  clientIp?: string;
  expiresAt?: string;
};

/**
 * Current-login tables plus a two-step revoke (not one-click).
 * 当前登录表格，以及两步确认的吊销（不是一键退出）。
 */
export function SessionListConsole({
  phrases,
  operatorSessions,
  endUserSessions,
  tenants,
  selectedTenantId,
  endUserProblem,
  canRevoke,
  language,
}: {
  phrases: PhraseBook;
  operatorSessions: SessionRow[];
  endUserSessions: SessionRow[];
  tenants: Array<{ tenantId: string; tenantName: string }>;
  selectedTenantId: string;
  endUserProblem: "sessionsEndUserForbidden" | "sessionsEndUserLoadFailed" | null;
  canRevoke: boolean;
  language: string;
}) {
  const router = useRouter();
  const [tenantId, setTenantId] = useState(selectedTenantId);
  const [pendingId, setPendingId] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const timeFormat = new Intl.DateTimeFormat(language === "zh" ? "zh-CN" : "en", {
    dateStyle: "medium",
    timeStyle: "medium",
  });

  function loadEndUsers(event: FormEvent) {
    event.preventDefault();
    const id = tenantId.trim();
    const query = id ? `?tenantId=${encodeURIComponent(id)}` : "";
    router.push(`/sessions${query}`);
  }

  async function revoke(row: SessionRow) {
    setProblem(null);
    setNotice(null);
    setBusyId(row.familyId);
    const path =
      row.realm === "end-user"
        ? `/api/platform/sessions/end-users/${encodeURIComponent(row.familyId)}/revoke?tenantId=${encodeURIComponent(row.tenantId || tenantId)}`
        : `/api/platform/sessions/operators/${encodeURIComponent(row.familyId)}/revoke`;
    const reply = await fetch(path, { method: "POST", headers: { accept: "application/json" } }).catch(
      () => null,
    );
    setBusyId(null);
    setPendingId(null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply?.ok) {
      setProblem(fillPhrase(phrases.sessionsRevokeFailed, { status: reply?.status ?? 0 }));
      return;
    }
    setNotice(phrases.sessionsRevokeDone);
    router.refresh();
  }

  return (
    <div className="space-y-8">
      {notice ? (
        <p role="status" className="text-xs text-green-700">
          {notice}
        </p>
      ) : null}
      {problem ? (
        <p role="alert" className="text-xs text-red-700">
          {problem}
        </p>
      ) : null}

      <section>
        <h2 className="text-sm font-medium">{phrases.sessionsOperatorsTitle}</h2>
        <p className="mt-1 text-xs text-muted">{phrases.sessionsOperatorsHint}</p>
        <SessionTable
          phrases={phrases}
          rows={operatorSessions}
          timeFormat={timeFormat}
          canRevoke={canRevoke}
          pendingId={pendingId}
          busyId={busyId}
          showTenant={false}
          onAsk={(id) => setPendingId(id)}
          onCancel={() => setPendingId(null)}
          onConfirm={revoke}
        />
      </section>

      <section>
        <h2 className="text-sm font-medium">{phrases.sessionsEndUsersTitle}</h2>
        <p className="mt-1 text-xs text-muted">{phrases.sessionsEndUsersHint}</p>
        <form onSubmit={loadEndUsers} className="mt-3 flex flex-wrap items-end gap-2 text-xs">
          <label className="flex flex-col gap-1">
            {phrases.sessionsTenantLabel}
            <select
              value={tenantId}
              onChange={(event) => setTenantId(event.target.value)}
              className="rounded-md border border-border bg-background px-2 py-1"
            >
              <option value="">{phrases.sessionsTenantPlaceholder}</option>
              {tenants.map((tenant) => (
                <option key={tenant.tenantId} value={tenant.tenantId}>
                  {tenant.tenantName ? `${tenant.tenantName} (${tenant.tenantId})` : tenant.tenantId}
                </option>
              ))}
            </select>
          </label>
          <button
            type="submit"
            className="rounded-md bg-foreground px-3 py-1.5 text-background disabled:opacity-60"
            disabled={!tenantId.trim()}
          >
            {phrases.sessionsSearchAction}
          </button>
        </form>
        {!selectedTenantId ? (
          <p className="mt-3 text-sm text-muted">{phrases.sessionsPickTenant}</p>
        ) : endUserProblem ? (
          <p className="mt-3 text-sm text-muted">{phrases[endUserProblem]}</p>
        ) : (
          <SessionTable
            phrases={phrases}
            rows={endUserSessions}
            timeFormat={timeFormat}
            canRevoke={canRevoke}
            pendingId={pendingId}
            busyId={busyId}
            showTenant={true}
            onAsk={(id) => setPendingId(id)}
            onCancel={() => setPendingId(null)}
            onConfirm={revoke}
          />
        )}
      </section>
    </div>
  );
}

function SessionTable({
  phrases,
  rows,
  timeFormat,
  canRevoke,
  pendingId,
  busyId,
  showTenant,
  onAsk,
  onCancel,
  onConfirm,
}: {
  phrases: PhraseBook;
  rows: SessionRow[];
  timeFormat: Intl.DateTimeFormat;
  canRevoke: boolean;
  pendingId: string | null;
  busyId: string | null;
  showTenant: boolean;
  onAsk: (familyId: string) => void;
  onCancel: () => void;
  onConfirm: (row: SessionRow) => void;
}) {
  if (rows.length === 0) {
    return <p className="mt-3 text-sm text-muted">{phrases.sessionsEmpty}</p>;
  }
  return (
    <table className="mt-3 w-full border-collapse overflow-hidden rounded-lg border border-border bg-surface text-sm">
      <thead className="bg-background text-left text-muted">
        <tr>
          <th className="px-3 py-2 font-medium">{phrases.sessionsWhoColumn}</th>
          {showTenant ? <th className="px-3 py-2 font-medium">{phrases.sessionsTenantColumn}</th> : null}
          <th className="px-3 py-2 font-medium">{phrases.sessionsClientColumn}</th>
          <th className="px-3 py-2 font-medium">{phrases.sessionsAddressColumn}</th>
          <th className="px-3 py-2 font-medium">{phrases.sessionsExpiresColumn}</th>
          {canRevoke ? <th className="px-3 py-2 font-medium">{phrases.sessionsActionsColumn}</th> : null}
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => {
          const pending = pendingId === row.familyId;
          const busy = busyId === row.familyId;
          return (
            <tr key={row.familyId} className="border-t border-border align-top">
              <td className="px-3 py-2">
                <div>{row.displayName || phrases.sessionsUnknownPerson}</div>
                <div className="font-mono text-xs text-muted">{row.subjectId}</div>
              </td>
              {showTenant ? <td className="px-3 py-2 font-mono text-xs">{row.tenantId}</td> : null}
              <td className="max-w-xs break-all px-3 py-2 text-xs">
                {row.userAgent?.trim() ? row.userAgent : phrases.sessionsNotRecorded}
              </td>
              <td className="px-3 py-2 font-mono text-xs">
                {row.clientIp?.trim() ? row.clientIp : phrases.sessionsNotRecorded}
              </td>
              <td className="whitespace-nowrap px-3 py-2">
                {row.expiresAt ? timeFormat.format(new Date(row.expiresAt)) : phrases.sessionsNotRecorded}
              </td>
              {canRevoke ? (
                <td className="px-3 py-2">
                  {pending ? (
                    <div className="flex flex-col items-start gap-1">
                      <p>{phrases.sessionsRevokeConfirm}</p>
                      <div className="flex gap-1">
                        <button
                          type="button"
                          onClick={() => onConfirm(row)}
                          disabled={busy}
                          className="rounded-md bg-red-800 px-2 py-0.5 text-xs text-white disabled:opacity-60"
                        >
                          {busy ? phrases.sessionsRevokeWorking : phrases.sessionsRevokeConfirmAction}
                        </button>
                        <button
                          type="button"
                          onClick={onCancel}
                          disabled={busy}
                          className="rounded-md border border-border px-2 py-0.5 text-xs"
                        >
                          {phrases.cancelAction}
                        </button>
                      </div>
                    </div>
                  ) : (
                    <button
                      type="button"
                      onClick={() => onAsk(row.familyId)}
                      className="rounded-md border border-border px-2 py-0.5 text-xs hover:bg-background"
                    >
                      {phrases.sessionsRevokeAction}
                    </button>
                  )}
                </td>
              ) : null}
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
