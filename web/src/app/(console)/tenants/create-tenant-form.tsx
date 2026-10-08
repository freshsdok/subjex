"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";
import { isReservedTenant, normalizeTenantAdminId, normalizeTenantDisplayName } from "@/lib/tenant-admin";

type CreateStep = "editing" | "reviewing" | "saving";

export function CreateTenantForm({ phrases }: { phrases: PhraseBook }) {
  const router = useRouter();
  const [step, setStep] = useState<CreateStep>("editing");
  const [tenantId, setTenantId] = useState("");
  const [tenantName, setTenantName] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  function reviewCreate() {
    setNotice(null);
    const id = normalizeTenantAdminId(tenantId);
    if (!id) {
      setProblem(tenantId.trim() ? phrases.tenantIdInvalid : phrases.tenantIdRequired);
      return;
    }
    if (isReservedTenant(id)) {
      setProblem(phrases.reservedTenantHint);
      return;
    }
    const name = normalizeTenantDisplayName(tenantName || id);
    if (!name) {
      setProblem(phrases.tenantNameRequired);
      return;
    }
    setProblem(null);
    setStep("reviewing");
  }

  async function confirmCreate() {
    setStep("saving");
    setProblem(null);
    const id = normalizeTenantAdminId(tenantId)!;
    const name = normalizeTenantDisplayName(tenantName || id)!;
    const reply = await fetch("/api/platform/tenants", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ tenantId: id, tenantName: name }),
    }).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply?.ok) {
      setProblem(fillPhrase(phrases.createTenantFailed, { status: reply?.status ?? 0 }));
      setStep("editing");
      return;
    }
    setTenantId("");
    setTenantName("");
    setNotice(fillPhrase(phrases.createTenantCreatedNotice, { id, name }));
    setStep("editing");
    router.refresh();
  }

  const reviewId = normalizeTenantAdminId(tenantId) ?? tenantId.trim();
  const reviewName = normalizeTenantDisplayName(tenantName || tenantId) ?? tenantName.trim();

  return (
    <div className="mt-8 max-w-md rounded-lg border border-border bg-surface p-4">
      <h2 className="text-sm font-medium">{phrases.createTenantTitle}</h2>
      <p className="mt-1 text-xs text-muted">{phrases.createTenantHint}</p>
      {step === "editing" ? (
        <div className="mt-3 flex flex-col gap-3">
          <label className="flex flex-col gap-1 text-xs">
            {phrases.tenantIdLabel}
            <input
              value={tenantId}
              onChange={(e) => setTenantId(e.target.value)}
              className="rounded-md border border-border bg-background px-2 py-1 font-mono"
              autoComplete="off"
              required
            />
          </label>
          <label className="flex flex-col gap-1 text-xs">
            {phrases.tenantNameLabel}
            <input
              value={tenantName}
              onChange={(e) => setTenantName(e.target.value)}
              className="rounded-md border border-border bg-background px-2 py-1"
              placeholder={tenantId.trim() || undefined}
              autoComplete="off"
            />
          </label>
        </div>
      ) : (
        <div className="mt-3 space-y-1 text-xs">
          <p className="font-medium">{phrases.createTenantReviewTitle}</p>
          <p>
            {phrases.tenantIdLabel}: <span className="font-mono">{reviewId}</span>
          </p>
          <p>
            {phrases.tenantNameLabel}: {reviewName}
          </p>
        </div>
      )}
      {problem && (
        <p role="alert" className="mt-2 text-xs text-red-700">
          {problem}
        </p>
      )}
      {notice && (
        <p role="status" className="mt-2 text-xs text-green-700">
          {notice}
        </p>
      )}
      <div className="mt-3 flex gap-2">
        {step === "editing" ? (
          <button
            type="button"
            onClick={reviewCreate}
            className="rounded-md bg-foreground px-3 py-1.5 text-xs text-background"
          >
            {phrases.reviewChangeAction}
          </button>
        ) : (
          <button
            type="button"
            onClick={confirmCreate}
            disabled={step === "saving"}
            className="rounded-md bg-foreground px-3 py-1.5 text-xs text-background disabled:opacity-60"
          >
            {step === "saving" ? phrases.createTenantCreatingAction : phrases.createTenantConfirmAction}
          </button>
        )}
        {step !== "editing" ? (
          <button
            type="button"
            onClick={() => setStep("editing")}
            disabled={step === "saving"}
            className="rounded-md border border-border px-3 py-1.5 text-xs"
          >
            {phrases.cancelAction}
          </button>
        ) : null}
      </div>
    </div>
  );
}
