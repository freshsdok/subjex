"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";

// Override steps — 修改步骤：查看 → 编辑 → 核对改动 → 保存。多一步核对，避免误改线上配置。
type OverrideStep = "viewing" | "editing" | "reviewing" | "saving";

export function ConfigOverrideCell({
  configKey,
  currentValue,
  phrases,
}: {
  configKey: string;
  currentValue: string;
  phrases: PhraseBook;
}) {
  const router = useRouter();
  const [step, setStep] = useState<OverrideStep>("viewing");
  const [draftValue, setDraftValue] = useState(currentValue);
  const [problem, setProblem] = useState<string | null>(null);
  const [savedMessage, setSavedMessage] = useState<string | null>(null);

  function startEditing() {
    setDraftValue(currentValue);
    setProblem(null);
    setSavedMessage(null);
    setStep("editing");
  }

  function reviewChange() {
    if (draftValue.trim() === "") return setProblem(phrases.blankValueRefused);
    if (draftValue === currentValue) return setProblem(phrases.unchangedValue);
    setProblem(null);
    setStep("reviewing");
  }

  async function confirmSave() {
    setStep("saving");
    const reply = await fetch(`/api/platform/config/${encodeURIComponent(configKey)}`, {
      method: "PUT",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ value: draftValue }),
    }).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply?.ok) {
      setProblem(fillPhrase(phrases.saveFailed, { status: reply?.status ?? 0 }));
      setStep("editing");
      return;
    }
    setSavedMessage(fillPhrase(phrases.savedNotice, { key: configKey }));
    setStep("viewing");
    router.refresh();
  }

  if (step === "viewing") {
    return (
      <div className="flex items-center gap-3">
        <button onClick={startEditing} className="rounded-md border border-border px-2 py-0.5 text-xs hover:bg-background">
          {phrases.editAction}
        </button>
        {savedMessage && <span role="status" className="text-xs text-green-700">{savedMessage}</span>}
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-2">
      {step === "editing" ? (
        <input
          aria-label={configKey}
          value={draftValue}
          onChange={(event) => setDraftValue(event.target.value)}
          className="rounded-md border border-border bg-surface px-2 py-1 font-mono text-xs"
          autoFocus
        />
      ) : (
        <p className="text-xs">
          {fillPhrase(phrases.changeSummary, { key: configKey, before: currentValue, after: draftValue })}
        </p>
      )}
      {problem && <p role="alert" className="text-xs text-danger">{problem}</p>}
      <div className="flex gap-2">
        {step === "editing" ? (
          <button onClick={reviewChange} className="rounded-md bg-accent px-2 py-0.5 text-xs text-white">
            {phrases.reviewChangeAction}
          </button>
        ) : (
          <button
            onClick={confirmSave}
            disabled={step === "saving"}
            className="rounded-md bg-accent px-2 py-0.5 text-xs text-white disabled:opacity-60"
          >
            {step === "saving" ? phrases.savingAction : phrases.confirmSaveAction}
          </button>
        )}
        <button
          onClick={() => setStep(step === "reviewing" ? "editing" : "viewing")}
          disabled={step === "saving"}
          className="rounded-md border border-border px-2 py-0.5 text-xs"
        >
          {phrases.cancelAction}
        </button>
      </div>
    </div>
  );
}
