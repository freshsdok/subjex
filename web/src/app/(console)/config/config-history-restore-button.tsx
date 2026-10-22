"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";

/**
 * Config-7a: restore = POST history row value as a new put (no silent rewind).
 * Confirm step mirrors override-cell review; If-Match uses current live revision.
 * <p>
 * 从历史行显式恢复：POST 再 put；核对一步；If-Match 对应当前生效修订。
 */
type RestoreStep = "idle" | "confirming" | "saving";

export function ConfigHistoryRestoreButton({
  configKey,
  namespace,
  fromRevision,
  fromValue,
  currentRevision,
  phrases,
}: {
  configKey: string;
  namespace: string;
  fromRevision: number;
  fromValue: string;
  currentRevision: number;
  phrases: PhraseBook;
}) {
  const router = useRouter();
  const [step, setStep] = useState<RestoreStep>("idle");
  const [problem, setProblem] = useState<string | null>(null);

  async function confirmRestore() {
    setStep("saving");
    setProblem(null);
    const ns = namespace.trim() || "default";
    const query = `?namespace=${encodeURIComponent(ns)}`;
    const headers: Record<string, string> = {};
    if (Number.isFinite(currentRevision) && currentRevision >= 0) {
      headers["If-Match"] = `"${currentRevision}"`;
    }
    const path =
      `/api/platform/config/${encodeURIComponent(configKey)}` +
      `/history/${encodeURIComponent(String(fromRevision))}/restore${query}`;
    const reply = await fetch(path, { method: "POST", headers }).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (reply?.status === 412) {
      setProblem(phrases.configRevisionMismatch);
      setStep("idle");
      return;
    }
    if (reply?.status === 404) {
      setProblem(phrases.configRestoreMissing);
      setStep("idle");
      return;
    }
    if (!reply?.ok) {
      setProblem(fillPhrase(phrases.configRestoreFailed, { status: reply?.status ?? 0 }));
      setStep("idle");
      return;
    }
    setStep("idle");
    router.refresh();
  }

  if (step === "confirming" || step === "saving") {
    return (
      <div className="flex flex-col gap-1">
        <p className="text-[11px] text-muted">
          {fillPhrase(phrases.configRestoreSummary, {
            key: configKey,
            revision: fromRevision,
            value: fromValue,
          })}
        </p>
        {problem && (
          <p role="alert" className="text-[11px] text-danger">
            {problem}
          </p>
        )}
        <div className="flex gap-2">
          <button
            type="button"
            onClick={confirmRestore}
            disabled={step === "saving"}
            className="rounded-md bg-accent px-2 py-0.5 text-[11px] text-white disabled:opacity-60"
          >
            {step === "saving" ? phrases.savingAction : phrases.configRestoreConfirm}
          </button>
          <button
            type="button"
            onClick={() => setStep("idle")}
            disabled={step === "saving"}
            className="rounded-md border border-border px-2 py-0.5 text-[11px]"
          >
            {phrases.cancelAction}
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-1">
      {problem && (
        <p role="alert" className="text-[11px] text-danger">
          {problem}
        </p>
      )}
      <button
        type="button"
        onClick={() => {
          setProblem(null);
          setStep("confirming");
        }}
        className="rounded-md border border-border px-2 py-0.5 text-[11px] hover:bg-background"
      >
        {phrases.configRestoreAction}
      </button>
    </div>
  );
}
