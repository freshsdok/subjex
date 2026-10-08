import { describe, expect, it } from "vitest";
import {
  applyFormWizardToYaml,
  emptyFormWizard,
  parseFormWizardFromYaml,
  withDefaultEffectsIfEmpty,
} from "@/lib/form-wizard";

const demoTicketForm = `# Demo ticket form
formKey: demo-ticket
titleEn: Demo ticket
titleZh: 演示工单
version: 1
permission: page.read
tenantScoped: false
domainAction: entity.record.upsert
entityKey: demo-ticket
fields:
  - name: ticketId
    kind: text
    required: true
    maxLength: 64
  - name: title
    kind: text
    required: true
    maxLength: 200
  - name: status
    kind: text
    required: true
    maxLength: 32
  - name: assignee
    kind: text
    required: false
    maxLength: 64
  - name: orgUnit
    kind: text
    required: false
    maxLength: 64
effects:
  - key: audit.write
    params:
      actionName: entity.record.upsert
      actionTargetField: ticketId
`;

describe("form-wizard — 表单向导", () => {
  it("parses demo-ticket-like stub and effects — 解析样例与 effects", () => {
    const state = parseFormWizardFromYaml(demoTicketForm);
    expect(state.formKey).toBe("demo-ticket");
    expect(state.titleZh).toBe("演示工单");
    expect(state.domainAction).toBe("entity.record.upsert");
    expect(state.entityKey).toBe("demo-ticket");
    expect(state.fields.map((f) => f.name)).toEqual([
      "ticketId",
      "title",
      "status",
      "assignee",
      "orgUnit",
    ]);
    expect(state.effects).toEqual([
      {
        key: "audit.write",
        params: {
          actionName: "entity.record.upsert",
          actionTargetField: "ticketId",
        },
      },
    ]);
  });

  it("round-trips and preserves effects — 往返并保留 effects", () => {
    const parsed = parseFormWizardFromYaml(demoTicketForm);
    const yaml = applyFormWizardToYaml("", parsed);
    expect(yaml).toContain("domainAction: entity.record.upsert");
    expect(yaml).toContain("entityKey: demo-ticket");
    expect(yaml).toContain("actionTargetField: ticketId");
    const again = parseFormWizardFromYaml(yaml);
    expect(again.formKey).toBe(parsed.formKey);
    expect(again.fields).toEqual(parsed.fields);
    expect(again.effects).toEqual(parsed.effects);
  });

  it("defaults audit.write when effects missing — 无 effects 时补默认桩", () => {
    const bare = `formKey: demo-item
titleEn: Demo
titleZh: 演示
version: 1
permission: page.read
tenantScoped: false
domainAction: entity.record.upsert
entityKey: demo-item
fields:
  - name: itemId
    kind: text
    required: true
    maxLength: 64
`;
    const parsed = parseFormWizardFromYaml(bare);
    expect(parsed.effects).toEqual([]);
    const withDefault = withDefaultEffectsIfEmpty(parsed);
    expect(withDefault.effects[0].key).toBe("audit.write");
    expect(withDefault.effects[0].params.actionTargetField).toBe("itemId");
    const yaml = applyFormWizardToYaml("", parsed);
    expect(yaml).toContain("effects:");
    expect(yaml).toContain("actionTargetField: itemId");
  });

  it("writes integer min/max without maxLength — integer 写 min/max", () => {
    const state = emptyFormWizard("demo-item");
    state.fields = [
      {
        name: "itemId",
        kind: "text",
        required: true,
        maxLength: 64,
        minimum: null,
        maximum: null,
        enumValues: [],
      },
      {
        name: "priority",
        kind: "integer",
        required: false,
        maxLength: null,
        minimum: 0,
        maximum: 100,
        enumValues: [],
      },
    ];
    const yaml = applyFormWizardToYaml("", state);
    expect(yaml).toContain("minimum: 0");
    expect(yaml).toContain("maximum: 100");
    expect(yaml).not.toMatch(/priority[\s\S]*maxLength/);
  });

  it("round-trips boolean date enum — boolean/date/enum 往返", () => {
    const yamlIn = `formKey: kinds-sample
titleEn: Kinds
titleZh: 种类
version: 1
permission: page.read
tenantScoped: false
domainAction: entity.record.upsert
entityKey: kinds-sample
fields:
  - name: id
    kind: text
    required: true
    maxLength: 32
  - name: urgent
    kind: boolean
    required: true
  - name: dueDate
    kind: date
    required: false
    maxLength: 10
  - name: status
    kind: enum
    required: true
    enumValues: [open, closed]
effects:
  - key: audit.write
    params:
      actionName: entity.record.upsert
      actionTargetField: id
`;
    const state = parseFormWizardFromYaml(yamlIn);
    expect(state.fields[1].kind).toBe("boolean");
    expect(state.fields[2]).toMatchObject({ kind: "date", maxLength: 10 });
    expect(state.fields[3]).toMatchObject({ kind: "enum", enumValues: ["open", "closed"] });
    const yaml = applyFormWizardToYaml("", state);
    expect(yaml).toContain("kind: boolean");
    expect(yaml).toContain("kind: date");
    expect(yaml).toContain("enumValues: [open, closed]");
    expect(parseFormWizardFromYaml(yaml).fields).toEqual(state.fields);
  });
});
