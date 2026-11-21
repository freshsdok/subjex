package com.subjex.platform.app.identity.enduser;

/**
 * WeChatUnconfiguredException — production (or this runtime) has no WeChat mini-program config (HTTP 503).
 * 未配置微信小程序（映射为 503）。不得静默开户。
 */
public final class WeChatUnconfiguredException extends RuntimeException {

    public static final String REASON = "wechat-unconfigured";

    public WeChatUnconfiguredException() {
        super(REASON);
    }
}
