package com.subjex.platform.app.identity.enduser;

/**
 * DevVerificationCodeSender — no-op delivery for tests and the {@code local} profile (WP2-3).
 * 开发用空投递：测试与 {@code local} 通过端口的 peek/reveal 取码，不把验证码打进日志。
 * <p>
 * Does not log the plaintext code. Callers that need the value use {@link VerificationCodePort#peekForDev}.
 * 不把明文验证码写入日志。需要取值时走 {@link VerificationCodePort#peekForDev}。
 */
public final class DevVerificationCodeSender implements VerificationCodeSender {

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public void send(String destination, String purpose, String code) {
        // Intentionally empty — the in-memory port retains the code for peek/reveal.
        // 有意留空：内存端口为 peek/reveal 保留验证码。
    }
}
