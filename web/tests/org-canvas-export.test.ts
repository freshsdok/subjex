import { describe, expect, it } from "vitest";
import { bakeOrgSvgColors, wrapOrgSvgXml } from "@/lib/org-canvas-export";

describe("org-canvas-export — 组织图导出 (S3)", () => {
  it("bakes CSS variables and wraps xml", () => {
    const raw = `<svg width="100" height="80"><rect fill="var(--foreground)" stroke="currentColor" stroke-width="2"/><text fill="currentColor">x</text><line stroke="currentColor"/></svg>`;
    const baked = bakeOrgSvgColors(raw);
    expect(baked).toContain("#111827");
    expect(baked).not.toContain("var(--foreground)");
    expect(baked).toContain("#6b7280");
    const wrapped = wrapOrgSvgXml(baked);
    expect(wrapped.startsWith("<?xml")).toBe(true);
    expect(wrapped).toContain("xmlns");
  });
});
