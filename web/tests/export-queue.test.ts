import { describe, expect, it } from "vitest";
import {
  canDecideExport,
  canGenerateExport,
  canIssueExportDownload,
  exportQueueErrorPhraseKey,
  filterExportQueueRows,
  isOwnExportRequest,
  resolveIssuedDownloadHref,
  sortExportQueueRows,
} from "@/lib/export-queue";

describe("export queue helpers", () => {
  const rows = [
    { requestId: "r1", status: "READY", createdAt: "2026-11-22T08:00:00Z" },
    { requestId: "r2", status: "REQUESTED", createdAt: "2026-11-22T10:00:00Z" },
    { requestId: "r3", status: "REQUESTED", createdAt: "2026-11-22T09:00:00Z" },
    { requestId: "r4", status: "EXPIRED", createdAt: "2026-11-21T08:00:00Z" },
  ];

  it("filters by status and keeps all rows for blank or unknown filters", () => {
    expect(filterExportQueueRows(rows, "REQUESTED").map((row) => row.requestId)).toEqual(["r2", "r3"]);
    expect(filterExportQueueRows(rows, "all")).toHaveLength(4);
    expect(filterExportQueueRows(rows, "")).toHaveLength(4);
    expect(filterExportQueueRows(rows, "nope")).toHaveLength(4);
  });

  it("sorts pending first, then approved, ready, rejected, expired; newest created first", () => {
    expect(sortExportQueueRows(rows).map((row) => row.requestId)).toEqual(["r2", "r3", "r1", "r4"]);
  });

  it("hides decide actions for the requester and for non-pending rows", () => {
    expect(isOwnExportRequest("sub-op", "sub-op")).toBe(true);
    expect(canDecideExport("REQUESTED", "sub-other", "sub-op", true)).toBe(true);
    expect(canDecideExport("REQUESTED", "sub-op", "sub-op", true)).toBe(false);
    expect(canDecideExport("APPROVED", "sub-other", "sub-op", true)).toBe(false);
    expect(canDecideExport("REQUESTED", "sub-other", "sub-op", false)).toBe(false);
  });

  it("allows generate only when approved, and download only when ready", () => {
    expect(canGenerateExport("APPROVED", true)).toBe(true);
    expect(canGenerateExport("REQUESTED", true)).toBe(false);
    expect(canGenerateExport("READY", true)).toBe(false);
    expect(canIssueExportDownload("READY", true)).toBe(true);
    expect(canIssueExportDownload("APPROVED", true)).toBe(false);
    expect(canIssueExportDownload("EXPIRED", true)).toBe(false);
    expect(canIssueExportDownload("READY", false)).toBe(false);
  });

  it("reuses the issued URL; maps local redeem paths through the console proxy", () => {
    expect(resolveIssuedDownloadHref("https://files.example/signed")).toBe("https://files.example/signed");
    expect(
      resolveIssuedDownloadHref("/api/v1/end-user/objects/content?tenantId=t&objectKey=k&expires=1&sig=s"),
    ).toBe("/api/platform/end-user/objects/content?tenantId=t&objectKey=k&expires=1&sig=s");
    expect(resolveIssuedDownloadHref("")).toBe("");
  });

  it("maps stable failure reasons to phrase keys", () => {
    expect(exportQueueErrorPhraseKey("export-self-approval-forbidden")).toBe("exportsSelfApprovalForbidden");
    expect(exportQueueErrorPhraseKey("export-expired")).toBe("exportsExpiredForbidden");
    expect(exportQueueErrorPhraseKey("export-not-approved")).toBe("exportsNotApprovedForbidden");
    expect(exportQueueErrorPhraseKey("export-artifact-unavailable")).toBe("exportsArtifactUnavailable");
    expect(exportQueueErrorPhraseKey("unexpected")).toBe("exportsActionFailed");
  });
});
