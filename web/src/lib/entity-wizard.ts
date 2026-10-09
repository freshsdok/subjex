// Entity draft wizard helpers — 实体草稿向导：从 YAML 解析/写回（无 yaml 依赖；首字段视为主键）。

import { DEFAULT_DECLARATION_PERMISSION } from "@/lib/declaration-permission-catalog";

export const ENTITY_FIELD_KINDS = [
  "text",
  "integer",
  "boolean",
  "enum",
  "date",
  "subjectRef",
  "organizationRef",
  "entityRef",
] as const;
export type EntityWizardFieldKind = (typeof ENTITY_FIELD_KINDS)[number];

/** Accept only canonical entity field kinds (O8-5) — 仅接受规范实体字段种类。 */
export function normalizeEntityFieldKind(raw: string): EntityWizardFieldKind | null {
  if ((ENTITY_FIELD_KINDS as readonly string[]).includes(raw)) {
    return raw as EntityWizardFieldKind;
  }
  return null;
}

export type EntityWizardField = {
  name: string;
  kind: EntityWizardFieldKind;
  required: boolean;
  maxLength: number | null;
  /** Enum allowed values — enum 允许值。 */
  enumValues: string[];
  /** Optional target entity key for entityRef — entityRef 可选目标实体键。 */
  refEntityKey: string | null;
};

export type EntityWizardState = {
  entityKey: string;
  tableName: string;
  version: number;
  permission: string;
  tenantScoped: boolean;
  fields: EntityWizardField[];
};

