import { createCipheriv, createDecipheriv, createHash, randomBytes } from "node:crypto";
import { createClient, type RedisClientType } from "redis";

// Operator session — 操作员会话：浏览器只持有随机会话号（httpOnly cookie），
// 真正的认证头只留在 Next.js 服务端（Redis 或多副本共享；未配 Redis 时退回进程内存）。
export interface OperatorSession {
  loginName: string;
  credentialHeader: string;
  expiresAtMillis: number;
}

/** Shape stored in Redis: credential header is AES-256-GCM ciphertext, not plaintext Basic. */
interface RedisStoredOperatorSession {
  loginName: string;
  credentialHeaderEnc: string;
  expiresAtMillis: number;
}

// Cookie name and lifetime — cookie 名称与有效期（8 小时，过期后需重新登录）。
export const sessionCookieName = "subjex_session";
export const sessionLifetimeSeconds = 8 * 60 * 60;

const sessionKeyPrefix = "subjex:operator-session:";
const MIN_OPERATOR_SESSION_SECRET_LENGTH = 32;
const CREDENTIAL_ENC_PREFIX = "v1:";

// Keep the memory store on globalThis so dev hot reload does not drop sessions —
// 挂在 globalThis 上，开发热重载时内存会话不丢（仅无 Redis 时使用）。
const sessionHolder = globalThis as typeof globalThis & {
  subjexOperatorSessions?: Map<string, OperatorSession>;
  subjexSessionRedis?: RedisClientType;
  subjexSessionRedisUrl?: string;
};
const memorySessions: Map<string, OperatorSession> =
  (sessionHolder.subjexOperatorSessions ??= new Map());

function sessionRedisUrl(): string | undefined {
  const fromSession = process.env.SESSION_REDIS_URL?.trim();
  if (fromSession) return fromSession;
  const fromRedis = process.env.REDIS_URL?.trim();
  return fromRedis || undefined;
}

/**
 * AES-256 key derived from OPERATOR_SESSION_SECRET (required when Redis sessions are used).
 * 多副本 / Redis 会话时必须配置 OPERATOR_SESSION_SECRET（至少 32 字符），口令头加密后写入 Redis。
 */
export function resolveOperatorSessionEncryptionKey(): Buffer {
  const secret = process.env.OPERATOR_SESSION_SECRET?.trim();
  if (!secret || secret.length < MIN_OPERATOR_SESSION_SECRET_LENGTH) {
    throw new Error(
      "OPERATOR_SESSION_SECRET must be set to at least 32 characters when SESSION_REDIS_URL (or REDIS_URL) is configured",
    );
  }
  return createHash("sha256").update(secret, "utf8").digest();
}

/** Encrypt credential header for Redis at-rest storage (AES-256-GCM). */
export function encryptCredentialHeader(plaintext: string, key: Buffer): string {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key, iv);
  const encrypted = Buffer.concat([cipher.update(plaintext, "utf8"), cipher.final()]);
  const tag = cipher.getAuthTag();
  return CREDENTIAL_ENC_PREFIX + Buffer.concat([iv, tag, encrypted]).toString("base64url");
}

/** Decrypt credential header previously written by encryptCredentialHeader. */
export function decryptCredentialHeader(blob: string, key: Buffer): string {
  if (!blob.startsWith(CREDENTIAL_ENC_PREFIX)) {
    throw new Error("unsupported credential encryption version");
  }
  const raw = Buffer.from(blob.slice(CREDENTIAL_ENC_PREFIX.length), "base64url");
  if (raw.length < 12 + 16) {
    throw new Error("credential ciphertext too short");
  }
  const iv = raw.subarray(0, 12);
  const tag = raw.subarray(12, 28);
  const data = raw.subarray(28);
  const decipher = createDecipheriv("aes-256-gcm", key, iv);
  decipher.setAuthTag(tag);
  return Buffer.concat([decipher.update(data), decipher.final()]).toString("utf8");
}

async function redisClient(): Promise<RedisClientType | undefined> {
  const url = sessionRedisUrl();
  if (!url) return undefined;
  if (sessionHolder.subjexSessionRedis && sessionHolder.subjexSessionRedisUrl === url) {
    return sessionHolder.subjexSessionRedis;
  }
  const client = createClient({ url }) as RedisClientType;
  client.on("error", (error) => {
    console.error("operator-session redis error", error);
  });
  await client.connect();
  sessionHolder.subjexSessionRedis = client;
  sessionHolder.subjexSessionRedisUrl = url;
  return client;
}

export async function openOperatorSession(loginName: string, credentialHeader: string): Promise<string> {
  const sessionId = randomBytes(32).toString("base64url");
  const expiresAtMillis = Date.now() + sessionLifetimeSeconds * 1000;
  const redisUrl = sessionRedisUrl();
  if (redisUrl) {
    // Require secret before connecting so misconfig fails fast without touching Redis.
    const key = resolveOperatorSessionEncryptionKey();
    const stored: RedisStoredOperatorSession = {
      loginName,
      credentialHeaderEnc: encryptCredentialHeader(credentialHeader, key),
      expiresAtMillis,
    };
    const redis = await redisClient();
    if (!redis) {
      throw new Error("SESSION_REDIS_URL is set but Redis client failed to initialize");
    }
    await redis.set(sessionKeyPrefix + sessionId, JSON.stringify(stored), {
      EX: sessionLifetimeSeconds,
    });
    return sessionId;
  }
  memorySessions.set(sessionId, {
    loginName,
    credentialHeader,
    expiresAtMillis,
  });
  return sessionId;
}

export async function findOperatorSession(
  sessionId: string | undefined,
): Promise<OperatorSession | undefined> {
  if (!sessionId) return undefined;
  const redisUrl = sessionRedisUrl();
  if (redisUrl) {
    const key = resolveOperatorSessionEncryptionKey();
    const redis = await redisClient();
    if (!redis) return undefined;
    const raw = await redis.get(sessionKeyPrefix + sessionId);
    if (!raw) return undefined;
    const stored = JSON.parse(raw) as RedisStoredOperatorSession;
    if (stored.expiresAtMillis <= Date.now()) {
      await redis.del(sessionKeyPrefix + sessionId);
      return undefined;
    }
    return {
      loginName: stored.loginName,
      credentialHeader: decryptCredentialHeader(stored.credentialHeaderEnc, key),
      expiresAtMillis: stored.expiresAtMillis,
    };
  }
  const session = memorySessions.get(sessionId);
  if (!session) return undefined;
  if (session.expiresAtMillis <= Date.now()) {
    memorySessions.delete(sessionId);
    return undefined;
  }
  return session;
}

export async function closeOperatorSession(sessionId: string | undefined): Promise<void> {
  if (!sessionId) return;
  const redis = await redisClient();
  if (redis) {
    await redis.del(sessionKeyPrefix + sessionId);
    return;
  }
  memorySessions.delete(sessionId);
}

/** Test helper: wipe memory store (Redis tests use a real/fake URL separately). */
export function clearMemoryOperatorSessionsForTests(): void {
  memorySessions.clear();
}
