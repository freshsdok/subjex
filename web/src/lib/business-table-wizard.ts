// Business-table wizard — 新建业务表向导：一次生成 entity+form+flow 三份草稿 YAML（无 yaml 依赖；非自由画布）。

import {
  ENTITY_FIELD_KINDS,
  applyEntityWizardToYaml,
  type EntityWizardField,
  type EntityWizardFieldKind,
  type EntityWizardState,
} from "@/lib/entity-wizard";
import {
  applyFormWizardToYaml,
  defaultFormAuditEffect,
  type FormWizardField,
  type FormWizardFieldKind,
  type FormWizardState,
} from "@/lib/form-wizard";
import {
  DECLARATION_PERMISSION_CATALOG,
  DEFAULT_DECLARATION_PERMISSION,
} from "@/lib/declaration-permission-catalog";

/** Permissions the wizard may pick (RT-3 pick-only catalog) — 向导可选权限（只选不造）。 */
export const BUSINESS_TABLE_PERMISSION_OPTIONS = DECLARATION_PERMISSION_CATALOG;



export const BUSINESS_TABLE_URGENCY_VALUES = [
  "low",
  "medium",
  "high",
  "critical",
] as const;

export type BusinessTableField = {
  name: string;
  kind: EntityWizardFieldKind;
  required: boolean;
  maxLength: number | null;
  enumValues: string[];
};

export type BusinessTableWizardState = {
  key: string;
  titleEn: string;
  titleZh: string;
  permission: string;
  tenantScoped: boolean;
  fields: BusinessTableField[];
};

export type BusinessTableDraftBundle = {
  key: string;
  entityYaml: string;
  formYaml: string;
  flowYaml: string;
};

function blankField(name = ""): BusinessTableField {
  return {
    name,
    kind: "text",
    required: false,
    maxLength: 64,
    enumValues: [],
  };
}

/**
 * Default 报修单 shape — 默认报修单模板（键/表名可改）。
 * PK = first field ticketId; urgency enum; assignee userRef.
 */
export function defaultRepairTicketWizard(): BusinessTableWizardState {
  return {
    key: "repair-ticket",
    titleEn: "Repair ticket",
    titleZh: "报修单",
    permission: DEFAULT_DECLARATION_PERMISSION,
    tenantScoped: true,
    fields: [
      {
        name: "ticketId",
        kind: "text",
        required: true,
        maxLength: 64,
        enumValues: [],
      },
      {
        name: "title",
        kind: "text",
        required: true,
        maxLength: 200,
        enumValues: [],
      },
      {
        name: "location",
        kind: "text",
        required: true,
        maxLength: 200,
        enumValues: [],
      },
      {
        name: "urgency",
        kind: "enum",
        required: true,
        maxLength: 32,
        enumValues: [...BUSINESS_TABLE_URGENCY_VALUES],
      },
      {
        name: "assignee",
        kind: "userRef",
        required: false,
        maxLength: 64,
        enumValues: [],
      },
    ],
  };
}

/** Normalize key → table_name — 键转表名。 */
export function tableNameFromKey(key: string): string {
  const k = key.trim() || "untitled";
  return k.replace(/-/g, "_");
}

/** Validate key (no spaces/slashes) — 校验声明键。 */
export function normalizeBusinessTableKey(raw: string): string | null {
  const trimmed = raw.trim();
  if (!trimmed) return null;
  if (/\s/.test(trimmed) || trimmed.includes("/")) return null;
  return trimmed;
}

/** Whether tenantScoped is off (wizard should warn) — 未开租户隔离时需警告。 */
export function businessTableNeedsTenantScopeWarn(
  state: BusinessTableWizardState,
): boolean {
  return !state.tenantScoped;
}

function toEntityFields(
  fields: readonly BusinessTableField[],
): EntityWizardField[] {
  return fields.map((f) => ({
    name: f.name.trim(),
    kind: f.kind,
    required: f.required,
    maxLength: f.maxLength,
    enumValues: f.kind === "enum" ? [...f.enumValues] : [],
    refEntityKey: null,
  }));
}

/** Map entity field kind onto form kinds (userRef kept) — 实体种类映射到表单。 */
export function mapEntityKindToFormKind(
  kind: EntityWizardFieldKind,
): FormWizardFieldKind {
  if ((ENTITY_FIELD_KINDS as readonly string[]).includes(kind)) {
    if (
      kind === "text" ||
      kind === "integer" ||
      kind === "boolean" ||
      kind === "date" ||
      kind === "enum" ||
      kind === "userRef"
    ) {
      return kind;
    }
  }
  // orgRef / entityRef → text at form layer for this wizard (fail soft)
  return "text";
}

