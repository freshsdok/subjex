package com.subjex.platform.app.idp;

/**
 * IdpAdapter — pluggable external IdP (ID-4). startAuth + handleCallback → external identity.
 * Fail-closed: adapters never provision operators; caller resolves {@code operator_idp_link}.
 * 可插拔外部 IdP：启动认证与回调得到外部身份；从不自动建号。
 */
public interface IdpAdapter {

    /** Stable provider id (e.g. generic-oidc, wechat) — 稳定提供方 id。 */
    String id();

    /** Human-readable type label — 类型标签。 */
    String type();

    boolean enabled();

    /**
     * Begin browser / QR OAuth (may throw if unsupported, e.g. mini-program-only).
     * 启动浏览器/扫码 OAuth（小程序-only 可抛不支持）。
     */
    IdpAuthStart startAuth();

    /**
     * Complete authorization_code (or provider-specific) callback → external identity.
     * 完成授权码（或提供方专用）回调 → 外部身份。
     */
    IdpExternalIdentity handleCallback(IdpCallbackRequest request);
}
