package com.subjex.platform.app.identity.enduser;

/**
 * InvalidVerificationCodeException — verification code is missing, wrong, expired, or already used (HTTP 401).
 * 验证码缺失、错误、过期或已使用（映射为 401）。
 */
public final class InvalidVerificationCodeException extends RuntimeException {

    public InvalidVerificationCodeException() {
        super("invalid-verification-code");
    }
}