const SCALAR =
  /^(entityKey|tableName|version|permission|tenantScoped):\s*(.*?)\s*(?:#.*)?$/;
const FIELD_START = /^ {2}-\s+name:\s*(\S+)\s*(?:#.*)?$/;
const FIELD_PROP =
  /^ {4}(kind|required|maxLength|enumValues|refEntityKey):\s*(.*?)\s*(?:#.*)?$/;
const ROOT_KEY = /^[A-Za-z_][\w-]*:\s*/;

/** Kinds that must not carry maxLength — 不得带 maxLength 的种类。 */
export function entityKindClearsMaxLength(kind: EntityWizardFieldKind): boolean {
  return kind === "integer" || kind === "boolean" || kind === "date";
}

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

/** Parse YAML simple list `[a, b]` or empty — 解析 YAML 简单列表。 */
export function parseEnumValuesList(raw: string): string[] {
  const t = raw.trim();
  if (!t.startsWith("[") || !t.endsWith("]")) return [];
  const inner = t.slice(1, -1).trim();
  if (!inner) return [];
  return inner
    .split(",")
    .map((part) => stripQuotes(part.trim()))
    .filter((v) => v.length > 0);
}

export function formatEnumValuesList(values: readonly string[]): string {
  return `[${values.map((v) => v.trim()).filter(Boolean).join(", ")}]`;
}

export function isEntityWizardFieldKind(value: string): value is EntityWizardFieldKind {
  return normalizeEntityFieldKind(value) !== null;
}

function blankField(name = ""): EntityWizardField {
  return {
    name,
    kind: "text",
    required: false,
    maxLength: 64,
    enumValues: [],
    refEntityKey: null,
  };
}

/** Empty / template-like state — 空状态（首字段作主键占位）。 */
export function emptyEntityWizard(entityKey = "example-key"): EntityWizardState {
  const key = entityKey.trim() || "example-key";
  return {
    entityKey: key,
    tableName: key.replace(/-/g, "_"),
    version: 1,
    permission: DEFAULT_DECLARATION_PERMISSION,
    tenantScoped: false,
    fields: [{ ...blankField("id"), required: true, maxLength: 64 }],
  };
}

/**
 * Parse entity YAML into wizard state (best-effort line scan).
 * 解析实体 YAML；缺字段则用空/默认；首字段仍视为主键。
 */
export function parseEntityWizardFromYaml(yaml: string): EntityWizardState {
  const state = emptyEntityWizard("untitled");
  state.fields = [];
  const lines = yaml.replace(/\r\n/g, "\n").split("\n");
  let inFields = false;
  let current: EntityWizardField | null = null;

  const flush = () => {
    if (current && current.name) {
      if (entityKindClearsMaxLength(current.kind)) current.maxLength = null;
      if (current.kind !== "enum") current.enumValues = [];
      if (current.kind !== "entityRef") current.refEntityKey = null;
      state.fields.push(current);
    }
    current = null;
  };

  for (const line of lines) {
    if (!inFields) {
      if (/^fields:\s*(?:#.*)?$/.test(line)) {
        inFields = true;
        continue;
      }
      const m = line.match(SCALAR);
      if (!m) continue;
      const [, key, raw] = m;
      if (key === "entityKey") state.entityKey = stripQuotes(raw) || state.entityKey;
      else if (key === "tableName") state.tableName = stripQuotes(raw) || state.tableName;
      else if (key === "version") {
        const n = parseIntLoose(raw);
        if (n != null && n >= 1) state.version = n;
      } else if (key === "permission") state.permission = stripQuotes(raw) || state.permission;
      else if (key === "tenantScoped") {
        const b = parseBool(raw);
        if (b != null) state.tenantScoped = b;
      }
      continue;
    }

    if (ROOT_KEY.test(line) && !line.startsWith(" ")) {
      flush();
      inFields = false;
      const m = line.match(SCALAR);
      if (m) {
        const [, key, raw] = m;
        if (key === "entityKey") state.entityKey = stripQuotes(raw) || state.entityKey;
      }
      continue;
    }

    const start = line.match(FIELD_START);
    if (start) {
      flush();
      current = {
        name: start[1],
        kind: "text",
        required: false,
        maxLength: null,
        enumValues: [],
        refEntityKey: null,
      };
      continue;
    }
    if (current) {
      const prop = line.match(FIELD_PROP);
      if (prop) {
        const [, name, raw] = prop;
        if (name === "kind") {
          const k = stripQuotes(raw);
          const normalized = normalizeEntityFieldKind(k);
          if (normalized) current.kind = normalized;
        } else if (name === "required") {
          const b = parseBool(raw);
          if (b != null) current.required = b;
        } else if (name === "maxLength") {
          const n = parseIntLoose(raw);
          if (n != null && n >= 1) current.maxLength = n;
        } else if (name === "enumValues") {
          current.enumValues = parseEnumValuesList(raw);
        } else if (name === "refEntityKey") {
          const v = stripQuotes(raw);
          current.refEntityKey = v || null;
        }
        continue;
      }
      if (line.trim() === "") continue;
    }
  }
  flush();
  if (state.fields.length === 0) {
    state.fields = emptyEntityWizard(state.entityKey).fields;
  }
  return state;
}

function formatField(field: EntityWizardField): string[] {
  const lines = [
    `  - name: ${field.name}`,
    `    kind: ${field.kind}`,
    `    required: ${field.required ? "true" : "false"}`,
  ];
  if (
    !entityKindClearsMaxLength(field.kind) &&
    field.maxLength != null &&
    field.maxLength >= 1
  ) {
    lines.push(`    maxLength: ${field.maxLength}`);
  }
  if (field.kind === "enum" && field.enumValues.length > 0) {
    lines.push(`    enumValues: ${formatEnumValuesList(field.enumValues)}`);
  }
  if (field.kind === "entityRef" && field.refEntityKey) {
    lines.push(`    refEntityKey: ${field.refEntityKey}`);
  }
  return lines;
}

/**
 * Generate entity YAML from wizard state (full document replace of known keys).
 * 由向导状态生成实体 YAML；首字段为主键；integer/boolean/date 不写 maxLength。
 */
export function applyEntityWizardToYaml(_yaml: string, state: EntityWizardState): string {
  const entityKey = state.entityKey.trim() || "untitled";
  const tableName = state.tableName.trim() || entityKey.replace(/-/g, "_");
  const version = state.version >= 1 ? Math.floor(state.version) : 1;
  const permission = state.permission.trim() || DEFAULT_DECLARATION_PERMISSION;
  const fields =
    state.fields.length > 0
      ? state.fields
      : emptyEntityWizard(entityKey).fields;

  const lines: string[] = [
    `entityKey: ${entityKey}`,
    `tableName: ${tableName}`,
    `version: ${version}`,
    `permission: ${permission}`,
    `tenantScoped: ${state.tenantScoped ? "true" : "false"}`,
    "fields:",
  ];
  for (const field of fields) {
    lines.push(...formatField(field));
  }
  lines.push("");
  return lines.join("\n");
}

/** Add a blank field row — 追加空字段行。 */
export function addEntityField(fields: readonly EntityWizardField[]): EntityWizardField[] {
  return [...fields, blankField("")];
}

/** Remove field by index — 按索引删除字段。 */
export function removeEntityField(
  fields: readonly EntityWizardField[],
  index: number,
): EntityWizardField[] {
  return fields.filter((_, i) => i !== index);
}
