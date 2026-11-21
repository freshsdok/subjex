/**
 * End-user auth for uni-app (WP2-6 / ADR 0014) — 终端认证客户端。
 * <p>
 * Calls only {@code /api/v1/end-user/auth/**}. Does not call operator
 * {@code POST /api/v1/auth/login} or {@code /api/v1/auth/idp/wechat/callback}.
 * H5 uses phone request-code + login; the mini-program also uses {@code uni.login}
 * {@code jsCode} → WeChat login. Bind/unbind require a stored end-user Bearer.
 * 只调用终端认证接口，不再走操作员登录或微信回调。H5 用手机号；小程序另走 jsCode。
 */

import { platformFetch, registerSessionRefresh } from "./platform";
import {
  clearTokens,
  isAccessExpiringSoon,
  persistTokens,
  readBearer,
  readRefreshToken,
  type PersistableTokens,
} from "./auth-token";
import { requireBusinessTenantId } from "./tenant";

const AUTH = "/api/v1/end-user/auth";

export const PHONE_PURPOSE_LOGIN = "phone-login";
export const PHONE_PURPOSE_BIND = "phone-bind";
export const PHONE_PURPOSE_UNBIND = "phone-unbind";

export type TokenResponse = PersistableTokens & {
  accessToken: string;
  refreshToken?: string;
  expiresIn?: number;
  tokenType?: string;
};

export type RequestCodeResponse = {
  expiresIn?: number;
  purpose?: string;
  /** Present only when the server is in a dev/local reveal mode — 仅本地/开发揭示。 */
  devCode?: string | null;
};

let refreshInFlight: Promise<TokenResponse> | null = null;

function requireAccess(): string {
  const access = readBearer();
  if (!access) {
    throw new Error("请先登录 / sign in required");
  }
  return access;
}

/** Persist access (+ refresh) — 持久化访问令牌（及刷新令牌）. */
export function saveTokens(tokens: TokenResponse): TokenResponse {
  persistTokens(tokens);
  return tokens;
}

/** Password-era helper kept as a clear alias of {@link clearTokens}. */
export { clearTokens };

/** Request a phone verification code — 请求手机验证码。 */
export async function requestPhoneCode(
  phone: string,
  purpose: string = PHONE_PURPOSE_LOGIN
): Promise<RequestCodeResponse> {
  return platformFetch<RequestCodeResponse>({
    method: "POST",
    path: `${AUTH}/phone/request-code`,
    skipAuthRetry: true,
    body: { phone: phone.trim(), purpose },
  });
}

/** Phone register-or-login — 手机号注册或登录。 */
export async function loginWithPhone(
  phone: string,
  code: string,
  tenantId: string
): Promise<TokenResponse> {
  const tenant = requireBusinessTenantId(tenantId);
  const tokens = await platformFetch<TokenResponse>({
    method: "POST",
    path: `${AUTH}/phone/login`,
    skipAuthRetry: true,
    tenantId: tenant,
    body: { phone: phone.trim(), code: code.trim(), tenantId: tenant },
  });
  return saveTokens(tokens);
}

/** Bind a phone to the signed-in account — 为已登录账号绑定手机号。 */
export async function bindPhone(phone: string, code: string): Promise<void> {
  await platformFetch<void>({
    method: "POST",
    path: `${AUTH}/phone/bind`,
    bearer: requireAccess(),
    body: { phone: phone.trim(), code: code.trim() },
  });
}

/** Unbind a phone from the signed-in account — 解绑已登录账号的手机号。 */
export async function unbindPhone(phone: string, code: string): Promise<void> {
  await platformFetch<void>({
    method: "POST",
    path: `${AUTH}/phone/unbind`,
    bearer: requireAccess(),
    body: { phone: phone.trim(), code: code.trim() },
  });
}

/**
 * WeChat mini-program login ({@code jsCode} + explicit tenant). First login may
 * auto-provision an END_USER in that existing ACTIVE tenant.
 * 微信小程序登录；首次可在已有 ACTIVE 租户中自动开户。
 */
export async function loginWithWeChatMini(
  jsCode: string,
  tenantId: string
): Promise<TokenResponse> {
  const tenant = requireBusinessTenantId(tenantId);
  const tokens = await platformFetch<TokenResponse>({
    method: "POST",
    path: `${AUTH}/wechat/login`,
    skipAuthRetry: true,
    tenantId: tenant,
    body: { jsCode: jsCode.trim(), tenantId: tenant },
  });
  return saveTokens(tokens);
}

/** Bind WeChat to the signed-in account — 为已登录账号绑定微信。 */
export async function bindWeChat(jsCode: string): Promise<void> {
  await platformFetch<void>({
    method: "POST",
    path: `${AUTH}/wechat/bind`,
    bearer: requireAccess(),
    body: { jsCode: jsCode.trim() },
  });
}

/** Unbind WeChat from the signed-in account — 解绑已登录账号的微信。 */
export async function unbindWeChat(jsCode: string): Promise<void> {
  await platformFetch<void>({
    method: "POST",
    path: `${AUTH}/wechat/unbind`,
    bearer: requireAccess(),
    body: { jsCode: jsCode.trim() },
  });
}

/** Rotate tokens with the stored refresh token — 用已存刷新令牌轮换。 */
export async function refreshSession(): Promise<TokenResponse> {
  if (refreshInFlight) return refreshInFlight;
  const refreshToken = readRefreshToken();
  if (!refreshToken) {
    throw new Error("没有刷新令牌 / no refresh token");
  }
  refreshInFlight = (async () => {
    try {
      const tokens = await platformFetch<TokenResponse>({
        method: "POST",
        path: `${AUTH}/refresh`,
    skipAuthRetry: true,
        body: { refreshToken },
      });
      return saveTokens(tokens);
    } finally {
      refreshInFlight = null;
    }
  })();
  return refreshInFlight;
}

/**
 * Refresh silently when access is missing or near expiry.
 * Returns whether a usable access token is present afterwards.
 * 访问令牌缺失或即将过期时静默刷新；返回之后是否仍有可用访问令牌。
 */
export async function ensureSession(): Promise<boolean> {
  const access = readBearer();
  const refreshToken = readRefreshToken();
  if (access && !isAccessExpiringSoon()) return true;
  if (!refreshToken) return Boolean(access);
  try {
    await refreshSession();
    return true;
  } catch {
    clearTokens();
    return false;
  }
}

/**
 * Revoke the refresh family on the server, then clear local storage.
 * Local storage is cleared even if the network call fails.
 * 先调用服务端退出再清本地；网络失败也清本地。
 */
export async function logoutAndClear(): Promise<void> {
  const refreshToken = readRefreshToken();
  try {
    if (refreshToken) {
      await platformFetch<void>({
        method: "POST",
        path: `${AUTH}/logout`,
    skipAuthRetry: true,
        body: { refreshToken },
      });
    }
  } finally {
    clearTokens();
  }
}

export function isSignedIn(): boolean {
  return Boolean(readBearer() || readRefreshToken());
}

registerSessionRefresh(refreshSession);
