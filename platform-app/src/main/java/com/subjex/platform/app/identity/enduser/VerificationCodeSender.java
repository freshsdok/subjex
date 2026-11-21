package com.subjex.platform.app.identity.enduser;

/**
 * VerificationCodeSender — delivers a verification code (WP2-3). No vendor is hardcoded.
 * 验证码发送器。不内置商业短信厂商。
 */
public interface VerificationCodeSender {

    /**
     * Whether this sender may actually deliver codes (production requires a real one).
     * 是否真正可投递（生产必须配置真实发送器）。
     */
    boolean configured();

    /**
     * Deliver {@code code} to {@code destination}. Must not be called when {@link #configured()} is false.
     * 向目的地投递验证码。未配置时不得调用。
     */
    void send(String destination, String purpose, String code);
}
