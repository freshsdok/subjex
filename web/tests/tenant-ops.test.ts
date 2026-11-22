import { describe, expect, it } from "vitest";
import {
  buildEraseConfirmBody,
  buildQuotaOverrideBody,
  canStartHardErase,
  eraseIdConfirmationMatches,
  parseQuotaLimitInput,
  tenantZipFilename,
} from "@/lib/tenant-ops";

describe("tenant-ops — 租户用量与硬删除辅助", () => {
  it("allows hard erase only for a disabled business tenant — 仅已禁用的业务租户可硬删除", () => {
    expect(canStartHardErase("tenant-north", "SUSPENDED")).toBe(true);
    expect(canStartHardErase("tenant-north", "ACTIVE")).toBe(false);
    expect(canStartHardErase("platform", "SUSPENDED")).toBe(false);
  });

  it("requires an exact typed tenant id — 确认输入须与租户 ID 完全一致", () => {
    expect(eraseIdConfirmationMatches("tenant-north", "tenant-north")).toBe(true);
    expect(eraseIdConfirmationMatches("  tenant-north  ", "tenant-north")).toBe(true);
    expect(eraseIdConfirmationMatches("tenant-south", "tenant-north")).toBe(false);
    expect(eraseIdConfirmationMatches("", "tenant-north")).toBe(false);
  });

  it("parses quota caps and builds the PUT body — 解析上限并生成写入体", () => {
    expect(parseQuotaLimitInput("")).toEqual({ ok: true, value: null });
    expect(parseQuotaLimitInput("  ")).toEqual({ ok: true, value: null });
    expect(parseQuotaLimitInput("100")).toEqual({ ok: true, value: 100 });
    expect(parseQuotaLimitInput("0")).toEqual({ ok: false });
    expect(parseQuotaLimitInput("-1")).toEqual({ ok: false });
    expect(parseQuotaLimitInput("1.5")).toEqual({ ok: false });
    expect(buildQuotaOverrideBody(null, 200)).toEqual({ dailySubmitLimit: null, storageRowLimit: 200 });
    expect(buildEraseConfirmBody(" ch-1 ", " tok-9 ")).toEqual({ challengeId: "ch-1", token: "tok-9" });
    expect(tenantZipFilename("tenant-north")).toBe("tenant-tenant-north-export.zip");
    expect(tenantZipFilename("acme/east")).toBe("tenant-acme_east-export.zip");
  });
});
