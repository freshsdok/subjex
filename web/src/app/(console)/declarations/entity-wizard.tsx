"use client";

import type { PhraseBook } from "@/i18n/phrases";
import {
  ENTITY_FIELD_KINDS,
  addEntityField,
  entityKindClearsMaxLength,
  formatEnumValuesList,
  parseEnumValuesList,
  removeEntityField,
  type EntityWizardField,
  type EntityWizardState,
} from "@/lib/entity-wizard";

type Props = {
  phrases: PhraseBook;
  value: EntityWizardState;
  /** Lock entityKey when editing an existing declaration key — 编辑已有键时锁定 entityKey。 */
  keyLocked?: boolean;
  disabled?: boolean;
  onChange: (next: EntityWizardState) => void;
  onApply: () => void;
};

function updateField(
  fields: EntityWizardField[],
  index: number,
  patch: Partial<EntityWizardField>,
): EntityWizardField[] {
  return fields.map((field, i) => {
    if (i !== index) return field;
    const next = { ...field, ...patch };
    if (entityKindClearsMaxLength(next.kind)) next.maxLength = null;
    else if (patch.kind && !entityKindClearsMaxLength(next.kind) && next.maxLength == null) {
      next.maxLength = 64;
    }
    if (next.kind !== "enum") next.enumValues = [];
    if (next.kind !== "entityRef") next.refEntityKey = null;
    return next;
  });
}

// EntityWizard — 实体声明结构化向导（非自由画布）：字段表编辑后「应用到 YAML」。
export function EntityWizard({ phrases, value, keyLocked, disabled, onChange, onApply }: Props) {
  return (
    <div className="rounded-md border border-border bg-background px-3 py-3">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <div>
          <p className="text-sm font-medium">{phrases.declarationsEntityWizardTitle}</p>
          <p className="text-xs text-muted">{phrases.declarationsEntityWizardHint}</p>
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
          <span className="text-muted">{phrases.declarationsWizardEntityKey}</span>
          <input
            value={value.entityKey}
            disabled={disabled || keyLocked}
            onChange={(event) => onChange({ ...value, entityKey: event.target.value })}
            className="rounded-md border border-border bg-surface px-2 py-1 font-mono disabled:opacity-70"
            aria-label={phrases.declarationsWizardEntityKey}
          />
        </label>
        <label className="flex flex-col gap-1 text-xs">
          <span className="text-muted">{phrases.declarationsWizardTableName}</span>
          <input
            value={value.tableName}
            disabled={disabled}
            onChange={(event) => onChange({ ...value, tableName: event.target.value })}
            className="rounded-md border border-border bg-surface px-2 py-1 font-mono disabled:opacity-70"
            aria-label={phrases.declarationsWizardTableName}
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
          <input
            value={value.permission}
            disabled={disabled}
            onChange={(event) => onChange({ ...value, permission: event.target.value })}
            className="rounded-md border border-border bg-surface px-2 py-1 font-mono disabled:opacity-70"
            aria-label={phrases.declarationsWizardPermission}
          />
        </label>
        <label className="flex items-center gap-2 text-xs sm:col-span-2 lg:col-span-1">
          <input
            type="checkbox"
            checked={value.tenantScoped}
            disabled={disabled}
            onChange={(event) => onChange({ ...value, tenantScoped: event.target.checked })}
          />
          <span>{phrases.declarationsWizardTenantScoped}</span>
        </label>
      </div>

      <p className="mb-1 text-xs font-semibold">{phrases.declarationsWizardFieldsTitle}</p>
      <p className="mb-2 text-xs text-muted">{phrases.declarationsEntityWizardPkHint}</p>
      <div className="overflow-x-auto">
        <table className="w-full border-collapse text-xs">
          <thead className="text-left text-muted">
            <tr>
              <th className="px-1 py-1 font-medium">{phrases.declarationsWizardFieldName}</th>
              <th className="px-1 py-1 font-medium">{phrases.declarationsWizardFieldKind}</th>
              <th className="px-1 py-1 font-medium">{phrases.declarationsWizardFieldRequired}</th>
              <th className="px-1 py-1 font-medium">{phrases.declarationsWizardFieldMaxLength}</th>
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
                  {index === 0 ? (
                    <span className="ml-1 text-muted">{phrases.declarationsWizardPkBadge}</span>
                  ) : null}
                </td>
                <td className="px-1 py-1">
                  <select
                    value={field.kind}
                    disabled={disabled}
                    onChange={(event) =>
                      onChange({
                        ...value,
                        fields: updateField(value.fields, index, {
                          kind: event.target.value as EntityWizardField["kind"],
                        }),
                      })
                    }
                    className="rounded border border-border bg-surface px-1 py-0.5 disabled:opacity-70"
                    aria-label={phrases.declarationsWizardFieldKind}
                  >
                    {ENTITY_FIELD_KINDS.map((kind) => (
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
                    disabled={disabled || entityKindClearsMaxLength(field.kind)}
                    placeholder={entityKindClearsMaxLength(field.kind) ? "—" : ""}
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
                      value={formatEnumValuesList(field.enumValues).slice(1, -1)}
                      disabled={disabled}
                      placeholder="a, b"
                      onChange={(event) =>
                        onChange({
                          ...value,
                          fields: updateField(value.fields, index, {
                            enumValues: parseEnumValuesList(`[${event.target.value}]`),
                          }),
                        })
                      }
                      className="mt-1 w-full min-w-[8rem] rounded border border-border bg-surface px-1 py-0.5 font-mono disabled:opacity-70"
                      aria-label="enumValues"
                    />
                  ) : null}
                  {field.kind === "entityRef" ? (
                    <input
                      value={field.refEntityKey ?? ""}
                      disabled={disabled}
                      placeholder="refEntityKey"
                      onChange={(event) =>
                        onChange({
                          ...value,
                          fields: updateField(value.fields, index, {
                            refEntityKey: event.target.value.trim() || null,
                          }),
                        })
                      }
                      className="mt-1 w-full min-w-[8rem] rounded border border-border bg-surface px-1 py-0.5 font-mono disabled:opacity-70"
                      aria-label="refEntityKey"
                    />
                  ) : null}
                </td>
                <td className="px-1 py-1">
                  <button
                    type="button"
                    disabled={disabled || value.fields.length <= 1}
                    onClick={() =>
                      onChange({ ...value, fields: removeEntityField(value.fields, index) })
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
        onClick={() => onChange({ ...value, fields: addEntityField(value.fields) })}
        className="mt-2 rounded-md border border-border px-2 py-1 text-xs hover:bg-surface disabled:opacity-60"
      >
        {phrases.declarationsWizardAddField}
      </button>
    </div>
  );
}
