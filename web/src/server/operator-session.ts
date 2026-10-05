import { randomBytes } from "node:crypto";

// Operator session — 操作员会话：浏览器只持有随机会话号（httpOnly cookie），
// 真正的认证头只留在 Next.js 服务端内存里。
export interface OperatorSession {
  loginName: string;
  credentialHeader: string;
  expiresAtMillis: number;
}

// Cookie name and lifetime — cookie 名称与有效期（8 小时，过期后需重新登录）。
export const sessionCookieName = "subjex_session";
export const sessionLifetimeSeconds = 8 * 60 * 60;

// Keep the store on globalThis so dev hot reload does not drop sessions — 挂在 globalThis 上，开发热重载时会话不丢。
const sessionHolder = globalThis as typeof globalThis & {
  subjexOperatorSessions?: Map<string, OperatorSession>;
};
const operatorSessions: Map<string, OperatorSession> =
  (sessionHolder.subjexOperatorSessions ??= new Map());

export function openOperatorSession(loginName: string, credentialHeader: string): string {
  const sessionId = randomBytes(32).toString("base64url");
  operatorSessions.set(sessionId, {
    loginName,
    credentialHeader,
    expiresAtMillis: Date.now() + sessionLifetimeSeconds * 1000,
  });
  return sessionId;
}

export function findOperatorSession(sessionId: string | undefined): OperatorSession | undefined {
  if (!sessionId) return undefined;
  const session = operatorSessions.get(sessionId);
  if (!session) return undefined;
  if (session.expiresAtMillis <= Date.now()) {
    operatorSessions.delete(sessionId);
    return undefined;
  }
  return session;
}

export function closeOperatorSession(sessionId: string | undefined): void {
  if (sessionId) operatorSessions.delete(sessionId);
}
