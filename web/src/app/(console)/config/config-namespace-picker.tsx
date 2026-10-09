"use client";

import { useRouter } from "next/navigation";
import type { PhraseBook } from "@/i18n/phrases";

/**
 * Namespace picker — MVP always includes {@code default}.
 * Deep link: changing select pushes {@code /config} or {@code /config?namespace=...}.
 * <p>
 * 命名空间选择：MVP 必含 default。切换时深链到 /config 或带 ?namespace=。
 */
export const CONFIG_NAMESPACE_OPTIONS = ["default"] as const;

export function ConfigNamespacePicker({
  selected,
  phrases,
}: {
  selected: string;
  phrases: PhraseBook;
}) {
  const router = useRouter();
  const options = Array.from(new Set([...CONFIG_NAMESPACE_OPTIONS, selected].filter(Boolean)));

  return (
    <div className="mb-4 flex flex-wrap items-center gap-2 text-sm">
      <label htmlFor="config-namespace" className="text-muted">
        {phrases.configNamespaceLabel}:
      </label>
      <select
        id="config-namespace"
        value={selected}
        onChange={(event) => {
          const next = event.target.value.trim() || "default";
          const query = next === "default" ? "" : `?namespace=${encodeURIComponent(next)}`;
          router.push(`/config${query}`);
        }}
        className="rounded-md border border-border bg-surface px-2 py-1 font-mono text-xs"
      >
        {options.map((ns) => (
          <option key={ns} value={ns}>
            {ns}
          </option>
        ))}
      </select>
    </div>
  );
}
