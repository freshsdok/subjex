import { randomBytes } from "node:crypto";
import { createClient, type RedisClientType } from "redis";

// Operator session — 操作员会话：浏览器只持有随机会话号（httpOnly cookie），
// 真正的认证头只留在 Next.js 服务端（Redis 或多副本共享；未配 Redis 时退回进程内存）。
export interface OperatorSession {
  loginName: string;
  credentialHeader: string;
  expiresAtMillis: number;
}

// Cookie name and lifetime — cookie 名称与有效期（8 小时，过期后需重新登录）。
export const sessionCookieName = "subjex_session";
export const sessionLifetimeSeconds = 8 * 60 * 60;

const sessionKeyPrefix = "subjex:operator-session:";

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
  const session: OperatorSession = {
    loginName,
    credentialHeader,
    expiresAtMillis: Date.now() + sessionLifetimeSeconds * 1000,
  };
  const redis = await redisClient();
  if (redis) {
    await redis.set(sessionKeyPrefix + sessionId, JSON.stringify(session), {
      EX: sessionLifetimeSeconds,
    });
    return sessionId;
  }
  memorySessions.set(sessionId, session);
  return sessionId;
}

export async function findOperatorSession(
  sessionId: string | undefined,
): Promise<OperatorSession | undefined> {
  if (!sessionId) return undefined;
  const redis = await redisClient();
  if (redis) {
    const raw = await redis.get(sessionKeyPrefix + sessionId);
    if (!raw) return undefined;
    const session = JSON.parse(raw) as OperatorSession;
    if (session.expiresAtMillis <= Date.now()) {
      await redis.del(sessionKeyPrefix + sessionId);
      return undefined;
    }
    return session;
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
