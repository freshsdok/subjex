package com.subjex.platform.app.identity.enduser;

/**
 * VerificationCodeSendFailedException — a configured sender failed to deliver (HTTP 503).
 * 已配置的发送器投递失败（映射为 503）。
 * <p>
 * The message is a stable reason only. It must not include the code, destination, or API key.
 * 消息只含稳定原因码，不得包含验证码、目的地或 API 密钥。
 */
public final class VerificationCodeSendFailedException extends RuntimeException {

    public static final String REASON = "verification-code-send-failed";

    public VerificationCodeSendFailedException() {
        super(REASON);
    }
}
