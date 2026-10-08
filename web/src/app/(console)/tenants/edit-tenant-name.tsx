"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";
import { normalizeTenantDisplayName } from "@/lib/tenant-admin";

type Step = "closed" | "editing" | "reviewing" | "saving";

export function EditTenantName({
  tenantId,
  currentName,
  phrases,
}: {
  tenantId: string;
  currentName: string;
  phrases: PhraseBook;
}) {
  const router = useRouter();
  const [step, setStep] = useState<Step>("closed");
  const [name, setName] = useState(currentName);
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  function open() {
    setName(currentName);
    setProblem(null);
    setNotice(null);
    setStep("editing");
  }

  function review() {
    const next = normalizeTenantDisplayName(name);
    if (!next) {
      setProblem(phrases.tenantNameRequired);
      return;
    }
    if (next === currentName) {
      setProblem(phrases.grantsUnchanged);
      return;
    }
    setProblem(null);
    setStep("reviewing");
  }

  async function confirm() {
    setStep("saving");
    setProblem(null);
    const next = normalizeTenantDisplayName(name)!;
    const reply = await fetch(`/api/platform/tenants/${encodeURIComponent(tenantId)}`, {
      method: "PATCH",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ tenantName: next }),
    }).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply?.ok) {
      setProblem(fillPhrase(phrases.editTenantNameFailed, { status: reply?.status ?? 0 }));
      setStep("editing");
      return;
    }
    setNotice(fillPhrase(phrases.editTenantNameDone, { id: tenantId }));
    setStep("closed");
    router.refresh();
  }

  if (step === "closed") {
    return (
      <div>
        <button
          type="button"
          onClick={open}
          className="rounded-md border border-border px-2 py-0.5 text-xs hover:bg-background"
        >
          {phrases.editTenantNameAction}
        </button>
        {notice && <p className="mt-1 text-xs text-green-700">{notice}</p>}
      </div>
    );
  }

  const nextName = normalizeTenantDisplayName(name) ?? name.trim();

  return (
    <div className="max-w-xs rounded-md border border-border bg-background p-2">
      <p className="text-xs font-medium">{fillPhrase(phrases.editTenantNameTitle, { id: tenantId })}</p>
      {step === "editing" ? (
        <label className="mt-2 flex flex-col gap-1 text-xs">
          {phrases.tenantNameLabel}
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            className="rounded-md border border-border bg-surface px-2 py-1"
            autoComplete="off"
          />
        </label>
      ) : (
        <p className="mt-2 text-xs">
          {fillPhrase(phrases.editTenantNameReview, {
            id: tenantId,
            before: currentName,
            after: nextName,
          })}
        </p>
      )}
      {problem && (
        <p role="alert" className="mt-1 text-xs text-red-700">
          {problem}
        </p>
      )}
      <div className="mt-2 flex gap-1">
        {step === "editing" ? (
          <button
            type="button"
            onClick={review}
            className="rounded-md bg-foreground px-2 py-0.5 text-xs text-background"
          >
            {phrases.reviewChangeAction}
          </button>
        ) : (
          <button
            type="button"
            onClick={confirm}
            disabled={step === "saving"}
            className="rounded-md bg-foreground px-2 py-0.5 text-xs text-background disabled:opacity-60"
          >
            {phrases.editTenantNameConfirm}
          </button>
        )}
        <button
          type="button"
          onClick={() => setStep("closed")}
          disabled={step === "saving"}
          className="rounded-md border border-border px-2 py-0.5 text-xs"
        >
          {phrases.cancelAction}
        </button>
      </div>
    </div>
  );
}
