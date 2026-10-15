package com.subjex.platform.app.idp;

/**
 * WeChatProperties — {@code platform.idp.wechat.*} open-platform + mini-program (ID-4).
 * 微信开放平台 + 小程序配置。
 */
public final class WeChatProperties {

    public static final String ISSUER_OPEN = "wechat:open";
    public static final String ISSUER_MINI = "wechat:mini";

    private final boolean enabled;
    private final String appId;
    private final String appSecret;
    private final String redirectUri;

    public WeChatProperties(boolean enabled, String appId, String appSecret, String redirectUri) {
        this.enabled = enabled;
        this.appId = appId == null ? "" : appId.trim();
        this.appSecret = appSecret == null ? "" : appSecret;
        this.redirectUri = redirectUri == null ? "" : redirectUri.trim();
    }

    public boolean enabled() {
        return enabled;
    }

    public String appId() {
        return appId;
    }

    public String appSecret() {
        return appSecret;
    }

    public String redirectUri() {
        return redirectUri;
    }

    /** Open-platform QR ready — 开放平台扫码配置齐全。 */
    public boolean openReady() {
        return enabled && !appId.isBlank() && !appSecret.isBlank() && !redirectUri.isBlank();
    }

    /** Mini-program code2session ready — 小程序 code2session 配置齐全。 */
    public boolean miniReady() {
        return enabled && !appId.isBlank() && !appSecret.isBlank();
    }
}