function toFormFields(
  fields: readonly BusinessTableField[],
): FormWizardField[] {
  return fields.map((f) => {
    const kind = mapEntityKindToFormKind(f.kind);
    return {
      name: f.name.trim(),
      kind,
      required: f.required,
      maxLength:
        kind === "integer" || kind === "boolean" ? null : f.maxLength,
      minimum: null,
      maximum: null,
      enumValues: kind === "enum" ? [...f.enumValues] : [],
    };
  });
}

/** Build entity YAML from wizard state — 生成实体 YAML。 */
export function buildBusinessTableEntityYaml(
  state: BusinessTableWizardState,
): string {
  const key = state.key.trim() || "untitled";
  const entity: EntityWizardState = {
    entityKey: key,
    tableName: tableNameFromKey(key),
    version: 1,
    permission: state.permission.trim() || DEFAULT_DECLARATION_PERMISSION,
    tenantScoped: state.tenantScoped,
    fields: toEntityFields(state.fields).filter((f) => f.name.length > 0),
  };
  return applyEntityWizardToYaml("", entity);
}

/** Build form YAML (audit.write only) — 生成表单 YAML（仅 audit.write）。 */
export function buildBusinessTableFormYaml(
  state: BusinessTableWizardState,
): string {
  const key = state.key.trim() || "untitled";
  const fields = toFormFields(state.fields).filter((f) => f.name.length > 0);
  const pk = fields[0]?.name || "id";
  const form: FormWizardState = {
    formKey: key,
    titleEn: state.titleEn.trim() || "Untitled",
    titleZh: state.titleZh.trim() || "未命名",
    version: 1,
    permission: state.permission.trim() || DEFAULT_DECLARATION_PERMISSION,
    tenantScoped: state.tenantScoped,
    domainAction: "entity.record.upsert",
    entityKey: key,
    fields,
    effects: [defaultFormAuditEffect("entity.record.upsert", pk)],
  };
  return applyFormWizardToYaml("", form);
}

/**
 * Build flow YAML with fixed paths + four blocks only.
 * 固定路径与四积木：ListTable / FormFields+SubmitBar / DetailReadonly。
 */
export function buildBusinessTableFlowYaml(
  state: BusinessTableWizardState,
): string {
  const key = state.key.trim() || "untitled";
  const titleEn = state.titleEn.trim() || "Untitled";
  const titleZh = state.titleZh.trim() || "未命名";
  const permission = state.permission.trim() || DEFAULT_DECLARATION_PERMISSION;
  const pk =
    state.fields.find((f) => f.name.trim())?.name.trim() || "id";
  const lines = [
    `flowKey: ${key}`,
    `titleEn: ${titleEn}`,
    `titleZh: ${titleZh}`,
    `formKey: ${key}`,
    `entityKey: ${key}`,
    "version: 1",
    `permission: ${permission}`,
    `tenantScoped: ${state.tenantScoped ? "true" : "false"}`,
    "list:",
    `  path: /pages/${key}`,
    `  apiPath: /api/v1/entities/${key}/records`,
    "  itemsKey: records",
    "  blocks:",
    "    - ListTable",
    "detail:",
    `  path: /pages/${key}/{id}`,
    `  apiPath: /api/v1/entities/${key}/records`,
    "  itemsKey: records",
    `  idField: ${pk}`,
    "  blocks:",
    "    - DetailReadonly",
    "submit:",
    `  path: /pages/${key}/new`,
    `  apiPath: /api/v1/forms/${key}/submissions`,
    `  redirectTo: /pages/${key}`,
    "  blocks:",
    "    - FormFields",
    "    - SubmitBar",
    "",
  ];
  return lines.join("\n");
}

/** One-shot three drafts for the same key — 同键三份草稿。 */
export function buildBusinessTableDrafts(
  state: BusinessTableWizardState,
): BusinessTableDraftBundle {
  const key = state.key.trim() || "untitled";
  return {
    key,
    entityYaml: buildBusinessTableEntityYaml(state),
    formYaml: buildBusinessTableFormYaml(state),
    flowYaml: buildBusinessTableFlowYaml(state),
  };
}

export function addBusinessTableField(
  fields: readonly BusinessTableField[],
): BusinessTableField[] {
  return [...fields, blankField("")];
}

export function removeBusinessTableField(
  fields: readonly BusinessTableField[],
  index: number,
): BusinessTableField[] {
  return fields.filter((_, i) => i !== index);
}
