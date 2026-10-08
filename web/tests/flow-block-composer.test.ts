import { describe, expect, it } from "vitest";
import {
  applyFlowBlocksToYaml,
  emptyFlowPageBlocks,
  moveBlockId,
  parseFlowBlocksFromYaml,
  toggleBlockId,
} from "@/lib/flow-block-composer";
import { PAGE_BLOCK_IDS } from "@/lib/page-blocks-catalog";

const sampleFlow = `flowKey: demo-item
titleEn: Demo
titleZh: 演示
version: 1
permission: page.read
list:
  path: /pages/demo-item
  apiPath: /api/v1/demo/items
  itemsKey: records
detail:
  path: /pages/demo-item/{id}
  apiPath: /api/v1/demo/items
  idField: itemId
submit:
  path: /pages/demo-item/new
  apiPath: /api/v1/demo/items
  redirectTo: /pages/demo-item
`;

describe("flow-block-composer — 流程积木编排", () => {
  it("parses missing blocks as empty — 缺失 blocks 视为空", () => {
    expect(parseFlowBlocksFromYaml(sampleFlow)).toEqual(emptyFlowPageBlocks());
  });

  it("parses blocks under each section — 解析各节 blocks", () => {
    const yaml = `list:
  path: /pages/x
  blocks:
    - ListTable
    - Section
detail:
  path: /pages/x/{id}
  blocks:
    - DetailReadonly
submit:
  path: /pages/x/new
  blocks: []
`;
    expect(parseFlowBlocksFromYaml(yaml)).toEqual({
      list: ["ListTable", "Section"],
      detail: ["DetailReadonly"],
      submit: [],
    });
  });

  it("applies blocks without wiping other keys — 合并 blocks 不抹其它键", () => {
    const next = applyFlowBlocksToYaml(sampleFlow, {
      list: ["Section", "ListTable"],
      detail: ["DetailReadonly"],
      submit: ["FormFields", "SubmitBar"],
    });
    expect(next).toContain("flowKey: demo-item");
    expect(next).toContain("itemsKey: records");
    expect(next).toContain("idField: itemId");
    expect(next).toContain("redirectTo: /pages/demo-item");
    expect(parseFlowBlocksFromYaml(next)).toEqual({
      list: ["Section", "ListTable"],
      detail: ["DetailReadonly"],
      submit: ["FormFields", "SubmitBar"],
    });
    // order within list section: existing keys then blocks
    const listChunk = next.slice(next.indexOf("list:"), next.indexOf("detail:"));
    expect(listChunk.indexOf("path:")).toBeLessThan(listChunk.indexOf("blocks:"));
    expect(listChunk.indexOf("itemsKey:")).toBeLessThan(listChunk.indexOf("blocks:"));
  });

  it("omits blocks when emptied — 清空时省略 blocks", () => {
    const withBlocks = applyFlowBlocksToYaml(sampleFlow, {
      list: ["ListTable"],
      detail: [],
      submit: [],
    });
    expect(withBlocks).toContain("  blocks:\n    - ListTable");
    const cleared = applyFlowBlocksToYaml(withBlocks, emptyFlowPageBlocks());
    expect(cleared).not.toMatch(/^\s+blocks:/m);
    expect(parseFlowBlocksFromYaml(cleared)).toEqual(emptyFlowPageBlocks());
    expect(cleared).toContain("apiPath: /api/v1/demo/items");
  });

  it("round-trips replace — 替换后往返一致", () => {
    const once = applyFlowBlocksToYaml(sampleFlow, {
      list: ["ListTable"],
      detail: ["Tabs", "DetailReadonly"],
      submit: ["UserPicker", "OrgPicker", "SubmitBar"],
    });
    const twice = applyFlowBlocksToYaml(once, {
      list: ["FlowSorter", "ListTable"],
      detail: ["DetailReadonly"],
      submit: ["SubmitBar"],
    });
    expect(parseFlowBlocksFromYaml(twice)).toEqual({
      list: ["FlowSorter", "ListTable"],
      detail: ["DetailReadonly"],
      submit: ["SubmitBar"],
    });
  });

  it("toggles and reorders — 勾选与调序", () => {
    expect(toggleBlockId([], "ListTable", true)).toEqual(["ListTable"]);
    expect(toggleBlockId(["ListTable"], "ListTable", false)).toEqual([]);
    expect(moveBlockId(["ListTable", "Section", "Tabs"], "ListTable", 1)).toEqual([
      "Section",
      "ListTable",
      "Tabs",
    ]);
    expect(moveBlockId(["ListTable", "Section"], "Section", -1)).toEqual(["Section", "ListTable"]);
    expect(PAGE_BLOCK_IDS).toContain("FlowSorter");
  });
});
