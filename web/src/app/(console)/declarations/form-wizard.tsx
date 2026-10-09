"use client";

import { fillPhrase, type PhraseBook } from "@/i18n/phrases";
import {
  FORM_FIELD_KINDS,
  addFormField,
  formKindAllowsIntegerBounds,
  formKindClearsMaxLength,
  removeFormField,
  type FormWizardField,
  type FormWizardState,
} from "@/lib/form-wizard";
import { DECLARATION_PERMISSION_CATALOG } from "@/lib/declaration-permission-catalog";

type Props = {
  phrases: PhraseBook;
  value: FormWizardState;
  /** Lock formKey when editing an existing declaration key — 编辑已有键时锁定 formKey。 */
  keyLocked?: boolean;
  disabled?: boolean;
  onChange: (next: FormWizardState) => void;
  onApply: () => void;
};

function updateField(
  fields: FormWizardField[],
  index: number,
  patch: Partial<FormWizardField>,
): FormWizardField[] {
  return fields.map((field, i) => {
    if (i !== index) return field;
    const next = { ...field, ...patch };
    if (formKindClearsMaxLength(next.kind)) {
      next.maxLength = null;
    } else if (patch.kind && next.maxLength == null) {
      next.maxLength = 64;
    }
    if (!formKindAllowsIntegerBounds(next.kind)) {
      next.minimum = null;
      next.maximum = null;
    }
    if (next.kind !== "enum") next.enumValues = [];
    return next;
  });
}

