import { describe, expect, it } from "vitest";
import { endUserStatePhraseKey, endUsersConsoleQuery } from "@/lib/end-user-directory";

describe("end-user directory helpers", () => {
  it("maps known store states and leaves unknown ones unmapped — 映射库里已有状态，未知则原样留给页面", () => {
    expect(endUserStatePhraseKey("ACTIVE")).toBe("endUsersStateActive");
    expect(endUserStatePhraseKey("revoked")).toBe("endUsersStateRevoked");
    expect(endUserStatePhraseKey("last-login")).toBeUndefined();
    expect(endUserStatePhraseKey("")).toBeUndefined();
  });

  it("builds a tenant lookup path without inventing filters — 只带上租户和查找词", () => {
    expect(endUsersConsoleQuery(" ", "")).toBe("/end-users");
    expect(endUsersConsoleQuery("tenant-a", "  Pat  ")).toBe("/end-users?tenantId=tenant-a&q=Pat");
  });
});
