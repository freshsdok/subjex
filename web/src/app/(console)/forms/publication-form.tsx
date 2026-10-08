"use client";

import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { FormDebugPanel } from "@/components/form-debug-panel";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";
import { parseSubmitReply, type FormDebugState } from "@/lib/form-debug";
import {
  buildFormPayload,
  emptyFieldValues,
  fieldControlKind,
  fieldKindLabel,
  isRequiredFieldMissing,
  type FormFieldInput,
} from "@/lib/form-field-input";

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
  fields: FormFieldInput[];
  canWrite: boolean;
  writePermission: string;
  phrases: PhraseBook;
}) {
  const router = useRouter();
  const emptyValues = useMemo(() => emptyFieldValues(fields), [fields]);
  const [values, setValues] = useState<Record<string, string>>(emptyValues);
  const [step, setStep] = useState<SubmitStep>("editing");
  const [debug, setDebug] = useState<FormDebugState>({ kind: "idle" });

  // Reset edit state when the operator picks another form — 换表单时清空填写状态。
  useEffect(() => {
    setValues(emptyValues);
    setStep("editing");
    setDebug({ kind: "idle" });
  }, [formKey, emptyValues]);

  function updateField(name: string, next: string) {
    setValues((current) => ({ ...current, [name]: next }));
  }

  function reviewSubmission() {
    for (const field of fields) {
      if (isRequiredFieldMissing(field, values[field.name])) {
        setDebug({
          kind: "validation",
          fieldErrors: [
            {
              field: field.name,
              code: "required",
              message: fillPhrase(phrases.formMissingRequired, { name: field.name }),
            },
          ],
          message: fillPhrase(phrases.formMissingRequired, { name: field.name }),
        });
        return;
      }
    }
    setDebug({ kind: "idle" });
    setStep("reviewing");
  }

  async function confirmSubmit() {
    setStep("submitting");
    const payload = buildFormPayload(fields, values);
    const reply = await fetch(`/api/platform/forms/${encodeURIComponent(formKey)}/submissions`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ values: payload }),
    }).catch(() => null);
    if (reply?.status === 401) {
      router.replace("/login");
      return;
    }
    const body = reply ? await reply.json().catch(() => null) : null;
    const next = parseSubmitReply(reply?.status ?? 0, body);
    setDebug(next);
    if (next.kind === "persist_ok") {
      setValues(emptyValues);
      setStep("editing");
      router.refresh();
      return;
    }
    setStep("editing");
  }

  return (
    <fieldset
      disabled={!canWrite || step === "submitting"}
      className="max-w-lg rounded-lg border border-border bg-surface p-5 disabled:opacity-80"
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
          {fields.map((field) => {
            const kindLabel = fieldKindLabel(field.kind, phrases);
            const control = fieldControlKind(field);
            const label = (
              <span>
                <code>{field.name}</code>
                <span className="ml-2 text-xs text-muted">
                  {kindLabel}
                  {field.required ? ` · ${phrases.requiredMark}` : ""}
                </span>
              </span>
            );
            if (control === "checkbox") {
              return (
                <label key={field.name} className="flex items-center gap-2 text-sm">
                  <input
                    name={field.name}
                    type="checkbox"
                    checked={(values[field.name] ?? "false") === "true"}
                    onChange={(event) => updateField(field.name, event.target.checked ? "true" : "false")}
                    className="rounded border border-border"
                  />
                  {label}
                </label>
              );
            }
            if (control === "select") {
              return (
                <label key={field.name} className="flex flex-col gap-1 text-sm">
                  {label}
                  <select
                    name={field.name}
                    value={values[field.name] ?? ""}
                    onChange={(event) => updateField(field.name, event.target.value)}
                    className="rounded-md border border-border bg-background px-2 py-1"
                  >
                    <option value="">—</option>
                    {(field.enumValues ?? []).map((option) => (
                      <option key={option} value={option}>
                        {option}
                      </option>
                    ))}
                  </select>
                </label>
              );
            }
            return (
              <label key={field.name} className="flex flex-col gap-1 text-sm">
                {label}
                <input
                  name={field.name}
                  type={control === "date" ? "date" : control === "number" ? "number" : "text"}
                  value={values[field.name] ?? ""}
                  onChange={(event) => updateField(field.name, event.target.value)}
                  className="rounded-md border border-border bg-background px-2 py-1"
                />
              </label>
            );
          })}
        </div>
      )}

      <FormDebugPanel state={debug} phrases={phrases} />

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
