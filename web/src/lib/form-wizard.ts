// Form draft wizard helpers — 表单草稿向导：从 YAML 解析/写回（无 yaml 依赖；尽量保留 effects）。

import { DEFAULT_DECLARATION_PERMISSION } from "@/lib/declaration-permission-catalog";

export const FORM_FIELD_KINDS = ["text", "integer", "boolean", "date", "enum", "userRef"] as const;
export type FormWizardFieldKind = (typeof FORM_FIELD_KINDS)[number];

export type FormWizardField = {
  name: string;
  kind: FormWizardFieldKind;
  required: boolean;
  maxLength: number | null;
  minimum: number | null;
  maximum: number | null;
  enumValues: string[];
};

export function formKindClearsMaxLength(kind: FormWizardFieldKind): boolean {
  return kind === "integer" || kind === "boolean";
}

export function formKindAllowsIntegerBounds(kind: FormWizardFieldKind): boolean {
  return kind === "integer";
}

function parseEnumValuesList(raw: string): string[] {
  const t = raw.trim();
  if (!t.startsWith("[") || !t.endsWith("]")) return [];
  const inner = t.slice(1, -1).trim();
  if (!inner) return [];
  return inner
    .split(",")
    .map((part) => {
      const p = part.trim();
      if ((p.startsWith('"') && p.endsWith('"')) || (p.startsWith("'") && p.endsWith("'"))) {
        return p.slice(1, -1);
      }
      return p;
    })
    .filter((v) => v.length > 0);
}

function formatEnumValuesList(values: readonly string[]): string {
  return `[${values.map((v) => v.trim()).filter(Boolean).join(", ")}]`;
}

export type FormWizardEffect = {
  key: string;
  params: Record<string, string>;
};

export type FormWizardState = {
  formKey: string;
  titleEn: string;
  titleZh: string;
  version: number;
  permission: string;
  tenantScoped: boolean;
  domainAction: string;
  entityKey: string;
  fields: FormWizardField[];
  effects: FormWizardEffect[];
};

