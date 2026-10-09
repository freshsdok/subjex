import { createCipheriv, createDecipheriv, createHash, randomBytes } from "node:crypto";
import { createClient, type RedisClientType } from "redis";

// Operator session — 操作员会话：浏览器只持有随机会话号（httpOnly cookie）。
// 服务端 Map / Redis 只存 AES-256-GCM 密文的 access/refresh 令牌，从不存 Basic/口令。
export interface OperatorSession {
  loginName: string;
  accessToken: string;
  refreshToken: string;
  accessExpiresAtMillis: number;
  expiresAtMillis: number;
}

/** Shape stored in Redis and process memory: tokens are ciphertext, never plaintext. */
interface StoredOperatorSession {
  loginName: string;
  accessTokenEnc: string;
  refreshTokenEnc: string;
  accessExpiresAtMillis: number;
  expiresAtMillis: number;
}

export interface OpenOperatorSessionInput {
  loginName: string;
  accessToken: string;
  refreshToken: string;
  accessExpiresAtMillis: number;
}

// Cookie name and lifetime — cookie 名称与有效期（8 小时，与刷新令牌默认 TTL 对齐）。
export const sessionCookieName = "subjex_session";
export const sessionLifetimeSeconds = 8 * 60 * 60;

const sessionKeyPrefix = "subjex:operator-session:";
const MIN_OPERATOR_SESSION_SECRET_LENGTH = 32;
const TOKEN_ENC_PREFIX = "v1:";

const sessionHolder = globalThis as typeof globalThis & {
  subjexOperatorSessions?: Map<string, StoredOperatorSession>;
  subjexSessionRedis?: RedisClientType;
  subjexSessionRedisUrl?: string;
  subjexEphemeralSessionKey?: Buffer;
};
const memorySessions: Map<string, StoredOperatorSession> =
  (sessionHolder.subjexOperatorSessions ??= new Map());

function sessionRedisUrl(): string | undefined {
  const fromSession = process.env.SESSION_REDIS_URL?.trim();
  if (fromSession) return fromSession;
  const fromRedis = process.env.REDIS_URL?.trim();
  return fromRedis || undefined;
}

/**
 * AES-256 key derived from OPERATOR_SESSION_SECRET (required when Redis sessions are used).
 * 多副本 / Redis 会话时必须配置 OPERATOR_SESSION_SECRET（至少 32 字符）。
 */
function refuseChangeMePlaceholder(secret: string, name: string): void {
  // Non-local / production must not use laptop placeholders (P3).
  // 非本地/生产禁止 change-me 占位（P3）。
  const nodeEnv = process.env.NODE_ENV?.trim();
  if (nodeEnv === "development" || process.env.SUBJEX_ALLOW_LOCAL_SECRETS === "true") {
    return;
  }
  const lower = secret.toLowerCase();
  if (lower.includes("change-me") || lower.includes("changeme")) {
    throw new Error(name + " must not use a change-me placeholder outside local/dev");
  }
}

export function resolveOperatorSessionEncryptionKey(): Buffer {
  const secret = process.env.OPERATOR_SESSION_SECRET?.trim();
  if (!secret || secret.length < MIN_OPERATOR_SESSION_SECRET_LENGTH) {
    throw new Error(
      "OPERATOR_SESSION_SECRET must be set to at least 32 characters when SESSION_REDIS_URL (or REDIS_URL) is configured",
    );
  }
  refuseChangeMePlaceholder(secret, "OPERATOR_SESSION_SECRET");
  return createHash("sha256").update(secret, "utf8").digest();
}

/** Optional previous key digest for rotation overlap decrypt / 轮换重叠期旧密钥摘要。 */
export function resolveOperatorSessionPreviousEncryptionKey(): Buffer | undefined {
  const secret = process.env.OPERATOR_SESSION_SECRET_PREVIOUS?.trim();
  if (!secret) {
    return undefined;
  }
  if (secret.length < MIN_OPERATOR_SESSION_SECRET_LENGTH) {
    throw new Error("OPERATOR_SESSION_SECRET_PREVIOUS must be at least 32 characters when set");
  }
  refuseChangeMePlaceholder(secret, "OPERATOR_SESSION_SECRET_PREVIOUS");
  return createHash("sha256").update(secret, "utf8").digest();
}

/**
 * Key for encrypting tokens at rest in the session store.
 * Redis path: OPERATOR_SESSION_SECRET required. Memory path: same secret if set, else a process-ephemeral key.
 * Redis：必填 OPERATOR_SESSION_SECRET。进程内存：有密钥则用；否则用进程内临时密钥。
 */
