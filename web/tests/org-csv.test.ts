
import { describe, expect, it } from "vitest";
import { dryRunOrgCsv, organizationsToCsv, parseOrgCsv } from "@/lib/org-csv";

describe("org-csv — 组织 CSV (S4)", () => {
  it("round-trips export parse", () => {
    const csv = organizationsToCsv([
      { organizationId: "acme", organizationName: "Acme", parentOrganizationId: null, organizationState: "ACTIVE" },
      { organizationId: "eng", organizationName: "Eng, Inc", parentOrganizationId: "acme", organizationState: "ACTIVE" },
    ]);
    const { rows, errors } = parseOrgCsv(csv);
    expect(errors).toEqual([]);
    expect(rows).toHaveLength(2);
    expect(rows[1].organizationName).toBe("Eng, Inc");
    expect(rows[1].parentOrganizationId).toBe("acme");
  });

  it("dry-run flags unknown parent and self-parent", () => {
    const { ok, issues } = dryRunOrgCsv(
      [
        { organizationId: "a", organizationName: "A", parentOrganizationId: "missing", organizationState: "ACTIVE" },
        { organizationId: "b", organizationName: "B", parentOrganizationId: "b", organizationState: "ACTIVE" },
      ],
      new Set(),
    );
    expect(ok).toBe(false);
    expect(issues.some((i) => i.message.includes("unknown parent"))).toBe(true);
    expect(issues.some((i) => i.message === "self-parent")).toBe(true);
  });
});