const SCALAR =
  /^(formKey|titleEn|titleZh|version|permission|tenantScoped|domainAction|entityKey):\s*(.*?)\s*(?:#.*)?$/;
const FIELD_START = /^ {2}-\s+name:\s*(\S+)\s*(?:#.*)?$/;
const FIELD_PROP = /^ {4}(kind|required|maxLength|minimum|maximum|enumValues):\s*(.*?)\s*(?:#.*)?$/;
const EFFECT_START = /^ {2}-\s+key:\s*(\S+)\s*(?:#.*)?$/;
const EFFECT_PARAM = /^ {6}([A-Za-z_][\w]*):\s*(.*?)\s*(?:#.*)?$/;
const ROOT_KEY = /^[A-Za-z_][\w-]*:\s*/;

function stripQuotes(raw: string): string {
  const t = raw.trim();
  if (
    (t.startsWith('"') && t.endsWith('"')) ||
    (t.startsWith("'") && t.endsWith("'"))
  ) {
    return t.slice(1, -1);
  }
  return t;
}

function parseBool(raw: string): boolean | null {
  const t = stripQuotes(raw).toLowerCase();
  if (t === "true") return true;
  if (t === "false") return false;
  return null;
}

function parseIntLoose(raw: string): number | null {
  const t = stripQuotes(raw);
  if (!/^-?\d+$/.test(t)) return null;
  const n = Number(t);
  return Number.isSafeInteger(n) ? n : null;
}

export function isFormWizardFieldKind(value: string): value is FormWizardFieldKind {
  return (FORM_FIELD_KINDS as readonly string[]).includes(value);
}

/** Default audit.write effect stub — 默认审计副作用桩。 */
export function defaultFormAuditEffect(
  domainAction: string,
  targetField: string,
): FormWizardEffect {
  return {
    key: "audit.write",
    params: {
      actionName: domainAction.trim() || "entity.record.upsert",
      actionTargetField: targetField.trim() || "id",
    },
  };
}

/** Empty / template-like state — 空状态（含默认 audit.write）。 */
export function emptyFormWizard(formKey = "example-key"): FormWizardState {
  const key = formKey.trim() || "example-key";
  const fields: FormWizardField[] = [
    { name: "id", kind: "text", required: true, maxLength: 64, minimum: null, maximum: null, enumValues: [] },
  ];
  return {
    formKey: key,
    titleEn: "Untitled",
    titleZh: "未命名",
    version: 1,
    permission: DEFAULT_DECLARATION_PERMISSION,
    tenantScoped: false,
    domainAction: "entity.record.upsert",
    entityKey: key,
    fields,
    effects: [defaultFormAuditEffect("entity.record.upsert", "id")],
  };
}

/**
 * Parse form YAML into wizard state (best-effort).
 * 解析表单 YAML；发现 effects 则保留；缺失则 effects 为空（apply 时可补默认）。
 */
export function parseFormWizardFromYaml(yaml: string): FormWizardState {
  const state = emptyFormWizard("untitled");
  state.fields = [];
  state.effects = [];
  state.entityKey = "";
  const lines = yaml.replace(/\r\n/g, "\n").split("\n");
  let section: "none" | "fields" | "effects" = "none";
  let currentField: FormWizardField | null = null;
  let currentEffect: FormWizardEffect | null = null;
  let inParams = false;

  const flushField = () => {
    if (currentField && currentField.name) {
      if (formKindClearsMaxLength(currentField.kind)) currentField.maxLength = null;
      if (!formKindAllowsIntegerBounds(currentField.kind)) {
        currentField.minimum = null;
        currentField.maximum = null;
      }
      if (currentField.kind !== "enum") currentField.enumValues = [];
      state.fields.push(currentField);
    }
    currentField = null;
  };

  const flushEffect = () => {
    if (currentEffect && currentEffect.key) {
      state.effects.push(currentEffect);
    }
    currentEffect = null;
    inParams = false;
  };

  for (const line of lines) {
    if (section === "none") {
      if (/^fields:\s*(?:#.*)?$/.test(line)) {
        section = "fields";
        continue;
      }
      if (/^effects:\s*(?:#.*)?$/.test(line)) {
        section = "effects";
        continue;
      }
      const m = line.match(SCALAR);
      if (!m) continue;
      const [, key, raw] = m;
      if (key === "formKey") state.formKey = stripQuotes(raw) || state.formKey;
      else if (key === "titleEn") state.titleEn = stripQuotes(raw);
      else if (key === "titleZh") state.titleZh = stripQuotes(raw);
      else if (key === "version") {
        const n = parseIntLoose(raw);
        if (n != null && n >= 1) state.version = n;
      } else if (key === "permission") state.permission = stripQuotes(raw) || state.permission;
      else if (key === "tenantScoped") {
        const b = parseBool(raw);
        if (b != null) state.tenantScoped = b;
      } else if (key === "domainAction") state.domainAction = stripQuotes(raw) || state.domainAction;
      else if (key === "entityKey") state.entityKey = stripQuotes(raw);
      continue;
    }

    if (ROOT_KEY.test(line) && !line.startsWith(" ")) {
      if (section === "fields") flushField();
      if (section === "effects") flushEffect();
      section = "none";
      if (/^fields:\s*(?:#.*)?$/.test(line)) {
        section = "fields";
        continue;
      }
      if (/^effects:\s*(?:#.*)?$/.test(line)) {
        section = "effects";
        continue;
      }
      const m = line.match(SCALAR);
      if (m) {
        const [, key, raw] = m;
        if (key === "formKey") state.formKey = stripQuotes(raw) || state.formKey;
        else if (key === "entityKey") state.entityKey = stripQuotes(raw);
        else if (key === "domainAction") state.domainAction = stripQuotes(raw) || state.domainAction;
      }
      continue;
    }

    if (section === "fields") {
      const start = line.match(FIELD_START);
      if (start) {
        flushField();
        currentField = {
          name: start[1],
          kind: "text",
          required: false,
          maxLength: null,
          minimum: null,
          maximum: null,
          enumValues: [],
        };
        continue;
      }
      if (currentField) {
        const prop = line.match(FIELD_PROP);
        if (prop) {
          const [, name, raw] = prop;
          if (name === "kind") {
            const k = stripQuotes(raw);
            if (isFormWizardFieldKind(k)) currentField.kind = k;
          } else if (name === "required") {
            const b = parseBool(raw);
            if (b != null) currentField.required = b;
          } else if (name === "maxLength") {
            const n = parseIntLoose(raw);
            if (n != null && n >= 1) currentField.maxLength = n;
          } else if (name === "minimum") {
            const n = parseIntLoose(raw);
            if (n != null) currentField.minimum = n;
          } else if (name === "maximum") {
            const n = parseIntLoose(raw);
            if (n != null) currentField.maximum = n;
          } else if (name === "enumValues") {
            currentField.enumValues = parseEnumValuesList(raw);
          }
          continue;
        }
      }
      continue;
    }

    if (section === "effects") {
      const start = line.match(EFFECT_START);
      if (start) {
        flushEffect();
        currentEffect = { key: start[1], params: {} };
        inParams = false;
        continue;
      }
      if (currentEffect) {
        if (/^ {4}params:\s*(?:#.*)?$/.test(line)) {
          inParams = true;
          continue;
        }
        if (inParams) {
          const param = line.match(EFFECT_PARAM);
          if (param) {
            currentEffect.params[param[1]] = stripQuotes(param[2]);
            continue;
          }
          if (line.trim() === "") continue;
          inParams = false;
        }
      }
    }
  }
  flushField();
  flushEffect();
  if (state.fields.length === 0) {
    state.fields = emptyFormWizard(state.formKey).fields;
  }
  return state;
}

function formatFormField(field: FormWizardField): string[] {
  const lines = [
    `  - name: ${field.name}`,
    `    kind: ${field.kind}`,
    `    required: ${field.required ? "true" : "false"}`,
  ];
  if (!formKindClearsMaxLength(field.kind) && field.maxLength != null && field.maxLength >= 1) {
    lines.push(`    maxLength: ${field.maxLength}`);
  }
  if (formKindAllowsIntegerBounds(field.kind)) {
    if (field.minimum != null) lines.push(`    minimum: ${field.minimum}`);
    if (field.maximum != null) lines.push(`    maximum: ${field.maximum}`);
  }
  if (field.kind === "enum" && field.enumValues.length > 0) {
    lines.push(`    enumValues: ${formatEnumValuesList(field.enumValues)}`);
  }
  return lines;
}

function formatEffects(effects: readonly FormWizardEffect[]): string[] {
  if (effects.length === 0) return [];
  const lines = ["effects:"];
  for (const effect of effects) {
    lines.push(`  - key: ${effect.key}`);
    const entries = Object.entries(effect.params);
    if (entries.length > 0) {
      lines.push("    params:");
      for (const [k, v] of entries) {
        lines.push(`      ${k}: ${v}`);
      }
    }
  }
  return lines;
}

/**
 * Ensure at least one audit.write when applying a new/blank effects list.
 * 应用时若无 effects，补一条默认 audit.write（用首字段作目标）。
 */
export function withDefaultEffectsIfEmpty(state: FormWizardState): FormWizardState {
  if (state.effects.length > 0) return state;
  const target = state.fields[0]?.name?.trim() || "id";
  return {
    ...state,
    effects: [defaultFormAuditEffect(state.domainAction, target)],
  };
}

/**
 * Generate form YAML from wizard state.
 * 由向导状态生成表单 YAML；保留 state.effects；空则按 withDefaultEffectsIfEmpty 补桩。
 */
export function applyFormWizardToYaml(_yaml: string, rawState: FormWizardState): string {
  const state = withDefaultEffectsIfEmpty(rawState);
  const formKey = state.formKey.trim() || "untitled";
  const version = state.version >= 1 ? Math.floor(state.version) : 1;
  const permission = state.permission.trim() || DEFAULT_DECLARATION_PERMISSION;
  const domainAction = state.domainAction.trim() || "entity.record.upsert";
  const fields =
    state.fields.length > 0 ? state.fields : emptyFormWizard(formKey).fields;

  const lines: string[] = [
    `formKey: ${formKey}`,
    `titleEn: ${state.titleEn.trim() || "Untitled"}`,
    `titleZh: ${state.titleZh.trim() || "未命名"}`,
    `version: ${version}`,
    `permission: ${permission}`,
    `tenantScoped: ${state.tenantScoped ? "true" : "false"}`,
    `domainAction: ${domainAction}`,
  ];
  const entityKey = state.entityKey.trim();
  if (entityKey) {
    lines.push(`entityKey: ${entityKey}`);
  }
  lines.push("fields:");
  for (const field of fields) {
    lines.push(...formatFormField(field));
  }
  lines.push(...formatEffects(state.effects));
  lines.push("");
  return lines.join("\n");
}

/** Add a blank field row — 追加空字段行。 */
export function addFormField(fields: readonly FormWizardField[]): FormWizardField[] {
  return [
    ...fields,
    { name: "", kind: "text", required: false, maxLength: 64, minimum: null, maximum: null, enumValues: [] },
  ];
}

/** Remove field by index — 按索引删除字段。 */
export function removeFormField(
  fields: readonly FormWizardField[],
  index: number,
): FormWizardField[] {
  return fields.filter((_, i) => i !== index);
}
