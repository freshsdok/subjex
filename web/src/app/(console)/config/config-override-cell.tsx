"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";

// Override steps — 修改步骤：查看 → 编辑 → 核对改动 → 保存。多一步核对，避免误改线上配置。
// Config-5c/5d: If-Match from revision; PUT carries namespace.
type OverrideStep = "viewing" | "editing" | "reviewing" | "saving";

export function ConfigOverrideCell({
  configKey,
  currentValue,
  revision,
  namespace,
  phrases,
}: {
  configKey: string;
  currentValue: string;
  revision: number;
  namespace: string;
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
    const headers: Record<string, string> = { "content-type": "application/json" };
    // Optimistic concurrency when we know a revision (including 0 for local-only).
    // 已知修订号时带 If-Match（含本地 0）。
    if (Number.isFinite(revision) && revision >= 0) {
      headers["If-Match"] = `"${revision}"`;
    }
    const ns = namespace.trim() || "default";
    const query = ns === "default" ? "" : `?namespace=${encodeURIComponent(ns)}`;
    const reply = await fetch(`/api/platform/config/${encodeURIComponent(configKey)}${query}`, {
      method: "PUT",
      headers,
      body: JSON.stringify({ value: draftValue, namespace: ns }),
    }).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (reply?.status === 412) {
      setProblem(phrases.configRevisionMismatch);
      setStep("editing");
      return;
    }
    if (!reply?.ok) {
      setProblem(fillPhrase(phrases.saveFailed, { status: reply?.status ?? 0 }));
      setStep("editing");
      return;
    }
    let nextRevision = revision + 1;
    try {
      const body = (await reply.json()) as { revision?: number };
      if (typeof body.revision === "number") nextRevision = body.revision;
    } catch {
      // body optional on some paths
    }
    setSavedMessage(fillPhrase(phrases.savedNotice, { key: configKey, revision: nextRevision }));
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
