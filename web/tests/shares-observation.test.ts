import { describe, expect, it } from "vitest";
import { hasRecordedEvents, sharesConsoleQuery } from "@/lib/shares-observation";

describe("shares observation helpers", () => {
  it("builds a tenant period path and treats only real counts as present — 只把真实次数当作已有记录", () => {
    expect(sharesConsoleQuery(" ", "")).toBe("/shares");
    expect(sharesConsoleQuery("tenant-a", "2026-11-01T00:00:00.000Z", "2026-11-23T00:00:00.000Z")).toBe(
      "/shares?tenantId=tenant-a&from=2026-11-01T00%3A00%3A00.000Z&to=2026-11-23T00%3A00%3A00.000Z",
    );
    expect(hasRecordedEvents({ shares: 0, opens: 0 })).toBe(false);
    expect(hasRecordedEvents({ shares: 1, opens: 0 })).toBe(true);
  });
});
