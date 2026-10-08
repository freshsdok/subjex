"use client";

import type { PhraseBook } from "@/i18n/phrases";
import {
  FLOW_SECTION_KEYS,
  type FlowPageBlocks,
  type FlowSectionKey,
  moveBlockId,
  toggleBlockId,
} from "@/lib/flow-block-composer";
import { PAGE_BLOCKS_CATALOG } from "@/lib/page-blocks-catalog";

type Props = {
  phrases: PhraseBook;
  language: "zh" | "en";
  value: FlowPageBlocks;
  disabled?: boolean;
  onChange: (next: FlowPageBlocks) => void;
  onApply: () => void;
};

function sectionTitle(phrases: PhraseBook, key: FlowSectionKey): string {
  switch (key) {
    case "list":
      return phrases.declarationsComposerList;
    case "detail":
      return phrases.declarationsComposerDetail;
    case "submit":
      return phrases.declarationsComposerSubmit;
  }
}

// FlowBlockComposer — 流程页结构化积木编排（非自由画布）：勾选/调序后「应用到 YAML」。
export function FlowBlockComposer({ phrases, language, value, disabled, onChange, onApply }: Props) {
  function updateSection(key: FlowSectionKey, ids: string[]) {
    onChange({ ...value, [key]: ids });
  }

  return (
    <div className="rounded-md border border-border bg-background px-3 py-3">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <div>
          <p className="text-sm font-medium">{phrases.declarationsComposerTitle}</p>
          <p className="text-xs text-muted">{phrases.declarationsComposerHint}</p>
        </div>
        <button
          type="button"
          onClick={onApply}
          disabled={disabled}
          className="rounded-md border border-border px-2 py-1 text-xs hover:bg-surface disabled:opacity-60"
        >
          {phrases.declarationsComposerApplyAction}
        </button>
      </div>
      <div className="grid gap-3 md:grid-cols-3">
        {FLOW_SECTION_KEYS.map((section) => {
          const selected = value[section];
          return (
            <div key={section} className="rounded-md border border-border bg-surface p-2">
              <p className="mb-2 text-xs font-semibold">{sectionTitle(phrases, section)}</p>
              <ul className="flex flex-col gap-1">
                {PAGE_BLOCKS_CATALOG.map((entry) => {
                  const checked = selected.includes(entry.id);
                  const orderIndex = selected.indexOf(entry.id);
                  const title = language === "zh" ? entry.titleZh : entry.titleEn;
                  return (
                    <li key={entry.id} className="flex items-start gap-1 text-xs">
                      <label className="flex min-w-0 flex-1 items-start gap-1">
                        <input
                          type="checkbox"
                          className="mt-0.5"
                          checked={checked}
                          disabled={disabled}
                          onChange={(event) =>
                            updateSection(
                              section,
                              toggleBlockId(selected, entry.id, event.target.checked),
                            )
                          }
                        />
                        <span className="min-w-0">
                          <span className="font-mono">{entry.id}</span>
                          <span className="ml-1 text-muted">{title}</span>
                          {checked ? (
                            <span className="ml-1 text-muted">#{orderIndex + 1}</span>
                          ) : null}
                        </span>
                      </label>
                      {checked ? (
                        <span className="flex shrink-0 gap-0.5">
                          <button
                            type="button"
                            disabled={disabled || orderIndex <= 0}
                            aria-label={phrases.declarationsComposerMoveUp}
                            title={phrases.declarationsComposerMoveUp}
                            onClick={() =>
                              updateSection(section, moveBlockId(selected, entry.id, -1))
                            }
                            className="rounded border border-border px-1 disabled:opacity-40"
                          >
                            ↑
                          </button>
                          <button
                            type="button"
                            disabled={disabled || orderIndex >= selected.length - 1}
                            aria-label={phrases.declarationsComposerMoveDown}
                            title={phrases.declarationsComposerMoveDown}
                            onClick={() =>
                              updateSection(section, moveBlockId(selected, entry.id, 1))
                            }
                            className="rounded border border-border px-1 disabled:opacity-40"
                          >
                            ↓
                          </button>
                        </span>
                      ) : null}
                    </li>
                  );
                })}
              </ul>
            </div>
          );
        })}
      </div>
    </div>
  );
}
