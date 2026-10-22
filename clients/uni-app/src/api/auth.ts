/**
 * Thin end-user auth for uni-app (order #3a) — 终端薄认证（#3a）。
 * <p>
 * H5: password login via {@code POST /api/v1/auth/login} (Bearer opaque; same token store as
 * operators — product END_USER identity still later). MP: {@code wechat:mini} jsCode →
 * {@code POST /api/v1/auth/idp/wechat/callback}. Do not reuse web/ Redis sessions.
 * H5：口令登录拿不透明 Bearer；小程序：jsCode 走微信 IdP 回调。勿复用 web/ Redis 会话。
 */

import { platformFetch } from "./platform";
import { readBearer, writeBearer } from "./auth-token";

const REFRESH_KEY = "subjex_end_user_refresh";

export type TokenResponse = {
  accessToken: string;
  refreshToken?: string;
  expiresInSeconds?: number;
  tokenType?: string;
};

export type MeDocument = {
  loginName?: string;
  subjectId?: string;
  permissions?: string[];
};

/** Persist access (+ optional refresh) — 持久化访问令牌（及可选刷新令牌）. */
export function persistTokens(tokens: TokenResponse): void {
  writeBearer(tokens.accessToken);
  try {
    if (tokens.refreshToken) {
      uni.setStorageSync(REFRESH_KEY, tokens.refreshToken);
    }
  } catch {
    // ignore
  }
}

/** Clear stored tokens — 清除已存令牌. */
export function clearTokens(): void {
  writeBearer(null);
  try {
    uni.removeStorageSync(REFRESH_KEY);
  } catch {
    // ignore
  }
}

/** Password login (H5 / dev) — 口令登录（H5 / 开发）. */
export async function loginWithPassword(
  loginName: string,
  password: string
): Promise<TokenResponse> {
  const tokens = await platformFetch<TokenResponse>({
    method: "POST",
    path: "/api/v1/auth/login",
    body: { loginName, password },
  });
  persistTokens(tokens);
  return tokens;
}

/**
 * WeChat mini-program IdP callback ({@code jsCode} → code2session).
 * Requires a pre-linked operator IdP row (fail-closed / no auto-provision).
 * 微信小程序 IdP 回调；须预先绑定，失败关闭、不自动建号。
 */
export async function loginWithWeChatMini(jsCode: string): Promise<TokenResponse> {
  const tokens = await platformFetch<TokenResponse>({
    method: "POST",
    path: "/api/v1/auth/idp/wechat/callback",
    body: { jsCode },
  });
  persistTokens(tokens);
  return tokens;
}

/** Current operator/me probe — 当前主体 /me 探针. */
export async function fetchMe(): Promise<MeDocument> {
  return platformFetch<MeDocument>({
    path: "/api/v1/me",
    bearer: readBearer(),
  });
}

export function isSignedIn(): boolean {
  return Boolean(readBearer());
}
