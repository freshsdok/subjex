import { describe, expect, it } from "vitest";
import { circuitStatePhraseKey, rateLimitBackendPhraseKey } from "@/lib/rate-limits-observation";

describe("rate-limits observation helpers", () => {
  it("maps known backends and breaker states only — 只映射已知存储方式与开合状态", () => {
    expect(rateLimitBackendPhraseKey("process")).toBe("rateLimitsBackendProcess");
    expect(rateLimitBackendPhraseKey("JDBC")).toBe("rateLimitsBackendJdbc");
    expect(rateLimitBackendPhraseKey("prometheus")).toBe("rateLimitsBackendUnknown");
    expect(circuitStatePhraseKey("half-open")).toBe("rateLimitsCircuitHalfOpen");
    expect(circuitStatePhraseKey("closed")).toBe("rateLimitsCircuitClosed");
    expect(circuitStatePhraseKey("tripped")).toBeUndefined();
  });
});
