import { describe, expect, it } from "vitest";
import { flagIsEnabled, parseFlagEnabled } from "@/lib/feature-flags";

describe("feature-flags client helper", () => {
  it("parses common tokens", () => {
    expect(parseFlagEnabled("true")).toBe(true);
    expect(parseFlagEnabled("OFF")).toBe(false);
    expect(parseFlagEnabled("maybe")).toBeNull();
  });

  it("flagIsEnabled prefers enabled then value then default", () => {
    expect(flagIsEnabled({ enabled: false, value: "true" }, true)).toBe(false);
    expect(flagIsEnabled({ value: "yes" }, false)).toBe(true);
    expect(flagIsEnabled({}, true)).toBe(true);
  });
});
