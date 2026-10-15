package com.subjex.platform.app.idp;

/**
 * Verified external identity from an IdP callback — IdP 回调校验后的外部身份。
 *
 * @param providerId adapter id
 * @param issuer link table issuer (OIDC iss or wechat:open / wechat:mini)
 * @param subject IdP subject (OIDC sub / WeChat openid|unionid)
 * @param email optional hint only
 */
public record IdpExternalIdentity(String providerId, String issuer, String subject, String email) {}
