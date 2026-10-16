"use client";

import { useState } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";

type Hit = {
  tenantId?: string;
  entityKey?: string;
  recordId?: string;
  recordState?: string;
  attrsPreview?: string;
  updatedAt?: string;
};

type Props = {
  phrases: PhraseBook;
  initialTenantId: string;
};

/**
 * Thin read-only entity_record search (Search read path MVP).
 * 只读 entity_record 搜索。
 */
export function SearchConsole({ phrases, initialTenantId }: Props) {
  const [tenantId, setTenantId] = useState(initialTenantId);
  const [q, setQ] = useState("");
  const [entityKey, setEntityKey] = useState("");
  const [field, setField] = useState("");
  const [value, setValue] = useState("");
  const [hits, setHits] = useState<Hit[]>([]);
  const [status, setStatus] = useState<"idle" | "loading" | "ok" | "error">("idle");
  const [errorStatus, setErrorStatus] = useState(0);
  const [mode, setMode] = useState<string | null>(null);

  async function runSearch() {
    const tid = tenantId.trim();
    if (!tid) {
      setStatus("error");
      setErrorStatus(0);
      return;
    }
    if (!q.trim() && !(field.trim() && value.trim())) {
      setStatus("error");
      setErrorStatus(0);
      return;
    }
    setStatus("loading");
    const params = new URLSearchParams({ tenantId: tid });
    if (q.trim()) params.set("q", q.trim());
    if (entityKey.trim()) params.set("entityKey", entityKey.trim());
    if (field.trim() && value.trim()) {
      params.set("field", field.trim());
      params.set("value", value.trim());
    }
    params.set("limit", "50");
    const reply = await fetch(`/api/platform/search/records?${params}`, {
      cache: "no-store",
      headers: { "X-Tenant-Id": tid },
    }).catch(() => null);
    if (!reply?.ok) {
      setHits([]);
      setMode(null);
      setErrorStatus(reply?.status ?? 0);
      setStatus("error");
      return;
    }
    const body = (await reply.json()) as { hits?: Hit[]; mode?: string };
    setHits(body.hits ?? []);
    setMode(body.mode ?? null);
    setStatus("ok");
  }

  return (
    <div className="flex flex-col gap-4">
      <p role="note" className="max-w-3xl rounded-md border border-border bg-surface px-3 py-2 text-sm text-muted">
        {phrases.searchStubNote}
      </p>
      <div className="flex flex-wrap gap-2 items-end">
        <label className="flex flex-col gap-1 text-sm">
          {phrases.searchTenantLabel}
          <input
            value={tenantId}
            onChange={(e) => setTenantId(e.target.value)}
            className="rounded-md border border-border bg-background px-2 py-1 font-mono text-sm"
            autoComplete="off"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm min-w-[12rem]">
          {phrases.searchQueryLabel}
          <input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder={phrases.searchQueryPlaceholder}
            className="rounded-md border border-border bg-background px-2 py-1 text-sm"
            autoComplete="off"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          {phrases.searchEntityKeyLabel}
          <input
            value={entityKey}
            onChange={(e) => setEntityKey(e.target.value)}
            placeholder={phrases.searchEntityKeyPlaceholder}
            className="rounded-md border border-border bg-background px-2 py-1 font-mono text-sm"
            autoComplete="off"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          {phrases.searchFieldLabel}
          <input
            value={field}
            onChange={(e) => setField(e.target.value)}
            className="rounded-md border border-border bg-background px-2 py-1 font-mono text-sm"
            autoComplete="off"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          {phrases.searchValueLabel}
          <input
            value={value}
            onChange={(e) => setValue(e.target.value)}
            className="rounded-md border border-border bg-background px-2 py-1 text-sm"
            autoComplete="off"
          />
        </label>
        <button
          type="button"
          onClick={() => void runSearch()}
          disabled={status === "loading"}
          className="rounded-md bg-foreground px-3 py-1.5 text-sm text-background disabled:opacity-60"
        >
          {status === "loading" ? phrases.searchSearching : phrases.searchAction}
        </button>
      </div>
      {status === "error" ? (
        <p role="alert" className="text-sm text-red-700">
          {errorStatus
            ? fillPhrase(phrases.searchFailed, { status: String(errorStatus) })
            : phrases.searchQueryRequired}
        </p>
      ) : null}
      {status === "ok" ? (
        <>
          <p className="text-xs text-muted">
            {fillPhrase(phrases.searchHitsCount, { count: String(hits.length) })}
            {mode ? ` · ${mode}` : null}
          </p>
          {hits.length === 0 ? (
            <p className="text-sm text-muted">{phrases.searchEmpty}</p>
          ) : (
            <div className="overflow-auto rounded-md border border-border">
              <table className="min-w-full text-left text-sm">
                <thead className="bg-surface text-xs text-muted">
                  <tr>
                    <th className="px-2 py-1">{phrases.searchColEntity}</th>
                    <th className="px-2 py-1">{phrases.searchColRecord}</th>
                    <th className="px-2 py-1">{phrases.searchColState}</th>
                    <th className="px-2 py-1">{phrases.searchColPreview}</th>
                    <th className="px-2 py-1">{phrases.searchColUpdated}</th>
                  </tr>
                </thead>
                <tbody>
                  {hits.map((h) => (
                    <tr key={`${h.entityKey}:${h.recordId}`} className="border-t border-border align-top">
                      <td className="px-2 py-1 font-mono text-xs">{h.entityKey}</td>
                      <td className="px-2 py-1 font-mono text-xs">{h.recordId}</td>
                      <td className="px-2 py-1 text-xs">{h.recordState}</td>
                      <td className="px-2 py-1 font-mono text-xs break-all max-w-md">{h.attrsPreview}</td>
                      <td className="px-2 py-1 text-xs whitespace-nowrap">{h.updatedAt}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      ) : null}
    </div>
  );
}
