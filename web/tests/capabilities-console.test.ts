import { describe, expect, it } from "vitest";
import {
  capabilityRunPath,
  capabilitySummary,
  capabilityTitle,
  capabilityWriteBackPath,
  capabilityWriteTicketPath,
  isKnownCapabilityKind,
  type CapabilityRow,
} from "@/lib/capabilities-console";

const sample: CapabilityRow = {
  id: "algo.hashFingerprint",
  kind: "ALGORITHM",
  titleEn: "Hash fingerprint",
  titleZh: "哈希指纹",
  summaryEn: "SHA-256 of inputText",
  summaryZh: "对 inputText 做 SHA-256",
};

describe("capabilities-console — 能力控制台辅助", () => {
  it("picks title and summary by language", () => {
    expect(capabilityTitle(sample, "zh")).toBe("哈希指纹");
    expect(capabilityTitle(sample, "en")).toBe("Hash fingerprint");
    expect(capabilitySummary(sample, "zh")).toBe("对 inputText 做 SHA-256");
    expect(capabilitySummary(sample, "en")).toBe("SHA-256 of inputText");
  });

  it("builds encoded run path", () => {
    expect(capabilityRunPath("algo.hashFingerprint")).toBe(
      "/api/platform/capabilities/algo.hashFingerprint/run",
    );
    expect(capabilityRunPath("ai/weird")).toBe("/api/platform/capabilities/ai%2Fweird/run");
    expect(capabilityWriteTicketPath("ai.summarizePreview")).toBe(
      "/api/platform/capabilities/ai.summarizePreview/write-ticket",
    );
    expect(capabilityWriteBackPath("ai.summarizePreview")).toBe(
      "/api/platform/capabilities/ai.summarizePreview/write-back",
    );
  });

  it("recognizes known kinds", () => {
    expect(isKnownCapabilityKind("ALGORITHM")).toBe(true);
    expect(isKnownCapabilityKind("AI")).toBe(true);
    expect(isKnownCapabilityKind("OTHER")).toBe(false);
  });
});
