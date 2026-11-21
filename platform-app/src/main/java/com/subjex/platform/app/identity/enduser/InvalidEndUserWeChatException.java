package com.subjex.platform.app.identity.enduser;

/**
 * InvalidEndUserWeChatException — WeChat jsCode is missing, invalid, or failed to redeem (HTTP 401).
 * 微信 jsCode 缺失、无效或兑换失败（映射为 401）。
 */
public final class InvalidEndUserWeChatException extends RuntimeException {

    public static final String REASON = "invalid-wechat-code";

    public InvalidEndUserWeChatException() {
        super(REASON);
    }

    public InvalidEndUserWeChatException(String message) {
        super(message);
    }
}
