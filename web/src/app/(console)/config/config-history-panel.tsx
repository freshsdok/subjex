import Link from "next/link";
import type { PhraseBook } from "@/i18n/phrases";
import { ConfigHistoryRestoreButton } from "./config-history-restore-button";

/** One history row from GET /api/v1/config/{key}/history — 历史行。 */
export type ConfigHistoryEntry = {
  namespace?: string;
  key?: string;
  value?: string;
  revision?: number;
  overriddenAt?: string;
};

/**
 * Thin override-history table (Config-6b) + optional restore (Config-7a).
 * Restore is an explicit new put from a row — never silent rewind.
 * <p>
 * 覆盖历史薄表；可选恢复（显式再 put，不静默倒带）。
 */
export function ConfigHistoryPanel({
  namespace,
  historyKey,
  entries,
  phrases,
  canRestore,
  currentRevision,
}: {
  namespace: string;
  historyKey: string;
  entries: ConfigHistoryEntry[];
  phrases: PhraseBook;
  canRestore: boolean;
  currentRevision: number;
}) {
  const clearHref =
    namespace === "default"
      ? "/config"
      : `/config?namespace=${encodeURIComponent(namespace)}`;
  return (
    <section
      aria-labelledby="config-history-heading"
      className="mb-6 max-w-4xl rounded-lg border border-border bg-surface p-3"
    >
      <div className="mb-2 flex flex-wrap items-baseline justify-between gap-2">
        <h2 id="config-history-heading" className="text-sm font-medium">
          {phrases.configHistoryTitle}:{" "}
          <span className="font-mono text-xs">{historyKey}</span>
        </h2>
        <Link href={clearHref} className="text-xs text-muted underline">
          {phrases.configHistoryClose}
        </Link>
      </div>
      <p className="mb-3 text-xs text-muted">{phrases.configHistoryHint}</p>
      {entries.length === 0 ? (
        <p className="text-sm text-muted">{phrases.configHistoryEmpty}</p>
      ) : (
        <table className="w-full border-collapse text-sm">
          <thead className="bg-background text-left text-muted">
            <tr>
              <th className="px-2 py-1 font-medium">{phrases.configRevisionColumn}</th>
              <th className="px-2 py-1 font-medium">{phrases.configValueColumn}</th>
              <th className="px-2 py-1 font-medium">{phrases.configHistoryAtColumn}</th>
              {canRestore && <th className="px-2 py-1 font-medium">{phrases.configRestoreColumn}</th>}
            </tr>
          </thead>
          <tbody>
            {entries.map((row) => (
              <tr
                key={`${row.namespace ?? namespace}:${row.key ?? historyKey}:${row.revision}`}
                className="border-t border-border align-top"
              >
                <td className="px-2 py-1 font-mono text-xs">{row.revision ?? 0}</td>
                <td className="px-2 py-1 font-mono text-xs">{row.value ?? ""}</td>
                <td className="px-2 py-1 font-mono text-xs text-muted">
                  {row.overriddenAt ?? ""}
                </td>
                {canRestore && (
                  <td className="px-2 py-1">
                    <ConfigHistoryRestoreButton
                      configKey={historyKey}
                      namespace={namespace}
                      fromRevision={row.revision ?? 0}
                      fromValue={row.value ?? ""}
                      currentRevision={currentRevision}
                      phrases={phrases}
                    />
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
