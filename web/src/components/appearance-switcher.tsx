"use client";

import { useRouter } from "next/navigation";
import type { LanguageCode, PhraseBook } from "@/i18n/phrases";

// Readable skin names — 皮肤的可读名称；未知皮肤直接显示原名。
const skinPhraseKey: Record<string, keyof PhraseBook> = {
  plain: "skinPlain",
  "high-contrast": "skinHighContrast",
  calm: "skinCalm",
};

const oneYearSeconds = 365 * 24 * 60 * 60;

// Language and skin switcher — 语言与外观切换：写 cookie 后刷新，服务端按 cookie 渲染。
export function AppearanceSwitcher({
  phrases,
  language,
  skinNames,
  chosenSkinName,
}: {
  phrases: PhraseBook;
  language: LanguageCode;
  skinNames: string[];
  chosenSkinName: string | null;
}) {
  const router = useRouter();
  function rememberChoice(cookieName: string, chosenValue: string) {
    document.cookie = `${cookieName}=${encodeURIComponent(chosenValue)}; path=/; max-age=${oneYearSeconds}; samesite=strict`;
    router.refresh();
  }
  return (
    <div className="flex items-center gap-2">
      <label className="flex items-center gap-1">
        <span className="sr-only">{phrases.languageLabel}</span>
        <select
          aria-label={phrases.languageLabel}
          value={language}
          onChange={(event) => rememberChoice("subjex_language", event.target.value)}
          className="rounded-md border border-border bg-surface px-1 py-0.5"
        >
          <option value="zh">中文</option>
          <option value="en">English</option>
        </select>
      </label>
      {skinNames.length > 0 && (
        <label className="flex items-center gap-1">
          <span className="sr-only">{phrases.skinLabel}</span>
          <select
            aria-label={phrases.skinLabel}
            value={chosenSkinName ?? skinNames[0]}
            onChange={(event) => rememberChoice("subjex_skin", event.target.value)}
            className="rounded-md border border-border bg-surface px-1 py-0.5"
          >
            {skinNames.map((skinName) => (
              <option key={skinName} value={skinName}>
                {skinPhraseKey[skinName] ? phrases[skinPhraseKey[skinName]] : skinName}
              </option>
            ))}
          </select>
        </label>
      )}
    </div>
  );
}
