import { afterEach, describe, expect, it, vi } from "vitest";
import {
  clearMemoryOperatorSessionsForTests,
  closeOperatorSession,
  findOperatorSession,
  openOperatorSession,
  sessionLifetimeSeconds,
} from "@/server/operator-session";

describe("operator session — 操作员会话", () => {
  afterEach(() => {
    vi.useRealTimers();
    clearMemoryOperatorSessionsForTests();
    delete process.env.SESSION_REDIS_URL;
    delete process.env.REDIS_URL;
  });

  it("finds an open session and forgets a closed one — 能找到、关后找不到", async () => {
    const sessionId = await openOperatorSession("platform-operator", "Basic abc");
    expect((await findOperatorSession(sessionId))?.loginName).toBe("platform-operator");
    await closeOperatorSession(sessionId);
    expect(await findOperatorSession(sessionId)).toBeUndefined();
  });

  it("expires after its lifetime — 到期失效", async () => {
    vi.useFakeTimers();
    const sessionId = await openOperatorSession("platform-operator", "Basic abc");
    vi.advanceTimersByTime(sessionLifetimeSeconds * 1000 + 1);
    expect(await findOperatorSession(sessionId)).toBeUndefined();
  });

  it("rejects unknown or missing ids — 未知会话号无效", async () => {
    expect(await findOperatorSession(undefined)).toBeUndefined();
    expect(await findOperatorSession("not-a-session")).toBeUndefined();
  });
});
