package com.subjex.platform.app.security;

/**
 * OidcTokenClient — 用授权码 + PKCE 向 IdP 换令牌并取出已校验的身份声明。
 * <p>
 * Production talks to the real token / JWKS endpoints. Tests inject a stub.
 * 生产走真实 token / JWKS；测试注入桩实现。
 */
public interface OidcTokenClient {

    OidcClaims redeemAuthorizationCode(OidcPendingLogin pending, String authorizationCode);
}
