"use client";

import { useState } from "react";
import { fillPhrase, type PhraseBook } from "@/i18n/phrases";
import {
  BUSINESS_TABLE_PERMISSION_OPTIONS,
  addBusinessTableField,
  businessTableNeedsTenantScopeWarn,
  defaultRepairTicketWizard,
  normalizeBusinessTableKey,
  removeBusinessTableField,
  tableNameFromKey,
  type BusinessTableField,
  type BusinessTableWizardState,
} from "@/lib/business-table-wizard";
import { ENTITY_FIELD_KINDS, entityKindClearsMaxLength } from "@/lib/entity-wizard";

type Step = "editing" | "reviewing" | "saving";

type Props = {
  phrases: PhraseBook;
  disabled?: boolean;
  tenantId: string;
  onConfirm: (state: BusinessTableWizardState) => Promise<void>;
};

function updateField(
  fields: BusinessTableField[],
  index: number,
  patch: Partial<BusinessTableField>,
): BusinessTableField[] {
  return fields.map((field, i) => {
    if (i !== index) return field;
    const next = { ...field, ...patch };
    if (entityKindClearsMaxLength(next.kind)) {
      next.maxLength = null;
    } else if (patch.kind && next.maxLength == null) {
      next.maxLength = 64;
    }
    if (next.kind !== "enum") next.enumValues = [];
    return next;
  });
}

