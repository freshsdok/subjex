/**
 * End-user Bearer holder (PC-5 storage; filled by #3a auth.ts).
 * 终端 Bearer 存放（PC-5 存储；由 #3a auth.ts 写入）。
 *
 * Do NOT use operator Redis / httpOnly session from web/.
 * 不要复用 web/ 操作员 Redis / httpOnly 会话。
 */

const KEY = "subjex_end_user_bearer";

/** Read stored Bearer, if any — 读取已存 Bearer（可无）。 */
export function readBearer(): string | null {
  try {
    const v = uni.getStorageSync(KEY);
    return typeof v === "string" && v.trim() ? v.trim() : null;
  } catch {
    return null;
  }
}

/** Persist or clear Bearer — 写入或清除 Bearer。 */
export function writeBearer(token: string | null): void {
  try {
    if (token && token.trim()) {
      uni.setStorageSync(KEY, token.trim());
    } else {
      uni.removeStorageSync(KEY);
    }
  } catch {
    // Storage may be unavailable in some runtimes — ignore.
    // 部分运行时无存储：忽略。
  }
}
