// Form field input helpers — 表单字段控件：种类文案、控件映射、提交值强制转换（供 publication / declared submit 共用）。

import type { PhraseBook } from "@/i18n/phrases";

export type FormFieldInput = {
  name: string;
  kind: string;
  required: boolean;
  enumValues?: string[];
};

/** Control used in the console form — 控制台表单用的控件种类。 */
export type FieldControlKind = "checkbox" | "date" | "select" | "number" | "text";

export type FormFieldPayloadValue = string | number | boolean;

/** Initial string value for controlled inputs — 受控输入的初始字符串值。 */
export function emptyFieldValue(field: FormFieldInput): string {
  // Boolean defaults to false so required checkboxes are never "missing".
  // 布尔默认 false，必填复选框不会一直处于缺失态。
  if (field.kind === "boolean") return "false";
  return "";
}

export function emptyFieldValues(fields: readonly FormFieldInput[]): Record<string, string> {
  return Object.fromEntries(fields.map((field) => [field.name, emptyFieldValue(field)]));
}

export function fieldKindLabel(kind: string, phrases: PhraseBook): string {
  switch (kind) {
    case "integer":
      return phrases.fieldKindInteger;
    case "boolean":
      return phrases.fieldKindBoolean;
    case "date":
      return phrases.fieldKindDate;
    case "enum":
      return phrases.fieldKindEnum;
    case "subjectRef":
      return phrases.fieldKindSubjectRef;
    case "organizationRef":
      return phrases.fieldKindOrganizationRef;
    default:
      return phrases.fieldKindText;
  }
}

/**
 * Maps field kind (+ enumValues) to a console control.
 * Enum without values falls back to text. subjectRef/organizationRef map to text here;
 * FormFields uses fieldPickerRole for SubjectPicker/OrganizationPicker.
 * 按种类映射控件；无 enumValues 的 enum 退回文本；选主体/选组织由 fieldPickerRole 决定。
 */
export function fieldControlKind(field: FormFieldInput): FieldControlKind {
  switch (field.kind) {
    case "boolean":
      return "checkbox";
    case "date":
      return "date";
    case "enum":
      return field.enumValues && field.enumValues.length > 0 ? "select" : "text";
    case "integer":
      return "number";
    default:
      return "text";
  }
}

/**
 * Coerce one raw string (from inputs) into the JSON payload value.
 * Empty optional → omit (undefined). Integer → Number; boolean → true/false; else string.
 * 将输入字符串转为提交值；可选空值返回 undefined 以便省略。
 */
export function coerceFormFieldValue(
  field: FormFieldInput,
  raw: string | undefined,
): FormFieldPayloadValue | undefined {
  const trimmed = (raw ?? "").trim();
  if (field.kind === "boolean") {
    if (trimmed === "" || trimmed.toLowerCase() === "false") return false;
    if (trimmed.toLowerCase() === "true") return true;
    // Non-canonical truthy strings still become true so checkbox "on" works.
    // 非规范真值字符串仍当 true，兼容 checkbox 的 on。
    return true;
  }
  if (trimmed === "") {
    return undefined;
  }
  if (field.kind === "integer") {
    return Number(trimmed);
  }
  return trimmed;
}

/** Build submission values map; skips empty optional fields — 组装提交值，跳过可选空字段。 */
export function buildFormPayload(
  fields: readonly FormFieldInput[],
  values: Record<string, string>,
): Record<string, FormFieldPayloadValue> {
  const payload: Record<string, FormFieldPayloadValue> = {};
  for (const field of fields) {
    const coerced = coerceFormFieldValue(field, values[field.name]);
    if (coerced === undefined) {
      // Match prior UI: still send "" for empty optional non-boolean so server blank-skip applies,
      // except we omit when undefined from coerce (empty string kinds). Prefer omit for cleanliness.
      // 与原先「空也塞进 payload」不同：空可选直接省略；服务端同样跳过缺失键。
      continue;
    }
    payload[field.name] = coerced;
  }
  return payload;
}

/** Whether a required field is missing a usable value — 必填字段是否缺值。 */
export function isRequiredFieldMissing(field: FormFieldInput, raw: string | undefined): boolean {
  if (!field.required) return false;
  if (field.kind === "boolean") {
    // Boolean always has a value once initialized; treat only blank as missing.
    // 布尔初始化后总有值；仅空白视为缺失。
    return (raw ?? "").trim() === "";
  }
  return (raw ?? "").trim() === "";
}

/** Which page-block picker to use — 用哪个选主体/选组织积木（kind 或约定字段名）。 */
export type FieldPickerRole = "subject" | "organization" | null;

/**
 * Prefer subjectRef/organizationRef kinds;
 * also special-case assignee / organization field names so DeclaredSubmitForm and FormFields stay aligned.
 * 优先 kind；并兼容 assignee / organization 字段名。
 */
export function fieldPickerRole(field: FormFieldInput): FieldPickerRole {
  if (field.kind === "subjectRef" || field.name === "assignee") {
    return "subject";
  }
  if (field.kind === "organizationRef" || field.name === "organization") {
    return "organization";
  }
  return null;
}