// BusinessTableWizard — 新建业务表唯一入口：一次确认写出 entity+form+flow 三份草稿。
export function BusinessTableWizard({ phrases, disabled, tenantId, onConfirm }: Props) {
  const [open, setOpen] = useState(false);
  const [state, setState] = useState<BusinessTableWizardState>(() => defaultRepairTicketWizard());
  const [step, setStep] = useState<Step>("editing");
  const [problem, setProblem] = useState<string | null>(null);

  function reset() {
    setState(defaultRepairTicketWizard());
    setStep("editing");
    setProblem(null);
  }

  function close() {
    setOpen(false);
    reset();
  }

  function review() {
    const key = normalizeBusinessTableKey(state.key);
    if (!key) {
      setProblem(
        state.key.trim()
          ? phrases.declarationsNewKeyInvalid
          : phrases.declarationsBusinessTableKeyRequired,
      );
      return;
    }
    if (!tenantId.trim()) {
      setProblem(phrases.declarationsTenantRequired);
      return;
    }
    if (state.fields.length === 0 || !state.fields[0]?.name.trim()) {
      setProblem(phrases.declarationsBusinessTablePkRequired);
      return;
    }
    if (!state.permission.trim()) {
      setProblem(phrases.declarationsBusinessTablePermissionRequired);
      return;
    }
    setProblem(null);
    setState({ ...state, key });
    setStep("reviewing");
  }

  async function confirm() {
    setStep("saving");
    setProblem(null);
    try {
      await onConfirm(state);
      close();
    } catch (err) {
      const message =
        err instanceof Error ? err.message : phrases.declarationsBusinessTableSaveFailed;
      setProblem(message);
      setStep("editing");
    }
  }

  if (!open) {
    return (
      <div className="rounded-lg border-2 border-accent/40 bg-surface p-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 className="text-sm font-semibold">{phrases.declarationsBusinessTableTitle}</h2>
            <p className="mt-1 text-xs text-muted">{phrases.declarationsBusinessTableHint}</p>
          </div>
          <button
            type="button"
            disabled={disabled || !tenantId.trim()}
            onClick={() => {
              reset();
              setOpen(true);
            }}
            className="rounded-md bg-accent px-3 py-1.5 text-sm font-medium text-white disabled:opacity-60"
          >
            {phrases.declarationsBusinessTableAction}
          </button>
        </div>
        {!tenantId.trim() ? (
          <p className="mt-2 text-xs text-muted">{phrases.declarationsTenantRequired}</p>
        ) : null}
      </div>
    );
  }

  const tableHint = tableNameFromKey(state.key);
  const busy = step === "saving";

  return (
    <div className="rounded-lg border-2 border-accent/40 bg-surface p-4">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-sm font-semibold">{phrases.declarationsBusinessTableTitle}</h2>
        <button
          type="button"
          onClick={close}
          disabled={busy}
          className="rounded-md border border-border px-2 py-0.5 text-xs hover:bg-background disabled:opacity-60"
        >
          {phrases.cancelAction}
        </button>
      </div>

      {step === "reviewing" || step === "saving" ? (
        <div className="rounded-md border border-border bg-background px-3 py-2 text-sm">
          <p className="font-medium">{phrases.declarationsBusinessTableReviewTitle}</p>
          <p className="mt-1 text-muted">
            {fillPhrase(phrases.declarationsBusinessTableReviewBody, {
              tenant: tenantId.trim(),
              key: state.key,
            })}
          </p>
          <ul className="mt-2 list-inside list-disc text-xs text-muted">
            <li>entity/{state.key}</li>
            <li>form/{state.key}</li>
            <li>flow/{state.key}</li>
          </ul>
          <div className="mt-3 flex flex-wrap gap-2">
            <button
              type="button"
              onClick={() => void confirm()}
              disabled={busy}
              className="rounded-md bg-accent px-2 py-1 text-xs text-white disabled:opacity-60"
            >
              {step === "saving"
                ? phrases.declarationsBusinessTableSavingAction
                : phrases.declarationsBusinessTableConfirmAction}
            </button>
            <button
              type="button"
              onClick={() => setStep("editing")}
              disabled={busy}
              className="rounded-md border border-border px-2 py-1 text-xs"
            >
              {phrases.cancelAction}
            </button>
          </div>
        </div>
      ) : (
        <>
          <div className="mb-3 grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
            <label className="flex flex-col gap-1 text-xs">
              <span className="text-muted">{phrases.declarationsBusinessTableKeyLabel}</span>
              <input
                value={state.key}
                disabled={disabled}
                onChange={(event) => setState({ ...state, key: event.target.value })}
                className="rounded-md border border-border bg-background px-2 py-1 font-mono disabled:opacity-70"
                aria-label={phrases.declarationsBusinessTableKeyLabel}
              />
              <span className="text-muted">
                {phrases.declarationsWizardTableName}: {tableHint}
              </span>
            </label>
            <label className="flex flex-col gap-1 text-xs">
              <span className="text-muted">{phrases.declarationsWizardTitleZh}</span>
              <input
                value={state.titleZh}
                disabled={disabled}
                onChange={(event) => setState({ ...state, titleZh: event.target.value })}
                className="rounded-md border border-border bg-background px-2 py-1 disabled:opacity-70"
              />
            </label>
            <label className="flex flex-col gap-1 text-xs">
              <span className="text-muted">{phrases.declarationsWizardTitleEn}</span>
              <input
                value={state.titleEn}
                disabled={disabled}
                onChange={(event) => setState({ ...state, titleEn: event.target.value })}
                className="rounded-md border border-border bg-background px-2 py-1 disabled:opacity-70"
              />
            </label>
            <label className="flex flex-col gap-1 text-xs">
              <span className="text-muted">{phrases.declarationsWizardPermission}</span>
              <select
                value={state.permission}
                disabled={disabled}
                onChange={(event) => setState({ ...state, permission: event.target.value })}
                className="rounded-md border border-border bg-background px-2 py-1 disabled:opacity-70"
              >
                {BUSINESS_TABLE_PERMISSION_OPTIONS.map((p) => (
                  <option key={p} value={p}>
                    {p}
                  </option>
                ))}
              </select>
            </label>
            <label className="flex items-center gap-2 text-xs pt-5">
              <input
                type="checkbox"
                checked={state.tenantScoped}
                disabled={disabled}
                onChange={(event) =>
                  setState({ ...state, tenantScoped: event.target.checked })
                }
              />
              <span>{phrases.declarationsWizardTenantScoped}</span>
            </label>
          </div>

          {businessTableNeedsTenantScopeWarn(state) ? (
            <p role="status" className="mb-2 text-xs text-danger">
              {phrases.declarationsBusinessTableTenantScopeWarn}
            </p>
          ) : null}

          <p className="mb-1 text-xs text-muted">{phrases.declarationsEntityWizardPkHint}</p>
          <div className="overflow-x-auto">
            <table className="w-full border-collapse text-xs">
              <thead className="text-muted">
                <tr>
                  <th className="px-1 py-1 text-left font-medium">{phrases.declarationsWizardPkBadge}</th>
                  <th className="px-1 py-1 text-left font-medium">{phrases.declarationsWizardFieldName}</th>
                  <th className="px-1 py-1 text-left font-medium">{phrases.declarationsWizardFieldKind}</th>
                  <th className="px-1 py-1 text-left font-medium">{phrases.declarationsWizardFieldRequired}</th>
                  <th className="px-1 py-1 text-left font-medium">{phrases.declarationsWizardFieldMaxLength}</th>
                  <th className="px-1 py-1 text-left font-medium">enumValues</th>
                  <th className="px-1 py-1" />
                </tr>
              </thead>
              <tbody>
                {state.fields.map((field, index) => (
                  <tr key={index} className="border-t border-border align-top">
                    <td className="px-1 py-1">{index === 0 ? phrases.declarationsWizardPkBadge : ""}</td>
                    <td className="px-1 py-1">
                      <input
                        value={field.name}
                        disabled={disabled}
                        onChange={(event) =>
                          setState({
                            ...state,
                            fields: updateField(state.fields, index, {
                              name: event.target.value,
                            }),
                          })
                        }
                        className="w-28 rounded border border-border bg-background px-1 py-0.5 font-mono"
                      />
                    </td>
                    <td className="px-1 py-1">
                      <select
                        value={field.kind}
                        disabled={disabled}
                        onChange={(event) =>
                          setState({
                            ...state,
                            fields: updateField(state.fields, index, {
                              kind: event.target.value as BusinessTableField["kind"],
                            }),
                          })
                        }
                        className="rounded border border-border bg-background px-1 py-0.5"
                      >
                        {ENTITY_FIELD_KINDS.map((k) => (
                          <option key={k} value={k}>
                            {k}
                          </option>
                        ))}
                      </select>
                    </td>
                    <td className="px-1 py-1">
                      <input
                        type="checkbox"
                        checked={field.required}
                        disabled={disabled}
                        onChange={(event) =>
                          setState({
                            ...state,
                            fields: updateField(state.fields, index, {
                              required: event.target.checked,
                            }),
                          })
                        }
                      />
                    </td>
                    <td className="px-1 py-1">
                      <input
                        type="number"
                        value={field.maxLength ?? ""}
                        disabled={disabled || entityKindClearsMaxLength(field.kind)}
                        onChange={(event) => {
                          const raw = event.target.value;
                          const n = raw === "" ? null : Number(raw);
                          setState({
                            ...state,
                            fields: updateField(state.fields, index, {
                              maxLength: n != null && Number.isFinite(n) ? n : null,
                            }),
                          });
                        }}
                        className="w-16 rounded border border-border bg-background px-1 py-0.5 disabled:opacity-50"
                      />
                    </td>
                    <td className="px-1 py-1">
                      <input
                        value={field.enumValues.join(", ")}
                        disabled={disabled || field.kind !== "enum"}
                        onChange={(event) =>
                          setState({
                            ...state,
                            fields: updateField(state.fields, index, {
                              enumValues: event.target.value
                                .split(",")
                                .map((v) => v.trim())
                                .filter(Boolean),
                            }),
                          })
                        }
                        className="w-40 rounded border border-border bg-background px-1 py-0.5 disabled:opacity-50"
                        placeholder="low, medium, …"
                      />
                    </td>
                    <td className="px-1 py-1">
                      <button
                        type="button"
                        disabled={disabled || state.fields.length <= 1}
                        onClick={() =>
                          setState({
                            ...state,
                            fields: removeBusinessTableField(state.fields, index),
                          })
                        }
                        className="rounded border border-border px-1 py-0.5 hover:bg-background disabled:opacity-50"
                      >
                        {phrases.declarationsWizardRemoveField}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="mt-2 flex flex-wrap gap-2">
            <button
              type="button"
              disabled={disabled}
              onClick={() =>
                setState({ ...state, fields: addBusinessTableField(state.fields) })
              }
              className="rounded-md border border-border px-2 py-1 text-xs hover:bg-background disabled:opacity-60"
            >
              {phrases.declarationsWizardAddField}
            </button>
            <button
              type="button"
              disabled={disabled}
              onClick={review}
              className="rounded-md bg-accent px-2 py-1 text-xs text-white disabled:opacity-60"
            >
              {phrases.declarationsBusinessTableReviewAction}
            </button>
          </div>
        </>
      )}

      {problem ? (
        <p role="alert" className="mt-2 text-xs text-danger">
          {problem}
        </p>
      ) : null}
    </div>
  );
}
