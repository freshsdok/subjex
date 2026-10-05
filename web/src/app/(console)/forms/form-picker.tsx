"use client";

import Link from "next/link";
import type { LanguageCode, PhraseBook } from "@/i18n/phrases";

type FormIndexEntry = {
  formKey: string;
  titleZh: string;
  titleEn: string;
  permission?: string;
  tenantScoped?: boolean;
  allowed: boolean;
};

// Form picker — 表单选择：从目录里挑一份；无声明权限的项置灰并标明所需权限。
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
        const label = (
          <>
            {title}
            <span className="ml-2 font-mono text-xs opacity-80">{entry.formKey}</span>
            {entry.permission ? (
              <span className="ml-2 font-mono text-xs opacity-70">{entry.permission}</span>
            ) : null}
            {entry.tenantScoped ? (
              <span className="ml-2 text-xs opacity-70">tenant</span>
            ) : null}
          </>
        );
        if (!entry.allowed) {
          return (
            <span
              key={entry.formKey}
              title={entry.permission}
              className="cursor-not-allowed rounded-md border border-border px-3 py-1.5 text-muted opacity-60"
            >
              {label}
            </span>
          );
        }
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
            {label}
          </Link>
        );
      })}
    </div>
  );
}
