import { describe, expect, it } from "vitest";
import {
  ancestorIdsOf,
  buildOrgForest,
  defaultExpandedIds,
  expandIdsForSearch,
  findOrgNode,
  flattenOrgForest,
  searchMatchingIds,
  wouldClientMoveCycle,
} from "@/lib/org-tree";

describe("org-tree — 组织树辅助 (ORG-UI-1)", () => {
  const sample = [
    { organizationId: "eng", organizationName: "Engineering", parentOrganizationId: "acme" },
    { organizationId: "acme", organizationName: "Acme", parentOrganizationId: null },
    { organizationId: "fe", organizationName: "Frontend", parentOrganizationId: "eng" },
    { organizationId: "be", organizationName: "Backend", parentOrganizationId: "eng" },
    { organizationId: "orphan", organizationName: "Orphan", parentOrganizationId: "missing" },
  ];

  it("builds forest with CONTAINS parents; unknown parent → root", () => {
    const forest = buildOrgForest(sample);
    expect(forest.map((n) => n.organizationId).sort()).toEqual(["acme", "orphan"]);
    const acme = findOrgNode(forest, "acme")!;
    expect(acme.children.map((c) => c.organizationId)).toEqual(["eng"]);
    const eng = findOrgNode(forest, "eng")!;
    expect(eng.children.map((c) => c.organizationId)).toEqual(["be", "fe"]);
  });

  it("sorts siblings by name", () => {
    const forest = buildOrgForest(sample);
    const eng = findOrgNode(forest, "eng")!;
    expect(eng.children.map((c) => c.organizationName)).toEqual(["Backend", "Frontend"]);
  });

  it("breaks simple cycles into roots", () => {
    const forest = buildOrgForest([
      { organizationId: "a", organizationName: "A", parentOrganizationId: "b" },
      { organizationId: "b", organizationName: "B", parentOrganizationId: "a" },
    ]);
    expect(flattenOrgForest(forest).map((n) => n.organizationId).sort()).toEqual(["a", "b"]);
    // at least one is root; no infinite children
    expect(forest.length).toBeGreaterThanOrEqual(1);
  });

  it("ancestor path and search expand", () => {
    const forest = buildOrgForest(sample);
    expect(ancestorIdsOf(forest, "fe")).toEqual(["acme", "eng"]);
    expect([...searchMatchingIds(forest, "front")]).toEqual(["fe"]);
    expect([...expandIdsForSearch(forest, "front")].sort()).toEqual(["acme", "eng"]);
    expect(expandIdsForSearch(forest, "").size).toBe(0);
  });

  it("defaultExpandedIds covers parents with children", () => {
    const forest = buildOrgForest(sample);
    const expanded = defaultExpandedIds(forest);
    expect(expanded.has("acme")).toBe(true);
    expect(expanded.has("eng")).toBe(true);
    expect(expanded.has("fe")).toBe(false);
  });

  it("skips blank ids", () => {
    expect(buildOrgForest([{ organizationId: "  ", organizationName: "x" }])).toEqual([]);
  });

  it("client cycle guard for drop onto descendant", () => {
    const forest = buildOrgForest(sample);
    expect(wouldClientMoveCycle(forest, "eng", "fe")).toBe(true);
    expect(wouldClientMoveCycle(forest, "fe", "acme")).toBe(false);
    expect(wouldClientMoveCycle(forest, "fe", null)).toBe(false);
    expect(wouldClientMoveCycle(forest, "eng", "eng")).toBe(true);
  });
});
