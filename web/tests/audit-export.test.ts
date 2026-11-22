import { describe, expect, it } from "vitest";
import {
  AUDIT_EXPORT_DEFAULT_LIMIT,
  AUDIT_EXPORT_MAX_LIMIT,
  auditExportFilename,
  auditExportWindowError,
  buildAuditExportPath,
  clampAuditExportLimit,
  localDateTimeToIso,
} from "@/lib/audit-export";
import { filenameFromContentDisposition } from "@/lib/download-blob";

describe("audit-export — 审计导出辅助", () => {
  it("accepts a 31-day window and rejects a longer one — 刚好 31 天可通过，更长则拒绝", () => {
    const start = "2026-11-01T00:00:00.000Z";
    const exactEnd = "2026-12-02T00:00:00.000Z";
    const tooLong = "2026-12-02T00:00:00.001Z";
    expect(auditExportWindowError(start, exactEnd)).toBeNull();
    expect(auditExportWindowError(start, tooLong)).toBe("tooLong");
    expect(auditExportWindowError("", exactEnd)).toBe("missing");
    expect(auditExportWindowError("nope", exactEnd)).toBe("invalid");
    expect(auditExportWindowError(exactEnd, start)).toBe("order");
  });

  it("builds the proxy path and clamps the row limit — 生成代理路径并钳制条数", () => {
    expect(clampAuditExportLimit("")).toBe(AUDIT_EXPORT_DEFAULT_LIMIT);
    expect(clampAuditExportLimit("0")).toBe(AUDIT_EXPORT_DEFAULT_LIMIT);
    expect(clampAuditExportLimit("200")).toBe(200);
    expect(clampAuditExportLimit("99999")).toBe(AUDIT_EXPORT_MAX_LIMIT);
    expect(
      buildAuditExportPath({
        tenantId: "tenant-north",
        fromIso: "2026-11-01T00:00:00.000Z",
        toIso: "2026-11-08T00:00:00.000Z",
        limit: 200,
      }),
    ).toBe(
      "/api/platform/audit/export?tenantId=tenant-north&from=2026-11-01T00%3A00%3A00.000Z&to=2026-11-08T00%3A00%3A00.000Z&limit=200",
    );
  });

  it("builds a safe filename and reads Content-Disposition — 生成安全文件名并解析响应头", () => {
    expect(auditExportFilename("tenant-north", "2026-11-01T00:00:00.000Z", "2026-11-08T12:00:00.000Z")).toBe(
      "audit-tenant-north-2026-11-01-to-2026-11-08.ndjson",
    );
    expect(auditExportFilename("acme/east", "2026-11-01T00:00:00.000Z", "2026-11-02T00:00:00.000Z")).toBe(
      "audit-acme_east-2026-11-01-to-2026-11-02.ndjson",
    );
    expect(filenameFromContentDisposition(null, "fallback.ndjson")).toBe("fallback.ndjson");
    expect(
      filenameFromContentDisposition(
        'attachment; filename="audit-tenant-north.ndjson"',
        "fallback.ndjson",
      ),
    ).toBe("audit-tenant-north.ndjson");
    expect(
      filenameFromContentDisposition(
        "attachment; filename*=UTF-8''audit-tenant-north.ndjson",
        "fallback.ndjson",
      ),
    ).toBe("audit-tenant-north.ndjson");
  });

  it("converts a datetime-local value to an ISO instant — 把本地时间框转成 ISO 时刻", () => {
    expect(localDateTimeToIso("")).toBeNull();
    const iso = localDateTimeToIso("2026-11-01T00:00");
    expect(iso).toMatch(/^2026-1[01]-\d{2}T/);
  });
});
