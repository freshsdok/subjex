"use client";

import Link from "next/link";
import type { LanguageCode, PhraseBook } from "@/i18n/phrases";

type FormIndexEntry = { formKey: string; titleZh: string; titleEn: string };

// Form picker — 表单选择：从目录里挑一份，刷新页面加载详情。
export function FormPicker({
  forms,
  selectedFormKey,
  language,
  phrases,
}: {
  forms: FormIndexEntry[];
  selectedFormKey: string;
  language: LanguageCode;
  phrases: PhraseBook;
}) {
  return (
    <div className="mb-4 flex flex-wrap items-center gap-2 text-sm">
      <span className="text-muted">{phrases.formPickLabel}:</span>
      {forms.map((entry) => {
        const title = language === "zh" ? entry.titleZh : entry.titleEn;
        const selected = entry.formKey === selectedFormKey;
        return (
          <Link
            key={entry.formKey}
            href={`/forms?form=${encodeURIComponent(entry.formKey)}`}
            className={
              selected
                ? "rounded-md bg-accent px-3 py-1.5 text-white"
                : "rounded-md border border-border px-3 py-1.5 hover:bg-surface"
            }
          >
            {title}
            <span className="ml-2 font-mono text-xs opacity-80">{entry.formKey}</span>
          </Link>
        );
      })}
    </div>
  );
}