function resolveSessionStoreKey(redisConfigured: boolean): Buffer {
  if (redisConfigured) {
    return resolveOperatorSessionEncryptionKey();
  }
  const secret = process.env.OPERATOR_SESSION_SECRET?.trim();
  if (secret && secret.length >= MIN_OPERATOR_SESSION_SECRET_LENGTH) {
    refuseChangeMePlaceholder(secret, "OPERATOR_SESSION_SECRET");
    return createHash("sha256").update(secret, "utf8").digest();
  }
  if (!sessionHolder.subjexEphemeralSessionKey) {
    sessionHolder.subjexEphemeralSessionKey = randomBytes(32);
  }
  return sessionHolder.subjexEphemeralSessionKey;
}

/** Encrypt a token string for at-rest storage (AES-256-GCM). */
export function encryptSessionSecret(plaintext: string, key: Buffer): string {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key, iv);
  const encrypted = Buffer.concat([cipher.update(plaintext, "utf8"), cipher.final()]);
  const tag = cipher.getAuthTag();
  return TOKEN_ENC_PREFIX + Buffer.concat([iv, tag, encrypted]).toString("base64url");
}

function decryptWithKey(blob: string, key: Buffer): string {
  if (!blob.startsWith(TOKEN_ENC_PREFIX)) {
    throw new Error("unsupported token encryption version");
  }
  const raw = Buffer.from(blob.slice(TOKEN_ENC_PREFIX.length), "base64url");
  if (raw.length < 12 + 16) {
    throw new Error("token ciphertext too short");
  }
  const iv = raw.subarray(0, 12);
  const tag = raw.subarray(12, 28);
  const data = raw.subarray(28);
  const decipher = createDecipheriv("aes-256-gcm", key, iv);
  decipher.setAuthTag(tag);
  return Buffer.concat([decipher.update(data), decipher.final()]).toString("utf8");
}

/** Decrypt a token previously written by encryptSessionSecret (tries previous key on failure). */
export function decryptSessionSecret(blob: string, key: Buffer, previousKey?: Buffer): string {
  try {
    return decryptWithKey(blob, key);
  } catch (first) {
    if (!previousKey) {
      throw first;
    }
    return decryptWithKey(blob, previousKey);
  }
}

/** @deprecated Use encryptSessionSecret — kept name alias for clarity in older tests. */
export const encryptCredentialHeader = encryptSessionSecret;
/** @deprecated Use decryptSessionSecret. */
export const decryptCredentialHeader = decryptSessionSecret;

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

function toOperatorSession(stored: StoredOperatorSession, key: Buffer): OperatorSession | undefined {
  if (stored.expiresAtMillis <= Date.now()) {
    return undefined;
  }
  const previous = resolveOperatorSessionPreviousEncryptionKey();
  return {
    loginName: stored.loginName,
    accessToken: decryptSessionSecret(stored.accessTokenEnc, key, previous),
    refreshToken: decryptSessionSecret(stored.refreshTokenEnc, key, previous),
    accessExpiresAtMillis: stored.accessExpiresAtMillis,
    expiresAtMillis: stored.expiresAtMillis,
  };
}

function toStored(input: OpenOperatorSessionInput, expiresAtMillis: number, key: Buffer): StoredOperatorSession {
  return {
    loginName: input.loginName,
    accessTokenEnc: encryptSessionSecret(input.accessToken, key),
    refreshTokenEnc: encryptSessionSecret(input.refreshToken, key),
    accessExpiresAtMillis: input.accessExpiresAtMillis,
    expiresAtMillis,
  };
}

export async function openOperatorSession(input: OpenOperatorSessionInput): Promise<string> {
  const sessionId = randomBytes(32).toString("base64url");
  const expiresAtMillis = Date.now() + sessionLifetimeSeconds * 1000;
  const redisUrl = sessionRedisUrl();
  const key = resolveSessionStoreKey(!!redisUrl);
  const stored = toStored(input, expiresAtMillis, key);
  if (redisUrl) {
    const redis = await redisClient();
    if (!redis) {
      throw new Error("SESSION_REDIS_URL is set but Redis client failed to initialize");
    }
    await redis.set(sessionKeyPrefix + sessionId, JSON.stringify(stored), {
      EX: sessionLifetimeSeconds,
    });
    return sessionId;
  }
  memorySessions.set(sessionId, stored);
  return sessionId;
}

