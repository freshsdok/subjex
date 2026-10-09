"use client";

import type { PhraseBook } from "@/i18n/phrases";
import {
  fieldControlKind,
  fieldKindLabel,
  fieldPickerRole,
  type FormFieldInput,
} from "@/lib/form-field-input";
import { OrgPicker } from "./org-picker";
import { UserPicker } from "./user-picker";

// FormFields — 按表单/实体字段渲染控件（checkbox/date/select/number/text；userRef/orgRef → 选人/选部门）。
export function FormFields({
  fields,
  values,
  onChange,
  phrases,
  tenantId,
  disabled = false,
  readOnly = false,
}: {
  fields: FormFieldInput[];
  values: Record<string, string>;
  onChange: (name: string, value: string) => void;
  phrases: PhraseBook;
  tenantId?: string;
  disabled?: boolean;
  readOnly?: boolean;
}) {
  const locked = disabled || readOnly;

  return (
    <div className="flex flex-col gap-3">
      {fields.map((field) => {
        const kindLabel = fieldKindLabel(field.kind, phrases);
        const labelText = `${field.name} (${kindLabel}${field.required ? ` · ${phrases.requiredMark}` : ""})`;
        const picker = fieldPickerRole(field);
        const value = values[field.name] ?? "";

        if (picker === "org") {
          if (readOnly) {
            return (
              <p key={field.name} className="text-sm">
                <span className="text-muted">{labelText}: </span>
                <code>{value || "—"}</code>
              </p>
            );
          }
          return (
            <OrgPicker
              key={field.name}
              name={field.name}
              label={labelText}
              value={value}
              onChange={(next) => onChange(field.name, next)}
              tenantId={tenantId}
              phrases={phrases}
              disabled={locked}
            />
          );
        }
        if (picker === "user") {
          if (readOnly) {
            return (
              <p key={field.name} className="text-sm">
                <span className="text-muted">{labelText}: </span>
                <code>{value || "—"}</code>
              </p>
            );
          }
          return (
            <UserPicker
              key={field.name}
              name={field.name}
              label={labelText}
              value={value}
              onChange={(next) => onChange(field.name, next)}
              tenantId={tenantId}
              phrases={phrases}
              disabled={locked}
            />
          );
        }

        const control = fieldControlKind(field);
        const label = (
          <>
            <code>{field.name}</code>
            <span className="ml-2 text-xs text-muted">
              {kindLabel}
              {field.required ? ` · ${phrases.requiredMark}` : ""}
            </span>
          </>
        );

        if (readOnly) {
          const display =
            control === "checkbox" ? ((value || "false") === "true" ? "true" : "false") : value || "—";
          return (
            <p key={field.name} className="text-sm">
              <span>{label}: </span>
              <code>{display}</code>
            </p>
          );
        }

        if (control === "checkbox") {
          return (
            <label key={field.name} className="flex items-center gap-2 text-sm">
              <input
                name={field.name}
                type="checkbox"
                checked={(value || "false") === "true"}
                disabled={locked}
                onChange={(event) => onChange(field.name, event.target.checked ? "true" : "false")}
                className="rounded border border-border"
              />
              <span>{label}</span>
            </label>
          );
        }
        if (control === "select") {
          return (
            <label key={field.name} className="flex flex-col gap-1 text-sm">
              <span>{label}</span>
              <select
                name={field.name}
                value={value}
                disabled={locked}
                onChange={(event) => onChange(field.name, event.target.value)}
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
            <span>{label}</span>
            <input
              name={field.name}
              type={control === "date" ? "date" : control === "number" ? "number" : "text"}
              value={value}
              disabled={locked}
              onChange={(event) => onChange(field.name, event.target.value)}
              className="rounded-md border border-border bg-background px-2 py-1"
            />
          </label>
        );
      })}
    </div>
  );
}
