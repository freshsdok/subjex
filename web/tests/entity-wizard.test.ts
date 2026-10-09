import { describe, expect, it } from "vitest";
import {
  applyEntityWizardToYaml,
  emptyEntityWizard,
  parseEntityWizardFromYaml,
} from "@/lib/entity-wizard";

const demoTicketLike = `# Demo ticket entity
entityKey: demo-ticket
tableName: demo_ticket
version: 1
permission: page.read
tenantScoped: false
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
    kind: subjectRef
    required: false
    maxLength: 64
  - name: organization
    kind: organizationRef
    required: false
    maxLength: 64
`;

describe("entity-wizard — 实体向导", () => {
  it("parses demo-ticket-like stub — 解析演示工单样例", () => {
    const state = parseEntityWizardFromYaml(demoTicketLike);
    expect(state.entityKey).toBe("demo-ticket");
    expect(state.tableName).toBe("demo_ticket");
    expect(state.version).toBe(1);
    expect(state.permission).toBe("page.read");
    expect(state.tenantScoped).toBe(false);
    expect(state.fields.map((f) => f.name)).toEqual([
      "ticketId",
      "title",
      "status",
      "assignee",
      "organization",
    ]);
    expect(state.fields[0]).toMatchObject({
      name: "ticketId",
      kind: "text",
      required: true,
      maxLength: 64,
      enumValues: [],
      refEntityKey: null,
    });
    expect(state.fields[3].kind).toBe("subjectRef");
    expect(state.fields[4].kind).toBe("organizationRef");
  });

  it("round-trips apply → parse — 应用后再解析一致", () => {
    const parsed = parseEntityWizardFromYaml(demoTicketLike);
    const yaml = applyEntityWizardToYaml("", parsed);
    expect(yaml).toContain("entityKey: demo-ticket");
    expect(yaml).toContain("tableName: demo_ticket");
    expect(yaml).toContain("kind: subjectRef");
    expect(yaml).toContain("kind: organizationRef");
    expect(yaml).not.toContain("kind: userRef");
    expect(yaml).not.toContain("kind: orgRef");
    const fieldsChunk = yaml.slice(yaml.indexOf("fields:"));
    expect(fieldsChunk.indexOf("ticketId")).toBeLessThan(fieldsChunk.indexOf("title"));
    const again = parseEntityWizardFromYaml(yaml);
    expect(again).toEqual(parsed);
  });

  it("omits maxLength on integer boolean date — 这些种类不写 maxLength", () => {
    const state = emptyEntityWizard("demo-item");
    state.fields = [
      { name: "itemId", kind: "text", required: true, maxLength: 64, enumValues: [], refEntityKey: null },
      { name: "priority", kind: "integer", required: false, maxLength: 99, enumValues: [], refEntityKey: null },
      { name: "urgent", kind: "boolean", required: true, maxLength: 10, enumValues: [], refEntityKey: null },
      { name: "due", kind: "date", required: false, maxLength: 10, enumValues: [], refEntityKey: null },
    ];
    const yaml = applyEntityWizardToYaml("", state);
    expect(yaml).toContain("kind: integer");
    expect(yaml).toContain("kind: boolean");
    expect(yaml).toContain("kind: date");
    expect(yaml).not.toMatch(/priority[\s\S]*maxLength/);
    expect(yaml).not.toMatch(/urgent[\s\S]*maxLength/);
    expect(yaml).not.toMatch(/due[\s\S]*maxLength/);
    const round = parseEntityWizardFromYaml(yaml);
    expect(round.fields[1]).toMatchObject({ name: "priority", kind: "integer", maxLength: null });
    expect(round.fields[2]).toMatchObject({ name: "urgent", kind: "boolean", maxLength: null });
    expect(round.fields[3]).toMatchObject({ name: "due", kind: "date", maxLength: null });
  });

  it("round-trips enum and entityRef — enum / entityRef 往返", () => {
    const yamlIn = `entityKey: kind-sample
tableName: kind_sample
version: 1
permission: page.read
tenantScoped: false
fields:
  - name: id
    kind: text
    required: true
    maxLength: 32
  - name: status
    kind: enum
    required: true
    maxLength: 32
    enumValues: [open, closed]
  - name: related
    kind: entityRef
    required: false
    maxLength: 64
    refEntityKey: demo-ticket
`;
    const state = parseEntityWizardFromYaml(yamlIn);
    expect(state.fields[1]).toMatchObject({
      kind: "enum",
      enumValues: ["open", "closed"],
      maxLength: 32,
    });
    expect(state.fields[2]).toMatchObject({
      kind: "entityRef",
      refEntityKey: "demo-ticket",
      maxLength: 64,
    });
    const yaml = applyEntityWizardToYaml("", state);
    expect(yaml).toContain("enumValues: [open, closed]");
    expect(yaml).toContain("refEntityKey: demo-ticket");
    expect(parseEntityWizardFromYaml(yaml)).toEqual(state);
  });

  it("empty yaml falls back to stub field — 空 YAML 回落占位字段", () => {
    const state = parseEntityWizardFromYaml("");
    expect(state.fields.length).toBeGreaterThan(0);
    expect(state.fields[0].name).toBe("id");
  });
});
