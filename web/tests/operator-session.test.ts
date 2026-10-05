import { afterEach, describe, expect, it, vi } from "vitest";
import { closeOperatorSession, findOperatorSession, openOperatorSession, sessionLifetimeSeconds } from "@/server/operator-session";

describe("operator session — 操作员会话", () => {
  afterEach(() => vi.useRealTimers());

  it("finds an open session and forgets a closed one — 能找到、关后找不到", () => {
    const sessionId = openOperatorSession("platform-operator", "Basic abc");
    expect(findOperatorSession(sessionId)?.loginName).toBe("platform-operator");
    closeOperatorSession(sessionId);
    expect(findOperatorSession(sessionId)).toBeUndefined();
  });

  it("expires after its lifetime — 到期失效", () => {
    vi.useFakeTimers();
    const sessionId = openOperatorSession("platform-operator", "Basic abc");
    vi.advanceTimersByTime(sessionLifetimeSeconds * 1000 + 1);
    expect(findOperatorSession(sessionId)).toBeUndefined();
  });

  it("rejects unknown or missing ids — 未知会话号无效", () => {
    expect(findOperatorSession(undefined)).toBeUndefined();
    expect(findOperatorSession("not-a-session")).toBeUndefined();
  });
});
