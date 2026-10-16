import { describe, expect, it } from "vitest";
import { buildOrgForest } from "@/lib/org-tree";
import { layoutMatrixEdges, layoutOrgCanvas } from "@/lib/org-canvas-layout";

describe("org-canvas-layout — 组织图画布布局 (ORG-UI-5)", () => {
  it("layouts parent above children with edges", () => {
    const forest = buildOrgForest([
      { organizationId: "acme", organizationName: "Acme", parentOrganizationId: null },
      { organizationId: "eng", organizationName: "Eng", parentOrganizationId: "acme" },
      { organizationId: "hr", organizationName: "HR", parentOrganizationId: "acme" },
    ]);
    const layout = layoutOrgCanvas(forest, {
      nodeWidth: 100,
      nodeHeight: 40,
      hGap: 20,
      vGap: 30,
      padding: 10,
    });
    expect(layout.boxes).toHaveLength(3);
    expect(layout.edges).toHaveLength(2);
    const acme = layout.boxes.find((b) => b.organizationId === "acme")!;
    const eng = layout.boxes.find((b) => b.organizationId === "eng")!;
    expect(acme.depth).toBe(0);
    expect(eng.depth).toBe(1);
    expect(eng.y).toBeGreaterThan(acme.y);
    expect(layout.width).toBeGreaterThan(0);
    expect(layout.height).toBeGreaterThan(0);
  });

  it("empty forest has padded size only", () => {
    const layout = layoutOrgCanvas([], { padding: 8 });
    expect(layout.boxes).toEqual([]);
    expect(layout.edges).toEqual([]);
    expect(layout.width).toBe(16);
  });
});

describe("layoutMatrixEdges", () => {
  it("projects MATRIX_OF onto box centers and skips missing nodes", () => {
    const forest = buildOrgForest([
      { organizationId: "root", organizationName: "Root", parentOrganizationId: null },
      { organizationId: "eng", organizationName: "Eng", parentOrganizationId: "root" },
      { organizationId: "sales", organizationName: "Sales", parentOrganizationId: "root" },
    ]);
    const layout = layoutOrgCanvas(forest);
    const edges = layoutMatrixEdges(layout, [
      { fromId: "eng", toId: "sales" },
      { fromId: "eng", toId: "missing" },
    ]);
    expect(edges).toHaveLength(1);
    expect(edges[0]!.fromId).toBe("eng");
    expect(edges[0]!.toId).toBe("sales");
    expect(edges[0]!.x1).not.toBe(edges[0]!.x2);
  });
});
