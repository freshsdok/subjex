/**
 * Org CSV export/import helpers (Should S4).
 * 组织 CSV 导出/导入辅助（Should S4）。
 */

export type OrgCsvRow = {
  organizationId: string;
  organizationName: string;
  parentOrganizationId: string;
  organizationState: string;
};

const HEADER = "organizationId,organizationName,parentOrganizationId,organizationState";

function escapeCsv(value: string): string {
  if (/[",\n\r]/.test(value)) {
    return `"${value.replace(/"/g, '""')}"`;
  }
  return value;
}

export function organizationsToCsv(
  rows: Array<{
    organizationId?: string;
    organizationName?: string;
    parentOrganizationId?: string | null;
    organizationState?: string;
  }>,
): string {
  const lines = [HEADER];
  for (const row of rows) {
    const id = (row.organizationId ?? "").trim();
    if (!id) continue;
    lines.push(
      [
        escapeCsv(id),
        escapeCsv((row.organizationName ?? "").trim()),
        escapeCsv((row.parentOrganizationId ?? "").trim()),
        escapeCsv((row.organizationState ?? "ACTIVE").trim() || "ACTIVE"),
      ].join(","),
    );
  }
  return lines.join("\n") + "\n";
}

/** Minimal CSV parser for our 4-column export (handles quotes). */
export function parseOrgCsv(text: string): { rows: OrgCsvRow[]; errors: string[] } {
  const errors: string[] = [];
  const rows: OrgCsvRow[] = [];
  const lines = text.replace(/^\uFEFF/, "").split(/\r?\n/).filter((l) => l.trim());
  if (lines.length === 0) {
    return { rows, errors: ["empty"] };
  }
  const header = lines[0].toLowerCase();
  if (!header.includes("organizationid")) {
    errors.push("missing organizationId header");
  }
  for (let i = 1; i < lines.length; i++) {
    const cols = splitCsvLine(lines[i]);
    if (cols.length < 4) {
      errors.push(`line ${i + 1}: expected 4 columns`);
      continue;
    }
    const organizationId = cols[0].trim();
    if (!organizationId) {
      errors.push(`line ${i + 1}: blank organizationId`);
      continue;
    }
    rows.push({
      organizationId,
      organizationName: cols[1].trim() || organizationId,
      parentOrganizationId: cols[2].trim(),
      organizationState: cols[3].trim() || "ACTIVE",
    });
  }
  return { rows, errors };
}

function splitCsvLine(line: string): string[] {
  const out: string[] = [];
  let cur = "";
  let inQuotes = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (inQuotes) {
      if (ch === '"') {
        if (line[i + 1] === '"') {
          cur += '"';
          i++;
        } else {
          inQuotes = false;
        }
      } else {
        cur += ch;
      }
    } else if (ch === '"') {
      inQuotes = true;
    } else if (ch === ",") {
      out.push(cur);
      cur = "";
    } else {
      cur += ch;
    }
  }
  out.push(cur);
  return out;
}

export type OrgCsvDryRunIssue = {
  organizationId: string;
  message: string;
};

/** Dry-run: detect blank ids, self-parent, unknown parent refs within file, cycles in file graph. */
export function dryRunOrgCsv(
  rows: OrgCsvRow[],
  existingIds: Set<string>,
): { ok: boolean; issues: OrgCsvDryRunIssue[]; createCount: number; updateCount: number } {
  const issues: OrgCsvDryRunIssue[] = [];
  const inFile = new Set(rows.map((r) => r.organizationId));
  let createCount = 0;
  let updateCount = 0;
  for (const row of rows) {
    if (row.parentOrganizationId === row.organizationId) {
      issues.push({ organizationId: row.organizationId, message: "self-parent" });
    }
    if (
      row.parentOrganizationId &&
      !inFile.has(row.parentOrganizationId) &&
      !existingIds.has(row.parentOrganizationId)
    ) {
      issues.push({
        organizationId: row.organizationId,
        message: `unknown parent ${row.parentOrganizationId}`,
      });
    }
    if (existingIds.has(row.organizationId)) updateCount++;
    else createCount++;
  }
  // simple cycle among file parents
  const parentOf = new Map(rows.map((r) => [r.organizationId, r.parentOrganizationId || ""]));
  for (const id of inFile) {
    const seen = new Set<string>();
    let walk: string | null = id;
    while (walk) {
      if (seen.has(walk)) {
        issues.push({ organizationId: id, message: "cycle in CSV parents" });
        break;
      }
      seen.add(walk);
      const p = parentOf.get(walk) || "";
      walk = p || null;
      if (walk && !parentOf.has(walk) && !inFile.has(walk)) break;
    }
  }
  return { ok: issues.length === 0, issues, createCount, updateCount };
}
