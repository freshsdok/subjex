"use client";

import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { FormDebugPanel } from "@/components/form-debug-panel";
import { FormFields, SubmitBar } from "@/components/page-blocks";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";
import { parseSubmitReply, type FormDebugState } from "@/lib/form-debug";
import {
  buildFormPayload,
  emptyFieldValues,
  isRequiredFieldMissing,
  type FormFieldInput,
} from "@/lib/form-field-input";

type SubmitStep = "editing" | "reviewing" | "submitting";

// Declared submit form — 声明式提交表单：填写 → 核对 → POST 到声明 API → 展示结果面板（可继续跳转）。
export function DeclaredSubmitForm({
  formKey,
  formTitle,
  fields,
  submitApiPath,
  redirectTo,
  canWrite,
  writePermission,
  phrases,
  tenantId,
}: {
  formKey: string;
  formTitle: string;
  fields: FormFieldInput[];
  submitApiPath: string;
  redirectTo: string;
  canWrite: boolean;
  writePermission: string;
  phrases: PhraseBook;
  tenantId?: string;
}) {
  const router = useRouter();
  const emptyValues = useMemo(() => emptyFieldValues(fields), [fields]);
  const [values, setValues] = useState<Record<string, string>>(emptyValues);
  const [step, setStep] = useState<SubmitStep>("editing");
  const [debug, setDebug] = useState<FormDebugState>({ kind: "idle" });

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
    const reply = await fetch(`/api/platform/${submitApiPath}`, {
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
        <FormFields
          fields={fields}
          values={values}
          onChange={updateField}
          phrases={phrases}
          tenantId={tenantId}
        />
      )}

      <FormDebugPanel
        state={debug}
        phrases={phrases}
        continueHref={debug.kind === "persist_ok" ? redirectTo : undefined}
      />

      {canWrite ? (
        <SubmitBar>
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
        </SubmitBar>
      ) : null}
    </fieldset>
  );
}
