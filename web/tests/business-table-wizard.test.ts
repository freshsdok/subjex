import { describe, expect, it } from "vitest";
import {
  BUSINESS_TABLE_PERMISSION_OPTIONS,
  buildBusinessTableDrafts,
  buildBusinessTableEntityYaml,
  buildBusinessTableFlowYaml,
  buildBusinessTableFormYaml,
  businessTableNeedsTenantScopeWarn,
  defaultRepairTicketWizard,
  normalizeBusinessTableKey,
  tableNameFromKey,
} from "@/lib/business-table-wizard";
import { DECLARATION_PERMISSION_CATALOG } from "@/lib/declaration-permission-catalog";
import { parseEntityWizardFromYaml } from "@/lib/entity-wizard";
import { parseFormWizardFromYaml } from "@/lib/form-wizard";
import { parseFlowBlocksFromYaml } from "@/lib/flow-block-composer";

describe("business-table-wizard — 新建业务表向导", () => {
  it("defaults repair-ticket shape — 默认报修单形态", () => {
    const state = defaultRepairTicketWizard();
    expect(state.key).toBe("repair-ticket");
    expect(tableNameFromKey(state.key)).toBe("repair_ticket");
    expect(state.tenantScoped).toBe(true);
    expect(state.permission).toBe("page.read");
    expect(BUSINESS_TABLE_PERMISSION_OPTIONS).toContain("page.read");
    expect(BUSINESS_TABLE_PERMISSION_OPTIONS).toEqual([...DECLARATION_PERMISSION_CATALOG]);
    expect(state.fields.map((f) => f.name)).toEqual([
      "ticketId",
      "title",
      "location",
      "urgency",
      "assignee",
    ]);
    expect(state.fields[0]).toMatchObject({
      name: "ticketId",
      kind: "text",
      required: true,
    });
    expect(state.fields[3]).toMatchObject({
      kind: "enum",
      enumValues: ["low", "medium", "high", "critical"],
    });
    expect(state.fields[4].kind).toBe("userRef");
  });

  it("builds entity yaml with tenantScoped and userRef — 实体 YAML", () => {
    const yaml = buildBusinessTableEntityYaml(defaultRepairTicketWizard());
    expect(yaml).toContain("entityKey: repair-ticket");
    expect(yaml).toContain("tableName: repair_ticket");
    expect(yaml).toContain("tenantScoped: true");
    expect(yaml).toContain("kind: userRef");
    expect(yaml).toContain("enumValues: [low, medium, high, critical]");
    const parsed = parseEntityWizardFromYaml(yaml);
    expect(parsed.fields[0].name).toBe("ticketId");
    expect(parsed.fields[4].kind).toBe("userRef");
  });

  it("builds form yaml with audit.write and userRef — 表单 YAML", () => {
    const yaml = buildBusinessTableFormYaml(defaultRepairTicketWizard());
    expect(yaml).toContain("formKey: repair-ticket");
    expect(yaml).toContain("domainAction: entity.record.upsert");
    expect(yaml).toContain("entityKey: repair-ticket");
    expect(yaml).toContain("kind: userRef");
    expect(yaml).toContain("actionTargetField: ticketId");
    expect(yaml).not.toContain("capability");
    expect(yaml).not.toContain("algo.");
    expect(yaml).not.toContain("ai.");
    const parsed = parseFormWizardFromYaml(yaml);
    expect(parsed.effects).toEqual([
      {
        key: "audit.write",
        params: {
          actionName: "entity.record.upsert",
          actionTargetField: "ticketId",
        },
      },
    ]);
    expect(parsed.fields[4].kind).toBe("userRef");
  });

  it("builds flow yaml with fixed paths and four blocks — 流程路径与积木", () => {
    const yaml = buildBusinessTableFlowYaml(defaultRepairTicketWizard());
    expect(yaml).toContain("path: /pages/repair-ticket");
    expect(yaml).toContain("path: /pages/repair-ticket/new");
    expect(yaml).toContain("path: /pages/repair-ticket/{id}");
    expect(yaml).toContain("idField: ticketId");
    expect(yaml).toContain("tenantScoped: true");
    const blocks = parseFlowBlocksFromYaml(yaml);
    expect(blocks).toEqual({
      list: ["ListTable"],
      detail: ["DetailReadonly"],
      submit: ["FormFields", "SubmitBar"],
    });
  });

  it("bundle shares one key — 三份草稿同键", () => {
    const state = defaultRepairTicketWizard();
    state.key = "my-repair";
    state.titleZh = "我的报修";
    const bundle = buildBusinessTableDrafts(state);
    expect(bundle.key).toBe("my-repair");
    expect(bundle.entityYaml).toContain("entityKey: my-repair");
    expect(bundle.formYaml).toContain("formKey: my-repair");
    expect(bundle.flowYaml).toContain("flowKey: my-repair");
    expect(bundle.formYaml).toContain("titleZh: 我的报修");
  });

  it("normalizes key and warns when not tenant-scoped — 键校验与租户警告", () => {
    expect(normalizeBusinessTableKey("repair-ticket")).toBe("repair-ticket");
    expect(normalizeBusinessTableKey("ba d")).toBe(null);
    expect(normalizeBusinessTableKey("a/b")).toBe(null);
    const state = defaultRepairTicketWizard();
    expect(businessTableNeedsTenantScopeWarn(state)).toBe(false);
    state.tenantScoped = false;
    expect(businessTableNeedsTenantScopeWarn(state)).toBe(true);
  });
});
