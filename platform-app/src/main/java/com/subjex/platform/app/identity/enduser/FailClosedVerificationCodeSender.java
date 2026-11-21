package com.subjex.platform.app.identity.enduser;

/**
 * FailClosedVerificationCodeSender — production stand-in when no real SMS/sender is configured.
 * 生产占位：未配置真实发送器时失败关闭，绝不投递或打印验证码。
 */
public final class FailClosedVerificationCodeSender implements VerificationCodeSender {

    @Override
    public boolean configured() {
        return false;
    }

    @Override
    public void send(String destination, String purpose, String code) {
        throw new VerificationCodeSenderUnconfiguredException();
    }
}
