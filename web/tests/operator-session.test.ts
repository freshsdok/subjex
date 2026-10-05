import { afterEach, describe, expect, it, vi } from "vitest";
import {
  clearMemoryOperatorSessionsForTests,
  closeOperatorSession,
  decryptCredentialHeader,
  encryptCredentialHeader,
  findOperatorSession,
  memorySessionIsEncryptedAtRestForTests,
  openOperatorSession,
  resolveOperatorSessionEncryptionKey,
  sessionLifetimeSeconds,
} from "@/server/operator-session";

describe("operator session — 操作员会话", () => {
  afterEach(() => {
    vi.useRealTimers();
    clearMemoryOperatorSessionsForTests();
    delete process.env.SESSION_REDIS_URL;
    delete process.env.REDIS_URL;
    delete process.env.OPERATOR_SESSION_SECRET;
  });

  it("finds an open session and forgets a closed one — 能找到、关后找不到", async () => {
    const sessionId = await openOperatorSession("platform-operator", "Basic abc");
    expect((await findOperatorSession(sessionId))?.loginName).toBe("platform-operator");
    expect((await findOperatorSession(sessionId))?.credentialHeader).toBe("Basic abc");
    await closeOperatorSession(sessionId);
    expect(await findOperatorSession(sessionId)).toBeUndefined();
  });

  it("never keeps plaintext Basic in the memory Map — 进程内存 Map 不存明文 Basic", async () => {
    const sessionId = await openOperatorSession("platform-operator", "Basic dXNlcjpwYXNz");
    expect(memorySessionIsEncryptedAtRestForTests(sessionId)).toBe(true);
    // Decrypted form is only on the returned object for the request, not in the Map.
    expect((await findOperatorSession(sessionId))?.credentialHeader).toBe("Basic dXNlcjpwYXNz");
    expect(memorySessionIsEncryptedAtRestForTests(sessionId)).toBe(true);
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

  it("encrypts and decrypts credential headers with AES-256-GCM — 凭据加解密往返", () => {
    process.env.OPERATOR_SESSION_SECRET = "a".repeat(32);
    const key = resolveOperatorSessionEncryptionKey();
    const blob = encryptCredentialHeader("Basic dXNlcjpwYXNz", key);
    expect(blob.startsWith("v1:")).toBe(true);
    expect(blob).not.toContain("Basic");
    expect(decryptCredentialHeader(blob, key)).toBe("Basic dXNlcjpwYXNz");
    // Different IVs → different ciphertext for the same plaintext.
    expect(encryptCredentialHeader("Basic dXNlcjpwYXNz", key)).not.toBe(blob);
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
    await expect(openOperatorSession("platform-operator", "Basic abc")).rejects.toThrow(
      /OPERATOR_SESSION_SECRET/,
    );
  });

  it("refuses Redis session read when secret is missing — 读会话同样要求密钥", async () => {
    process.env.REDIS_URL = "redis://127.0.0.1:16379";
    delete process.env.OPERATOR_SESSION_SECRET;
    await expect(findOperatorSession("any-id")).rejects.toThrow(/OPERATOR_SESSION_SECRET/);
  });
});
