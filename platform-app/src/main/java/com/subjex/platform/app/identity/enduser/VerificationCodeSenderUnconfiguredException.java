package com.subjex.platform.app.identity.enduser;

/**
 * VerificationCodeSenderUnconfiguredException — production has no real verification-code sender (HTTP 503).
 * 生产未配置真实验证码发送器（映射为 503）。
 */
public final class VerificationCodeSenderUnconfiguredException extends RuntimeException {

    public static final String REASON = "verification-code-sender-unconfigured";

    public VerificationCodeSenderUnconfiguredException() {
        super(REASON);
    }
}
