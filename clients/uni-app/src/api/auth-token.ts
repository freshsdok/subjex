/**
 * End-user Bearer holder (WP2-6 / ADR 0014).
 * 终端 Bearer 存放（只接受 eu.at. / eu.rt. 前缀）。
 *
 * Persist only end-user tokens. Leftover operator tokens under these keys are cleared.
 * 只持久化终端令牌；遗留的操作员令牌会被清除。
 * Do NOT use operator Redis / httpOnly session from web/.
 * 不要复用 web/ 操作员 Redis / httpOnly 会话。
 */

const ACCESS_KEY = "subjex_end_user_bearer";
const REFRESH_KEY = "subjex_end_user_refresh";
const ACCESS_EXPIRES_KEY = "subjex_end_user_access_expires_at";

export const ACCESS_PREFIX = "eu.at.";
export const REFRESH_PREFIX = "eu.rt.";

/** Access tokens expire this many ms early — 访问令牌提前这么多毫秒视为过期。 */
const EXPIRY_SKEW_MS = 30_000;

function readString(key: string): string | null {
  try {
    const v = uni.getStorageSync(key);
    return typeof v === "string" && v.trim() ? v.trim() : null;
  } catch {
    return null;
  }
}

function writeString(key: string, value: string | null): void {
  try {
    if (value && value.trim()) {
      uni.setStorageSync(key, value.trim());
    } else {
      uni.removeStorageSync(key);
    }
  } catch {
    // Storage may be unavailable in some runtimes — ignore.
    // 部分运行时无存储：忽略。
  }
}

/** True if the value is an end-user access token — 是否为终端访问令牌。 */
export function isEndUserAccessToken(token: string | null | undefined): boolean {
  return Boolean(token && token.trim().startsWith(ACCESS_PREFIX));
}

/** True if the value is an end-user refresh token — 是否为终端刷新令牌。 */
export function isEndUserRefreshToken(token: string | null | undefined): boolean {
  return Boolean(token && token.trim().startsWith(REFRESH_PREFIX));
}

/** Read stored access token, if it is a real end-user token — 读取终端访问令牌。 */
export function readBearer(): string | null {
  const token = readString(ACCESS_KEY);
  if (!token) return null;
  if (isEndUserAccessToken(token)) return token;
  // Leftover operator / unknown token — 遗留操作员或未知令牌。
  clearTokens();
  return null;
}

/** Read stored refresh token, if it is a real end-user token — 读取终端刷新令牌。 */
export function readRefreshToken(): string | null {
  const token = readString(REFRESH_KEY);
  if (!token) return null;
  if (isEndUserRefreshToken(token)) return token;
  clearTokens();
  return null;
}

/** Persist or clear the access token only — 仅写入或清除访问令牌。 */
export function writeBearer(token: string | null): void {
  if (token && !isEndUserAccessToken(token)) {
    clearTokens();
    throw new Error("拒绝写入非终端访问令牌 / refusing non end-user access token");
  }
  writeString(ACCESS_KEY, token);
  if (!token) writeString(ACCESS_EXPIRES_KEY, null);
}

export type PersistableTokens = {
  accessToken: string;
  refreshToken?: string;
  /** Seconds until access expiry (server field {@code expiresIn}) — 访问令牌剩余秒数。 */
  expiresIn?: number;
  expiresInSeconds?: number;
};

/** Persist a token pair after login or refresh — 登录或刷新后写入令牌对。 */
export function persistTokens(tokens: PersistableTokens): void {
  if (!isEndUserAccessToken(tokens.accessToken)) {
    clearTokens();
    throw new Error("拒绝写入非终端访问令牌 / refusing non end-user access token");
  }
  writeString(ACCESS_KEY, tokens.accessToken);
  if (tokens.refreshToken) {
    if (!isEndUserRefreshToken(tokens.refreshToken)) {
      clearTokens();
      throw new Error("拒绝写入非终端刷新令牌 / refusing non end-user refresh token");
    }
    writeString(REFRESH_KEY, tokens.refreshToken);
  }
  const seconds = tokens.expiresIn ?? tokens.expiresInSeconds;
  if (typeof seconds === "number" && Number.isFinite(seconds) && seconds > 0) {
    writeString(ACCESS_EXPIRES_KEY, String(Date.now() + seconds * 1000));
  } else {
    writeString(ACCESS_EXPIRES_KEY, null);
  }
}

/** True when access is missing or within the skew window — 访问令牌缺失或即将过期。 */
export function isAccessExpiringSoon(): boolean {
  const access = readBearer();
  if (!access) return true;
  const raw = readString(ACCESS_EXPIRES_KEY);
  if (!raw) return false;
  const at = Number(raw);
  if (!Number.isFinite(at)) return false;
  return Date.now() + EXPIRY_SKEW_MS >= at;
}

/** Clear stored tokens — 清除已存令牌。 */
export function clearTokens(): void {
  writeString(ACCESS_KEY, null);
  writeString(REFRESH_KEY, null);
  writeString(ACCESS_EXPIRES_KEY, null);
}
