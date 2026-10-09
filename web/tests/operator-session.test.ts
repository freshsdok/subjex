import { afterEach, describe, expect, it, vi } from "vitest";

/**
 * operator-session Vitest — purpose: console session cookie helpers for login/sign-out path.
 * Gates: missing/invalid cookie → unauthenticated (fail-closed); parse round-trip.
 * <p>
 * 目的：登录/退出会话 cookie 辅助。门禁：缺失/非法 cookie → 未认证（失败关闭）。
 */

import {
  clearMemoryOperatorSessionsForTests,
  closeOperatorSession,
  decryptSessionSecret,
  encryptSessionSecret,
  findOperatorSession,
  memorySessionIsEncryptedAtRestForTests,
  openOperatorSession,
  resolveOperatorSessionEncryptionKey,
  sessionLifetimeSeconds,
  updateOperatorSessionTokens,
} from "@/server/operator-session";

function sampleTokens(suffix = "a") {
  return {
    loginName: "platform-operator",
    accessToken: `access-token-${suffix}`,
    refreshToken: `refresh-token-${suffix}`,
    accessExpiresAtMillis: Date.now() + 30 * 60 * 1000,
  };
}

describe("operator session — 操作员会话", () => {
  afterEach(() => {
    vi.useRealTimers();
    clearMemoryOperatorSessionsForTests();
    delete process.env.SESSION_REDIS_URL;
    delete process.env.REDIS_URL;
    delete process.env.OPERATOR_SESSION_SECRET;
  });

  it("finds an open session and forgets a closed one — 能找到、关后找不到", async () => {
    const sessionId = await openOperatorSession(sampleTokens());
    expect((await findOperatorSession(sessionId))?.loginName).toBe("platform-operator");
    expect((await findOperatorSession(sessionId))?.accessToken).toBe("access-token-a");
    expect((await findOperatorSession(sessionId))?.refreshToken).toBe("refresh-token-a");
    await closeOperatorSession(sessionId);
    expect(await findOperatorSession(sessionId)).toBeUndefined();
  });

  it("never keeps plaintext tokens or Basic in the memory Map — 进程内存不存明文令牌/Basic", async () => {
    const sessionId = await openOperatorSession(sampleTokens());
    expect(memorySessionIsEncryptedAtRestForTests(sessionId)).toBe(true);
    expect((await findOperatorSession(sessionId))?.accessToken).toBe("access-token-a");
    expect(memorySessionIsEncryptedAtRestForTests(sessionId)).toBe(true);
  });

  it("updates rotated tokens in place — 轮换后就地更新令牌", async () => {
    const sessionId = await openOperatorSession(sampleTokens("old"));
    await updateOperatorSessionTokens(sessionId, {
      accessToken: "access-token-new",
      refreshToken: "refresh-token-new",
      accessExpiresAtMillis: Date.now() + 60_000,
    });
    const session = await findOperatorSession(sessionId);
    expect(session?.accessToken).toBe("access-token-new");
    expect(session?.refreshToken).toBe("refresh-token-new");
    expect(memorySessionIsEncryptedAtRestForTests(sessionId)).toBe(true);
  });

  it("expires after its lifetime — 到期失效", async () => {
    vi.useFakeTimers();
    const sessionId = await openOperatorSession(sampleTokens());
    vi.advanceTimersByTime(sessionLifetimeSeconds * 1000 + 1);
    expect(await findOperatorSession(sessionId)).toBeUndefined();
  });

  it("rejects unknown or missing ids — 未知会话号无效", async () => {
    expect(await findOperatorSession(undefined)).toBeUndefined();
    expect(await findOperatorSession("not-a-session")).toBeUndefined();
  });

  it("encrypts and decrypts tokens with AES-256-GCM — 令牌加解密往返", () => {
    process.env.OPERATOR_SESSION_SECRET = "a".repeat(32);
    const key = resolveOperatorSessionEncryptionKey();
    const blob = encryptSessionSecret("access-token-xyz", key);
    expect(blob.startsWith("v1:")).toBe(true);
    expect(blob).not.toContain("access-token");
    expect(decryptSessionSecret(blob, key)).toBe("access-token-xyz");
    expect(encryptSessionSecret("access-token-xyz", key)).not.toBe(blob);
  });

  it("rejects a short or missing OPERATOR_SESSION_SECRET — 密钥过短或缺失时报错", () => {
    delete process.env.OPERATOR_SESSION_SECRET;
    expect(() => resolveOperatorSessionEncryptionKey()).toThrow(/OPERATOR_SESSION_SECRET/);
    process.env.OPERATOR_SESSION_SECRET = "too-short";
    expect(() => resolveOperatorSessionEncryptionKey()).toThrow(/at least 32/);
  });

  it("refuses Redis session write when secret is missing — Redis 已配但无密钥则失败", async () => {
    process.env.SESSION_REDIS_URL = "redis://127.0.0.1:16379";
    delete process.env.OPERATOR_SESSION_SECRET;
    await expect(openOperatorSession(sampleTokens())).rejects.toThrow(/OPERATOR_SESSION_SECRET/);
  });

  it("refuses Redis session read when secret is missing — 读会话同样要求密钥", async () => {
    process.env.REDIS_URL = "redis://127.0.0.1:16379";
    delete process.env.OPERATOR_SESSION_SECRET;
    await expect(findOperatorSession("any-id")).rejects.toThrow(/OPERATOR_SESSION_SECRET/);
  });
});