export async function updateOperatorSessionTokens(
  sessionId: string,
  tokens: { accessToken: string; refreshToken: string; accessExpiresAtMillis: number },
): Promise<void> {
  const redisUrl = sessionRedisUrl();
  const key = resolveSessionStoreKey(!!redisUrl);
  if (redisUrl) {
    const redis = await redisClient();
    if (!redis) return;
    const raw = await redis.get(sessionKeyPrefix + sessionId);
    if (!raw) return;
    const previous = JSON.parse(raw) as StoredOperatorSession;
    const stored = toStored(
      {
        loginName: previous.loginName,
        accessToken: tokens.accessToken,
        refreshToken: tokens.refreshToken,
        accessExpiresAtMillis: tokens.accessExpiresAtMillis,
      },
      previous.expiresAtMillis,
      key,
    );
    const ttlSeconds = Math.max(1, Math.ceil((previous.expiresAtMillis - Date.now()) / 1000));
    await redis.set(sessionKeyPrefix + sessionId, JSON.stringify(stored), { EX: ttlSeconds });
    return;
  }
  const previous = memorySessions.get(sessionId);
  if (!previous) return;
  memorySessions.set(
    sessionId,
    toStored(
      {
        loginName: previous.loginName,
        accessToken: tokens.accessToken,
        refreshToken: tokens.refreshToken,
        accessExpiresAtMillis: tokens.accessExpiresAtMillis,
      },
      previous.expiresAtMillis,
      key,
    ),
  );
}

export async function findOperatorSession(
  sessionId: string | undefined,
): Promise<OperatorSession | undefined> {
  if (!sessionId) return undefined;
  const redisUrl = sessionRedisUrl();
  const key = resolveSessionStoreKey(!!redisUrl);
  if (redisUrl) {
    const redis = await redisClient();
    if (!redis) return undefined;
    const raw = await redis.get(sessionKeyPrefix + sessionId);
    if (!raw) return undefined;
    const stored = JSON.parse(raw) as StoredOperatorSession;
    // Refuse legacy Basic-at-rest sessions (Slice C cutover).
    // 拒绝仍含 Basic 密文的旧会话（切片 C 切换）。
    if ("credentialHeaderEnc" in stored || !("accessTokenEnc" in stored)) {
      await redis.del(sessionKeyPrefix + sessionId);
      return undefined;
    }
    const session = toOperatorSession(stored, key);
    if (!session) {
      await redis.del(sessionKeyPrefix + sessionId);
      return undefined;
    }
    return session;
  }
  const stored = memorySessions.get(sessionId);
  if (!stored) return undefined;
  if ("credentialHeaderEnc" in (stored as object) || !("accessTokenEnc" in stored)) {
    memorySessions.delete(sessionId);
    return undefined;
  }
  const session = toOperatorSession(stored, key);
  if (!session) {
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

/** Bearer Authorization header for upstream platform calls — 上游平台用的 Bearer 头。 */
export function bearerAuthorizationHeader(accessToken: string): string {
  return `Bearer ${accessToken}`;
}

/** Test helper: wipe memory store. */
export function clearMemoryOperatorSessionsForTests(): void {
  memorySessions.clear();
  delete sessionHolder.subjexEphemeralSessionKey;
}

/** Test helper: memory Map must hold encrypted tokens only (no Basic / plaintext tokens). */
export function memorySessionIsEncryptedAtRestForTests(sessionId: string): boolean {
  const stored = memorySessions.get(sessionId) as
    | (StoredOperatorSession & {
        credentialHeader?: string;
        credentialHeaderEnc?: string;
        accessToken?: string;
        refreshToken?: string;
      })
    | undefined;
  if (!stored) return false;
  if (typeof stored.credentialHeader === "string") return false;
  if (typeof stored.credentialHeaderEnc === "string") return false;
  if (typeof stored.accessToken === "string" || typeof stored.refreshToken === "string") return false;
  return (
    typeof stored.accessTokenEnc === "string" &&
    stored.accessTokenEnc.startsWith(TOKEN_ENC_PREFIX) &&
    typeof stored.refreshTokenEnc === "string" &&
    stored.refreshTokenEnc.startsWith(TOKEN_ENC_PREFIX) &&
    !stored.accessTokenEnc.includes("Basic") &&
    !stored.refreshTokenEnc.includes("Basic")
  );
}