// FormWizard — 表单声明结构化向导（非自由画布）：字段表编辑后「应用到 YAML」；effects 尽量保留。
export function FormWizard({ phrases, value, keyLocked, disabled, onChange, onApply }: Props) {
  const upsertHint =
    value.domainAction.trim() === "entity.record.upsert"
      ? phrases.declarationsFormWizardEntityKeyRequired
      : phrases.declarationsFormWizardEntityKeyOptional;

  return (
    <div className="rounded-md border border-border bg-background px-3 py-3">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <div>
          <p className="text-sm font-medium">{phrases.declarationsFormWizardTitle}</p>
          <p className="text-xs text-muted">{phrases.declarationsFormWizardHint}</p>
        </div>
        <button
          type="button"
          onClick={onApply}
          disabled={disabled}
          className="rounded-md border border-border px-2 py-1 text-xs hover:bg-surface disabled:opacity-60"
        >
          {phrases.declarationsComposerApplyAction}
        </button>
      </div>

      <div className="mb-3 grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
        <label className="flex flex-col gap-1 text-xs">
          <span className="text-muted">{phrases.declarationsWizardFormKey}</span>
          <input
            value={value.formKey}
            disabled={disabled || keyLocked}
            onChange={(event) => onChange({ ...value, formKey: event.target.value })}
            className="rounded-md border border-border bg-surface px-2 py-1 font-mono disabled:opacity-70"
            aria-label={phrases.declarationsWizardFormKey}
          />
        </label>
        <label className="flex flex-col gap-1 text-xs">
          <span className="text-muted">{phrases.declarationsWizardTitleEn}</span>
          <input
            value={value.titleEn}
            disabled={disabled}
            onChange={(event) => onChange({ ...value, titleEn: event.target.value })}
            className="rounded-md border border-border bg-surface px-2 py-1 disabled:opacity-70"
            aria-label={phrases.declarationsWizardTitleEn}
          />
        </label>
        <label className="flex flex-col gap-1 text-xs">
          <span className="text-muted">{phrases.declarationsWizardTitleZh}</span>
          <input
            value={value.titleZh}
            disabled={disabled}
            onChange={(event) => onChange({ ...value, titleZh: event.target.value })}
            className="rounded-md border border-border bg-surface px-2 py-1 disabled:opacity-70"
            aria-label={phrases.declarationsWizardTitleZh}
          />
        </label>
        <label className="flex flex-col gap-1 text-xs">
          <span className="text-muted">{phrases.declarationsWizardVersion}</span>
          <input
            type="number"
            min={1}
            value={value.version}
            disabled={disabled}
            onChange={(event) =>
              onChange({ ...value, version: Math.max(1, Number(event.target.value) || 1) })
            }
            className="rounded-md border border-border bg-surface px-2 py-1 disabled:opacity-70"
            aria-label={phrases.declarationsWizardVersion}
          />
        </label>
        <label className="flex flex-col gap-1 text-xs">
          <span className="text-muted">{phrases.declarationsWizardPermission}</span>
          <select
            value={
              (DECLARATION_PERMISSION_CATALOG as readonly string[]).includes(value.permission)
                ? value.permission
                : DECLARATION_PERMISSION_CATALOG[0]
            }
            disabled={disabled}
            onChange={(event) => onChange({ ...value, permission: event.target.value })}
            className="rounded-md border border-border bg-surface px-2 py-1 font-mono disabled:opacity-70"
            aria-label={phrases.declarationsWizardPermission}
          >
            {DECLARATION_PERMISSION_CATALOG.map((p) => (
              <option key={p} value={p}>
                {p}
              </option>
            ))}
          </select>
        </label>
        <label className="flex items-center gap-2 text-xs">
          <input
            type="checkbox"
            checked={value.tenantScoped}
            disabled={disabled}
            onChange={(event) => onChange({ ...value, tenantScoped: event.target.checked })}
          />
          <span>{phrases.declarationsWizardTenantScoped}</span>
        </label>
        <label className="flex flex-col gap-1 text-xs sm:col-span-2">
          <span className="text-muted">{phrases.declarationsWizardDomainAction}</span>
          <input
            value={value.domainAction}
            disabled={disabled}
            list="form-wizard-domain-actions"
            onChange={(event) => onChange({ ...value, domainAction: event.target.value })}
            className="rounded-md border border-border bg-surface px-2 py-1 font-mono disabled:opacity-70"
            aria-label={phrases.declarationsWizardDomainAction}
          />
          <datalist id="form-wizard-domain-actions">
            <option value="entity.record.upsert" />
          </datalist>
          <span className="text-muted">{phrases.declarationsFormWizardDomainHint}</span>
        </label>
        <label className="flex flex-col gap-1 text-xs">
          <span className="text-muted">{phrases.declarationsWizardEntityKey}</span>
          <input
            value={value.entityKey}
            disabled={disabled}
            onChange={(event) => onChange({ ...value, entityKey: event.target.value })}
            className="rounded-md border border-border bg-surface px-2 py-1 font-mono disabled:opacity-70"
            aria-label={phrases.declarationsWizardEntityKey}
          />
          <span className="text-muted">{upsertHint}</span>
        </label>
      </div>

      <p className="mb-1 text-xs font-semibold">{phrases.declarationsWizardFieldsTitle}</p>
      <div className="overflow-x-auto">
        <table className="w-full border-collapse text-xs">
          <thead className="text-left text-muted">
            <tr>
              <th className="px-1 py-1 font-medium">{phrases.declarationsWizardFieldName}</th>
              <th className="px-1 py-1 font-medium">{phrases.declarationsWizardFieldKind}</th>
              <th className="px-1 py-1 font-medium">{phrases.declarationsWizardFieldRequired}</th>
              <th className="px-1 py-1 font-medium">{phrases.declarationsWizardFieldMaxLength}</th>
              <th className="px-1 py-1 font-medium">{phrases.declarationsWizardFieldMin}</th>
              <th className="px-1 py-1 font-medium">{phrases.declarationsWizardFieldMax}</th>
              <th className="px-1 py-1" />
            </tr>
          </thead>
          <tbody>
            {value.fields.map((field, index) => (
              <tr key={index} className="border-t border-border align-middle">
                <td className="px-1 py-1">
                  <input
                    value={field.name}
                    disabled={disabled}
                    onChange={(event) =>
                      onChange({
                        ...value,
                        fields: updateField(value.fields, index, { name: event.target.value }),
                      })
                    }
                    className="w-full min-w-[6rem] rounded border border-border bg-surface px-1 py-0.5 font-mono disabled:opacity-70"
                    aria-label={phrases.declarationsWizardFieldName}
                  />
                </td>
                <td className="px-1 py-1">
                  <select
                    value={field.kind}
                    disabled={disabled}
                    onChange={(event) =>
                      onChange({
                        ...value,
                        fields: updateField(value.fields, index, {
                          kind: event.target.value as FormWizardField["kind"],
                        }),
                      })
                    }
                    className="rounded border border-border bg-surface px-1 py-0.5 disabled:opacity-70"
                    aria-label={phrases.declarationsWizardFieldKind}
                  >
                    {FORM_FIELD_KINDS.map((kind) => (
                      <option key={kind} value={kind}>
                        {kind}
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
                      onChange({
                        ...value,
                        fields: updateField(value.fields, index, { required: event.target.checked }),
                      })
                    }
                    aria-label={phrases.declarationsWizardFieldRequired}
                  />
                </td>
                <td className="px-1 py-1">
                  <input
                    type="number"
                    min={1}
                    value={field.maxLength ?? ""}
                    disabled={disabled || field.kind !== "text"}
                    onChange={(event) => {
                      const raw = event.target.value;
                      const maxLength = raw === "" ? null : Math.max(1, Number(raw) || 1);
                      onChange({
                        ...value,
                        fields: updateField(value.fields, index, { maxLength }),
                      });
                    }}
                    className="w-20 rounded border border-border bg-surface px-1 py-0.5 disabled:opacity-50"
                    aria-label={phrases.declarationsWizardFieldMaxLength}
                  />
                  {field.kind === "enum" ? (
                    <input
                      value={field.enumValues.join(", ")}
                      disabled={disabled}
                      placeholder="a, b"
                      onChange={(event) =>
                        onChange({
                          ...value,
                          fields: updateField(value.fields, index, {
                            enumValues: event.target.value
                              .split(",")
                              .map((v) => v.trim())
                              .filter(Boolean),
                          }),
                        })
                      }
                      className="mt-1 w-full min-w-[8rem] rounded border border-border bg-surface px-1 py-0.5 font-mono disabled:opacity-70"
                      aria-label="enumValues"
                    />
                  ) : null}
                </td>
                <td className="px-1 py-1">
                  <input
                    type="number"
                    value={field.minimum ?? ""}
                    disabled={disabled || !formKindAllowsIntegerBounds(field.kind)}
                    onChange={(event) => {
                      const raw = event.target.value;
                      const minimum = raw === "" ? null : Number(raw);
                      onChange({
                        ...value,
                        fields: updateField(value.fields, index, {
                          minimum: Number.isFinite(minimum as number) ? minimum : null,
                        }),
                      });
                    }}
                    className="w-20 rounded border border-border bg-surface px-1 py-0.5 disabled:opacity-50"
                    aria-label={phrases.declarationsWizardFieldMin}
                  />
                </td>
                <td className="px-1 py-1">
                  <input
                    type="number"
                    value={field.maximum ?? ""}
                    disabled={disabled || !formKindAllowsIntegerBounds(field.kind)}
                    onChange={(event) => {
                      const raw = event.target.value;
                      const maximum = raw === "" ? null : Number(raw);
                      onChange({
                        ...value,
                        fields: updateField(value.fields, index, {
                          maximum: Number.isFinite(maximum as number) ? maximum : null,
                        }),
                      });
                    }}
                    className="w-20 rounded border border-border bg-surface px-1 py-0.5 disabled:opacity-50"
                    aria-label={phrases.declarationsWizardFieldMax}
                  />
                </td>
                <td className="px-1 py-1">
                  <button
                    type="button"
                    disabled={disabled || value.fields.length <= 1}
                    onClick={() =>
                      onChange({ ...value, fields: removeFormField(value.fields, index) })
                    }
                    className="rounded border border-border px-1 py-0.5 disabled:opacity-40"
                  >
                    {phrases.declarationsWizardRemoveField}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <button
        type="button"
        disabled={disabled}
        onClick={() => onChange({ ...value, fields: addFormField(value.fields) })}
        className="mt-2 rounded-md border border-border px-2 py-1 text-xs hover:bg-surface disabled:opacity-60"
      >
        {phrases.declarationsWizardAddField}
      </button>
      {value.effects.length > 0 ? (
        <p className="mt-2 text-xs text-muted">
          {fillPhrase(phrases.declarationsFormWizardEffectsPreserved, {
            count: value.effects.length,
          })}
        </p>
      ) : (
        <p className="mt-2 text-xs text-muted">{phrases.declarationsFormWizardEffectsDefault}</p>
      )}
    </div>
  );
}
