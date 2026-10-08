"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";
import { addTenantGrant, grantsEqual, removeTenantGrant } from "@/lib/operator-grants";

type GrantsStep = "closed" | "editing" | "reviewing" | "saving";

function labelFor(tenantIds: string[], phrases: PhraseBook): string {
  return tenantIds.length === 0 ? phrases.noTenantGrantsLabel : tenantIds.join(", ");
}

export function TenantGrantsEditor({
  loginName,
  initialTenantIds,
  phrases,
}: {
  loginName: string;
  initialTenantIds: string[];
  phrases: PhraseBook;
}) {
  const router = useRouter();
  const [step, setStep] = useState<GrantsStep>("closed");
  const [draft, setDraft] = useState<string[]>(initialTenantIds);
  const [addValue, setAddValue] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  function openEditor() {
    setDraft([...initialTenantIds]);
    setAddValue("");
    setProblem(null);
    setNotice(null);
    setStep("editing");
  }

  function onAdd() {
    const result = addTenantGrant(draft, addValue);
    if (result.error === "blank") {
      setProblem(phrases.grantsBlankRefused);
      return;
    }
    if (result.error === "invalid") {
      setProblem(phrases.grantsInvalidRefused);
      return;
    }
    if (result.error === "duplicate") {
      setProblem(phrases.grantsDuplicateRefused);
      return;
    }
    setProblem(null);
    setDraft(result.next);
    setAddValue("");
  }

  function reviewSave() {
    if (grantsEqual(draft, initialTenantIds)) {
      setProblem(phrases.grantsUnchanged);
      return;
    }
    setProblem(null);
    setStep("reviewing");
  }

  async function confirmSave() {
    setStep("saving");
    setProblem(null);
    const reply = await fetch(`/api/platform/operators/${encodeURIComponent(loginName)}/tenants`, {
      method: "PUT",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ tenantIds: draft }),
    }).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply?.ok) {
      setProblem(fillPhrase(phrases.grantsSaveFailed, { status: reply?.status ?? 0 }));
      setStep("editing");
      return;
    }
    setNotice(fillPhrase(phrases.grantsSavedNotice, { login: loginName }));
    setStep("closed");
    router.refresh();
  }

  if (step === "closed") {
    return (
      <div className="flex flex-col gap-1">
        <button
          type="button"
          onClick={openEditor}
          className="rounded-md border border-border px-2 py-0.5 text-xs hover:bg-background"
        >
          {phrases.editGrantsAction}
        </button>
        {notice && (
          <span role="status" className="text-xs text-green-700">
            {notice}
          </span>
        )}
      </div>
    );
  }

  return (
    <div className="flex min-w-[16rem] flex-col gap-2 rounded-md border border-border bg-background p-2">
      <p className="text-xs font-medium">{fillPhrase(phrases.grantsEditorTitle, { login: loginName })}</p>
      {step === "editing" ? (
        <>
          <p className="text-xs text-muted">{phrases.grantsWildcardHint}</p>
          <ul className="flex flex-wrap gap-1">
            {draft.length === 0 ? (
              <li className="text-xs text-muted">{phrases.noTenantGrantsLabel}</li>
            ) : (
              draft.map((id) => (
                <li
                  key={id}
                  className="inline-flex items-center gap-1 rounded-md border border-border bg-surface px-1.5 py-0.5 font-mono text-xs"
                >
                  {id}
                  <button
                    type="button"
                    onClick={() => setDraft(removeTenantGrant(draft, id))}
                    className="text-muted hover:text-foreground"
                    aria-label={`${phrases.grantsRemoveAction} ${id}`}
                  >
                    ×
                  </button>
                </li>
              ))
            )}
          </ul>
          <div className="flex gap-1">
            <input
              value={addValue}
              onChange={(e) => setAddValue(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") {
                  e.preventDefault();
                  onAdd();
                }
              }}
              placeholder={phrases.grantsAddLabel}
              className="min-w-0 flex-1 rounded-md border border-border bg-surface px-2 py-1 font-mono text-xs"
              aria-label={phrases.grantsAddLabel}
            />
            <button
              type="button"
              onClick={onAdd}
              className="rounded-md border border-border px-2 py-0.5 text-xs hover:bg-surface"
            >
              {phrases.grantsAddAction}
            </button>
          </div>
        </>
      ) : (
        <p className="text-xs">
          {fillPhrase(phrases.grantsReviewTitle, {
            login: loginName,
            before: labelFor(initialTenantIds, phrases),
            after: labelFor(draft, phrases),
          })}
        </p>
      )}
      {problem && (
        <p role="alert" className="text-xs text-red-700">
          {problem}
        </p>
      )}
      <div className="flex gap-2">
        {step === "editing" ? (
          <button
            type="button"
            onClick={reviewSave}
            className="rounded-md bg-accent px-2 py-0.5 text-xs text-white"
          >
            {phrases.grantsSaveAction}
          </button>
        ) : (
          <button
            type="button"
            onClick={confirmSave}
            disabled={step === "saving"}
            className="rounded-md bg-accent px-2 py-0.5 text-xs text-white disabled:opacity-60"
          >
            {step === "saving" ? phrases.savingAction : phrases.grantsConfirmAction}
          </button>
        )}
        <button
          type="button"
          onClick={() => setStep(step === "reviewing" ? "editing" : "closed")}
          disabled={step === "saving"}
          className="rounded-md border border-border px-2 py-0.5 text-xs"
        >
          {phrases.cancelAction}
        </button>
      </div>
    </div>
  );
}
