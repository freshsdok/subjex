"use client";

import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";

type FormField = { name: string; kind: string; required: boolean };

// Submit steps — 提交步骤：填写 → 核对 → 确认。与配置覆盖同一套节奏，避免误提交。
type SubmitStep = "editing" | "reviewing" | "submitting";

export function PublicationForm({
  formKey,
  formTitle,
  fields,
  canWrite,
  writePermission,
  phrases,
}: {
  formKey: string;
  formTitle: string;
  fields: FormField[];
  canWrite: boolean;
  writePermission: string;
  phrases: PhraseBook;
}) {
  const router = useRouter();
  const emptyValues = useMemo(
    () => Object.fromEntries(fields.map((field) => [field.name, ""])),
    [fields],
  );
  const [values, setValues] = useState<Record<string, string>>(emptyValues);
  const [step, setStep] = useState<SubmitStep>("editing");
  const [problem, setProblem] = useState<string | null>(null);
  const [savedMessage, setSavedMessage] = useState<string | null>(null);

  // Reset edit state when the operator picks another form — 换表单时清空填写状态。
  useEffect(() => {
    setValues(emptyValues);
    setStep("editing");
    setProblem(null);
    setSavedMessage(null);
  }, [formKey, emptyValues]);

  function updateField(name: string, next: string) {
    setValues((current) => ({ ...current, [name]: next }));
  }

  function reviewSubmission() {
    for (const field of fields) {
      if (field.required && (values[field.name] ?? "").trim() === "") {
        setProblem(fillPhrase(phrases.formMissingRequired, { name: field.name }));
        return;
      }
    }
    setProblem(null);
    setStep("reviewing");
  }

  async function confirmSubmit() {
    setStep("submitting");
    const payload: Record<string, string | number> = {};
    for (const field of fields) {
      const raw = (values[field.name] ?? "").trim();
      payload[field.name] = field.kind === "integer" ? Number(raw) : raw;
    }
    const reply = await fetch(`/api/platform/forms/${encodeURIComponent(formKey)}/submissions`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ values: payload }),
    }).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    if (!reply?.ok) {
      setProblem(fillPhrase(phrases.formSubmitFailed, { status: reply?.status ?? 0 }));
      setStep("editing");
      return;
    }
    const body = (await reply.json()) as { resultSummary?: string };
    setSavedMessage(
      fillPhrase(phrases.formSubmittedNotice, { summary: body.resultSummary ?? "" }),
    );
    setValues(emptyValues);
    setStep("editing");
    router.refresh();
  }

  return (
    <fieldset
      disabled={!canWrite || step === "submitting"}
      className="max-w-md rounded-lg border border-border bg-surface p-5 disabled:opacity-80"
    >
      <legend className="px-1 font-semibold">{formTitle}</legend>
      <p className="mb-4 text-xs text-muted">
        {phrases.formKeyLabel}: <code>{formKey}</code>
      </p>
      {!canWrite ? (
        <p role="status" className="mb-3 text-sm text-muted">
          {fillPhrase(phrases.formWriteForbidden, { permission: writePermission })}
        </p>
      ) : null}

      {step === "reviewing" || step === "submitting" ? (
        <div className="mb-3 rounded-md border border-border bg-background p-3 text-sm">
          <p className="mb-2 font-medium">{phrases.formReviewTitle}</p>
          <ul className="space-y-1 font-mono text-xs">
            {fields.map((field) => (
              <li key={field.name}>
                {field.name}: {values[field.name]}
              </li>
            ))}
          </ul>
        </div>
      ) : (
        <div className="flex flex-col gap-3">
          {fields.map((field) => (
            <label key={field.name} className="flex flex-col gap-1 text-sm">
              <span>
                <code>{field.name}</code>
                <span className="ml-2 text-xs text-muted">
                  {field.kind === "integer" ? phrases.fieldKindInteger : phrases.fieldKindText}
                  {field.required ? ` · ${phrases.requiredMark}` : ""}
                </span>
              </span>
              <input
                name={field.name}
                type={field.kind === "integer" ? "number" : "text"}
                value={values[field.name] ?? ""}
                onChange={(event) => updateField(field.name, event.target.value)}
                className="rounded-md border border-border bg-background px-2 py-1"
              />
            </label>
          ))}
        </div>
      )}

      {problem ? <p role="alert" className="mt-3 text-sm text-danger">{problem}</p> : null}
      {savedMessage ? (
        <p role="status" className="mt-3 text-sm text-green-700">
          {savedMessage}
        </p>
      ) : null}

      {canWrite ? (
        <div className="mt-4 flex gap-2">
          {step === "editing" ? (
            <button
              type="button"
              onClick={reviewSubmission}
              className="rounded-md bg-accent px-3 py-1.5 text-sm text-white"
            >
              {phrases.reviewChangeAction}
            </button>
          ) : (
            <button
              type="button"
              onClick={confirmSubmit}
              disabled={step === "submitting"}
              className="rounded-md bg-accent px-3 py-1.5 text-sm text-white disabled:opacity-60"
            >
              {step === "submitting" ? phrases.formSubmittingAction : phrases.formConfirmAction}
            </button>
          )}
          {step !== "editing" ? (
            <button
              type="button"
              onClick={() => setStep("editing")}
              disabled={step === "submitting"}
              className="rounded-md border border-border px-3 py-1.5 text-sm"
            >
              {phrases.cancelAction}
            </button>
          ) : null}
        </div>
      ) : null}
    </fieldset>
  );
}
