import { describe, expect, it } from "vitest";
import {
  formatBytes,
  formatCount,
  formatDurationSeconds,
  formatPercent,
  healthPhraseKey,
} from "@/lib/load-dashboard";

describe("load-dashboard formatters — 负载页数字格式", () => {
  it("formats bytes, percents, durations, and counts — 格式化字节、百分比、时长和计数", () => {
    expect(formatBytes(512, "zh")).toBe("512 B");
    expect(formatBytes(1536, "en")).toBe("1.5 KiB");
    expect(formatBytes(5 * 1024 * 1024, "en")).toBe("5 MiB");
    expect(formatBytes(null, "zh")).toBeNull();
    expect(formatPercent(0.256, "en")).toBe("25.6%");
    expect(formatDurationSeconds(90, "zh")).toBe("1 分钟 30 秒");
    expect(formatDurationSeconds(3661, "en")).toBe("1h 1m");
    expect(formatDurationSeconds(86400 + 3600, "zh")).toBe("1 天 1 小时");
    expect(formatCount(1200, "en")).toBe("1,200");
  });

  it("maps known health states and leaves others raw — 已知健康状态走文案键", () => {
    expect(healthPhraseKey("CORRECT")).toBe("loadLivenessUp");
    expect(healthPhraseKey("ACCEPTING_TRAFFIC")).toBe("loadReadinessUp");
    expect(healthPhraseKey("BROKEN")).toBe("loadHealthDown");
    expect(healthPhraseKey("REFUSING_TRAFFIC")).toBe("loadHealthDown");
    expect(healthPhraseKey("SOMETHING_ELSE")).toBeNull();
  });
});
