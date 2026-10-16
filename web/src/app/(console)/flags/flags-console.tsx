"use client";

import { useCallback, useEffect, useState } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";

type FlagRow = {
  key?: string;
  flagKey?: string;
  value?: string;
  enabled?: boolean | null;
  revision?: number;
  origin?: string;
};

type Props = {
  phrases: PhraseBook;
  initialTenantId: string;
  canWrite: boolean;
};

/**
 * Tenant feature flags list/edit (config namespace flags).
 * 租户功能开关列表/编辑。
 */
export function FlagsConsole({ phrases, initialTenantId, canWrite }: Props) {
  const [tenantId, setTenantId] = useState(initialTenantId);
  const [flags, setFlags] = useState<FlagRow[]>([]);
  const [status, setStatus] = useState<"idle" | "loading" | "ok" | "error">("idle");
  const [errorStatus, setErrorStatus] = useState(0);
  const [notice, setNotice] = useState<string | null>(null);
  const [newKey, setNewKey] = useState("search.records");
  const [newValue, setNewValue] = useState("true");
  const [savingKey, setSavingKey] = useState<string | null>(null);

  const load = useCallback(async () => {
    const tid = tenantId.trim();
    if (!tid) {
      setFlags([]);
      setStatus("idle");
      return;
    }
    setStatus("loading");
    setNotice(null);
    const q = `tenantId=${encodeURIComponent(tid)}`;
    const reply = await fetch(`/api/platform/flags?${q}`, { cache: "no-store" }).catch(() => null);
    if (!reply?.ok) {
      setFlags([]);
      setErrorStatus(reply?.status ?? 0);
      setStatus("error");
      return;
    }
    const body = (await reply.json()) as { flags?: FlagRow[] };
    setFlags(body.flags ?? []);
    setStatus("ok");
  }, [tenantId]);

  useEffect(() => {
    void load();
  }, [load]);

  async function saveFlag(flagKey: string, value: string) {
    if (!canWrite) return;
    const tid = tenantId.trim();
    const fk = flagKey.trim();
    if (!tid || !fk) return;
    setSavingKey(fk);
    setNotice(null);
    const q = `tenantId=${encodeURIComponent(tid)}`;
    const reply = await fetch(`/api/platform/flags/${encodeURIComponent(fk)}?${q}`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ value }),
    }).catch(() => null);
    setSavingKey(null);
    if (!reply?.ok) {
      setErrorStatus(reply?.status ?? 0);
      setStatus("error");
      return;
    }
    setNotice(phrases.flagsSavedNotice);
    await load();
  }

  return (
    <div className="flex flex-col gap-4">
      <p role="note" className="max-w-3xl rounded-md border border-border bg-surface px-3 py-2 text-sm text-muted">
        {phrases.flagsStubNote}
      </p>
      <div className="flex flex-wrap items-end gap-2">
        <label className="flex flex-col gap-1 text-sm">
          {phrases.flagsTenantLabel}
          <input
            value={tenantId}
            onChange={(e) => setTenantId(e.target.value)}
            className="rounded-md border border-border bg-background px-2 py-1 font-mono text-sm"
            autoComplete="off"
          />
        </label>
        <button
          type="button"
          onClick={() => void load()}
          className="rounded-md border border-border px-3 py-1.5 text-sm hover:bg-background"
        >
          {phrases.flagsRefreshAction}
        </button>
      </div>
      {status === "error" ? (
        <p role="alert" className="text-sm text-red-700">
          {fillPhrase(phrases.flagsFailed, { status: String(errorStatus) })}
        </p>
      ) : null}
      {notice ? (
        <p role="status" className="text-sm text-green-700">
          {notice}
        </p>
      ) : null}
      {status === "loading" ? <p className="text-sm text-muted">{phrases.flagsLoading}</p> : null}
      {status === "ok" && flags.length === 0 ? (
        <p className="text-sm text-muted">{phrases.flagsEmpty}</p>
      ) : null}
      {flags.length > 0 ? (
        <div className="overflow-auto rounded-md border border-border">
          <table className="min-w-full text-left text-sm">
            <thead className="bg-surface text-xs text-muted">
              <tr>
                <th className="px-2 py-1">{phrases.flagsColKey}</th>
                <th className="px-2 py-1">{phrases.flagsColValue}</th>
                <th className="px-2 py-1">{phrases.flagsColEnabled}</th>
                <th className="px-2 py-1">{phrases.flagsColRevision}</th>
                {canWrite ? <th className="px-2 py-1" /> : null}
              </tr>
            </thead>
            <tbody>
              {flags.map((f) => {
                const logical = (f.flagKey || f.key || "").trim();
                const storage = (f.key || "").trim();
                return (
                  <tr key={storage || logical} className="border-t border-border">
                    <td className="px-2 py-1 font-mono text-xs">
                      <div>{logical}</div>
                      {storage && storage !== logical ? (
                        <div className="text-muted">{storage}</div>
                      ) : null}
                    </td>
                    <td className="px-2 py-1 font-mono text-xs">{f.value}</td>
                    <td className="px-2 py-1 text-xs">
                      {f.enabled === true
                        ? phrases.flagsEnabledYes
                        : f.enabled === false
                          ? phrases.flagsEnabledNo
                          : phrases.flagsEnabledUnknown}
                    </td>
                    <td className="px-2 py-1 font-mono text-xs">{f.revision ?? 0}</td>
                    {canWrite ? (
                      <td className="px-2 py-1">
                        <div className="flex gap-1">
                          <button
                            type="button"
                            disabled={savingKey === logical}
                            onClick={() => void saveFlag(logical, "true")}
                            className="rounded border border-border px-1.5 py-0.5 text-xs"
                          >
                            {phrases.flagsEnableAction}
                          </button>
                          <button
                            type="button"
                            disabled={savingKey === logical}
                            onClick={() => void saveFlag(logical, "false")}
                            className="rounded border border-border px-1.5 py-0.5 text-xs"
                          >
                            {phrases.flagsDisableAction}
                          </button>
                        </div>
                      </td>
                    ) : null}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      ) : null}
      {canWrite ? (
        <div className="max-w-xl space-y-2 rounded-md border border-border bg-surface p-3">
          <p className="text-sm font-medium">{phrases.flagsAddTitle}</p>
          <p className="text-xs text-muted">{phrases.flagsAddHint}</p>
          <div className="flex flex-wrap gap-2 items-end">
            <label className="flex flex-col gap-1 text-sm">
              {phrases.flagsColKey}
              <input
                value={newKey}
                onChange={(e) => setNewKey(e.target.value)}
                className="rounded-md border border-border bg-background px-2 py-1 font-mono text-sm"
                autoComplete="off"
              />
            </label>
            <label className="flex flex-col gap-1 text-sm">
              {phrases.flagsColValue}
              <select
                value={newValue}
                onChange={(e) => setNewValue(e.target.value)}
                className="rounded-md border border-border bg-background px-2 py-1 text-sm"
              >
                <option value="true">true</option>
                <option value="false">false</option>
              </select>
            </label>
            <button
              type="button"
              disabled={!!savingKey}
              onClick={() => void saveFlag(newKey, newValue)}
              className="rounded-md bg-foreground px-3 py-1.5 text-sm text-background disabled:opacity-60"
            >
              {phrases.flagsSaveAction}
            </button>
          </div>
        </div>
      ) : (
        <p role="status" className="text-sm text-muted">
          {phrases.flagsReadOnlyNotice}
        </p>
      )}
    </div>
  );
}
